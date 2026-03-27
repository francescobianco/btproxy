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

data class NotificationEvent(
    val macAddress: String,
    val characteristicUuid: String,
    val payload: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
)

data class SubscribeResult(
    val success: Boolean,
    val queueDepth: Int = 0,
    val reason: String? = null
)

data class PollResult(
    val subscribed: Boolean,
    val event: NotificationEvent?,
    val queueDepth: Int
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
    private val notifySubscriptions = ConcurrentHashMap<String, MutableSet<String>>() // Per characteristic subscription clients
    private val notificationQueues = ConcurrentHashMap<String, MutableList<NotificationEvent>>() // Per client/characteristic queue
    
    companion object {
        private const val TAG = "BleConnectionManager"
        private const val RECONNECTION_DELAY_MS = 5000L // 5 seconds between reconnection attempts
        private const val MAX_RECONNECTION_ATTEMPTS = -1 // Infinite attempts (-1)
        private const val MAX_QUEUED_MESSAGES = 100 // Max messages per device
        private const val MAX_NOTIFICATION_EVENTS = 100
        private val CLIENT_QUEUE_POLL_INTERVAL_MS = 100L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
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
        clearNotificationState(macAddress)
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
        
        // Extract messages from queue in synchronized block
        val messages = synchronized(queue) {
            val messagesCopy = queue.toList()
            queue.clear()
            messagesCopy
        }
        
        Log.d(TAG, "Processing ${messages.size} queued messages for $macAddress")
        
        // Process messages outside of synchronized block
        messages.forEach { message ->
            try {
                val success = writeCharacteristicDirect(message.macAddress, message.characteristicUuid, message.data)
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
        
        return writeCharacteristicDirect(macAddress, characteristicUuid, data)
    }

    suspend fun subscribeToCharacteristic(macAddress: String, characteristicUuid: String, clientId: String): SubscribeResult {
        val gatt = connections[macAddress] ?: return SubscribeResult(false, reason = "Device not connected")
        val callback = connectionCallbacks[macAddress] ?: return SubscribeResult(false, reason = "Device not connected")
        if (!callback.isConnected) {
            return SubscribeResult(false, reason = "Device not connected")
        }

        val characteristic = getCharacteristic(gatt, characteristicUuid)
            ?: return SubscribeResult(false, reason = "Characteristic not found")

        if (!supportsNotify(characteristic)) {
            return SubscribeResult(false, reason = "Characteristic does not support notify")
        }

        val subscriptionKey = subscriptionKey(macAddress, characteristicUuid)
        val queueKey = notificationQueueKey(macAddress, characteristicUuid, clientId)
        val clients = notifySubscriptions.getOrPut(subscriptionKey) { mutableSetOf() }
        val shouldEnableBleNotify = synchronized(clients) {
            val wasEmpty = clients.isEmpty()
            clients.add(clientId)
            wasEmpty
        }
        notificationQueues.putIfAbsent(queueKey, mutableListOf())

        if (shouldEnableBleNotify) {
            val enabled = setCharacteristicNotification(gatt, callback, characteristic, true)
            if (!enabled) {
                synchronized(clients) { clients.remove(clientId) }
                if (clients.isEmpty()) {
                    notifySubscriptions.remove(subscriptionKey)
                }
                notificationQueues.remove(queueKey)
                return SubscribeResult(false, reason = "Failed to enable notify")
            }
        }

        return SubscribeResult(true, queueDepth = getNotificationQueueDepth(macAddress, characteristicUuid, clientId))
    }

    suspend fun unsubscribeFromCharacteristic(macAddress: String, characteristicUuid: String, clientId: String): Boolean {
        val subscriptionKey = subscriptionKey(macAddress, characteristicUuid)
        val clients = notifySubscriptions[subscriptionKey] ?: return false
        val queueKey = notificationQueueKey(macAddress, characteristicUuid, clientId)
        var shouldDisableBleNotify = false

        synchronized(clients) {
            clients.remove(clientId)
            shouldDisableBleNotify = clients.isEmpty()
        }

        notificationQueues.remove(queueKey)

        if (shouldDisableBleNotify) {
            notifySubscriptions.remove(subscriptionKey)
            val gatt = connections[macAddress]
            val callback = connectionCallbacks[macAddress]
            val characteristic = gatt?.let { getCharacteristic(it, characteristicUuid) }
            if (gatt != null && callback != null && characteristic != null) {
                setCharacteristicNotification(gatt, callback, characteristic, false)
            }
        }

        return true
    }

    suspend fun pollNotification(
        macAddress: String,
        characteristicUuid: String,
        clientId: String,
        waitMs: Long = 0L
    ): PollResult {
        if (!isSubscribed(macAddress, characteristicUuid, clientId)) {
            return PollResult(subscribed = false, event = null, queueDepth = 0)
        }

        val queueKey = notificationQueueKey(macAddress, characteristicUuid, clientId)
        val queue = notificationQueues.getOrPut(queueKey) { mutableListOf() }
        val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0L)

        while (true) {
            val event = synchronized(queue) {
                if (queue.isEmpty()) {
                    null
                } else {
                    queue.removeAt(0)
                }
            }

            if (event != null) {
                return PollResult(
                    subscribed = true,
                    event = event,
                    queueDepth = synchronized(queue) { queue.size }
                )
            }

            if (System.currentTimeMillis() >= deadline) {
                return PollResult(
                    subscribed = true,
                    event = null,
                    queueDepth = synchronized(queue) { queue.size }
                )
            }

            delay(CLIENT_QUEUE_POLL_INTERVAL_MS)
        }
    }

    fun getNotificationQueueDepth(macAddress: String, characteristicUuid: String, clientId: String): Int {
        val queueKey = notificationQueueKey(macAddress, characteristicUuid, clientId)
        val queue = notificationQueues[queueKey] ?: return 0
        return synchronized(queue) { queue.size }
    }

    fun isSubscribed(macAddress: String, characteristicUuid: String, clientId: String): Boolean {
        val subscriptionKey = subscriptionKey(macAddress, characteristicUuid)
        val clients = notifySubscriptions[subscriptionKey] ?: return false
        return synchronized(clients) { clientId in clients }
    }
    
    private suspend fun writeCharacteristicDirect(macAddress: String, characteristicUuid: String, data: ByteArray): Boolean {
        val gatt = connections[macAddress] ?: return false
        val callback = connectionCallbacks[macAddress] ?: return false
        
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

    private fun getCharacteristic(gatt: BluetoothGatt, characteristicUuid: String): BluetoothGattCharacteristic? {
        val serviceUuid = findServiceForCharacteristic(gatt, characteristicUuid) ?: return null
        val service = gatt.getService(UUID.fromString(serviceUuid)) ?: return null
        return service.getCharacteristic(UUID.fromString(characteristicUuid))
    }

    private fun supportsNotify(characteristic: BluetoothGattCharacteristic): Boolean {
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }

    private suspend fun setCharacteristicNotification(
        gatt: BluetoothGatt,
        callback: BleGattCallback,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ): Boolean {
        return try {
            val localSet = gatt.setCharacteristicNotification(characteristic, enabled)
            if (!localSet) {
                Log.w(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
                return false
            }

            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                Log.w(TAG, "CCCD not found for ${characteristic.uuid}")
                return false
            }

            descriptor.value = when {
                !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }

            callback.prepareForDescriptorWrite()
            gatt.writeDescriptor(descriptor)
            callback.waitForDescriptorWriteResult()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when changing notifications for ${characteristic.uuid}", e)
            false
        }
    }

    private fun enqueueNotificationEvent(event: NotificationEvent) {
        val clients = notifySubscriptions[subscriptionKey(event.macAddress, event.characteristicUuid)] ?: return
        val clientIds = synchronized(clients) { clients.toList() }

        clientIds.forEach { clientId ->
            val queue = notificationQueues.getOrPut(
                notificationQueueKey(event.macAddress, event.characteristicUuid, clientId)
            ) { mutableListOf() }
            synchronized(queue) {
                while (queue.size >= MAX_NOTIFICATION_EVENTS) {
                    queue.removeAt(0)
                }
                queue.add(event.copy(payload = event.payload.copyOf()))
            }
        }
    }

    private fun restoreNotifications(macAddress: String, gatt: BluetoothGatt, callback: BleGattCallback) {
        val normalizedMacAddress = macAddress.uppercase(Locale.ROOT)
        val subscriptionKeys = notifySubscriptions.keys.filter { it.startsWith("$normalizedMacAddress|") }
        subscriptionKeys.forEach { key ->
            val characteristicUuid = key.substringAfter('|')
            val characteristic = getCharacteristic(gatt, characteristicUuid)
            if (characteristic == null || !supportsNotify(characteristic)) {
                Log.w(TAG, "Cannot restore notify for $macAddress/$characteristicUuid")
                return@forEach
            }

            reconnectionScope.launch {
                val restored = setCharacteristicNotification(gatt, callback, characteristic, true)
                if (restored) {
                    Log.d(TAG, "Restored notify for $macAddress/$characteristicUuid")
                } else {
                    Log.w(TAG, "Failed to restore notify for $macAddress/$characteristicUuid")
                }
            }
        }
    }

    private fun clearNotificationState(macAddress: String) {
        val normalizedMacAddress = macAddress.uppercase(Locale.ROOT)
        notifySubscriptions.keys
            .filter { it.startsWith("$normalizedMacAddress|") }
            .forEach { subscriptionKey ->
                notifySubscriptions.remove(subscriptionKey)?.let { clients ->
                    val characteristicUuid = subscriptionKey.substringAfter('|')
                    synchronized(clients) {
                        clients.forEach { clientId ->
                            notificationQueues.remove(notificationQueueKey(macAddress, characteristicUuid, clientId))
                        }
                    }
                }
            }
    }

    private fun subscriptionKey(macAddress: String, characteristicUuid: String): String {
        return "${macAddress.uppercase(Locale.ROOT)}|${characteristicUuid.lowercase(Locale.ROOT)}"
    }

    private fun notificationQueueKey(macAddress: String, characteristicUuid: String, clientId: String): String {
        return "${subscriptionKey(macAddress, characteristicUuid)}|$clientId"
    }
    
    fun getCharacteristics(macAddress: String): String? {
        val gatt = connections[macAddress] ?: return null
        return try {
            val sb = StringBuilder()
            gatt.services?.forEach { service ->
                sb.appendLine("service: ${service.uuid}")
                service.characteristics?.forEach { characteristic ->
                    val props = buildList {
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
                        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
                    }.joinToString(" ")
                    sb.appendLine("  characteristic: ${characteristic.uuid} properties: $props")
                }
            }
            sb.toString()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied when listing characteristics for $macAddress", e)
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
        notifySubscriptions.clear()
        notificationQueues.clear()
        
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
        private var descriptorWriteResult = false
        private var isWaitingForDescriptorWrite = false
        
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
                if (gatt != null) {
                    restoreNotifications(macAddress, gatt, this)
                }
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

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic == null) return
            val event = NotificationEvent(
                macAddress = macAddress,
                characteristicUuid = characteristic.uuid.toString(),
                payload = characteristic.value?.copyOf() ?: byteArrayOf()
            )
            enqueueNotificationEvent(event)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (isWaitingForDescriptorWrite) {
                descriptorWriteResult = status == BluetoothGatt.GATT_SUCCESS
                isWaitingForDescriptorWrite = false
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

        fun prepareForDescriptorWrite() {
            descriptorWriteResult = false
            isWaitingForDescriptorWrite = true
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

        suspend fun waitForDescriptorWriteResult(): Boolean {
            var attempts = 0
            while (isWaitingForDescriptorWrite && attempts < 50) {
                delay(100)
                attempts++
            }
            return descriptorWriteResult
        }
    }
}
