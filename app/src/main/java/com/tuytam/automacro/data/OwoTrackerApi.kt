package com.tuytam.automacro.data

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "OwoTrackerApi"

/** 1 lenh nhan duoc tu owo-tracker qua GET /commands/pending */
data class PendingCommand(
    val id: Int,
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val status: String = "pending"
)

private data class PendingCommandsResponse(val commands: List<PendingCommand> = emptyList())

/**
 * Goi API nho cua owo-tracker (xem README/phan "Ket noi voi AutoMacro" ben
 * repo owo-tracker). Dung HttpURLConnection co san trong Android, khong can
 * them thu vien ngoai.
 */
object OwoTrackerApi {
    private val gson = Gson()

    /** Lay danh sach lenh dang cho. Loi mang -> tra ve danh sach rong, khong lam crash. */
    suspend fun fetchPending(apiUrl: String, token: String): List<PendingCommand> = withContext(Dispatchers.IO) {
        try {
            val url = URL(joinUrl(apiUrl, "/commands/pending"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "fetchPending: HTTP $code")
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().readText()
            gson.fromJson(body, PendingCommandsResponse::class.java)?.commands ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchPending loi: ${e.message}")
            emptyList()
        }
    }

    /** Bao owo-tracker biet 1 lenh da xu ly xong (hoac that bai), de no khong gui lai lenh do nua. */
    suspend fun ack(apiUrl: String, token: String, commandId: Int, status: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(joinUrl(apiUrl, "/commands/$commandId/ack"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write("""{"status":"$status"}""".toByteArray()) }
            conn.responseCode // can doc de request thuc su duoc gui di
        } catch (e: Exception) {
            Log.w(TAG, "ack loi: ${e.message}")
        }
    }

    private fun joinUrl(base: String, path: String): String = base.trimEnd('/') + path
}
