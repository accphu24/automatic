package com.tuytam.automacro.view

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tai anh emoji that cua Discord va giu tam trong bo nho (mat khi dong app, khong ghi ra
 * the nho). Moi link chi tai 1 lan du nhieu noi cung can; link tai loi duoc thu lai sau
 * [RETRY_AFTER_MS] hoac ngay khi goi [retryFailed] (vd bam Lam moi). Chi tiet xem [AsyncLoadCache].
 */
object EmojiImageLoader {
    private const val TAG = "EmojiImageLoader"
    private const val RETRY_AFTER_MS = 90_000L

    private val cache = AsyncLoadCache<Bitmap>(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        retryAfterMs = RETRY_AFTER_MS,
        now = { SystemClock.elapsedRealtime() },
        loader = { url -> download(url) }
    )

    suspend fun load(url: String): Bitmap? = cache.get(url)

    fun retryFailed() = cache.retryFailed()

    private fun download(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w(TAG, "tai icon loi ($url): ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
