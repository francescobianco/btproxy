package net.yafb.btproxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object MonitoringHelper {

    private const val TAG = "MonitoringHelper"
    private const val TIMEOUT_MS = 10_000

    suspend fun postEvent(context: Context, event: String, extras: Map<String, Any> = emptyMap()) {
        val url = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsActivity.PREF_MONITORING_URL, "")
            ?.trim()
            .orEmpty()

        if (url.isEmpty()) return

        val payload = buildJsonObject(
            mapOf("event" to event, "timestamp" to System.currentTimeMillis()) + extras
        )

        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val responseCode = connection.responseCode
                connection.disconnect()
                Log.d(TAG, "Monitoring event '$event' sent, response: $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send monitoring event '$event'", e)
            }
        }
    }

    private fun buildJsonObject(map: Map<String, Any>): String {
        val entries = map.entries.joinToString(", ") { (k, v) ->
            val value = when (v) {
                is String -> "\"${v.replace("\"", "\\\"")}\""
                is Number -> v.toString()
                is Boolean -> v.toString()
                else -> "\"$v\""
            }
            "\"$k\": $value"
        }
        return "{$entries}"
    }
}
