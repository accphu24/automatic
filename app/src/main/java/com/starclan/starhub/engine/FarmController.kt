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
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.Calendar
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
    val alert: String? = null,
    /** Dang reo chuong */
    val ringing: Boolean = false,
    /** Dang nghi (giai lao / nghi theo phien / gio yen lang) - khong go lenh */
    val resting: Boolean = false
)

/**
 * Farm tu dong: App la "tay" go lenh, bot owo-tracker la "mat" doc kenh.
 *
 *  1. Lay link kenh duoc chi dinh tu bot (,setchannel) -> mo Discord vao DUNG kenh.
 *  2. Go `owo zoo`, doi bot doc xong -> biet pet nao da co (ke ca so luong 0).
 *  3. Lap: viec vat (daily, quest...) -> `owo hunt` -> `owo battle` -> nghi ngau nhien.
 *  4. Treo lau: nghi giai lao, nghi theo phien (chay 1.5-2.5 gio nghi 15-45 phut), gioi han tong gio chay,
 *     gio yen lang (tuy chon), tu mo lai kenh khi go loi, tu mo lai khi OwO im lang.
 *  5. Su co -> dung + reo chuong + nho bot nhan tin TAG Ruby tren Discord (dien thoai/may khac cung bao).
 *  Lenh thay gem do bot tao: dich vu Accessibility lo (dung chung khoa DiscordSender.sendMutex).
 */
object FarmController {
    private const val TAG = "FarmController"

    // ================= Cau hinh mac dinh (muon doi thi sua o day) =================
    private const val HUNT_COMMAND = "owo hunt"
    private const val BATTLE_COMMAND = "owo battle"
    private const val ZOO_COMMAND = "owo zoo"
    private const val DAILY_COMMAND = "owo daily"
    private const val QUEST_COMMAND = "owo quest"

    // Nhip go lenh
    private const val GAP_MIN_MS = 1_500L         // nghi giua hunt va battle
    private const val GAP_MAX_MS = 3_500L
    private const val CYCLE_MIN_MS = 19_000L      // 1 vong (hunt + battle) dai it nhat the nay
    private const val CYCLE_MAX_MS = 27_000L

    // Nghi giai lao ngan: sau 45-80 vong nghi 1.5 - 4 phut
    private const val REST_EVERY_MIN = 45
    private const val REST_EVERY_MAX = 80
    private const val REST_MIN_MS = 90_000L
    private const val REST_MAX_MS = 240_000L

    // Nghi theo phien: chay 90-150 phut roi nghi 15-45 phut
    private const val SESSION_MIN_MS = 90 * 60_000L
    private const val SESSION_MAX_MS = 150 * 60_000L
    private const val BREAK_MIN_MS = 15 * 60_000L
    private const val BREAK_MAX_MS = 45 * 60_000L

    // Tong gio chay toi da cho 1 lan bam Bat dau (het thi dung han)
    private const val MAX_TOTAL_HOURS = 14

    // Gio yen lang (khong go lenh), gio dia phuong cua may. De -1 = tat.
    // Vi du muon nghi tu 2h den 6h sang: QUIET_START_HOUR = 2, QUIET_END_HOUR = 6
    private const val QUIET_START_HOUR = -1
    private const val QUIET_END_HOUR = -1

    // Viec vat
    private const val DAILY_GAP_MS = 30 * 60_000L          // da thay den gio nhan daily thi thu lai moi 30 phut
    private const val DAILY_UNKNOWN_GAP_MS = 6 * 3_600_000L // chua co du lieu daily: thu 1 lan roi cho 6 gio
    private const val QUEST_CLAIM_GAP_MS = 30 * 60_000L    // moi 30 phut: mo Quest Log, nhan thuong nhiem vu da xong

    // Huntbot: app chi NHAN thanh qua (owo hb) khi da xong. App KHONG tu chay lai huntbot vi
    // lenh khoi dong can mat khau xac minh dang hinh (kieu captcha) -> Ruby tu go, app nhac qua Discord.
    private const val HUNTBOT_COLLECT_COMMAND = "owo hb"
    private const val HUNTBOT_GAP_MS = 30 * 60_000L
    private const val HUNTBOT_IDLE_NOTIFY_GAP_MS = 3 * 3_600_000L

