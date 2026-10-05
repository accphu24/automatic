package com.starclan.starhub.engine

import android.content.Context
import android.util.Log
import com.starclan.starhub.data.HubResponse
import com.starclan.starhub.data.HubResult
import com.starclan.starhub.data.OwoTrackerApi
import com.starclan.starhub.data.OwoTrackerPrefs
import com.starclan.starhub.service.AutoAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

/** Trang thai farm de Hub noi hien thi */
data class FarmState(
    val running: Boolean = false,
    val status: String = "Đang dừng",
    val lastCommand: String = "",
    val hunts: Int = 0,
    val battles: Int = 0,
    val hub: HubResponse? = null,
    val hubFetchedAtMs: Long = 0L,
    val hubError: String? = null,
    val sessionStartIso: String? = null,
    val sessionNewPets: List<String> = emptyList(),
    /** Canh bao can Ruby de y (pet moi, can giai captcha...) - Hub noi tu mo rong khi co */
    val alert: String? = null
)

/**
 * Farm tu dong: App la "tay" go lenh, bot owo-tracker la "mat" doc kenh.
 *
 *  1. Lay link kenh duoc chi dinh tu bot (,setchannel) -> mo Discord vao DUNG kenh.
 *  2. Go `owo zoo`, doi bot doc xong -> biet pet nao da co (ke ca so luong 0).
 *  3. Lap: `owo hunt` -> `owo battle` -> nghi ngau nhien. Thinh thoang nghi dai hon.
 *  4. Lenh thay gem do bot tao: dich vu Accessibility lo (dung chung khoa DiscordSender.sendMutex).
 *  Dung ngay khi gap captcha, hoac go loi nhieu lan lien tiep.
 */
object FarmController {
    private const val TAG = "FarmController"

    // ---- Cau hinh mac dinh (muon doi thi sua o day) ----
    private const val HUNT_COMMAND = "owo hunt"
    private const val BATTLE_COMMAND = "owo battle"
    private const val ZOO_COMMAND = "owo zoo"

    private const val GAP_MIN_MS = 1_500L         // nghi giua hunt va battle
    private const val GAP_MAX_MS = 3_500L
    private const val CYCLE_MIN_MS = 19_000L      // 1 vong (hunt + battle) dai it nhat the nay
    private const val CYCLE_MAX_MS = 27_000L
    private const val REST_EVERY_MIN = 45         // sau 45-80 vong thi nghi dai
    private const val REST_EVERY_MAX = 80
    private const val REST_MIN_MS = 90_000L       // nghi 1.5 - 4 phut
    private const val REST_MAX_MS = 240_000L
    private const val MAX_FAILS_IN_ROW = 3
    private const val ZOO_WAIT_MS = 25_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(FarmState())
    val state: StateFlow<FarmState> = _state

    fun isRunning(): Boolean = job?.isActive == true

    private fun update(block: (FarmState) -> FarmState) {
        _state.value = block(_state.value)
    }

    fun clearAlert() {
        update { it.copy(alert = null) }
    }

