package com.starclan.starhub.view

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.starclan.starhub.data.HubFormat
import com.starclan.starhub.engine.FarmController
import com.starclan.starhub.engine.FarmState
import com.starclan.starhub.service.AutoAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Hub noi tren man hinh Discord. Dung cua so overlay cua dich vu Accessibility nen
 * KHONG can them quyen "Hien thi tren ung dung khac". Bong bong ngoi sao: cham de mo/thu gon,
 * keo de di chuyen. Mo rong ra thi co nut Bat dau/Dung farm va so lieu moi nhat tu bot.
 */
object FarmOverlay {
    private const val REFRESH_MS = 15_000L

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var tvStatus: TextView? = null
    private var tvAlert: TextView? = null
    private var tvStreak: TextView? = null
    private var tvGemUsing: TextView? = null
    private var tvGemStock: TextView? = null
    private var tvHb: TextView? = null
    private var tvDaily: TextView? = null
    private var tvCash: TextView? = null
    private var tvWs: TextView? = null
    private var tvQuest: TextView? = null
    private var tvZoo: TextView? = null
    private var tvPets: TextView? = null
    private var btnToggle: Button? = null
    private var btnMute: Button? = null
    private var btnMode: Button? = null
    private var keepingScreenOn = false
    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null

    fun isShown(): Boolean = root != null

    fun hide() {
        refreshJob?.cancel()
        refreshJob = null
        scope?.cancel()
        scope = null
        val r = root
        val wm = windowManager
        if (r != null && wm != null) {
            try {
                wm.removeView(r)
            } catch (e: Exception) {
                // da bi go roi
            }
        }
        root = null
        params = null
        panel = null
        windowManager = null
        keepingScreenOn = false
    }

    fun show(service: AutoAccessibilityService) {
        if (root != null) return
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = service.resources.displayMetrics.density
        val screenH = service.resources.displayMetrics.heightPixels
        val screenW = service.resources.displayMetrics.widthPixels
        fun dp(v: Int): Int = (v * density).toInt()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(110)
        params = lp

        val container = LinearLayout(service)
        container.orientation = LinearLayout.VERTICAL

        // ---- Bong bong ----
        val bubble = TextView(service)
        bubble.text = "⭐"
        bubble.textSize = 20f
        bubble.gravity = Gravity.CENTER
        val bubbleBg = GradientDrawable()
        bubbleBg.shape = GradientDrawable.OVAL
        bubbleBg.setColor(Color.parseColor("#E61B1F2A"))
        bubbleBg.setStroke(dp(2), Color.parseColor("#FFD54F"))
        bubble.background = bubbleBg
        container.addView(bubble, LinearLayout.LayoutParams(dp(46), dp(46)))

        // ---- Bang thong tin ----
        val panelBox = LinearLayout(service)
        panelBox.orientation = LinearLayout.VERTICAL
        panelBox.setPadding(dp(10), dp(8), dp(10), dp(8))
        val panelBg = GradientDrawable()
        panelBg.cornerRadius = dp(12).toFloat()
        panelBg.setColor(Color.parseColor("#EE1B1F2A"))
        panelBox.background = panelBg
        panelBox.visibility = View.GONE
        panel = panelBox

        fun makeText(size: Float, bold: Boolean = false, color: String = "#EDEDED"): TextView {
            val t = TextView(service)
            t.textSize = size
            t.setTextColor(Color.parseColor(color))
            if (bold) t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, dp(3), 0, dp(3))
            return t
        }

        val status = makeText(13f, bold = true)
        val alert = makeText(13f, bold = true, color = "#FFD54F")
        alert.visibility = View.GONE
        val streak = makeText(12f)
        val gemUsing = makeText(12f)
        val gemStock = makeText(12f)
        val zoo = makeText(12f)
        val pets = makeText(12f)
        tvStatus = status
        tvAlert = alert
        tvStreak = streak
        tvGemUsing = gemUsing
        tvGemStock = gemStock
        val hb = makeText(12f)
        val daily = makeText(12f)
        val cash = makeText(12f)
        val ws = makeText(12f)
        val quest = makeText(12f)
        tvHb = hb
        tvDaily = daily
        tvCash = cash
        tvWs = ws
        tvQuest = quest
        tvZoo = zoo
        tvPets = pets