    // Hien te lay essence (Ruby chon owo sc all), roi nang cap huntbot bang het essence.
    private val SACRIFICE_COMMANDS: List<String> = listOf("owo sc all")
    private const val SACRIFICE_GAP_MS = 3 * 3_600_000L
    private const val SACRIFICE_FIRST_DELAY_MS = 30 * 60_000L   // lan dau sau khi bat dau 30 phut
    // Nang cap huntbot tu dong theo huong dan cua Ruby (bot doc cap do tung chi so tu `owo hb`):
    //  1. cost (toi da cap 5) -> 2. efficiency va gain nang DEU nhau (toi da 215 / 200)
    //  -> 3. experience (200) -> 4. duration (235) -> 5. radar (999)
    private const val UPGRADE_ENABLED = true
    private const val UPGRADE_GAP_MS = 3 * 3_600_000L
    private const val TRAITS_REFRESH_GAP_MS = 30 * 60_000L
    private val TRAIT_MAX = mapOf(
        "cost" to 5, "efficiency" to 215, "gain" to 200,
        "experience" to 200, "duration" to 235, "radar" to 999
    )

    // Phuc hoi / canh gac
    private const val MAX_FAILS_IN_ROW = 6        // go loi 6 lan lien tiep -> dung han
    private const val ZOO_WAIT_MS = 25_000L
    private const val BOT_POLL_MS = 4_000L        // hoi bot moi 4 giay
    private const val BOT_UNREACHABLE_STOP_MS = 120_000L  // mat ket noi bot > 2 phut -> dung (khong con ai canh captcha)
    private const val SILENT_RECOVER_SEC = 150L   // OwO khong tra loi 2.5 phut -> mo lai kenh
    private const val SILENT_STOP_SEC = 360L      // 6 phut -> dung han
    private const val CAPTCHA_MESSAGE =
        "🚨 OwO đòi captcha — đã dừng farm! Giải xong gõ ,captcha clear rồi bấm Bắt đầu lại"

    // ===============================================================================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var appCtx: Context? = null

    private val _state = MutableStateFlow(FarmState())
    val state: StateFlow<FarmState> = _state

    // Chia se giua vong chinh va nguoi canh gac
    @Volatile private var restingNow = false
    @Volatile private var lastSendOkAtMs = 0L
    @Volatile private var silenceBaselineMs = 0L
    @Volatile private var silentRecoverRequested = false
    private var unreachableSinceMs = 0L

    private val choreAt = mutableMapOf<String, Long>()
    private var questUiWarned = false

    fun isRunning(): Boolean = job?.isActive == true

    private fun update(block: (FarmState) -> FarmState) {
        _state.value = block(_state.value)
    }

    fun clearAlert() {
        update { it.copy(alert = null) }
    }

    /** Tat chuong (nut "Tat chuong" tren Hub noi) */
    fun stopAlarm() {
        AlarmPlayer.stop()
        update { it.copy(ringing = false) }
    }

    private fun startAlarm(ctx: Context) {
        AlarmPlayer.onStopped = { update { it.copy(ringing = false) } }
        AlarmPlayer.start(ctx)
    }

    /** Bao bot: phien farm da ket thuc (bot thoi canh gac) */
    private fun signalFarmEnded() {
        val ctx = appCtx ?: return
        scope.launch {
            val s = OwoTrackerPrefs.load(ctx)
            if (s.apiUrl.isNotBlank() && s.token.isNotBlank()) {
                OwoTrackerApi.fetchAlerts(s.apiUrl, s.token, "0")
            }
        }
    }

    /** Nho bot nhan tin TAG Ruby tren Discord */
    private fun notifyOwner(text: String) {
        val ctx = appCtx ?: return
        scope.launch {
            val s = OwoTrackerPrefs.load(ctx)
            if (s.apiUrl.isNotBlank() && s.token.isNotBlank()) {
                OwoTrackerApi.notify(s.apiUrl, s.token, text)
            }
        }
    }

