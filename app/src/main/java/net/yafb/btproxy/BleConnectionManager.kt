package net.yafb.btproxy

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class QueuedMessage(
    val macAddress: String,
    val characteristicUuid: String,
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
)

class BleConnectionManager(private val context: Context) {
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectionCallbacks = ConcurrentHashMap<String, BleGattCallback>()
    
    // Reconnection and message queue system
    private val reconnectionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val targetDevices = mutableSetOf<String>() // Devices we should maintain connection to
    private val messageQueue = ConcurrentHashMap<String, MutableList<QueuedMessage>>() // Per-device message queues
    private val reconnectionJobs = ConcurrentHashMap<String, Job>() // Ongoing reconnection attempts
    
    companion object {
        private const val TAG = "BleConnectionManager"
        private const val RECONNECTION_DELAY_MS = 5000L // 5 seconds between reconnection attempts
        private const val MAX_RECONNECTION_ATTEMPTS = -1 // Infinite attempts (-1)
        private const val MAX_QUEUED_MESSAGES = 100 // Max messages per device
    }
    
    fun addTargetDevice(macAddress: String) {
        synchronized(targetDevices) {
            targetDevices.add(macAddress)
            messageQueue[macAddress] = mutableListOf()
        }
        Log.d(TAG, "Added target device: $macAddress")
    }
    
    fun removeTargetDevice(macAddress: String) {
        synchronized(targetDevices) {
            targetDevices.remove(macAddress)
            messageQueue.remove(macAddress)
            reconnectionJobs[macAddress]?.cancel()
            reconnectionJobs.remove(macAddress)
        }
        disconnectDevice(macAddress)
        Log.d(TAG, "Removed target device: $macAddress")
    }
    
    fun setTargetDevices(addresses: List<String>) {
        synchronized(targetDevices) {
            // Remove old devices
            val toRemove = targetDevices.toList() - addresses.toSet()
            toRemove.forEach { removeTargetDevice(it) }
            
            // Add new devices
            addresses.forEach { addTargetDevice(it) }
        }
        
        // Start connections to new devices
        addresses.forEach { macAddress ->
            if (!isDeviceConnected(macAddress)) {
                startReconnectionProcess(macAddress)
            }
        }
    }
    
