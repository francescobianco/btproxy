package com.example.btproxy

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
    private var isScanning = false
    private val logEntries = mutableListOf<String>()
    private val dateFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.btproxy.LOG") {
                val message = intent.getStringExtra("message") ?: return
                val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
                addLogEntry(message, timestamp)
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
        registerReceiver(logReceiver, IntentFilter("com.example.btproxy.LOG"))
        
        addLogEntry("BT Proxy started", System.currentTimeMillis())
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
            startHttpServer()
        }
        
        portEditText.setText("8080")
        authTokenEditText.setText("secret")
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
    
    private fun updateUI() {
        scanButton.text = if (isScanning) "Stop Scan" else "Start Scan"
        startServerButton.isEnabled = selectedDevices.isNotEmpty()
        statusTextView.text = "Selected devices: ${selectedDevices.size}"
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
    }
}