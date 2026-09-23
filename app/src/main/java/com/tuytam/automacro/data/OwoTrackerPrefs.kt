package com.tuytam.automacro.data

import android.content.Context

/**
 * Luu cai dat ket noi owo-tracker: dia chi API, token, bat/tat, va vi tri
 * (toa do) o nhap tin nhan + nut Gui tren Discord - cai 1 lan duy nhat,
 * dung lai cho MOI lenh sau nay tu owo-tracker gui toi (khong can tao
 * rieng 1 kich ban cho tung loai lenh).
 */
object OwoTrackerPrefs {
    private const val PREFS_NAME = "owo_tracker_prefs"
    private const val KEY_API_URL = "api_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MSG_X = "msg_x"
    private const val KEY_MSG_Y = "msg_y"
    private const val KEY_SEND_X = "send_x"
    private const val KEY_SEND_Y = "send_y"

    data class Settings(
        val enabled: Boolean,
        val apiUrl: String,
        val token: String,
        val messageBoxX: Float,
        val messageBoxY: Float,
        val sendButtonX: Float,
        val sendButtonY: Float
    ) {
        /** True neu Ruby da cham qua wizard "Cai vi tri Discord" it nhat 1 lan */
        val hasDiscordTargets: Boolean
            get() = messageBoxX >= 0f && messageBoxY >= 0f && sendButtonX >= 0f && sendButtonY >= 0f
    }

    fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            apiUrl = p.getString(KEY_API_URL, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: "",
            messageBoxX = p.getFloat(KEY_MSG_X, -1f),
            messageBoxY = p.getFloat(KEY_MSG_Y, -1f),
            sendButtonX = p.getFloat(KEY_SEND_X, -1f),
            sendButtonY = p.getFloat(KEY_SEND_Y, -1f)
        )
    }

    fun saveConnection(context: Context, enabled: Boolean, apiUrl: String, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_API_URL, apiUrl)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun saveDiscordTargets(context: Context, msgX: Float, msgY: Float, sendX: Float, sendY: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_MSG_X, msgX)
            .putFloat(KEY_MSG_Y, msgY)
            .putFloat(KEY_SEND_X, sendX)
            .putFloat(KEY_SEND_Y, sendY)
            .apply()
    }
}
