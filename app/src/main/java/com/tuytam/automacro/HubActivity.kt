package com.tuytam.automacro

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuytam.automacro.data.HubFormat
import com.tuytam.automacro.data.HubGems
import com.tuytam.automacro.data.HubQuest
import com.tuytam.automacro.data.HubReward
import com.tuytam.automacro.data.HubResponse
import com.tuytam.automacro.data.HubResult
import com.tuytam.automacro.data.HubSummary
import com.tuytam.automacro.data.HubTeam
import com.tuytam.automacro.data.IconSpec
import com.tuytam.automacro.data.LineState
import com.tuytam.automacro.data.OwoTrackerApi
import com.tuytam.automacro.data.OwoTrackerPrefs
import com.tuytam.automacro.data.TileState
import com.tuytam.automacro.data.Tone
import com.tuytam.automacro.data.gemsTileIcon
import com.tuytam.automacro.data.iconOf
import com.tuytam.automacro.data.inventoryCardIcon
import com.tuytam.automacro.data.questTileIcon
import com.tuytam.automacro.data.teamCardIcon
import com.tuytam.automacro.data.weaponsCardIcon
import com.tuytam.automacro.data.zooTileIcon
import com.tuytam.automacro.databinding.ActivityHubBinding
import com.tuytam.automacro.databinding.ItemHubCardBinding
import com.tuytam.automacro.databinding.ItemHubTileBinding
import com.tuytam.automacro.view.BarChartView
import com.tuytam.automacro.view.BarEntry
import com.tuytam.automacro.view.IconView
import com.tuytam.automacro.view.RingView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Man hinh Hub. Bo cuc:
 *  - 6 O TOM TAT o tren cung (Daily, HuntBot, Cowoncy, Quest, Gem, Pet): moi o co 1 vong
 *    tron % (khi co y nghia) + 1 icon THAT (anh emoji cua Discord, hoac Unicode, hoac
 *    chu cai dau) + 1 so to + mau trang thai (xanh la = xong/san sang, xanh duong = dang
 *    dem nguoc, cam = nen de y, do = can lam ngay, xam = chua co du lieu).
 *  - THE CHI TIET ben duoi: moi the 1 mau rieng + icon rieng o tieu de, luon hien 1 dong
 *    tom tat, cham vao de mo/dong noi dung day du. Gem/Quest dung vong tron % + icon that
 *    cho tung dong; Zoo co them bieu do cot so sanh so pet theo tier khi mo rong.
 *  - Tu lay lai du lieu moi 30 giay; moi giay chi cap nhat dong ho dem nguoc + "X phut truoc".
 */
class HubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubBinding
    private lateinit var cards: List<Card>

    private var hub: HubResponse? = null
    private var fetchedAtMs: Long = 0L
    private val expanded = mutableSetOf(KEY_GEMS, KEY_QUEST)

    private var fetchJob: Job? = null
    private var tickJob: Job? = null

    private class Card(val key: String, val ui: ItemHubCardBinding, val accentColor: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tileDaily.tvTileLabel.setText(R.string.hub_tile_daily)
        binding.tileHuntbot.tvTileLabel.setText(R.string.hub_tile_huntbot)
        binding.tileCowoncy.tvTileLabel.setText(R.string.hub_tile_cowoncy)
        binding.tileQuest.tvTileLabel.setText(R.string.hub_tile_quest)
        binding.tileGems.tvTileLabel.setText(R.string.hub_tile_gems)
        binding.tileZoo.tvTileLabel.setText(R.string.hub_tile_zoo)

        cards = listOf(
            setupCard(KEY_GEMS, binding.cardGems, R.string.hub_section_gems, R.color.hub_accent_gems),
            setupCard(KEY_QUEST, binding.cardQuest, R.string.hub_section_quest, R.color.hub_accent_quest),
            setupCard(KEY_TEAM, binding.cardTeam, R.string.hub_section_team, R.color.hub_accent_team),
            setupCard(KEY_ZOO, binding.cardZoo, R.string.hub_section_zoo, R.color.hub_accent_zoo),
            setupCard(KEY_WEAPONS, binding.cardWeapons, R.string.hub_section_weapons, R.color.hub_accent_weapons),
            setupCard(KEY_INVENTORY, binding.cardInventory, R.string.hub_section_inventory, R.color.hub_accent_inventory)
        )

        // Truoc khi co du lieu: cac o hien dau "…" thay vi de trong
        val loading = TileState("…", "Đang lấy dữ liệu", Tone.IDLE)
        for (t in listOf(binding.tileDaily, binding.tileHuntbot, binding.tileCowoncy,
                         binding.tileQuest, binding.tileGems, binding.tileZoo)) {
            applyTile(t, loading)
        }

        binding.btnHubRefresh.setOnClickListener {
            lifecycleScope.launch { loadHub() }
        }
    }

    private fun setupCard(key: String, ui: ItemHubCardBinding, titleRes: Int, accentRes: Int): Card {
        val accentColor = getColor(accentRes)
        ui.tvCardTitle.setText(titleRes)
        ui.vAccent.setBackgroundColor(accentColor)
        ui.flCardIconBg.background = circleDrawable(withAlpha(accentColor, 40))
        ui.ivCardIcon.bind(lifecycleScope, IconSpec(unicode = "•"))
        ui.tvCardSummary.text = ""
        ui.tvCardArrow.text = if (key in expanded) ARROW_OPEN else ARROW_CLOSED
        ui.root.setOnClickListener {
            if (!expanded.add(key)) expanded.remove(key)
            renderBodies()
        }
        return Card(key, ui, accentColor)
    }

    override fun onResume() {
        super.onResume()
        // Lay du lieu ngay, roi cu 30 giay lay lai 1 lan
        fetchJob = lifecycleScope.launch {
            while (isActive) {
                loadHub()
                delay(REFRESH_INTERVAL_MS)
            }
        }
        // Moi giay cap nhat dong ho dem nguoc + "X phut truoc" (khong goi mang)
        tickJob = lifecycleScope.launch {
            while (isActive) {
                renderTimed()
                delay(1000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        fetchJob?.cancel()
        tickJob?.cancel()
    }

    private suspend fun loadHub() {
        val settings = OwoTrackerPrefs.load(this)
        if (settings.apiUrl.isBlank() || settings.token.isBlank()) {
            binding.tvHubStatus.text = getString(R.string.hub_need_settings)
            return
        }
        if (hub == null) binding.tvHubStatus.text = getString(R.string.hub_loading)

        when (val result = OwoTrackerApi.fetchHub(settings.apiUrl, settings.token)) {
            is HubResult.Success -> {
                hub = result.hub
                fetchedAtMs = SystemClock.elapsedRealtime()
                val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                binding.tvHubStatus.text = getString(R.string.hub_updated_at, clock)
                renderBodies()
                renderTimed()
            }
            is HubResult.Failure -> {
                val note = if (hub != null) "\n" + getString(R.string.hub_showing_old_data) else ""
                binding.tvHubStatus.text = result.message + note
            }
        }
    }

    // ------------------------------------------------------------------
    // Ve giao dien
    // ------------------------------------------------------------------

    /** Ve lai dong tom tat + icon tieu de + noi dung cac the — khi co du lieu moi hoac khi cham mo/dong. */
    private fun renderBodies() {
        val h = hub ?: return
        for (card in cards) {
            val error = h.sectionErrors?.get(card.key)
            val line = if (error != null) LineState("⚠️ Không đọc được mục này", Tone.BAD) else summaryOf(card.key, h)
            val ui = card.ui
            ui.tvCardSummary.text = line.text
            ui.tvCardSummary.setTextColor(toneColor(line.tone))
            ui.ivCardIcon.bind(lifecycleScope, if (error != null) IconSpec(unicode = "⚠️") else cardIconFor(card.key, h))

            val open = card.key in expanded
            ui.tvCardArrow.text = if (open) ARROW_OPEN else ARROW_CLOSED
            ui.llCardBody.removeAllViews()
            ui.llCardBody.visibility = if (open) View.VISIBLE else View.GONE
            if (open) {
                if (error != null) addText(ui.llCardBody, HubFormat.sectionError(error)) else fillBody(card.key, h, ui.llCardBody)
            }
        }
    }

    private fun summaryOf(key: String, h: HubResponse): LineState = when (key) {
        KEY_GEMS -> HubSummary.gemsLine(h.gems)
        KEY_QUEST -> HubSummary.questLine(h.quest)
        KEY_TEAM -> HubSummary.teamLine(h.team)
        KEY_ZOO -> HubSummary.zooLine(h.zoo)
        KEY_WEAPONS -> HubSummary.weaponsLine(h.weapons)
        else -> HubSummary.inventoryLine(h.inventory)
    }

    private fun cardIconFor(key: String, h: HubResponse): IconSpec = when (key) {
        KEY_GEMS -> gemsTileIcon(h.gems)
        KEY_QUEST -> questTileIcon(h.quest)
        KEY_TEAM -> teamCardIcon(h.team)
        KEY_ZOO -> zooTileIcon(h.zoo)
        KEY_WEAPONS -> weaponsCardIcon()
        else -> inventoryCardIcon()
    }

    private fun fillBody(key: String, h: HubResponse, body: LinearLayout) {
        when (key) {
            KEY_GEMS -> fillGems(body, h.gems)
            KEY_QUEST -> fillQuest(body, h.quest)
            KEY_TEAM -> fillTeam(body, h.team)
            KEY_ZOO -> {
                fillZooChart(body, h.zoo)
                addText(body, HubFormat.zoo(h.zoo, true), topDp = 12)
            }
            KEY_WEAPONS -> addText(body, HubFormat.weapons(h.weapons, true))
            else -> addText(body, HubFormat.inventory(h.inventory, true))
        }
    }

    private fun fillGems(body: LinearLayout, g: HubGems?) {
        val equipped = g?.equipped.orEmpty()
        if (equipped.isEmpty()) {
            addText(body, "Chưa thấy gem đang trang bị — gõ owo hunt 1 lần để bot ghi nhận.", dim = true)
        }
        for ((i, e) in equipped.withIndex()) {
            val p = e.percent ?: 0
            val warn = if (p <= 15) "  ⚠️ sắp hết" else ""
            val icon = iconOf(e.emojiId, e.emojiAnimated, e.emoji, e.tier)
            val color = toneColor(HubSummary.gemTone(p))
            addIconTextRow(body, icon, "Slot ${e.slot ?: "?"} · ${e.tier ?: "?"}$warn",
                "${e.current ?: "?"}/${e.max ?: "?"} ($p%)", ringPercent = p, ringColor = color, topDp = if (i == 0) 0 else 14)
        }
        addText(body, "Dự phòng trong kho", sizeSp = 13f, bold = true, dim = true, topDp = 16)
        val spare = g?.spare.orEmpty()
        if (spare.isEmpty()) {
            addText(body, "(không có, hoặc bot chưa thấy kho)", dim = true, topDp = 2)
        } else {
            for ((idx, entry) in spare.groupBy { it.slot ?: "?" }.entries.withIndex()) {
                val (slot, list) = entry
                val first = list.first()
                val icon = iconOf(first.emojiId, first.emojiAnimated, first.emoji, first.tier)
                val line = list.joinToString(" · ") { "${it.tier ?: "?"} ×${HubFormat.num(it.count)}" }
                addIconTextRow(body, icon, "Slot $slot", line, topDp = if (idx == 0) 4 else 8, iconSizeDp = 26)
            }
        }
    }

    private fun fillQuest(body: LinearLayout, q: HubQuest?) {
        val items = q?.quests.orEmpty()
        if (items.isEmpty()) {
            val msg = if (q?.allDone == true) "🎉 Đã xong hết quest hôm nay" else "Chưa có chi tiết quest — gõ owo quest để bot ghi nhận."
            addText(body, msg, dim = q?.allDone != true)
            return
        }
        for ((i, qi) in items.withIndex()) {
            val done = qi.done == true
            val icon = iconOf(qi.emojiId, qi.emojiAnimated, qi.emoji, qi.title)
            val pct = if (qi.current != null && qi.max != null && qi.max > 0)
                (qi.current * 100 / qi.max).toInt().coerceIn(0, 100) else null
            val progressText = if (pct != null) "${HubFormat.num(qi.current)}/${HubFormat.num(qi.max)} ($pct%)" else null
            addIconTextRow(body, icon, "${if (done) "✅ " else ""}${qi.index ?: "?"}. ${qi.title ?: "?"}", progressText,
                ringPercent = pct, ringColor = toneColor(if (done) Tone.OK else Tone.INFO), topDp = if (i == 0) 0 else 18)
            if (!qi.description.isNullOrBlank()) addText(body, qi.description, sizeSp = 13f, dim = true, topDp = 4)
            addRewardsRow(body, qi.rewards.orEmpty(), topDp = 6)
        }
    }

    private fun fillTeam(body: LinearLayout, t: HubTeam?) {
        val members = t?.members.orEmpty()
        if (members.isEmpty()) {
            addText(body, "Chưa có đội hình — gõ owo team 1 lần để bot ghi nhận.", dim = true)
            return
        }
        for ((i, m) in members.withIndex()) {
            val icon = iconOf(m.emojiId, m.emojiAnimated, m.emoji, m.name)
            val stats = "HP${m.hp ?: "?"} WP${m.wp ?: "?"} ATT${m.att ?: "?"} MAG${m.mag ?: "?"}"
            val sub = if (m.weaponId.isNullOrBlank()) stats else "$stats · ${m.weaponId} ${HubFormat.trimNum(m.quality)}%"
            addIconTextRow(body, icon, "[${m.pos ?: "?"}] ${m.name ?: "?"} Lv${m.level ?: "?"}", sub,
                topDp = if (i == 0) 0 else 12)
        }
    }

    /** Bieu do cot: so pet dang co theo tung tier (tu dong cuoi bang zoo, xem HubFormat/README). */
    private fun fillZooChart(parent: LinearLayout, z: com.tuytam.automacro.data.HubZoo?) {
        val tiers = z?.byTier.orEmpty().filterValues { (it.total ?: 0) > 0 }
        if (tiers.isEmpty()) return
        addText(parent, "So sánh theo tier", sizeSp = 13f, bold = true, dim = true)
        val chart = BarChartView(this)
        chart.labelColor = binding.tvHubTitle.currentTextColor
        chart.entries = tiers.entries.sortedByDescending { it.value.total ?: 0L }
            .map { (name, tier) -> BarEntry(name.replaceFirstChar { c -> c.uppercaseChar() }, tier.total ?: 0L, tierColor(name)) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        parent.addView(chart, lp)
    }

    /** Phan phu thuoc thoi gian: 6 o tom tat + chu "X phut truoc" cua tung the. */
    private fun renderTimed() {
        val h = hub ?: return
        val elapsed = (SystemClock.elapsedRealtime() - fetchedAtMs) / 1000L
        val errors = h.sectionErrors

        applyTile(binding.tileDaily, tileOrError(errors, "daily") { HubSummary.dailyTile(h.daily, elapsed) })
        applyTile(binding.tileHuntbot, tileOrError(errors, "huntbot") { HubSummary.huntbotTile(h.huntbot, elapsed) })
        applyTile(binding.tileCowoncy, tileOrError(errors, "cowoncy") {
            HubSummary.cowoncyTile(h.cowoncy, h.cowoncy?.ageSeconds?.plus(elapsed))
        })
        applyTile(binding.tileQuest, tileOrError(errors, "quest") { HubSummary.questTile(h.quest, elapsed) })
        applyTile(binding.tileGems, tileOrError(errors, "gems") { HubSummary.gemsTile(h.gems) })
        applyTile(binding.tileZoo, tileOrError(errors, "zoo") { HubSummary.zooTile(h.zoo) })

        setCardAge(KEY_GEMS, HubFormat.gemsAgeSeconds(h.gems), elapsed)
        setCardAge(KEY_QUEST, h.quest?.ageSeconds, elapsed)
        setCardAge(KEY_TEAM, h.team?.ageSeconds, elapsed)
        setCardAge(KEY_ZOO, h.zoo?.ageSeconds, elapsed)
        setCardAge(KEY_WEAPONS, h.weapons?.ageSeconds, elapsed)
        setCardAge(KEY_INVENTORY, h.inventory?.ageSeconds, elapsed)
    }

    private fun tileOrError(errors: Map<String, String>?, key: String, build: () -> TileState): TileState =
        if (errors?.containsKey(key) == true) TileState("Lỗi", "Không đọc được", Tone.BAD, IconSpec(unicode = "⚠️")) else build()

    private fun setCardAge(key: String, ageSeconds: Long?, elapsedSec: Long) {
        val card = cards.firstOrNull { it.key == key } ?: return
        card.ui.tvCardAge.text = if (ageSeconds == null) "" else HubFormat.age(ageSeconds + elapsedSec)
    }

    private fun applyTile(tile: ItemHubTileBinding, state: TileState) {
        val color = toneColor(state.tone)
        tile.tvTileValue.text = state.value
        tile.tvTileValue.setTextColor(color)
        tile.tvTileSub.text = state.sub
        tile.root.strokeColor = color
        tile.flTileIconBg.background = circleDrawable(withAlpha(color, 32))
        tile.rvTileRing.ringColor = color
        tile.rvTileRing.percent = state.ringPercent ?: 0
        tile.ivTileIcon.bind(lifecycleScope, state.icon, unicodeSp = 16f, letterSp = 12f)
    }

    private fun toneColor(tone: Tone): Int = getColor(
        when (tone) {
            Tone.OK -> R.color.hub_ok
            Tone.INFO -> R.color.hub_info
            Tone.WARN -> R.color.hub_warn
            Tone.BAD -> R.color.hub_bad
            Tone.IDLE -> R.color.hub_idle
        }
    )

    /** Mau rieng cho tung tier trong bieu do cot Zoo — chi de phan biet truc quan, khong mang y nghia gi khac. */
    private fun tierColor(tier: String?): Int {
        val idx = TIER_ORDER.indexOf(tier?.lowercase(Locale.US))
        return if (idx in TIER_PALETTE.indices) TIER_PALETTE[idx]
        else TIER_PALETTE[Math.floorMod(tier?.hashCode() ?: 0, TIER_PALETTE.size)]
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun circleDrawable(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    // ------------------------------------------------------------------
    // Ham dung view nho (dung trong noi dung the chi tiet)
    // ------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addText(
        parent: LinearLayout, text: CharSequence, sizeSp: Float = 15f,
        bold: Boolean = false, dim: Boolean = false, topDp: Int = 0
    ): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = sizeSp
        tv.setTextColor(binding.tvHubTitle.textColors)
        if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        if (dim) tv.alpha = 0.75f
        tv.setLineSpacing(0f, 1.15f)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topDp)
        parent.addView(tv, lp)
        return tv
    }

    /**
     * 1 hang: [vong tron % (neu co) + icon that o giua] + [tieu de / phu de].
     * Dung cho gem, quest, doi hinh trong noi dung the mo rong.
     */
    private fun addIconTextRow(
        parent: LinearLayout, icon: IconSpec, title: CharSequence, subtitle: CharSequence? = null,
        ringPercent: Int? = null, ringColor: Int? = null, topDp: Int = 0, iconSizeDp: Int = 32
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val frame = FrameLayout(this)
        if (ringPercent != null && ringColor != null) {
            val ring = RingView(this)
            ring.ringColor = ringColor
            ring.percent = ringPercent
            ring.strokeWidthDp = 3.5f
            frame.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val inset = dp(5)
            val iv = IconView(this)
            iv.bind(lifecycleScope, icon, unicodeSp = 14f, letterSp = 10f)
            val ivLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            ivLp.setMargins(inset, inset, inset, inset)
            frame.addView(iv, ivLp)
        } else {
            val iv = IconView(this)
            iv.bind(lifecycleScope, icon, unicodeSp = 16f, letterSp = 12f)
            frame.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        row.addView(frame, LinearLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val tvTitle = TextView(this)
        tvTitle.text = title
        tvTitle.textSize = 15f
        tvTitle.setTextColor(binding.tvHubTitle.textColors)
        col.addView(tvTitle)
        if (!subtitle.isNullOrEmpty()) {
            val tvSub = TextView(this)
            tvSub.text = subtitle
            tvSub.textSize = 13f
            tvSub.alpha = 0.75f
            tvSub.setTextColor(binding.tvHubTitle.textColors)
            col.addView(tvSub)
        }
        val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        colLp.marginStart = dp(10)
        row.addView(col, colLp)

        val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(topDp)
        parent.addView(row, rowLp)
    }

    /** 1 hang ngang cac phan thuong, moi phan thuong la 1 icon nho + "+so luong". Dung trong the Quest. */
    private fun addRewardsRow(parent: LinearLayout, rewards: List<HubReward>, topDp: Int) {
        if (rewards.isEmpty()) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        for ((idx, r) in rewards.withIndex()) {
            val iv = IconView(this)
            iv.bind(lifecycleScope, iconOf(r.emojiId, r.emojiAnimated, r.emoji, r.item), unicodeSp = 13f, letterSp = 10f)
            val ivLp = LinearLayout.LayoutParams(dp(18), dp(18))
            if (idx > 0) ivLp.marginStart = dp(12)
            row.addView(iv, ivLp)

            val tv = TextView(this)
            tv.text = "+${HubFormat.num(r.amount)}"
            tv.textSize = 13f
            tv.alpha = 0.85f
            tv.setTextColor(binding.tvHubTitle.textColors)
            val tvLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            tvLp.marginStart = dp(4)
            row.addView(tv, tvLp)
        }
        val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(topDp)
        parent.addView(row, rowLp)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val ARROW_OPEN = "▾"
        private const val ARROW_CLOSED = "▸"
        private const val KEY_GEMS = "gems"
        private const val KEY_QUEST = "quest"
        private const val KEY_TEAM = "team"
        private const val KEY_ZOO = "zoo"
        private const val KEY_WEAPONS = "weapons"
        private const val KEY_INVENTORY = "inventory"

        private val TIER_ORDER = listOf(
            "common", "uncommon", "rare", "epic", "mythic", "legendary",
            "gem", "patreon", "special", "botrank", "cpatreon", "fabled"
        )
        private val TIER_PALETTE = intArrayOf(
            0xFF8D8D99.toInt(), 0xFF4CAF7D.toInt(), 0xFF3FA7D6.toInt(), 0xFFB064E8.toInt(),
            0xFFE85D9E.toInt(), 0xFFE8B84B.toInt(), 0xFF29C7C7.toInt(), 0xFFEF6C57.toInt(),
            0xFF9575CD.toInt(), 0xFF78909C.toInt(), 0xFFD4AF37.toInt(), 0xFF546E7A.toInt()
        )
    }
}