    private fun startReconnectionProcess(macAddress: String) {
        // Cancel any existing reconnection job for this device
        reconnectionJobs[macAddress]?.cancel()
        
        val job = reconnectionScope.launch {
            var attempts = 0
            while (targetDevices.contains(macAddress) && !isDeviceConnected(macAddress)) {
                attempts++
                Log.d(TAG, "Reconnection attempt $attempts for $macAddress")
                
                try {
                    val connected = connectToDevice(macAddress)
                    if (connected) {
                        Log.d(TAG, "Successfully reconnected to $macAddress")
                        // Process queued messages for this device
                        processQueuedMessages(macAddress)
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection attempt $attempts failed for $macAddress", e)
                }
                
                // Check if we should continue attempting
                if (MAX_RECONNECTION_ATTEMPTS > 0 && attempts >= MAX_RECONNECTION_ATTEMPTS) {
                    Log.w(TAG, "Max reconnection attempts reached for $macAddress")
                    break
                }
                
                // Wait before next attempt
                delay(RECONNECTION_DELAY_MS)
            }
        }
        
        reconnectionJobs[macAddress] = job
    }
    
    private suspend fun processQueuedMessages(macAddress: String) {
        val queue = messageQueue[macAddress] ?: return
        
        synchronized(queue) {
            val messages = queue.toList()
            queue.clear()
            
            Log.d(TAG, "Processing ${messages.size} queued messages for $macAddress")
            
            messages.forEach { message ->
                try {
                    val success = writeCharacteristic(message.macAddress, message.characteristicUuid, message.data)
                    if (success) {
                        Log.d(TAG, "Successfully sent queued message to $macAddress/${message.characteristicUuid}")
                    } else {
                        Log.w(TAG, "Failed to send queued message to $macAddress/${message.characteristicUuid}")
                        // Re-queue the message if write failed
                        queueMessage(message)
                    }
                    // Small delay between messages to avoid overwhelming the device
                    delay(100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending queued message to $macAddress", e)
                    // Re-queue the message on error
                    queueMessage(message)
                }
            }
        }
    }
    
    private fun queueMessage(message: QueuedMessage) {
        val queue = messageQueue[message.macAddress] ?: return
        
        synchronized(queue) {
            // Remove oldest messages if queue is full
            while (queue.size >= MAX_QUEUED_MESSAGES) {
                queue.removeAt(0)
            }
            queue.add(message)
        }
        
        Log.d(TAG, "Queued message for ${message.macAddress}/${message.characteristicUuid} (queue size: ${queue.size})")
    }
    
    suspend fun connectToDevice(macAddress: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val device = bluetoothAdapter.getRemoteDevice(macAddress)
            val callback = BleGattCallback(macAddress)
            connectionCallbacks[macAddress] = callback
            
            val gatt = device.connectGatt(context, false, callback)
            connections[macAddress] = gatt
            
            // Wait for connection with timeout
            delay(5000)
            callback.isConnected
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $macAddress", e)
            false
        }
    }
    
    fun disconnectDevice(macAddress: String) {
        connections[macAddress]?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied when disconnecting $macAddress", e)
            }
        }
        connections.remove(macAddress)
        connectionCallbacks.remove(macAddress)
    }
    
    suspend fun readCharacteristic(macAddress: String, characteristicUuid: String): ByteArray? {
        val gatt = connections[macAddress] ?: return null
        val callback = connectionCallbacks[macAddress] ?: return null
        
        return try {
            val serviceUuid = findServiceForCharacteristic(gatt, characteristicUuid)
            if (serviceUuid != null) {
                val service = gatt.getService(UUID.fromString(serviceUuid))
                val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUuid))
                
                if (characteristic != null) {
                    callback.prepareForRead()
                    gatt.readCharacteristic(characteristic)
                    callback.waitForReadResult()
                } else {
                    Log.e(TAG, "Characteristic not found: $characteristicUuid")
                    null
                }
            } else {
                Log.e(TAG, "Service not found for characteristic: $characteristicUuid")
                null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when reading characteristic", e)
            null
        }
    }
    
    suspend fun writeCharacteristic(macAddress: String, characteristicUuid: String, data: ByteArray): Boolean {
        val gatt = connections[macAddress]
        val callback = connectionCallbacks[macAddress]
        
        // If device is not connected and it's a target device, queue the message
        if (gatt == null || callback == null || !callback.isConnected) {
            if (targetDevices.contains(macAddress)) {
                val queuedMessage = QueuedMessage(macAddress, characteristicUuid, data)
                queueMessage(queuedMessage)
                
                // Start reconnection if not already in progress
                if (!reconnectionJobs.containsKey(macAddress)) {
                    startReconnectionProcess(macAddress)
                }
                
                Log.d(TAG, "Device $macAddress not connected, message queued")
                return true // Return true to indicate message was queued successfully
            } else {
                Log.e(TAG, "Device $macAddress not connected and not in target devices")
                return false
            }
        }
        
        return try {
            val serviceUuid = findServiceForCharacteristic(gatt, characteristicUuid)
            if (serviceUuid != null) {
                val service = gatt.getService(UUID.fromString(serviceUuid))
                val characteristic = service?.getCharacteristic(UUID.fromString(characteristicUuid))
                
                if (characteristic != null) {
                    characteristic.value = data
                    callback.prepareForWrite()
                    gatt.writeCharacteristic(characteristic)
                    callback.waitForWriteResult()
                } else {
                    Log.e(TAG, "Characteristic not found: $characteristicUuid")
                    false
                }
            } else {
                Log.e(TAG, "Service not found for characteristic: $characteristicUuid")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when writing characteristic", e)
            false
        }
    }
    
    private fun findServiceForCharacteristic(gatt: BluetoothGatt, characteristicUuid: String): String? {
        return try {
            gatt.services?.forEach { service ->
                service.characteristics?.forEach { characteristic ->
                    if (characteristic.uuid.toString().equals(characteristicUuid, ignoreCase = true)) {
                        return service.uuid.toString()
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when finding service", e)
            null
        }
    }
    
    fun getConnectedDevices(): List<String> {
        return connections.keys.toList()
    }
    
    fun isDeviceConnected(macAddress: String): Boolean {
        return connectionCallbacks[macAddress]?.isConnected == true
    }
    
    fun disconnectAll() {
        connections.keys.forEach { macAddress ->
            disconnectDevice(macAddress)
        }
    }
    
    fun shutdown() {
        // Cancel all reconnection jobs
        reconnectionJobs.values.forEach { it.cancel() }
        reconnectionJobs.clear()
        
        // Clear all data structures
        synchronized(targetDevices) {
            targetDevices.clear()
            messageQueue.clear()
        }
        
        // Disconnect all devices
        disconnectAll()
        
        // Cancel the reconnection scope
        reconnectionScope.cancel()
        
        Log.d(TAG, "BleConnectionManager shut down")
    }
    
    private inner class BleGattCallback(private val macAddress: String) : BluetoothGattCallback() {
        
        var isConnected = false
        private var readResult: ByteArray? = null
        private var writeResult = false
        private var isWaitingForRead = false
        private var isWaitingForWrite = false
        
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to $macAddress")
                    isConnected = true
                    try {
                        gatt?.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied when discovering services", e)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from $macAddress")
                    isConnected = false
                    
                    // Start automatic reconnection if this is a target device
                    if (targetDevices.contains(macAddress)) {
                        Log.d(TAG, "Starting automatic reconnection for target device $macAddress")
                        startReconnectionProcess(macAddress)
                    }
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for $macAddress")
            }
        }
        
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (isWaitingForRead) {
                readResult = if (status == BluetoothGatt.GATT_SUCCESS) {
                    characteristic?.value
                } else {
                    null
                }
                isWaitingForRead = false
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (isWaitingForWrite) {
                writeResult = status == BluetoothGatt.GATT_SUCCESS
                isWaitingForWrite = false
            }
        }
        
        fun prepareForRead() {
            readResult = null
            isWaitingForRead = true
        }
        
        fun prepareForWrite() {
            writeResult = false
            isWaitingForWrite = true
        }
        
        suspend fun waitForReadResult(): ByteArray? {
            var attempts = 0
            while (isWaitingForRead && attempts < 50) {
                delay(100)
                attempts++
            }
            return readResult
        }
        
        suspend fun waitForWriteResult(): Boolean {
            var attempts = 0
            while (isWaitingForWrite && attempts < 50) {
                delay(100)
                attempts++
            }
            return writeResult
        }
    }
}