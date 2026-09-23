package com.tuytam.automacro.data

import android.content.Context

/** Luu cai dat ket noi owo-tracker (dia chi API, token, bat/tat) tren may. */
object OwoTrackerPrefs {
    private const val PREFS_NAME = "owo_tracker_prefs"
    private const val KEY_API_URL = "api_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"

    data class Settings(val enabled: Boolean, val apiUrl: String, val token: String)

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            apiUrl = p.getString(KEY_API_URL, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: ""
        )
    }

    fun save(context: Context, enabled: Boolean, apiUrl: String, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_API_URL, apiUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }
}