        val buttons = LinearLayout(service)
        buttons.orientation = LinearLayout.HORIZONTAL
        val toggle = Button(service)
        toggle.text = "▶ Bắt đầu"
        toggle.setAllCaps(false)
        toggle.textSize = 12f
        btnToggle = toggle
        val modeBtn = Button(service)
        modeBtn.text = "🎯 Hunt mode 3"
        modeBtn.setAllCaps(false)
        modeBtn.textSize = 12f
        btnMode = modeBtn
        val muteBtn = Button(service)
        muteBtn.text = "🔕 Tắt chuông"
        muteBtn.setAllCaps(false)
        muteBtn.textSize = 12f
        muteBtn.visibility = View.GONE
        btnMute = muteBtn
        val hideBtn = Button(service)
        hideBtn.text = "➖ Ẩn menu"
        hideBtn.setAllCaps(false)
        hideBtn.textSize = 12f
        val closeBtn = Button(service)
        closeBtn.text = "⏻ Tắt hub"
        closeBtn.setAllCaps(false)
        closeBtn.textSize = 12f
        buttons.addView(toggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(muteBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val buttons2 = LinearLayout(service)
        buttons2.orientation = LinearLayout.HORIZONTAL
        buttons2.addView(hideBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons2.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val content = LinearLayout(service)
        content.orientation = LinearLayout.VERTICAL
        content.addView(status)
        content.addView(alert)
        content.addView(streak)
        content.addView(gemUsing)
        content.addView(gemStock)
        content.addView(hb)
        content.addView(daily)
        content.addView(cash)
        content.addView(ws)
        content.addView(quest)
        content.addView(zoo)
        content.addView(pets)

        val scroll = ScrollView(service)
        scroll.addView(content)
        val panelWidth = minOf(dp(290), screenW - dp(40))
        panelBox.addView(buttons, LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        panelBox.addView(modeBtn, LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        panelBox.addView(buttons2, LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        panelBox.addView(scroll, LinearLayout.LayoutParams(panelWidth, (screenH * 0.42f).toInt()))
        container.addView(panelBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- Su kien ----
        toggle.setOnClickListener {
            if (FarmController.isRunning()) {
                FarmController.stop()
            } else {
                FarmController.clearAlert()
                FarmController.start(service)
            }
        }
        muteBtn.setOnClickListener { FarmController.stopAlarm() }
        modeBtn.setOnClickListener { FarmController.cycleHuntMode(service.applicationContext) }
        FarmController.loadHuntMode(service.applicationContext)
        // Chi thu gon menu, bong bong ngoi sao van o lai tren man hinh
        hideBtn.setOnClickListener {
            panelBox.visibility = View.GONE
            relayout()
        }
        // Tat hub: dung farm (neu dang chay) va go ca bong bong
        closeBtn.setOnClickListener {
            FarmController.stop()
            hide()
        }
        // Bat hub lai = bat dau phien moi: danh sach "pet moi" duoc xoa trang
        FarmController.resetSession()

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        lp.x = startX + dx
                        lp.y = startY + dy
                        try {
                            wm.updateViewLayout(container, lp)
                        } catch (ex: Exception) {
                            // view da bi go
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) togglePanel()
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(container, lp)
        root = container

        // ---- Cap nhat theo trang thai farm + lam moi so lieu dinh ky ----
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            FarmController.state.collect { st -> render(st) }
        }
        refreshJob = s.launch {
            while (true) {
                if (!FarmController.isRunning()) {
                    FarmController.refreshHub(service.applicationContext)
                }
                delay(REFRESH_MS)
            }
        }
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        relayout()
    }

    private fun relayout() {
        val r = root ?: return
        val lp = params ?: return
        try {
            windowManager?.updateViewLayout(r, lp)
        } catch (e: Exception) {
            // bo qua
        }
    }

    private fun ageText(seconds: Long?): String = if (seconds == null) "" else " · " + HubFormat.age(seconds)

    private fun render(st: FarmState) {
        if (root == null) return
        val hub = st.hub

        // --- trang thai ---
        val head = if (st.running) "▶ Đang farm · hunt ${st.hunts} · battle ${st.battles}" else "⏸ ${st.status}"
        val last = if (st.running && st.lastCommand.isNotEmpty()) "\nLệnh gần nhất: ${st.lastCommand}" else ""
        val running = if (st.running) "\n${st.status}" else ""
        val paused = if (!st.huntPaused.isNullOrBlank()) "\n⏸ Hunt tạm dừng: ${st.huntPaused}" else ""
        tvStatus?.text = head + running + last + paused
        val modeDesc = when (st.huntMode) {
            1 -> "dừng khi đủ lootbox ngày"
            2 -> "dừng khi thiếu gem"
            else -> "không dừng"
        }
        btnMode?.text = "🎯 Hunt mode ${st.huntMode}: $modeDesc (chạm để đổi)"
        btnToggle?.text = if (st.running) "⏹ Dừng" else "▶ Bắt đầu"
        btnMute?.visibility = if (st.ringing) View.VISIBLE else View.GONE

        // Dang farm thi giu man hinh luon sang (tat man hinh la app khong go duoc nua)
        if (st.running != keepingScreenOn) {
            keepingScreenOn = st.running
            val lp = params
            if (lp != null) {
                lp.flags = if (keepingScreenOn) {
                    lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                } else {
                    lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                }
                relayout()
            }
        }

        val alertText = st.alert
        if (alertText.isNullOrBlank()) {
            tvAlert?.visibility = View.GONE
        } else {
            tvAlert?.text = alertText
            tvAlert?.visibility = View.VISIBLE
            val p = panel
            if (p != null && p.visibility != View.VISIBLE) {
                p.visibility = View.VISIBLE // co canh bao -> tu mo rong
                relayout()
            }
        }

        if (hub == null) {
            val err = st.hubError
            tvStreak?.text = if (err.isNullOrBlank()) "Đang tải dữ liệu từ bot…" else "⚠️ $err"
            tvGemUsing?.text = ""
            tvGemStock?.text = ""
            tvHb?.text = ""
            tvDaily?.text = ""
            tvCash?.text = ""
            tvWs?.text = ""
            tvQuest?.text = ""
            tvZoo?.text = ""
            tvPets?.text = ""
            return
        }

        // --- chuoi battle ---
        val b = hub.battle
        tvStreak?.text = if (b == null) {
            "🔥 Chuỗi battle: chưa có dữ liệu (gõ owo battle)"
        } else {
            val lost = b.lastLostStreak ?: 0
            val extra = if (b.result == "lost" && lost > 0) " (vừa thua, mất chuỗi $lost)" else ""
            val best = b.bestStreak?.let { " · cao nhất $it" } ?: ""
            "🔥 Chuỗi battle: ${b.streak ?: 0}$extra$best${ageText(b.ageSeconds)}"
        }

        // --- gem dang dung ---
        val eq = hub.gems?.equipped.orEmpty()
        tvGemUsing?.text = if (eq.isEmpty()) {
            "💎 Gem đang dùng: chưa có dữ liệu (gõ owo hunt)"
        } else {
            "💎 Gem đang dùng:\n" + eq.joinToString("\n") { g ->
                "  Slot ${g.slot ?: "?"} · ${g.tier ?: "?"} [${g.current ?: 0}/${g.max ?: 0}]"
            }
        }

        // --- gem con trong kho theo slot va tier ---
        val spare = hub.gems?.spare.orEmpty()
        tvGemStock?.text = if (spare.isEmpty()) {
            "🎒 Gem còn trong kho: chưa có dữ liệu (gõ owo inv)"
        } else {
            val lines = spare.groupBy { it.slot ?: "?" }.entries.sortedBy { it.key }.map { entry ->
                "  Slot ${entry.key}: " + entry.value.joinToString(", ") { g -> "${g.tier ?: "?"}×${g.count ?: 0}" }
            }
            "🎒 Gem còn trong kho${ageText(hub.gems?.spareAgeSeconds)}:\n" + lines.joinToString("\n")
        }

        // --- huntbot (ah) ---
        val elapsed = if (st.hubFetchedAtMs > 0L) (System.currentTimeMillis() - st.hubFetchedAtMs) / 1000 else 0L
        val h = hub.huntbot
        tvHb?.text = if (h == null) {
            "🤖 Huntbot: chưa có dữ liệu (gõ owo hb)"
        } else if (h.hunting == true) {
            val left = h.secondsLeft?.let { it - elapsed }
            val timeText = when {
                left == null -> h.timeRemainingText?.let { "còn $it" } ?: "chưa rõ giờ xong"
                left > 0L -> "còn ${HubFormat.shortDuration(left)}"
                else -> "đã tới giờ xong"
            }
            val pct = h.progressPct?.let { " · ${HubFormat.trimNum(it)}%" } ?: ""
            val caught = h.animalsCaptured?.let { " · ${HubFormat.num(it)} con" } ?: ""
            val ess = h.essence?.let { " · essence ${HubFormat.num(it)}" } ?: ""
            "🤖 Huntbot: đang săn$pct · $timeText$caught$ess"
        } else {
            val ess = h.essence?.let { " · essence ${HubFormat.num(it)}" } ?: ""
            "🤖 Huntbot: KHÔNG chạy$ess — bạn tự chạy lại"
        }

        // --- daily ---
        val d = hub.daily
        tvDaily?.text = if (d == null) {
            "🎁 Daily: chưa có dữ liệu (gõ owo daily)"
        } else {
            val left = d.secondsLeft?.let { it - elapsed }
            val timeText = when {
                left == null -> "chưa rõ giờ nhận"
                left > 0L -> "còn ${HubFormat.shortDuration(left)} nữa nhận được"
                else -> "✅ đã sẵn sàng nhận"
            }
            val streak = d.streak?.let { " · chuỗi ${HubFormat.num(it)} ngày" } ?: ""
            "🎁 Daily: $timeText$streak"
        }

        // --- cowoncy ---
        val cash = hub.cowoncy
        val cashAmount = cash?.amount
        tvCash?.text = if (cash == null || cashAmount == null) {
            "💰 Cowoncy: chưa có dữ liệu (gõ owo cash)"
        } else {
            "💰 Cowoncy: ${HubFormat.num(cashAmount)}${ageText(cash.ageSeconds)}"
        }

        // --- weapon shards: bot chua doc duoc, can mau tin owo ws ---
        tvWs?.text = "🧿 Weapon shards (ws): chưa đọc được — cần mẫu tin của lệnh owo ws"

        // --- quest + checklist ---
        val q = hub.quest
        val dailyProg = hub.checklists?.daily?.progress
        val weeklyProg = hub.checklists?.weekly?.progress
        val parts = mutableListOf<String>()
        val sealCount = q?.seals
        if (sealCount != null) parts.add("seals ${HubFormat.num(sealCount)}")
        val dailyCur = dailyProg?.cur
        if (dailyCur != null) parts.add("daily $dailyCur/${dailyProg?.max ?: "?"}")
        val weeklyCur = weeklyProg?.cur
        if (weeklyCur != null) parts.add("weekly $weeklyCur/${weeklyProg?.max ?: "?"}")
        val qItems = q?.quests.orEmpty()
        if (qItems.isNotEmpty()) parts.add("quest còn ${qItems.count { it.done != true }}/${qItems.size}")
        tvQuest?.text = if (parts.isEmpty()) {
            "📜 Quest: chưa có dữ liệu (gõ owo q)"
        } else {
            "📜 Quest: " + parts.joinToString(" · ")
        }

        // --- zoo ---
        val z = hub.zoo
        tvZoo?.text = if (z == null) {
            "🐾 Zoo: chưa có dữ liệu (gõ owo zoo)"
        } else {
            val sacrificed = z.sacrificedPets.orEmpty().size
            val unlocked = z.unlockedTotal ?: (z.pets.orEmpty().size + sacrificed)
            val points = z.zooPoints?.let { " · $it điểm" } ?: ""
            "🐾 Zoo: $unlocked loài đã mở khóa (trong đó $sacrificed loài số lượng 0 do hiến tế)$points${ageText(z.ageSeconds)}"
        }

        // --- pet moi: chi tinh tu luc bat Hub / bat farm (tat bat lai la xoa trang) ---
        val sessionNames = st.sessionNewPets.toSet()
        val newPets = hub.newPets?.pets.orEmpty().filter { (it.name ?: "") in sessionNames }
        tvPets?.text = if (newPets.isEmpty()) {
            "🆕 Pet mới lần farm này: chưa có"
        } else {
            "🆕 Pet mới lần farm này (chưa có trong zoo):\n" + newPets.take(6).joinToString("\n") { p ->
                "  ${p.name ?: "?"}" + (p.rankVi?.let { " · $it" } ?: "") + " ×${p.timesFound ?: 1}"
            }
        }
    }
}