    /** OwO doi captcha: reo chuong + rung, dung farm. (Bot tu nhan tin tag Ruby, app khong can nhan them) */
    private fun handleCaptcha(ctx: Context) {
        startAlarm(ctx)
        update { it.copy(running = false, resting = false, status = CAPTCHA_MESSAGE, alert = CAPTCHA_MESSAGE, ringing = true) }
        signalFarmEnded()
    }

    /** Su co nghiem trong: dung han + reo chuong + nho bot nhan tin tag Ruby */
    private fun hardStop(ctx: Context, message: String) {
        startAlarm(ctx)
        update { it.copy(running = false, resting = false, status = message, alert = message, ringing = true) }
        notifyOwner(message)
        signalFarmEnded()
        job?.cancel()
        job = null
    }

    /** Dung theo ke hoach (het gio chay toi da...): khong reo chuong, nhung van nhan tin cho Ruby */
    private fun plannedStop(message: String) {
        update { it.copy(running = false, resting = false, status = message, alert = message) }
        notifyOwner(message)
        signalFarmEnded()
        job = null
    }

    fun start(service: AutoAccessibilityService) {
        if (isRunning()) return
        appCtx = service.applicationContext
        stopAlarm()
        job = scope.launch {
            try {
                runFarm(service)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Farm loi: ${e.message}", e)
                hardStop(service.applicationContext, "Lỗi bất ngờ: ${(e.message ?: "không rõ").take(100)}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        stopAlarm()
        restingNow = false
        update { it.copy(running = false, resting = false, status = "Đã dừng", alert = null) }
        signalFarmEnded()
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
        val result = try {
            sender.send(text, settings, reopenChannel = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gui '$text' loi: ${e.message}")
            SendResult.SEND_FAILED
        }
        if (result == SendResult.OK) lastSendOkAtMs = System.currentTimeMillis()
        return result
    }

    /** Mo lai Discord vao dung kenh (co khoa de khong dam vao lenh thay gem dang go) */
    private suspend fun reopenChannel(sender: DiscordSender, link: String): Boolean {
        update { it.copy(status = "Đang mở lại kênh Discord…") }
        val r = DiscordSender.sendMutex.withLock { sender.openDiscordDetailed(link) }
        return r == OpenResult.OK_CHANNEL
    }

    private fun inQuietHours(): Boolean {
        if (QUIET_START_HOUR < 0 || QUIET_END_HOUR < 0) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (QUIET_START_HOUR < QUIET_END_HOUR) {
            h >= QUIET_START_HOUR && h < QUIET_END_HOUR
        } else {
            h >= QUIET_START_HOUR || h < QUIET_END_HOUR
        }
    }

    /** Nghi `ms` mili giay (khong go lenh). Bao bot "dang nghi" de bot khong tuong OwO im lang. */
    private suspend fun CoroutineScope.restFor(ms: Long, label: String) {
        restingNow = true
        update { it.copy(resting = true) }
        val end = System.currentTimeMillis() + ms
        try {
            while (isActive && System.currentTimeMillis() < end) {
                val left = (end - System.currentTimeMillis()) / 1000
                val text = if (left >= 90) "$label, còn ${(left + 30) / 60} phút…" else "$label, còn ${left}s…"
                update { it.copy(status = text) }
                delay(5_000)
            }
        } finally {
            restingNow = false
            silenceBaselineMs = System.currentTimeMillis()
            update { it.copy(resting = false) }
        }
    }

    /** Chon chi so huntbot nen nang tiep theo (null = khong biet cap do hoac da toi da het) */
    private fun pickUpgradeTrait(traits: Map<String, Int>?): String? {
        if (traits == null || traits.isEmpty()) return null
        fun below(t: String): Boolean {
            val level = traits[t] ?: return false
            return level < (TRAIT_MAX[t] ?: Int.MAX_VALUE)
        }
        if (below("cost")) return "cost"
        val effBelow = below("efficiency")
        val gainBelow = below("gain")
        if (effBelow && gainBelow) {
            // nang deu: ben nao con thap hon (theo ti le tren cap toi da) thi nang truoc
            val e = (traits["efficiency"] ?: 0).toDouble() / 215.0
            val g = (traits["gain"] ?: 0).toDouble() / 200.0
            return if (e <= g) "efficiency" else "gain"
        }
        if (effBelow) return "efficiency"
        if (gainBelow) return "gain"
        if (below("experience")) return "experience"
        if (below("duration")) return "duration"
        if (below("radar")) return "radar"
        return null
    }

    private fun choreDue(key: String, gapMs: Long): Boolean {
        val last = choreAt[key] ?: 0L
        return System.currentTimeMillis() - last >= gapMs
    }

    /** Gõ lần lượt các lệnh của 1 việc vặt. Trả về CAPTCHA nếu gặp captcha, OK nếu xong, còn lại là lỗi. */
    private suspend fun runChoreCommands(
        sender: DiscordSender,
        settings: OwoTrackerPrefs.Settings,
        key: String,
        commands: List<String>
    ): SendResult {
        choreAt[key] = System.currentTimeMillis()
        for ((i, cmd) in commands.withIndex()) {
            update { it.copy(status = "Làm việc vặt: $cmd") }
            val r = sendCommand(sender, settings, cmd)
            if (r != SendResult.OK) return r
            if (i < commands.size - 1) delay(Random.nextLong(2_500L, 5_000L))
        }
        return SendResult.OK
    }

    /**
     * Moi vong lam toi da 1 viec vat, theo du lieu bot doc duoc (bot la "mat"):
     * daily khi den gio, lam moi quest, va (neu Ruby da cho lenh) huntbot / hien te / nang cap.
     */
    /** Go owo quest, doi Quest Log hien, roi bam nut nhan thuong o tab Daily / Weekly / Quests neu da xong */
    private suspend fun claimQuests(
        service: AutoAccessibilityService,
        sender: DiscordSender,
        settings: OwoTrackerPrefs.Settings
    ): SendResult {
        choreAt["quest"] = System.currentTimeMillis()
        update { it.copy(status = "Kiểm tra nhiệm vụ…", lastCommand = QUEST_COMMAND) }
        return DiscordSender.sendMutex.withLock {
            val r = try {
                sender.sendWithoutLock(QUEST_COMMAND, settings, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Gui owo quest loi: ${e.message}")
                SendResult.SEND_FAILED
            }
            if (r == SendResult.OK) {
                lastSendOkAtMs = System.currentTimeMillis()
                delay(4_000)
                val res = QuestClaimer.claimAll(service)
                if (res.claimed.isNotEmpty()) {
                    update { it.copy(alert = "🎁 Đã nhận thưởng nhiệm vụ: " + res.claimed.joinToString(", ")) }
                } else if (res.tabsFound == 0 && !questUiWarned) {
                    questUiWarned = true
                    update {
                        it.copy(alert = "⚠️ Không thấy các nút trong Quest Log trên màn hình (Discord có thể không cho app đọc nút). Nhận thưởng nhiệm vụ cần bấm tay")
                    }
                }
            }
            r
        }
    }

    private suspend fun runOneChore(
        service: AutoAccessibilityService,
        sender: DiscordSender,
        settings: OwoTrackerPrefs.Settings
    ): SendResult {
        val hub = _state.value.hub

        val daily = hub?.daily
        if (daily == null) {
            if (choreDue("daily_unknown", DAILY_UNKNOWN_GAP_MS)) {
                choreAt["daily_unknown"] = System.currentTimeMillis()
                return runChoreCommands(sender, settings, "daily", listOf(DAILY_COMMAND))
            }
        } else {
            val left = daily.secondsLeft
            if (left != null && left <= 0L && choreDue("daily", DAILY_GAP_MS)) {
                return runChoreCommands(sender, settings, "daily", listOf(DAILY_COMMAND))
            }
        }

        // Huntbot: nhan thanh qua khi da xong, roi nhac Ruby tu chay lai (can mat khau xac minh)
        val hb = hub?.huntbot
        if (hb != null) {
            val left = hb.secondsLeft
            if (left != null && left <= 0L && choreDue("huntbot", HUNTBOT_GAP_MS)) {
                val r = runChoreCommands(sender, settings, "huntbot", listOf(HUNTBOT_COLLECT_COMMAND))
                if (r == SendResult.OK) {
                    choreAt["huntbot_idle"] = System.currentTimeMillis()
                    notifyOwner(
                        "🤖 Huntbot đã chạy xong và app đã nhận thành quả (owo hb). " +
                            "Bạn tự gõ `owo hb 24h` + mã xác nhận để chạy lại nhé — app không điền được mã này."
                    )
                }
                return r
            }
            if (hb.hunting == false && choreDue("huntbot_idle", HUNTBOT_IDLE_NOTIFY_GAP_MS)) {
                choreAt["huntbot_idle"] = System.currentTimeMillis()
                notifyOwner("🤖 Huntbot đang không chạy. Bạn tự gõ `owo hb 24h` + mã xác nhận để chạy lại nhé.")
            }
        }

        // Hien te lay essence
        if (SACRIFICE_COMMANDS.isNotEmpty() && choreDue("sacrifice", SACRIFICE_GAP_MS)) {
            val r = runChoreCommands(sender, settings, "sacrifice", SACRIFICE_COMMANDS)
            if (r == SendResult.OK) choreAt["upgrade"] = 0L   // co essence moi -> nang cap ngay vong sau
            return r
        }

        // Nang cap huntbot bang het essence (chon chi so theo cap do bot doc duoc)
        if (UPGRADE_ENABLED) {
            val traits = hub?.huntbot?.traits
            if (traits == null) {
                // Chua biet cap do cac chi so -> go owo hb 1 lan de bot doc (an toan, chi xem thong tin)
                if (choreDue("traits_refresh", TRAITS_REFRESH_GAP_MS)) {
                    return runChoreCommands(sender, settings, "traits_refresh", listOf(HUNTBOT_COLLECT_COMMAND))
                }
            } else if (choreDue("upgrade", UPGRADE_GAP_MS)) {
                val trait = pickUpgradeTrait(traits)
                if (trait == null) {
                    choreAt["upgrade"] = System.currentTimeMillis()   // da toi da het, khong lam gi
                } else {
                    // go owo hb sau khi nang cap de bot cap nhat cap do + essence moi
                    return runChoreCommands(sender, settings, "upgrade", listOf("owo upg $trait all", HUNTBOT_COLLECT_COMMAND))
                }
            }
        }

        if (choreDue("quest", QUEST_CLAIM_GAP_MS)) {
            return claimQuests(service, sender, settings)
        }
        return SendResult.OK
    }

    /** Canh gac: hoi bot moi vai giay. Thay captcha -> dung; OwO im lang -> yeu cau mo lai kenh / dung han; mat bot -> dung. */
    private suspend fun watchBot(ctx: Context, apiUrl: String, token: String) {
        while (true) {
            delay(BOT_POLL_MS)
            val flag = if (restingNow) "2" else "1"
            val info = OwoTrackerApi.fetchAlerts(apiUrl, token, flag)
            val now = System.currentTimeMillis()

            if (info == null) {
                if (unreachableSinceMs == 0L) unreachableSinceMs = now
                if (now - unreachableSinceMs > BOT_UNREACHABLE_STOP_MS) {
                    hardStop(ctx, "Mất kết nối với bot hơn 2 phút — đã dừng vì không còn ai canh captcha")
                    return
                }
                continue
            }
            unreachableSinceMs = 0L

            if (info.captchaActive) {
                handleCaptcha(ctx)
                job?.cancel()
                job = null
                return
            }

            // OwO im lang: chi tinh tu luc da bat dau go (khong tinh thoi gian truoc do / luc nghi)
            if (!restingNow && lastSendOkAtMs > 0L && now - lastSendOkAtMs < 90_000L) {
                val sinceBaseline = (now - silenceBaselineMs) / 1000
                val age = info.lastReplyAgeSeconds
                if (age != null) {
                    val silent = minOf(age, sinceBaseline)
                    if (silent > SILENT_STOP_SEC) {
                        hardStop(ctx, "OwO không trả lời hơn ${silent / 60} phút dù app vẫn gõ — đã dừng")
                        return
                    }
                    if (silent > SILENT_RECOVER_SEC) {
                        silentRecoverRequested = true
                    }
                }
            }
        }
    }

    private suspend fun CoroutineScope.runFarm(service: AutoAccessibilityService) {
        val ctx = service.applicationContext
        val first = OwoTrackerPrefs.load(ctx)
        unreachableSinceMs = 0L
        val watcher = launch { watchBot(ctx, first.apiUrl, first.token) }
        try {
            runLoop(service)
        } finally {
            watcher.cancel()
            restingNow = false
        }
    }

    private suspend fun CoroutineScope.runLoop(service: AutoAccessibilityService) {
        val ctx = service.applicationContext
        var settings = OwoTrackerPrefs.load(ctx)
        if (settings.apiUrl.isBlank() || settings.token.isBlank()) {
            plannedStop("Chưa nhập địa chỉ API / token ở Cài đặt")
            return
        }

        val startIso = Instant.now().toString()
        choreAt.clear()
        choreAt["sacrifice"] = System.currentTimeMillis() - SACRIFICE_GAP_MS + SACRIFICE_FIRST_DELAY_MS
        choreAt["upgrade"] = System.currentTimeMillis()
        silentRecoverRequested = false
        questUiWarned = false
        lastSendOkAtMs = 0L
        silenceBaselineMs = System.currentTimeMillis()
        update {
            it.copy(
                running = true, resting = false, status = "Đang chuẩn bị…", hunts = 0, battles = 0,
                sessionStartIso = startIso, sessionNewPets = emptyList(), alert = null
            )
        }

        // Dang co canh bao captcha chua giai -> khong go them lenh nao
        if (OwoTrackerApi.fetchCaptchaActive(settings.apiUrl, settings.token) == true) {
            plannedStop("🚨 OwO đang đòi captcha. Giải xong gõ ,captcha clear (trong kênh Discord) rồi bấm Bắt đầu lại")
            return
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
            plannedStop("Chưa biết kênh nào để farm. Gõ ,setchannel trong kênh Discord (cho bot), hoặc dán link kênh ở Cài đặt")
            return
        }

        // --- 2. Mo Discord vao dung kenh (bat buoc vao dung kenh moi go lenh) ---
        update { it.copy(status = "Đang mở Discord…") }
        val sender = DiscordSender(service)
        val opened = sender.openDiscordDetailed(link)
        if (opened != OpenResult.OK_CHANNEL) {
            hardStop(ctx, "Không vào được kênh: " + describeOpen(opened))
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
            handleCaptcha(ctx)
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
            hardStop(ctx, "Không gõ được lệnh zoo: " + describeSend(zooResult))
            return
        }

        // --- 4. Vong farm ---
        val runStartMs = System.currentTimeMillis()
        var sessionStartMs = runStartMs
        var sessionLimitMs = Random.nextLong(SESSION_MIN_MS, SESSION_MAX_MS)
        var failsInRow = 0
        var cyclesUntilRest = Random.nextInt(REST_EVERY_MIN, REST_EVERY_MAX + 1)
        silenceBaselineMs = System.currentTimeMillis()

        while (isActive) {
            val cycleStart = System.currentTimeMillis()

            // 4.0 Het tong gio chay toi da
            if (cycleStart - runStartMs > MAX_TOTAL_HOURS * 3_600_000L) {
                plannedStop("Đã chạy đủ $MAX_TOTAL_HOURS giờ — dừng để nghỉ. Bấm Bắt đầu để chạy tiếp")
                return
            }

            // 4.1 Gio yen lang
            if (inQuietHours()) {
                restFor(5 * 60_000L, "Giờ yên lặng")
                continue
            }

            // 4.2 Het 1 phien -> nghi dai roi mo lai kenh
            if (cycleStart - sessionStartMs > sessionLimitMs) {
                restFor(Random.nextLong(BREAK_MIN_MS, BREAK_MAX_MS), "Nghỉ giữa phiên")
                if (!isActive) return
                if (!reopenChannel(sender, link)) {
                    hardStop(ctx, "Sau giờ nghỉ không mở lại được kênh Discord")
                    return
                }
                sessionStartMs = System.currentTimeMillis()
                sessionLimitMs = Random.nextLong(SESSION_MIN_MS, SESSION_MAX_MS)
                continue
            }

            // 4.3 OwO im lang -> mo lai kenh
            if (silentRecoverRequested) {
                silentRecoverRequested = false
                update { it.copy(alert = "⚠️ OwO không trả lời hơn 2,5 phút — đang mở lại kênh") }
                reopenChannel(sender, link)
                silenceBaselineMs = System.currentTimeMillis()
                delay(3_000)
            }

            // 4.4 Viec vat (toi da 1 viec / vong)
            update { it.copy(status = "Đang farm…") }
            val choreResult = runOneChore(service, sender, settings)
            if (choreResult == SendResult.CAPTCHA) {
                handleCaptcha(ctx)
                return
            }
            if (choreResult != SendResult.OK) {
                Log.w(TAG, "Viec vat loi: ${describeSend(choreResult)} (bo qua, thu lai sau)")
            } else {
                delay(Random.nextLong(GAP_MIN_MS, GAP_MAX_MS))
            }

            // 4.5 hunt
            val huntResult = sendCommand(sender, settings, HUNT_COMMAND)
            if (huntResult == SendResult.CAPTCHA) {
                handleCaptcha(ctx)
                return
            }
            if (huntResult == SendResult.OK) {
                failsInRow = 0
                update { it.copy(hunts = it.hunts + 1) }
            } else {
                failsInRow++
                if (failsInRow >= MAX_FAILS_IN_ROW) {
                    hardStop(ctx, "Gõ lệnh lỗi $failsInRow lần liên tiếp (${describeSend(huntResult)}) — đã dừng")
                    return
                }
                update { it.copy(status = "Lỗi gõ lệnh (${describeSend(huntResult)}), lần $failsInRow — đang khắc phục…") }
                if (failsInRow >= 2) reopenChannel(sender, link)
                delay(minOf(4_000L * failsInRow, 30_000L))
                continue
            }

            delay(Random.nextLong(GAP_MIN_MS, GAP_MAX_MS))

            // 4.6 battle
            val battleResult = sendCommand(sender, settings, BATTLE_COMMAND)
            if (battleResult == SendResult.CAPTCHA) {
                handleCaptcha(ctx)
                return
            }
            if (battleResult == SendResult.OK) {
                failsInRow = 0
                update { it.copy(battles = it.battles + 1) }
            } else {
                failsInRow++
                if (failsInRow >= MAX_FAILS_IN_ROW) {
                    hardStop(ctx, "Gõ lệnh lỗi $failsInRow lần liên tiếp (${describeSend(battleResult)}) — đã dừng")
                    return
                }
                if (failsInRow >= 2) reopenChannel(sender, link)
            }

            refreshHub(ctx)

            // 4.7 Nghi giai lao ngan cho giong nguoi that
            cyclesUntilRest--
            if (cyclesUntilRest <= 0) {
                cyclesUntilRest = Random.nextInt(REST_EVERY_MIN, REST_EVERY_MAX + 1)
                restFor(Random.nextLong(REST_MIN_MS, REST_MAX_MS), "Nghỉ giải lao")
                continue
            }

            // 4.8 Doi cho du 1 vong
            val target = Random.nextLong(CYCLE_MIN_MS, CYCLE_MAX_MS)
            val elapsed = System.currentTimeMillis() - cycleStart
            if (elapsed < target) delay(target - elapsed) else delay(1_500)
        }
    }
}
