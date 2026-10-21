package com.starclan.starhub.data

import android.content.Context

/**
 * Luu ban Hub (JSON goc cua bot) lan thanh cong gan nhat vao may. Lan mo app sau, man Hub hien NGAY ban cu nay
 * (kem "luu luc ...") trong luc app lay du lieu moi tu bot, thay vi de trong 2-3 giay.
 */
object HubCache {
    private const val PREFS = "hub_cache"
    private const val KEY_RAW = "raw"
    private const val KEY_AT = "saved_at_ms"

    class Cached(val raw: String, val savedAtMs: Long)

    fun save(context: Context, raw: String) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_RAW, raw)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // Cache chi la tien ich: loi ghi thi bo qua
        }
    }

    fun load(context: Context): Cached? = try {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_RAW, null)
        if (raw.isNullOrBlank()) null else Cached(raw, p.getLong(KEY_AT, 0L))
    } catch (e: Exception) {
        null
    }
}
