package net.yafb.btproxy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*
import android.text.TextWatcher
import android.text.Editable

class MainActivity : AppCompatActivity() {
    
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: BluetoothLeScanner
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var scanButton: Button
    private lateinit var startServerButton: Button
    private lateinit var portEditText: EditText
    private lateinit var authTokenEditText: EditText
    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private val selectedDevices = mutableSetOf<String>()
    private val savedDiscoveredAddresses = mutableSetOf<String>()
    private var isScanning = false
    private var isServerRunning = false
    private val logEntries = mutableListOf<String>()
    private val dateFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private val saveConfigRunnable = Runnable {
        saveAppState()
    }
    
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "net.yafb.btproxy.LOG") {
                val message = intent.getStringExtra("message") ?: return
                val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                addLogEntry(message, timestamp)
                
                // Track server state from log messages
                when {
                    message.contains("HTTP Server started successfully") -> {
                        isServerRunning = true
                        runOnUiThread { 
                            updateUI()
                            saveAppState()
                        }
                    }
                    message.contains("ERROR: Failed to start HTTP server") -> {
                        isServerRunning = false
                        runOnUiThread { 
                            updateUI()
                            saveAppState()
                        }
                    }
                    message.contains("HTTP server stopped") -> {
                        isServerRunning = false
                        runOnUiThread { 
                            updateUI()
                            saveAppState()
                        }
                    }
                }
            }
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                runOnUiThread {
                    deviceAdapter.notifyDataSetChanged()
                }
            }
        }
    }
    
    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val PREFS_NAME = "btproxy_app_state"
        private const val PREF_PORT = "server_port"
        private const val PREF_AUTH_TOKEN = "auth_token"
        private const val PREF_SELECTED_DEVICES = "selected_devices"
        private const val PREF_SERVER_RUNNING = "server_running"
        private const val PREF_DISCOVERED_DEVICE_ADDRESSES = "discovered_device_addresses"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).let { permissions ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions + arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                permissions
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        initBluetooth()
        checkPermissions()
        
        // Register log receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("net.yafb.btproxy.LOG"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("net.yafb.btproxy.LOG"))
        }
        
        addLogEntry("BT Proxy started", System.currentTimeMillis())
        
        // Load saved state
        loadAppState()
    }
    
    private fun initViews() {
        scanButton = findViewById(R.id.scanButton)
        startServerButton = findViewById(R.id.startServerButton)
        portEditText = findViewById(R.id.portEditText)
        authTokenEditText = findViewById(R.id.authTokenEditText)
        statusTextView = findViewById(R.id.statusTextView)
        logTextView = findViewById(R.id.logTextView)
        
        val recyclerView = findViewById<RecyclerView>(R.id.devicesRecyclerView)
        deviceAdapter = DeviceAdapter(discoveredDevices, selectedDevices) { device, isSelected ->
            if (isSelected) {
                selectedDevices.add(device.address)
            } else {
                selectedDevices.remove(device.address)
            }
            updateUI()
            saveAppState() // Save when device selection changes
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = deviceAdapter
        
        scanButton.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                startScan()
            }
        }
        
        startServerButton.setOnClickListener {
            if (isServerRunning) {
                stopHttpServer()
            } else {
                startHttpServer()
            }
        }
        
        portEditText.setText("8080")
        authTokenEditText.setText("secret")
        
        // Add text watchers to save configuration changes
        portEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Save with a small delay to avoid excessive saves while typing
                portEditText.removeCallbacks(saveConfigRunnable)
                portEditText.postDelayed(saveConfigRunnable, 1000)
            }
        })
        
        authTokenEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                authTokenEditText.removeCallbacks(saveConfigRunnable)
                authTokenEditText.postDelayed(saveConfigRunnable, 1000)
            }
        })
        
        updateUI()
    }
    
    private fun initBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter?.isEnabled == true) {
            bleScanner = bluetoothAdapter.bluetoothLeScanner
        }
    }
    
    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
    
    private fun startScan() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
            return
        }
        
        discoveredDevices.clear()
        deviceAdapter.notifyDataSetChanged()
        
        try {
            bleScanner.startScan(scanCallback)
            isScanning = true
            updateUI()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied for scanning", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun stopScan() {
        try {
            bleScanner.stopScan(scanCallback)
            isScanning = false
            updateUI()
        } catch (e: SecurityException) {
            // Ignore
        }
    }
    
    private fun startHttpServer() {
        val port = portEditText.text.toString().toIntOrNull() ?: 8080
        val authToken = authTokenEditText.text.toString().ifBlank { "secret" }
        
        // Save auth token to shared preferences
        getSharedPreferences("btproxy_prefs", MODE_PRIVATE)
            .edit()
            .putString("auth_token", authToken)
            .apply()
        
        val intent = Intent(this, HttpServerService::class.java).apply {
            putExtra("port", port)
            putStringArrayListExtra("selectedDevices", ArrayList(selectedDevices))
        }
        
        startForegroundService(intent)
        statusTextView.text = "HTTP Server starting on port $port"
        addLogEntry("Starting HTTP server on port $port with auth token: $authToken", System.currentTimeMillis())
    }
    
    private fun stopHttpServer() {
        val intent = Intent(this, HttpServerService::class.java)
        stopService(intent)
        isServerRunning = false
        statusTextView.text = "HTTP Server stopped"
        addLogEntry("HTTP Server stopped by user", System.currentTimeMillis())
        updateUI()
    }
    
    private fun updateUI() {
        scanButton.text = if (isScanning) "Stop Scan" else "Start Scan"
        
        when {
            isServerRunning -> {
                startServerButton.text = "Stop HTTP Server"
                startServerButton.isEnabled = true
            }
            selectedDevices.isNotEmpty() -> {
                startServerButton.text = "Start HTTP Server"
                startServerButton.isEnabled = true
            }
            else -> {
                startServerButton.text = "Start HTTP Server"
                startServerButton.isEnabled = false
            }
        }
        
        val deviceText = if (selectedDevices.isEmpty()) "No devices selected" else "Selected devices: ${selectedDevices.size}"
        val serverText = if (isServerRunning) " • Server running" else ""
        statusTextView.text = "$deviceText$serverText"
    }
    
    private fun hasRequiredPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun addLogEntry(message: String, timestamp: Long) {
        val timeStr = dateFormatter.format(Date(timestamp))
        val logEntry = "[$timeStr] $message"
        
        logEntries.add(logEntry)
        if (logEntries.size > 100) {
            logEntries.removeAt(0)
        }
        
        runOnUiThread {
            logTextView.text = logEntries.joinToString("\n")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) {
            stopScan()
        }
        try {
            unregisterReceiver(logReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered
        }
        
        // Save current state before destroying
        saveAppState()
    }
    
    private fun saveAppState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            putString(PREF_PORT, portEditText.text.toString())
            putString(PREF_AUTH_TOKEN, authTokenEditText.text.toString())
            putStringSet(PREF_SELECTED_DEVICES, selectedDevices.toSet())
            putBoolean(PREF_SERVER_RUNNING, isServerRunning)
            
            // Save discovered device MAC addresses
            val discoveredAddresses = discoveredDevices.map { it.address }.toSet()
            putStringSet(PREF_DISCOVERED_DEVICE_ADDRESSES, discoveredAddresses)
            
            apply()
        }
    }
    
    private fun loadAppState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
        // Load configuration
        val savedPort = prefs.getString(PREF_PORT, "8080") ?: "8080"
        val savedAuthToken = prefs.getString(PREF_AUTH_TOKEN, "secret") ?: "secret"
        
        portEditText.setText(savedPort)
        authTokenEditText.setText(savedAuthToken)
        
        // Load selected devices
        val savedSelectedDevices = prefs.getStringSet(PREF_SELECTED_DEVICES, emptySet()) ?: emptySet()
        selectedDevices.clear()
        selectedDevices.addAll(savedSelectedDevices)
        
        // Load server running state
        isServerRunning = prefs.getBoolean(PREF_SERVER_RUNNING, false)
        
        // If server was running, restart it
        if (isServerRunning && selectedDevices.isNotEmpty()) {
            // Delay to ensure UI is ready
            postDelayed({
                addLogEntry("Auto-restarting server from saved state with ${selectedDevices.size} devices", System.currentTimeMillis())
                startHttpServer()
            }, 1000)
        } else {
            isServerRunning = false
        }
        
        // Update UI with loaded state
        updateUI()
        
        // Load previously discovered device addresses for future scans
        val loadedAddresses = prefs.getStringSet(PREF_DISCOVERED_DEVICE_ADDRESSES, emptySet()) ?: emptySet()
        savedDiscoveredAddresses.clear()
        savedDiscoveredAddresses.addAll(loadedAddresses)
        
        if (savedDiscoveredAddresses.isNotEmpty()) {
            addLogEntry("Loaded ${savedDiscoveredAddresses.size} previously discovered devices", System.currentTimeMillis())
            // Try to recreate devices from saved addresses if Bluetooth is available
            recreateKnownDevices()
        }
    }
    
    private fun recreateKnownDevices() {
        if (!hasRequiredPermissions()) return
        
        try {
            savedDiscoveredAddresses.forEach { address ->
                if (BluetoothAdapter.checkBluetoothAddress(address)) {
                    val device = bluetoothAdapter.getRemoteDevice(address)
                    if (!discoveredDevices.any { it.address == device.address }) {
                        discoveredDevices.add(device)
                    }
                }
            }
            
            if (discoveredDevices.isNotEmpty()) {
                runOnUiThread {
                    deviceAdapter.notifyDataSetChanged()
                    addLogEntry("Restored ${discoveredDevices.size} known devices", System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error recreating known devices", e)
        }
    }
}