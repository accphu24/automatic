package com.starclan.starhub.view

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
 * keo de di chuyen.
 *
 * Bo cuc (v29): phan dau co dinh (trang thai + nut) roi cac NHOM the rieng: Farm, Gem, Tai nguyen,
 * Huntbot, Nhiem vu, Zoo & pet moi, Su kien. Cham tieu de nhom de thu gon / mo rong; tieu de luon
 * kem 1 dong tom tat nen thu gon van doc duoc nhanh.
 */
object FarmOverlay {
    private const val REFRESH_MS = 15_000L

    // Mau: nen toi, nhan vang giong bong bong. Chu sang tren nen toi de de doc tren man Discord.
    private const val C_PANEL = "#F2161A24"
    private const val C_CARD = "#252A3A"
    private const val C_TITLE = "#FFFFFF"
    private const val C_TEXT = "#F2F4F8"
    private const val C_MUTED = "#A8B3C7"
    private const val C_GOLD = "#FFD54F"
    private const val C_GOOD = "#6EE7A0"
    private const val C_WARN = "#FFAB91"
    private const val C_OFF = "#8A93A6"
    private const val C_BTN = "#343B4F"
    private const val C_START = "#2E7D32"
    private const val C_STOP = "#C62828"

    private enum class Tone { NORMAL, GOOD, WARN, MUTED }

    /** 1 dong: nhan (cot trai, co the de trong) + gia tri */
    private class Row(val label: String, val value: String, val tone: Tone = Tone.NORMAL)

    private class Group(
        val card: LinearLayout,
        val body: LinearLayout,
        val summary: TextView,
        val chevron: TextView
    )

