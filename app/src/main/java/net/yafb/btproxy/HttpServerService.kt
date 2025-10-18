package net.yafb.btproxy

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

class HttpServerService : Service() {
    
    private lateinit var bleConnectionManager: BleConnectionManager
    private var server: NettyApplicationEngine? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    companion object {
        private const val TAG = "HttpServerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "btproxy_channel"
    }
    
    override fun onCreate() {
        super.onCreate()
        bleConnectionManager = BleConnectionManager(this)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8080) ?: 8080
        val selectedDevices = intent?.getStringArrayListExtra("selectedDevices") ?: arrayListOf()
        
        startForeground(NOTIFICATION_ID, createNotification(port))
        
        serviceScope.launch {
            // Stop existing server if running
            stopHttpServer()
            
            connectToSelectedDevices(selectedDevices)
            startHttpServer(port)
        }
        
        return START_STICKY
    }
    
    private suspend fun connectToSelectedDevices(deviceAddresses: List<String>) {
        // Set target devices for automatic reconnection and queuing
        bleConnectionManager.setTargetDevices(deviceAddresses)
        Log.d(TAG, "Set target devices: ${deviceAddresses.joinToString(", ")}")
        logToMainActivity("Target devices configured: ${deviceAddresses.size} devices")
    }
    
    private suspend fun startHttpServer(port: Int) {
        try {
            server = embeddedServer(Netty, port = port) {
                routing {
                    // Simple text ping - no JSON serialization
                    get("/ping") {
                        try {
                            Log.d(TAG, "Ping endpoint called")
                            call.respondText("pong", ContentType.Text.Plain)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in /ping endpoint", e)
                            logToMainActivity("ERROR in /ping: ${e.message}")
                            call.respondText("Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                    // Simple text test
                    get("/simple") {
                        try {
                            Log.d(TAG, "Simple endpoint called")
                            call.respondText("BT Proxy Server is running on port $port", ContentType.Text.Plain)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in /simple endpoint", e)
                            call.respondText("Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                    
                    // Health check endpoint - no authentication required
                    get("/health") {
                        try {
                            val connectedDevices = bleConnectionManager.getConnectedDevices()
                            val response = "status: healthy\nserver: running\nconnected_devices_count: ${connectedDevices.size}\ntimestamp: ${System.currentTimeMillis()}"
                            call.respondText(response, ContentType.Text.Plain)
                        } catch (e: Exception) {
                            call.respondText("ERROR: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                    // Test endpoint - no authentication required
                    get("/test") {
                        try {
                            val response = """BT Proxy HTTP Server is accessible
port: $port

Public endpoints:
/ping
/health
/test
/status
/devices

Authenticated endpoints (require X-BtProxy header):
/{macAddress}/{characteristicUuid}
/connect/{macAddress}
/disconnect/{macAddress}"""
                            call.respondText(response, ContentType.Text.Plain)
                        } catch (e: Exception) {
                            call.respondText("ERROR: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                    get("/status") {
                        try {
                            val connectedDevices = bleConnectionManager.getConnectedDevices()
                            val deviceList = if (connectedDevices.isEmpty()) "none" else connectedDevices.joinToString("\n")
                            val response = "status: running\nconnected_devices:\n$deviceList"
                            call.respondText(response, ContentType.Text.Plain)
                        } catch (e: Exception) {
                            call.respondText("ERROR: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                    get("/{macAddress}/{characteristicUuid}") {
                        val macAddress = call.parameters["macAddress"]!!
                        val characteristicUuid = call.parameters["characteristicUuid"]!!
                        
                        if (!bleConnectionManager.isDeviceConnected(macAddress)) {
                            call.respondText("ERROR: Device not connected", ContentType.Text.Plain, HttpStatusCode.NotFound)
                            return@get
                        }
                        
                        // Check authentication header
                        val authHeader = call.request.headers["X-BtProxy"]
                        val expectedToken = getSharedPreferences("btproxy_prefs", MODE_PRIVATE)
                            .getString("auth_token", "secret") ?: "secret"
                        
                        if (authHeader != expectedToken) {
                            call.respondText("ERROR: Invalid authentication token", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                            return@get
                        }
                        
                        val data = bleConnectionManager.readCharacteristic(macAddress, characteristicUuid)
                        if (data != null) {
                            val hexString = data.joinToString(" ") { "%02X".format(it) }
                            logToMainActivity("GET /$macAddress/$characteristicUuid -> $hexString")
                            call.respondText(hexString, ContentType.Text.Plain)
                        } else {
                            logToMainActivity("GET /$macAddress/$characteristicUuid -> FAILED")
                            call.respondText("ERROR: Failed to read characteristic", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                    post("/{macAddress}/{characteristicUuid}") {
                        val macAddress = call.parameters["macAddress"]!!
                        val characteristicUuid = call.parameters["characteristicUuid"]!!
                        
                        // Check authentication header
                        val authHeader = call.request.headers["X-BtProxy"]
                        val expectedToken = getSharedPreferences("btproxy_prefs", MODE_PRIVATE)
                            .getString("auth_token", "secret") ?: "secret"
                        
                        if (authHeader != expectedToken) {
                            call.respondText("ERROR: Invalid authentication token", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
                            return@post
                        }
                        
                        if (!bleConnectionManager.isDeviceConnected(macAddress)) {
                            call.respondText("ERROR: Device not connected", ContentType.Text.Plain, HttpStatusCode.NotFound)
                            return@post
                        }
                        
                        try {
                            val body = call.receiveText().trim()
                            logToMainActivity("POST /$macAddress/$characteristicUuid <- $body")
                            
                            // Parse space-separated hex bytes (e.g., "A0 81 D0")
                            val data = parseSpaceHexString(body)
                            
                            if (data != null) {
                                val success = bleConnectionManager.writeCharacteristic(macAddress, characteristicUuid, data)
                                if (success) {
                                    logToMainActivity("POST /$macAddress/$characteristicUuid -> SUCCESS")
                                    call.respondText("OK", ContentType.Text.Plain)
                                } else {
                                    logToMainActivity("POST /$macAddress/$characteristicUuid -> FAILED")
                                    call.respondText("ERROR: Failed to write characteristic", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                                }
                            } else {
                                logToMainActivity("POST /$macAddress/$characteristicUuid -> INVALID_FORMAT")
                                call.respondText("ERROR: Invalid data format. Use space-separated hex bytes (e.g., 'A0 81 D0')", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing POST request", e)
                            logToMainActivity("POST /$macAddress/$characteristicUuid -> ERROR: ${e.message}")
                            call.respondText("ERROR: Invalid request: ${e.message}", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                        }
                    }
                    
                    get("/devices") {
                        try {
                            val connectedDevices = bleConnectionManager.getConnectedDevices()
                            val deviceList = if (connectedDevices.isEmpty()) "" else connectedDevices.joinToString("\n")
                            call.respondText(deviceList, ContentType.Text.Plain)
                        } catch (e: Exception) {
                            call.respondText("ERROR: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                        }
                    }
                    
                }
            }.start(wait = false)
            
            Log.d(TAG, "HTTP Server started on port $port")
            logToMainActivity("HTTP Server started successfully on port $port")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            logToMainActivity("ERROR: Failed to start HTTP server: ${e.message}")
            
            if (e.message?.contains("Address already in use") == true) {
                logToMainActivity("Port $port is already in use. Try a different port or restart the app.")
            }
        }
    }
    
    private fun parseSpaceHexString(hexString: String): ByteArray? {
        return try {
            if (hexString.isBlank()) return byteArrayOf()
            
            hexString.trim()
                .split(Regex("\\s+"))
                .map { it.trim().toInt(16).toByte() }
                .toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse space-separated hex string: $hexString", e)
            null
        }
    }
    
    private suspend fun stopHttpServer() {
        try {
            server?.let {
                Log.d(TAG, "Stopping existing HTTP server...")
                it.stop(1000, 2000)
                server = null
                Log.d(TAG, "HTTP server stopped")
                logToMainActivity("HTTP server stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP server", e)
        }
    }
    
    private fun logToMainActivity(message: String) {
        val intent = Intent("net.yafb.btproxy.LOG").apply {
            putExtra("message", message)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(intent)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BT Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BT Proxy HTTP Server"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(port: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Proxy Server")
            .setContentText("HTTP server running on port $port")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            server?.stop(1000, 2000)
            bleConnectionManager.shutdown()
        }
        serviceJob.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}