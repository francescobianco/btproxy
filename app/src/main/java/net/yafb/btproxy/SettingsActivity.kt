package net.yafb.btproxy

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var portEditText: EditText
    private lateinit var authTokenEditText: EditText
    private lateinit var monitoringUrlEditText: EditText

    companion object {
        const val PREFS_NAME = "btproxy_app_state"
        const val PREF_PORT = "server_port"
        const val PREF_AUTH_TOKEN = "auth_token"
        const val PREF_MONITORING_URL = "monitoring_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        portEditText = findViewById(R.id.portEditText)
        authTokenEditText = findViewById(R.id.authTokenEditText)
        monitoringUrlEditText = findViewById(R.id.monitoringUrlEditText)

        loadSettings()

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveSettings()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        portEditText.setText(prefs.getString(PREF_PORT, "8080"))
        authTokenEditText.setText(prefs.getString(PREF_AUTH_TOKEN, "secret"))
        monitoringUrlEditText.setText(prefs.getString(PREF_MONITORING_URL, ""))
    }

    private fun saveSettings() {
        val port = portEditText.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }
        val authToken = authTokenEditText.text.toString().ifBlank { "secret" }
        val monitoringUrl = monitoringUrlEditText.text.toString().trim()

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
            putString(PREF_PORT, port.toString())
            putString(PREF_AUTH_TOKEN, authToken)
            putString(PREF_MONITORING_URL, monitoringUrl)
            apply()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
