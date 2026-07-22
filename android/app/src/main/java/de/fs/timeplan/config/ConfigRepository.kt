package de.fs.timeplan.config

import android.content.Context

data class ServerConfig(
    val baseUrl: String,
    val deviceId: String,
    val token: String
)

class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return ServerConfig(baseUrl, deviceId, token)
    }

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_DEVICE_ID, config.deviceId)
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "timeplan_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "token"
    }
}
