package de.fs.timeplan.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.fs.timeplan.R
import de.fs.timeplan.config.ConfigRepository
import de.fs.timeplan.config.ServerConfig
import de.fs.timeplan.ui.enableImmersiveFullscreen

class SettingsActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var baseUrlField: EditText
    private lateinit var deviceIdField: EditText
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()
        setContentView(R.layout.activity_settings)
        configRepository = ConfigRepository(applicationContext)

        baseUrlField = findViewById(R.id.fieldBaseUrl)
        deviceIdField = findViewById(R.id.fieldDeviceId)
        tokenField = findViewById(R.id.fieldToken)

        configRepository.load()?.let { config ->
            baseUrlField.setText(config.baseUrl)
            deviceIdField.setText(config.deviceId)
            tokenField.setText(config.token)
        }

        findViewById<Button>(R.id.buttonSave).setOnClickListener { onSave() }

        findViewById<Button>(R.id.buttonClearConfig).setOnClickListener {
            configRepository.clear()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }

    private fun onSave() {
        val baseUrl = baseUrlField.text.toString().trim()
        val deviceId = deviceIdField.text.toString().trim()
        val token = tokenField.text.toString().trim()

        if (baseUrl.isEmpty() || deviceId.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, R.string.settings_incomplete, Toast.LENGTH_SHORT).show()
            return
        }

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            Toast.makeText(this, R.string.settings_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        configRepository.save(ServerConfig(baseUrl, deviceId, token))
        finish()
    }
}
