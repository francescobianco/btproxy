package net.yafb.btproxy

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BleConnectionManager(private val context: Context) {
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectionCallbacks = ConcurrentHashMap<String, BleGattCallback>()
    
    companion object {
        private const val TAG = "BleConnectionManager"
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