    fun start(service: AutoAccessibilityService) {
        if (isRunning()) return
        job = scope.launch {
            try {
                runFarm(service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Farm loi: ${e.message}", e)
                finish("Lỗi bất ngờ: ${(e.message ?: "không rõ").take(100)}", alert = true)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        update { it.copy(running = false, status = "Đã dừng", alert = null) }
    }

    private fun finish(message: String, alert: Boolean = false) {
        job = null
        update { it.copy(running = false, status = message, alert = if (alert) message else null) }
    }

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (e: Exception) {
            null
        }
    }

    /** Lay du lieu Hub moi nhat tu bot, cap nhat trang thai va bao pet moi (neu dang farm). */
    suspend fun refreshHub(context: Context) {
        val s = OwoTrackerPrefs.load(context)
        if (s.apiUrl.isBlank() || s.token.isBlank()) {
            update { it.copy(hubError = "Chưa nhập địa chỉ API / token ở Cài đặt") }
            return
        }
        when (val r = OwoTrackerApi.fetchHub(s.apiUrl, s.token)) {
            is HubResult.Success -> {
                val hub = r.hub
                val cur = _state.value
                val startAt = parseInstant(cur.sessionStartIso)
                var pets = cur.sessionNewPets
                if (startAt != null) {
                    pets = hub.newPets?.pets.orEmpty()
                        .filter { p ->
                            val t = parseInstant(p.firstFoundAt)
                            t != null && !t.isBefore(startAt)
                        }
                        .mapNotNull { p -> p.name }
                }
                val added = pets.filter { name -> !cur.sessionNewPets.contains(name) }
                val newAlert = if (added.isNotEmpty()) "🆕 Pet mới: " + added.joinToString(", ") else cur.alert
                update {
                    it.copy(
                        hub = hub,
                        hubFetchedAtMs = System.currentTimeMillis(),
                        hubError = null,
                        sessionNewPets = pets,
                        alert = newAlert
                    )
                }
            }
            is HubResult.Failure -> update { it.copy(hubError = r.message) }
        }
    }

    private fun describeOpen(r: OpenResult): String = when (r) {
        OpenResult.OK_CHANNEL -> "đã vào kênh"
        OpenResult.OK_APP_ONLY -> "chỉ mở được app Discord, chưa vào đúng kênh"
        OpenResult.OK_LINK_FAILED -> "link kênh bị lỗi"
        OpenResult.NOT_INSTALLED -> "không thấy app Discord"
        OpenResult.LAUNCH_ERROR -> "Android không cho mở Discord"
        OpenResult.NOT_FOREGROUND -> "Discord không lên màn hình (kiểm tra đã mở khóa màn hình chưa)"
    }

    private fun describeSend(r: SendResult): String = when (r) {
        SendResult.OK -> "ok"
        SendResult.DISCORD_NOT_OPEN -> "không mở được Discord"
        SendResult.NO_INPUT -> "không thấy ô nhập tin nhắn"
        SendResult.TYPE_FAILED -> "không gõ được chữ vào ô nhập"
        SendResult.SEND_FAILED -> "không bấm gửi được"
        SendResult.CAPTCHA -> "OwO đang đòi captcha"
    }

    private suspend fun sendCommand(
        sender: DiscordSender,
        settings: OwoTrackerPrefs.Settings,
        text: String
    ): SendResult {
        update { it.copy(lastCommand = text) }
        return try {
            sender.send(text, settings, reopenChannel = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gui '$text' loi: ${e.message}")
            SendResult.SEND_FAILED
        }
    }

    private suspend fun CoroutineScope.runFarm(service: AutoAccessibilityService) {
        val ctx = service.applicationContext
        var settings = OwoTrackerPrefs.load(ctx)
        if (settings.apiUrl.isBlank() || settings.token.isBlank()) {
            finish("Chưa nhập địa chỉ API / token ở Cài đặt")
            return
        }

        val startIso = Instant.now().toString()
        update {
            it.copy(
                running = true, status = "Đang chuẩn bị…", hunts = 0, battles = 0,
                sessionStartIso = startIso, sessionNewPets = emptyList(), alert = null
            )
        }

        // --- 1. Tim kenh duoc chi dinh ---
        refreshHub(ctx)
        var link = settings.discordLink.trim()
        if (link.isBlank()) {
            val auto = _state.value.hub?.channel?.link.orEmpty()
            if (auto.isNotBlank()) {
                link = auto
                OwoTrackerPrefs.saveDiscordLink(ctx, auto)
                settings = OwoTrackerPrefs.load(ctx)
            }
        }
        if (link.isBlank()) {
            finish("Chưa biết kênh nào để farm. Gõ ,setchannel trong kênh Discord (cho bot), hoặc dán link kênh ở Cài đặt")
            return
        }

        // --- 2. Mo Discord vao dung kenh (bat buoc vao dung kenh moi go lenh) ---
        update { it.copy(status = "Đang mở Discord…") }
        val sender = DiscordSender(service)
        val opened = sender.openDiscordDetailed(link)
        if (opened != OpenResult.OK_CHANNEL) {
            finish("Không vào được kênh: " + describeOpen(opened), alert = true)
            return
        }
        if (!settings.enabled) {
            update {
                it.copy(alert = "ℹ️ Ô \"Bật tự động kiểm tra lệnh\" trong Cài đặt đang tắt — gem sẽ không tự thay")
            }
        }

        // --- 3. Check zoo luc bat dau ---
        update { it.copy(status = "Kiểm tra zoo…") }
        val zooSentAt = System.currentTimeMillis()
        val zooResult = sendCommand(sender, settings, ZOO_COMMAND)
        if (zooResult == SendResult.CAPTCHA) {
            finish("⚠️ OwO đòi captcha — đã dừng. Giải captcha rồi bấm Bắt đầu lại", alert = true)
            return
        }
        if (zooResult == SendResult.OK) {
            val waitStart = System.currentTimeMillis()
            var zooRead = false
            while (isActive && System.currentTimeMillis() - waitStart < ZOO_WAIT_MS) {
                delay(3_000)
                refreshHub(ctx)
                val age = _state.value.hub?.zoo?.ageSeconds
                if (age != null && age < (System.currentTimeMillis() - zooSentAt) / 1000 + 4) {
                    zooRead = true
                    break
                }
            }
            if (!zooRead) Log.w(TAG, "Chua xac nhan bot da doc zoo - van farm")
        } else {
            finish("Không gõ được lệnh zoo: " + describeSend(zooResult), alert = true)
            return
        }

        // --- 4. Vong farm ---
        var failsInRow = 0
        var cyclesUntilRest = Random.nextInt(REST_EVERY_MIN, REST_EVERY_MAX + 1)

        while (isActive) {
            val cycleStart = System.currentTimeMillis()

            // hunt
            update { it.copy(status = "Đang farm…") }
            val huntResult = sendCommand(sender, settings, HUNT_COMMAND)
            if (huntResult == SendResult.CAPTCHA) {
                finish("⚠️ OwO đòi captcha — đã dừng. Giải captcha rồi bấm Bắt đầu lại", alert = true)
                return
            }
            if (huntResult == SendResult.OK) {
                failsInRow = 0
                update { it.copy(hunts = it.hunts + 1) }
            } else {
                failsInRow++
                if (failsInRow >= MAX_FAILS_IN_ROW) {
                    finish("Gõ lệnh lỗi $failsInRow lần liên tiếp (${describeSend(huntResult)}) — đã dừng", alert = true)
                    return
                }
                update { it.copy(status = "Lỗi gõ lệnh (${describeSend(huntResult)}), thử lại…") }
                delay(4_000)
                continue
            }

            delay(Random.nextLong(GAP_MIN_MS, GAP_MAX_MS))

            // battle
            val battleResult = sendCommand(sender, settings, BATTLE_COMMAND)
            if (battleResult == SendResult.CAPTCHA) {
                finish("⚠️ OwO đòi captcha — đã dừng. Giải captcha rồi bấm Bắt đầu lại", alert = true)
                return
            }
            if (battleResult == SendResult.OK) {
                failsInRow = 0
                update { it.copy(battles = it.battles + 1) }
            } else {
                failsInRow++
                if (failsInRow >= MAX_FAILS_IN_ROW) {
                    finish("Gõ lệnh lỗi $failsInRow lần liên tiếp (${describeSend(battleResult)}) — đã dừng", alert = true)
                    return
                }
            }

            refreshHub(ctx)

            // nghi dai thinh thoang cho giong nguoi that
            cyclesUntilRest--
            if (cyclesUntilRest <= 0) {
                cyclesUntilRest = Random.nextInt(REST_EVERY_MIN, REST_EVERY_MAX + 1)
                val restMs = Random.nextLong(REST_MIN_MS, REST_MAX_MS)
                val restEnd = System.currentTimeMillis() + restMs
                while (isActive && System.currentTimeMillis() < restEnd) {
                    val left = (restEnd - System.currentTimeMillis()) / 1000
                    update { it.copy(status = "Nghỉ giải lao, còn ${left}s…") }
                    delay(5_000)
                }
                continue
            }

            // doi cho du 1 vong
            val target = Random.nextLong(CYCLE_MIN_MS, CYCLE_MAX_MS)
            val elapsed = System.currentTimeMillis() - cycleStart
            if (elapsed < target) delay(target - elapsed) else delay(1_500)
        }
    }
}
