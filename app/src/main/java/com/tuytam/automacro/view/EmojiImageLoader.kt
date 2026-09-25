package com.tuytam.automacro.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Tai anh emoji that cua Discord va giu tam trong bo nho (mat khi dong app,
 * khong ghi ra the nho). Moi URL chi tai 1 lan trong ca phien lam viec; URL
 * da tai loi thi khong thu lai nua trong phien do (tranh spam mang khi cham
 * lam moi Hub lien tuc ma mang dang yeu).
 */
object EmojiImageLoader {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(url: String): Bitmap? {
        cache[url]?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    cache[url] = bmp
                    bmp
                } else {
                    failed += url
                    null
                }
            } catch (e: Exception) {
                failed += url
                null
            }
        }
    }
}