    /** ScrollView cao toi da maxH, thap hon thi co theo noi dung (khong chua khoang trong thua) */
    private class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }
    }

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var viewCtx: Context? = null
    private var density = 1f

    private var tvDot: TextView? = null
    private var tvStatusTitle: TextView? = null
    private var tvStatusCounts: TextView? = null
    private var tvStatusDetail: TextView? = null
    private var tvAlert: TextView? = null
    private var btnToggle: TextView? = null
    private var btnMute: TextView? = null
    private var btnMode: TextView? = null

    private val groups = mutableMapOf<String, Group>()
    private val collapsed = mutableSetOf<String>()   // nho trang thai thu gon trong luc app chay

    private var keepingScreenOn = false
    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null

    fun isShown(): Boolean = root != null

    private fun dp(v: Int): Int = (v * density).toInt()

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
        viewCtx = null
        groups.clear()
        keepingScreenOn = false
    }

    // ------------------------------------------------------------------ dung giao dien

    private fun pill(ctx: Context, text: String, bg: String, size: Float = 12.5f): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(C_TEXT))
        t.setTypeface(null, Typeface.BOLD)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(6), dp(8), dp(6), dp(8))
        t.minHeight = dp(40)
        t.maxLines = 2
        val d = GradientDrawable()
        d.cornerRadius = dp(10).toFloat()
        d.setColor(Color.parseColor(bg))
        t.background = d
        t.isClickable = true
        return t
    }

    private fun setPillColor(t: TextView?, color: String) {
        (t?.background as? GradientDrawable)?.setColor(Color.parseColor(color))
    }

    private fun lparams(w: Int, h: Int, weight: Float = 0f, margin: Int = 0): LinearLayout.LayoutParams {
        val p = if (weight > 0f) LinearLayout.LayoutParams(w, h, weight) else LinearLayout.LayoutParams(w, h)
        p.setMargins(margin, margin, margin, margin)
        return p
    }

    private fun makeGroup(ctx: Context, key: String, title: String): LinearLayout {
        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(Color.parseColor(C_CARD))
        card.background = bg

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titleTv = TextView(ctx)
        titleTv.text = title
        titleTv.textSize = 13.5f
        titleTv.setTypeface(null, Typeface.BOLD)
        titleTv.setTextColor(Color.parseColor(C_TITLE))

        val summary = TextView(ctx)
        summary.textSize = 11.5f
        summary.setTextColor(Color.parseColor(C_MUTED))
        summary.maxLines = 1
        summary.ellipsize = TextUtils.TruncateAt.END
        summary.gravity = Gravity.END
        summary.setPadding(dp(8), 0, dp(6), 0)

        val chevron = TextView(ctx)
        chevron.textSize = 13f
        chevron.setTextColor(Color.parseColor(C_MUTED))

        header.addView(titleTv, lparams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(summary, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 1f))
        header.addView(chevron, lparams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, dp(4), 0, 0)

        card.addView(header, lparams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(body, lparams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val g = Group(card, body, summary, chevron)
        groups[key] = g
        applyCollapsed(key)
        header.setOnClickListener {
            if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
            applyCollapsed(key)
            relayout()
        }
        return card
    }

    private fun applyCollapsed(key: String) {
        val g = groups[key] ?: return
        val isCollapsed = key in collapsed
        g.body.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        g.chevron.text = if (isCollapsed) "▸" else "▾"
    }

    private fun rowView(ctx: Context, r: Row): View {
        val color = when (r.tone) {
            Tone.GOOD -> C_GOOD
            Tone.WARN -> C_WARN
            Tone.MUTED -> C_MUTED
            Tone.NORMAL -> C_TEXT
        }
        val line = LinearLayout(ctx)
        line.orientation = LinearLayout.HORIZONTAL
        line.setPadding(0, dp(3), 0, dp(3))
        if (r.label.isNotEmpty()) {
            val l = TextView(ctx)
            l.text = r.label
            l.textSize = 12f
            l.setTextColor(Color.parseColor(C_MUTED))
            line.addView(l, lparams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val v = TextView(ctx)
        v.text = r.value
        v.textSize = 13f
        v.setTextColor(Color.parseColor(color))
        line.addView(v, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 1f))
        return line
    }

    /** Dien noi dung 1 nhom: dong tom tat tren tieu de + cac dong ben duoi. visible=false -> an ca the */
    private fun setGroup(key: String, summary: String, rows: List<Row>, visible: Boolean = true) {
        val g = groups[key] ?: return
        val ctx = viewCtx ?: return
        g.card.visibility = if (visible) View.VISIBLE else View.GONE
        g.summary.text = summary
        g.body.removeAllViews()
        for (r in rows) g.body.addView(rowView(ctx, r))
    }

    // ------------------------------------------------------------------ hien Hub

    fun show(service: AutoAccessibilityService) {
        if (root != null) return
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        viewCtx = service
        density = service.resources.displayMetrics.density
        val screenH = service.resources.displayMetrics.heightPixels
        val screenW = service.resources.displayMetrics.widthPixels

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
        bubbleBg.setStroke(dp(2), Color.parseColor(C_GOLD))
        bubble.background = bubbleBg
        container.addView(bubble, LinearLayout.LayoutParams(dp(46), dp(46)))

        // ---- Khung chinh ----
        val panelWidth = minOf(dp(300), screenW - dp(40))
        val panelBox = LinearLayout(service)
        panelBox.orientation = LinearLayout.VERTICAL
        panelBox.setPadding(dp(10), dp(10), dp(10), dp(10))
        val panelBg = GradientDrawable()
        panelBg.cornerRadius = dp(14).toFloat()
        panelBg.setColor(Color.parseColor(C_PANEL))
        panelBox.background = panelBg
        panelBox.visibility = View.GONE
        panel = panelBox

        // -- Dau: trang thai (co dinh, khong cuon) --
        val statusRow = LinearLayout(service)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        val dot = TextView(service)
        dot.text = "●"
        dot.textSize = 14f
        dot.setTextColor(Color.parseColor(C_OFF))
        dot.setPadding(0, 0, dp(6), 0)
        val statusTitle = TextView(service)
        statusTitle.textSize = 15f
        statusTitle.setTypeface(null, Typeface.BOLD)
        statusTitle.setTextColor(Color.parseColor(C_TITLE))
        val statusCounts = TextView(service)
        statusCounts.textSize = 12f
        statusCounts.setTextColor(Color.parseColor(C_MUTED))
        statusRow.addView(dot, lparams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusRow.addView(statusTitle, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 1f))
        statusRow.addView(statusCounts, lparams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val statusDetail = TextView(service)
        statusDetail.textSize = 11.5f
        statusDetail.setTextColor(Color.parseColor(C_MUTED))
        statusDetail.setPadding(0, dp(2), 0, dp(4))

        val alert = TextView(service)
        alert.textSize = 12.5f
        alert.setTypeface(null, Typeface.BOLD)
        alert.setTextColor(Color.parseColor(C_GOLD))
        alert.setPadding(dp(10), dp(8), dp(10), dp(8))
        val alertBg = GradientDrawable()
        alertBg.cornerRadius = dp(10).toFloat()
        alertBg.setColor(Color.parseColor("#3DFFB300"))
        alertBg.setStroke(dp(1), Color.parseColor("#80FFB300"))
        alert.background = alertBg
        alert.visibility = View.GONE

        tvDot = dot
        tvStatusTitle = statusTitle
        tvStatusCounts = statusCounts
        tvStatusDetail = statusDetail
        tvAlert = alert

        // -- Nut: hang 1 (bat dau/dung + tat chuong), hang 2 (hunt mode, thu gon, tat hub) --
        val toggle = pill(service, "▶ Bắt đầu", C_START, 14f)
        val muteBtn = pill(service, "🔕 Tắt chuông", C_BTN)
        muteBtn.visibility = View.GONE
        val modeBtn = pill(service, "🎯 Mode 3 · không dừng", C_BTN)
        val hideBtn = pill(service, "➖", C_BTN, 14f)
        val closeBtn = pill(service, "⏻", C_BTN, 14f)
        btnToggle = toggle
        btnMute = muteBtn
        btnMode = modeBtn

        val row1 = LinearLayout(service)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(toggle, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 2f, margin = dp(2)))
        row1.addView(muteBtn, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 1.3f, margin = dp(2)))
        val row2 = LinearLayout(service)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(modeBtn, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 3f, margin = dp(2)))
        row2.addView(hideBtn, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 0.8f, margin = dp(2)))
        row2.addView(closeBtn, lparams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight = 0.8f, margin = dp(2)))

        // -- Cac nhom (cuon duoc) --
        val content = LinearLayout(service)
        content.orientation = LinearLayout.VERTICAL
        val keys = listOf(
            "farm" to "⚔️ Farm",
            "gem" to "💎 Gem",
            "res" to "💰 Tài nguyên",
            "hb" to "🤖 Huntbot",
            "quest" to "📋 Nhiệm vụ",
            "zoo" to "🐾 Zoo & pet mới",
            "event" to "🎉 Sự kiện"
        )
        for ((key, title) in keys) {
            content.addView(
                makeGroup(service, key, title),
                lparams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.setMargins(0, 0, 0, dp(6))
                }
            )
        }
        val scroll = MaxHeightScrollView(service, (screenH * 0.46f).toInt())
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content)

        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        panelBox.addView(statusRow, LinearLayout.LayoutParams(panelWidth, wrap))
        panelBox.addView(statusDetail, LinearLayout.LayoutParams(panelWidth, wrap))
        panelBox.addView(alert, LinearLayout.LayoutParams(panelWidth, wrap).also { it.setMargins(0, 0, 0, dp(6)) })
        panelBox.addView(row1, LinearLayout.LayoutParams(panelWidth, wrap))
        panelBox.addView(row2, LinearLayout.LayoutParams(panelWidth, wrap).also { it.setMargins(0, 0, 0, dp(8)) })
        panelBox.addView(scroll, LinearLayout.LayoutParams(panelWidth, wrap))
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

    // ------------------------------------------------------------------ do du lieu vao giao dien

    private fun render(st: FarmState) {
        if (root == null) return
        val hub = st.hub

        // --- phan dau: trang thai ---
        val dotColor: String
        val title: String
        when {
            st.running && st.resting -> { dotColor = C_GOLD; title = "Đang nghỉ" }
            st.running -> { dotColor = C_GOOD; title = "Đang farm" }
            else -> { dotColor = C_OFF; title = "Đã dừng" }
        }
        tvDot?.setTextColor(Color.parseColor(dotColor))
        tvStatusTitle?.text = title
        tvStatusCounts?.text = if (st.running) "hunt ${st.hunts} · battle ${st.battles}" else ""
        val detail = StringBuilder(st.status)
        if (st.running && st.lastCommand.isNotEmpty()) detail.append("\nLệnh gần nhất: ").append(st.lastCommand)
        if (!st.huntPaused.isNullOrBlank()) detail.append("\n⏸ Hunt tạm dừng: ").append(st.huntPaused)
        tvStatusDetail?.text = detail.toString()

        val modeShort = when (st.huntMode) {
            1 -> "đủ lootbox"
            2 -> "thiếu gem"
            else -> "không dừng"
        }
        btnMode?.text = "🎯 Mode ${st.huntMode} · $modeShort"
        btnToggle?.text = if (st.running) "⏹ Dừng" else "▶ Bắt đầu"
        setPillColor(btnToggle, if (st.running) C_STOP else C_START)
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

        // --- chua co du lieu tu bot ---
        if (hub == null) {
            val err = st.hubError
            val msg = if (err.isNullOrBlank()) "Đang tải dữ liệu từ bot…" else "⚠️ $err"
            setGroup("farm", "", listOf(Row("", msg, if (err.isNullOrBlank()) Tone.MUTED else Tone.WARN)))
            for (k in listOf("gem", "res", "hb", "quest", "zoo")) setGroup(k, "", emptyList())
            setGroup("event", "", emptyList(), visible = false)
            return
        }

        val elapsed = if (st.hubFetchedAtMs > 0L) (System.currentTimeMillis() - st.hubFetchedAtMs) / 1000 else 0L

        // ===== Farm =====
        val farmRows = mutableListOf<Row>()
        val b = hub.battle
        val streakNow = b?.streak ?: 0
        if (b == null) {
            farmRows.add(Row("Chuỗi battle", "Chưa có dữ liệu (gõ owo battle)", Tone.MUTED))
        } else {
            val lost = b.lastLostStreak ?: 0
            val best = b.bestStreak?.let { " · cao nhất $it" } ?: ""
            farmRows.add(Row("Chuỗi battle", "🔥 $streakNow$best${ageText(b.ageSeconds)}"))
            if (b.result == "lost" && lost > 0) farmRows.add(Row("", "Vừa thua, mất chuỗi $lost", Tone.WARN))
        }
        val modeLong = when (st.huntMode) {
            1 -> "Dừng hunt khi đủ lootbox ngày"
            2 -> "Dừng hunt khi thiếu gem"
            else -> "Không dừng"
        }
        farmRows.add(Row("Hunt mode", "${st.huntMode} · $modeLong"))
        if (!st.huntPaused.isNullOrBlank()) farmRows.add(Row("Hunt", "Tạm dừng: ${st.huntPaused}", Tone.WARN))
        setGroup("farm", if (b == null) "" else "🔥 $streakNow", farmRows)

        // ===== Gem =====
        val gemRows = mutableListOf<Row>()
        val eq = hub.gems?.equipped.orEmpty()
        if (eq.isEmpty()) {
            gemRows.add(Row("", "Đang dùng: chưa có dữ liệu (gõ owo hunt)", Tone.MUTED))
        } else {
            gemRows.add(Row("", "Đang dùng:", Tone.MUTED))
            for (g in eq) {
                val low = (g.percent ?: 100) < 20
                gemRows.add(Row(HubFormat.gemType(g.slot), "${g.tier ?: "?"} [${g.current ?: 0}/${g.max ?: 0}]", if (low) Tone.WARN else Tone.NORMAL))
            }
        }
        val spare = hub.gems?.spare.orEmpty()
        if (spare.isEmpty()) {
            gemRows.add(Row("", "Trong kho: chưa có dữ liệu (gõ owo inv)", Tone.MUTED))
        } else {
            gemRows.add(Row("", "Trong kho${ageText(hub.gems?.spareAgeSeconds)}:", Tone.MUTED))
            for (entry in spare.groupBy { it.slot ?: "?" }.entries.sortedBy { it.key }) {
                gemRows.add(Row(HubFormat.gemType(entry.key), entry.value.joinToString(" · ") { g -> "${g.tier ?: "?"} ×${g.count ?: 0}" }))
            }
        }
        setGroup("gem", if (eq.isEmpty()) "" else "${eq.size} đang dùng", gemRows)

        // ===== Tai nguyen =====
        val resRows = mutableListOf<Row>()
        val cashAmount = hub.cowoncy?.amount
        val wsAmount = hub.weaponShards?.amount
        if (cashAmount == null) {
            resRows.add(Row("Cowoncy", "Chưa có dữ liệu (gõ owo cash)", Tone.MUTED))
        } else {
            resRows.add(Row("Cowoncy", "💰 ${HubFormat.num(cashAmount)}${ageText(hub.cowoncy?.ageSeconds)}"))
        }
        if (wsAmount == null) {
            resRows.add(Row("Weapon shards", "Chưa có dữ liệu (gõ owo ws)", Tone.MUTED))
        } else {
            resRows.add(Row("Weapon shards", "🧿 ${HubFormat.num(wsAmount)}${ageText(hub.weaponShards?.ageSeconds)}"))
        }
        val resSummary = listOfNotNull(
            cashAmount?.let { "💰 ${HubFormat.num(it)}" },
            wsAmount?.let { "🧿 ${HubFormat.num(it)}" }
        ).joinToString(" · ")
        setGroup("res", resSummary, resRows)

        // ===== Huntbot =====
        val hbRows = mutableListOf<Row>()
        val h = hub.huntbot
        var hbSummary = ""
        if (h == null) {
            hbRows.add(Row("", "Chưa có dữ liệu (gõ owo hb)", Tone.MUTED))
        } else {
            if (h.hunting == true) {
                val left = h.secondsLeft?.let { it - elapsed }
                val timeText = when {
                    left == null -> h.timeRemainingText?.let { "còn $it" } ?: "chưa rõ giờ xong"
                    left > 0L -> "còn ${HubFormat.shortDuration(left)}"
                    else -> "đã tới giờ xong"
                }
                val pct = h.progressPct?.let { "${HubFormat.trimNum(it)}%" } ?: ""
                hbRows.add(Row("Trạng thái", "Đang săn${if (pct.isEmpty()) "" else " · $pct"}", Tone.GOOD))
                hbRows.add(Row("Thời gian", timeText))
                h.animalsCaptured?.let { hbRows.add(Row("Bắt được", "${HubFormat.num(it)} con")) }
                hbSummary = listOf(pct, timeText).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                hbRows.add(Row("Trạng thái", "KHÔNG chạy — bạn tự chạy lại", Tone.WARN))
                hbSummary = "không chạy"
            }
            h.essence?.let { hbRows.add(Row("Essence", HubFormat.num(it))) }
            val traitNames = listOf(
                "efficiency" to "Eff", "duration" to "Dur", "cost" to "Cost",
                "gain" to "Gain", "experience" to "Exp", "radar" to "Radar"
            )
            val traits = h.traits
            if (traits != null) {
                val text = traitNames.mapNotNull { (k, n) -> traits[k]?.let { "$n $it" } }.joinToString(" · ")
                if (text.isNotEmpty()) hbRows.add(Row("Cấp", text, Tone.MUTED))
            }
        }
        setGroup("hb", hbSummary, hbRows)

        // ===== Nhiem vu =====
        val questRows = mutableListOf<Row>()
        val d = hub.daily
        var questSummary = ""
        if (d == null) {
            questRows.add(Row("Daily", "Chưa có dữ liệu (gõ owo daily)", Tone.MUTED))
        } else {
            val left = d.secondsLeft?.let { it - elapsed }
            val ready = left != null && left <= 0L
            val timeText = when {
                left == null -> "chưa rõ giờ nhận"
                left > 0L -> "còn ${HubFormat.shortDuration(left)} nữa nhận được"
                else -> "✅ sẵn sàng nhận"
            }
            val streak = d.streak?.let { " · chuỗi ${HubFormat.num(it)} ngày" } ?: ""
            questRows.add(Row("Daily", "$timeText$streak", if (ready) Tone.GOOD else Tone.NORMAL))
            questSummary = if (ready) "daily ✅" else ""
        }
        val q = hub.quest
        val dailyProg = hub.checklists?.daily?.progress
        val weeklyProg = hub.checklists?.weekly?.progress
        val prog = mutableListOf<String>()
        dailyProg?.cur?.let { prog.add("daily $it/${dailyProg?.max ?: "?"}") }
        weeklyProg?.cur?.let { prog.add("weekly $it/${weeklyProg?.max ?: "?"}") }
        if (prog.isNotEmpty()) {
            questRows.add(Row("Checklist", prog.joinToString(" · ")))
            if (questSummary.isEmpty()) questSummary = prog.first()
        }
        val qItems = q?.quests.orEmpty()
        if (qItems.isNotEmpty()) questRows.add(Row("Quest", "còn ${qItems.count { it.done != true }}/${qItems.size} chưa xong"))
        q?.seals?.let { questRows.add(Row("Seals", HubFormat.num(it))) }
        if (q == null && prog.isEmpty()) questRows.add(Row("Quest", "Chưa có dữ liệu (gõ owo q)", Tone.MUTED))
        setGroup("quest", questSummary, questRows)

        // ===== Zoo & pet moi =====
        val zooRows = mutableListOf<Row>()
        val z = hub.zoo
        var unlocked = 0
        if (z == null) {
            zooRows.add(Row("Zoo", "Chưa có dữ liệu (gõ owo zoo)", Tone.MUTED))
        } else {
            val sacrificed = z.sacrificedPets.orEmpty().size
            unlocked = z.unlockedTotal ?: (z.pets.orEmpty().size + sacrificed)
            val points = z.zooPoints?.let { " · $it điểm" } ?: ""
            zooRows.add(Row("Zoo", "$unlocked loài đã mở khóa$points${ageText(z.ageSeconds)}"))
            if (sacrificed > 0) zooRows.add(Row("", "Trong đó $sacrificed loài số lượng 0 (đã hiến tế)", Tone.MUTED))
        }
        // pet moi: chi tinh tu luc bat Hub / bat farm (tat bat lai la xoa trang)
        val sessionNames = st.sessionNewPets.toSet()
        val newPets = hub.newPets?.pets.orEmpty().filter { (it.name ?: "") in sessionNames }
        if (newPets.isEmpty()) {
            zooRows.add(Row("Pet mới", "Chưa có lần farm này", Tone.MUTED))
        } else {
            newPets.take(6).forEachIndexed { i, p ->
                val text = (p.name ?: "?") + (p.rankVi?.let { " · $it" } ?: "") + " ×${p.timesFound ?: 1}"
                zooRows.add(Row(if (i == 0) "Pet mới" else "", text, Tone.GOOD))
            }
        }
        val zooSummary = listOfNotNull(
            if (z != null) "$unlocked loài" else null,
            if (newPets.isNotEmpty()) "🆕 ${newPets.size}" else null
        ).joinToString(" · ")
        setGroup("zoo", zooSummary, zooRows)

        // ===== Su kien (chi hien khi bot thay tin su kien) =====
        val evCounters = hub.event?.counters.orEmpty()
        if (evCounters.isEmpty()) {
            setGroup("event", "", emptyList(), visible = false)
        } else {
            val evRows = mutableListOf<Row>()
            for (c in evCounters) {
                evRows.add(Row(c.kind ?: "?", "${c.current ?: "?"}/${c.max ?: "?"}${ageText(c.ageSeconds)}"))
            }
            val latest = hub.event?.recent.orEmpty().firstOrNull()
            val rewardText = latest?.rewards.orEmpty().joinToString(", ") { r -> "${HubFormat.num(r.amount)} ${r.item ?: "?"}" }
            if (latest != null && rewardText.isNotEmpty()) {
                evRows.add(Row("Gần nhất", rewardText + ageText(latest.ageSeconds), Tone.GOOD))
            }
            val evSummary = evCounters.joinToString(" · ") { c -> "${c.kind ?: "?"} ${c.current ?: "?"}/${c.max ?: "?"}" }
            setGroup("event", evSummary, evRows)
        }
    }
}
