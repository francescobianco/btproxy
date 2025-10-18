package net.yafb.btproxy

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
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
            connectToSelectedDevices(selectedDevices)
            startHttpServer(port)
        }
        
        return START_STICKY
    }
    
    private suspend fun connectToSelectedDevices(deviceAddresses: List<String>) {
        deviceAddresses.forEach { address ->
            serviceScope.launch {
                val connected = bleConnectionManager.connectToDevice(address)
                Log.d(TAG, "Connection to $address: $connected")
            }
        }
    }
    
    private suspend fun startHttpServer(port: Int) {
        try {
            server = embeddedServer(Netty, port = port) {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    // Ping endpoint - no authentication required
                    get("/ping") {
                        call.respond(mapOf<String, Any>(
                            "message" to "pong",
                            "server" to "btproxy",
                            "timestamp" to System.currentTimeMillis(),
                            "version" to "1.0"
                        ))
                    }
                    
                    // Health check endpoint - no authentication required
                    get("/health") {
                        val connectedDevices = bleConnectionManager.getConnectedDevices()
                        call.respond(mapOf<String, Any>(
                            "status" to "healthy",
                            "server" to "running",
                            "connected_devices_count" to connectedDevices.size,
                            "timestamp" to System.currentTimeMillis()
                        ))
                    }
                    
                    // Test endpoint - no authentication required
                    get("/test") {
                        call.respond(mapOf<String, Any>(
                            "message" to "BT Proxy HTTP Server is accessible",
                            "port" to port,
                            "endpoints" to listOf("/ping", "/health", "/test", "/status", "/devices"),
                            "authenticated_endpoints" to listOf("/{macAddress}/{characteristicUuid}", "/connect/{macAddress}", "/disconnect/{macAddress}"),
                            "auth_header_required" to "X-BtProxy"
                        ))
                    }
                    
                    get("/status") {
                        val connectedDevices = bleConnectionManager.getConnectedDevices()
                        call.respond(mapOf<String, Any>(
                            "status" to "running",
                            "connected_devices" to connectedDevices
                        ))
                    }
                    
                    get("/{macAddress}/{characteristicUuid}") {
                        val macAddress = call.parameters["macAddress"]!!
                        val characteristicUuid = call.parameters["characteristicUuid"]!!
                        
                        if (!bleConnectionManager.isDeviceConnected(macAddress)) {
                            call.respond(HttpStatusCode.NotFound, mapOf<String, String>("error" to "Device not connected"))
                            return@get
                        }
                        
                        // Check authentication header
                        val authHeader = call.request.headers["X-BtProxy"]
                        val expectedToken = getSharedPreferences("btproxy_prefs", MODE_PRIVATE)
                            .getString("auth_token", "secret") ?: "secret"
                        
                        if (authHeader != expectedToken) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf<String, String>("error" to "Invalid authentication token"))
                            return@get
                        }
                        
                        val data = bleConnectionManager.readCharacteristic(macAddress, characteristicUuid)
                        if (data != null) {
                            val hexString = data.joinToString(" ") { "%02X".format(it) }
                            logToMainActivity("GET /$macAddress/$characteristicUuid -> $hexString")
                            call.respond(mapOf<String, Any>(
                                "success" to true,
                                "data" to hexString
                            ))
                        } else {
                            logToMainActivity("GET /$macAddress/$characteristicUuid -> FAILED")
                            call.respond(HttpStatusCode.InternalServerError, mapOf<String, String>("error" to "Failed to read characteristic"))
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
                            call.respond(HttpStatusCode.Unauthorized, mapOf<String, String>("error" to "Invalid authentication token"))
                            return@post
                        }
                        
                        if (!bleConnectionManager.isDeviceConnected(macAddress)) {
                            call.respond(HttpStatusCode.NotFound, mapOf<String, String>("error" to "Device not connected"))
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
                                    call.respond(mapOf<String, Boolean>("success" to true))
                                } else {
                                    logToMainActivity("POST /$macAddress/$characteristicUuid -> FAILED")
                                    call.respond(HttpStatusCode.InternalServerError, mapOf<String, String>("error" to "Failed to write characteristic"))
                                }
                            } else {
                                logToMainActivity("POST /$macAddress/$characteristicUuid -> INVALID_FORMAT")
                                call.respond(HttpStatusCode.BadRequest, mapOf<String, String>("error" to "Invalid data format. Use space-separated hex bytes (e.g., 'A0 81 D0')"))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing POST request", e)
                            logToMainActivity("POST /$macAddress/$characteristicUuid -> ERROR: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf<String, String>("error" to "Invalid request: ${e.message}"))
                        }
                    }
                    
                    get("/devices") {
                        val connectedDevices = bleConnectionManager.getConnectedDevices()
                        call.respond(mapOf<String, List<String>>("devices" to connectedDevices))
                    }
                    
                    post("/connect/{macAddress}") {
                        val macAddress = call.parameters["macAddress"]!!
                        val connected = bleConnectionManager.connectToDevice(macAddress)
                        call.respond(mapOf<String, Any>(
                            "success" to connected,
                            "device" to macAddress
                        ))
                    }
                    
                    post("/disconnect/{macAddress}") {
                        val macAddress = call.parameters["macAddress"]!!
                        bleConnectionManager.disconnectDevice(macAddress)
                        call.respond(mapOf<String, Any>(
                            "success" to true,
                            "device" to macAddress
                        ))
                    }
                }
            }.start(wait = false)
            
            Log.d(TAG, "HTTP Server started on port $port")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
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
            bleConnectionManager.disconnectAll()
        }
        serviceJob.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}