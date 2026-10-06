package com.starclan.starhub.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
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

/** GET /alerts — canh bao nhe, goi thuong xuyen luc dang farm */
private data class CaptchaInfo(val active: Boolean? = null)
private data class ActivityInfo(
    @SerializedName("last_reply_age_seconds") val lastReplyAgeSeconds: Long? = null,
    @SerializedName("last_command_age_seconds") val lastCommandAgeSeconds: Long? = null
)
private data class AlertsResponse(val captcha: CaptchaInfo? = null, val activity: ActivityInfo? = null)

/** Ket qua hoi bot: OwO co dang doi captcha khong, va OwO tra loi lenh gan nhat cach day bao lau */
data class AlertInfo(
    val captchaActive: Boolean,
    val lastReplyAgeSeconds: Long?,
    val lastCommandAgeSeconds: Long?
)

/** Ket qua goi GET /hub — tach ro thanh cong / that bai de man hinh Hub bao loi cho de hieu. */
sealed class HubResult {
    data class Success(val hub: HubResponse) : HubResult()
    data class Failure(val message: String) : HubResult()
}

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

    /**
     * Lay du lieu tong hop cho man hinh Hub (GET /hub). Khac fetchPending o cho:
     * tra ve ro thanh cong hay that bai (kem cau bao loi bang tieng Viet) de
     * man hinh Hub hien cho Ruby biet sai o dau (sai token, chua cap nhat bot...).
     */
    suspend fun fetchHub(apiUrl: String, token: String): HubResult = withContext<HubResult>(Dispatchers.IO) {
        try {
            val url = URL(joinUrl(apiUrl, "/hub"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            val code = conn.responseCode
            if (code == 401) {
                return@withContext HubResult.Failure("Token chưa đúng (401) — kiểm tra lại trong ⚙️ Kết nối owo-tracker")
            }
            if (code == 404) {
                return@withContext HubResult.Failure("Bot chưa có đường /hub (404) — cần cập nhật bot lên bản mới")
            }
            if (code != 200) {
                Log.w(TAG, "fetchHub: HTTP $code")
                return@withContext HubResult.Failure("Bot báo lỗi (HTTP $code)")
            }
            val body = conn.inputStream.bufferedReader().readText()
            if (body.isBlank()) {
                HubResult.Failure("Bot trả về dữ liệu trống")
            } else {
                HubResult.Success(HubParser.parse(body))
            }
        } catch (e: JsonParseException) {
            Log.w(TAG, "fetchHub doc JSON loi: ${e.message}")
            HubResult.Failure("Dữ liệu từ bot không đọc được (${(e.message ?: "lỗi không rõ").take(120)})")
        } catch (e: Exception) {
            Log.w(TAG, "fetchHub loi: ${e.message}")
            HubResult.Failure("Không kết nối được tới bot — kiểm tra mạng và địa chỉ API")
        }
    }

    /**
     * Hoi bot (GET /alerts). farmFlag: "1" = dang farm, "2" = dang nghi giai lao, "0" = vua dung farm,
     * null = chi hoi. Bot dung co nay de canh: mat ket noi dien thoai / OwO im lang thi nhan tin tag Ruby.
     * Tra ve null neu khong hoi duoc (mang loi / bot chua cap nhat).
     */
    suspend fun fetchAlerts(apiUrl: String, token: String, farmFlag: String?): AlertInfo? = withContext(Dispatchers.IO) {
        try {
            val query = if (farmFlag == null) "" else "?farm=$farmFlag"
            val url = URL(joinUrl(apiUrl, "/alerts$query"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            if (conn.responseCode != 200) {
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            val parsed = gson.fromJson(body, AlertsResponse::class.java) ?: return@withContext null
            AlertInfo(
                captchaActive = parsed.captcha?.active == true,
                lastReplyAgeSeconds = parsed.activity?.lastReplyAgeSeconds,
                lastCommandAgeSeconds = parsed.activity?.lastCommandAgeSeconds
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchAlerts loi: ${e.message}")
            null
        }
    }

    /** Chi hoi captcha: true/false, null = khong hoi duoc */
    suspend fun fetchCaptchaActive(apiUrl: String, token: String): Boolean? =
        fetchAlerts(apiUrl, token, null)?.captchaActive

    /** Nho bot nhan tin tag Ruby tren Discord (de dien thoai/may khac cung bao) */
    suspend fun notify(apiUrl: String, token: String, text: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL(joinUrl(apiUrl, "/notify"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val payload = gson.toJson(mapOf("text" to text))
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) {
            Log.w(TAG, "notify loi: ${e.message}")
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
