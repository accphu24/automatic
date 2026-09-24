package com.tuytam.automacro

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuytam.automacro.data.HubFormat
import com.tuytam.automacro.data.HubGems
import com.tuytam.automacro.data.HubQuest
import com.tuytam.automacro.data.HubResponse
import com.tuytam.automacro.data.HubResult
import com.tuytam.automacro.data.HubSummary
import com.tuytam.automacro.data.LineState
import com.tuytam.automacro.data.OwoTrackerApi
import com.tuytam.automacro.data.OwoTrackerPrefs
import com.tuytam.automacro.data.TileState
import com.tuytam.automacro.data.Tone
import com.tuytam.automacro.databinding.ActivityHubBinding
import com.tuytam.automacro.databinding.ItemHubCardBinding
import com.tuytam.automacro.databinding.ItemHubTileBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Man hinh Hub. Bo cuc:
 *  - 6 O TOM TAT o tren cung (Daily, HuntBot, Cowoncy, Quest, Gem, Pet): moi o 1 so to
 *    + 1 mau trang thai (xanh la = xong/san sang, xanh duong = dang dem nguoc,
 *    cam = nen de y, do = can lam ngay, xam = chua co du lieu).
 *  - THE CHI TIET ben duoi: moi the 1 mau rieng, luon hien 1 dong tom tat, cham vao de
 *    mo/dong noi dung day du. Gem va Quest mo san, cac the con lai dong cho do dai.
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

    private class Card(val key: String, val ui: ItemHubCardBinding)

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
            setupCard(KEY_BATTLES, binding.cardBattles, R.string.hub_section_battles, R.color.hub_accent_battles),
            setupCard(KEY_ZOO, binding.cardZoo, R.string.hub_section_zoo, R.color.hub_accent_zoo),
            setupCard(KEY_WEAPONS, binding.cardWeapons, R.string.hub_section_weapons, R.color.hub_accent_weapons),
            setupCard(KEY_INVENTORY, binding.cardInventory, R.string.hub_section_inventory, R.color.hub_accent_inventory)
        )

        // Truoc khi co du lieu: cac o hien dau "—" thay vi de trong
        applyTile(binding.tileDaily, TileState("…", "Đang lấy dữ liệu", Tone.IDLE))
        applyTile(binding.tileHuntbot, TileState("…", "Đang lấy dữ liệu", Tone.IDLE))
        applyTile(binding.tileCowoncy, TileState("…", "Đang lấy dữ liệu", Tone.IDLE))
        applyTile(binding.tileQuest, TileState("…", "Đang lấy dữ liệu", Tone.IDLE))
        applyTile(binding.tileGems, TileState("…", "Đang lấy dữ liệu", Tone.IDLE))
        applyTile(binding.tileZoo, TileState("…", "Đang lấy dữ liệu", Tone.IDLE))

        binding.btnHubRefresh.setOnClickListener {
            lifecycleScope.launch { loadHub() }
        }
    }

    private fun setupCard(key: String, ui: ItemHubCardBinding, titleRes: Int, accentRes: Int): Card {
        ui.tvCardTitle.setText(titleRes)
        ui.vAccent.setBackgroundColor(getColor(accentRes))
        ui.tvCardSummary.text = ""
        ui.tvCardArrow.text = if (key in expanded) ARROW_OPEN else ARROW_CLOSED
        ui.root.setOnClickListener {
            if (!expanded.add(key)) expanded.remove(key)
            renderBodies()
        }
        return Card(key, ui)
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

    /** Ve lai dong tom tat + noi dung cac the — khi co du lieu moi hoac khi cham mo/dong. */
    private fun renderBodies() {
        val h = hub ?: return
        for (card in cards) {
            val error = h.sectionErrors?.get(card.key)
            val line = if (error != null) LineState("⚠️ Không đọc được mục này", Tone.BAD) else summaryOf(card.key, h)
            val ui = card.ui
            ui.tvCardSummary.text = line.text
            ui.tvCardSummary.setTextColor(toneColor(line.tone))

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
        KEY_BATTLES -> HubSummary.battlesLine(h.battles)
        KEY_ZOO -> HubSummary.zooLine(h.zoo)
        KEY_WEAPONS -> HubSummary.weaponsLine(h.weapons)
        else -> HubSummary.inventoryLine(h.inventory)
    }

    private fun fillBody(key: String, h: HubResponse, body: LinearLayout) {
        when (key) {
            KEY_GEMS -> fillGems(body, h.gems)
            KEY_QUEST -> fillQuest(body, h.quest)
            KEY_TEAM -> addText(body, HubFormat.team(h.team))
            KEY_BATTLES -> addText(body, HubFormat.battles(h.battles))
            KEY_ZOO -> addText(body, HubFormat.zoo(h.zoo, true))
            KEY_WEAPONS -> addText(body, HubFormat.weapons(h.weapons, true))
            else -> addText(body, HubFormat.inventory(h.inventory, true))
        }
    }

    private fun fillGems(body: LinearLayout, g: HubGems?) {
        val equipped = g?.equipped.orEmpty()
        if (equipped.isEmpty()) {
            addText(body, "Chưa thấy gem đang trang bị — gõ owo hunt 1 lần để bot ghi nhận.", dim = true)
        }
        for (e in equipped) {
            val p = e.percent ?: 0
            val warn = if (p <= 15) "  ⚠️ sắp hết" else ""
            addBarRow(
                body, "Slot ${e.slot ?: "?"} · ${e.tier ?: "?"}$warn",
                "${e.current ?: "?"}/${e.max ?: "?"} ($p%)", p, toneColor(HubSummary.gemTone(p)), 10
            )
        }
        addText(body, "Dự phòng trong kho", sizeSp = 13f, bold = true, dim = true, topDp = 16)
        val spare = g?.spare.orEmpty()
        if (spare.isEmpty()) {
            addText(body, "(không có, hoặc bot chưa thấy kho)", dim = true, topDp = 2)
        } else {
            for ((slot, list) in spare.groupBy { it.slot ?: "?" }) {
                val line = list.joinToString(" · ") { "${it.tier ?: "?"} ×${HubFormat.num(it.count)}" }
                addText(body, "Slot $slot: $line", topDp = 4)
            }
        }
    }

    private fun fillQuest(body: LinearLayout, q: HubQuest?) {
        val items = q?.quests.orEmpty()
        if (items.isEmpty()) {
            val msg = if (q?.allDone == true) "🎉 Đã xong hết quest hôm nay" else "Chưa có chi tiết quest — gõ owo quest để bot ghi nhận."
            addText(body, msg, dim = q?.allDone != true)
        }
        for ((i, qi) in items.withIndex()) {
            val done = qi.done == true
            val rarity = if (qi.rarity.isNullOrBlank()) "" else "  (${qi.rarity})"
            addText(body, "${if (done) "✅" else "•"} ${qi.index ?: "?"}. ${qi.title ?: "?"}$rarity",
                sizeSp = 16f, bold = true, topDp = if (i == 0) 0 else 14)
            if (qi.current != null && qi.max != null && qi.max > 0) {
                val pct = (qi.current * 100 / qi.max).toInt().coerceIn(0, 100)
                addBarRow(body, "", "${HubFormat.num(qi.current)}/${HubFormat.num(qi.max)} ($pct%)", pct,
                    toneColor(if (done) Tone.OK else Tone.INFO), 6)
            }
            if (!qi.description.isNullOrBlank()) addText(body, qi.description, sizeSp = 13f, dim = true, topDp = 4)
            val rewards = qi.rewards.orEmpty()
            if (rewards.isNotEmpty()) {
                addText(body, "Thưởng: " + rewards.joinToString(" · ") { r -> "${r.item ?: "?"} +${HubFormat.num(r.amount)}" },
                    sizeSp = 13f, dim = true, topDp = 2)
            }
        }
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
        setCardAge(KEY_BATTLES, h.battles?.ageSeconds, elapsed)
        setCardAge(KEY_ZOO, h.zoo?.ageSeconds, elapsed)
        setCardAge(KEY_WEAPONS, h.weapons?.ageSeconds, elapsed)
        setCardAge(KEY_INVENTORY, h.inventory?.ageSeconds, elapsed)
    }

    private fun tileOrError(errors: Map<String, String>?, key: String, build: () -> TileState): TileState =
        if (errors?.containsKey(key) == true) TileState("Lỗi", "Không đọc được", Tone.BAD) else build()

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
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        if (dim) tv.alpha = 0.75f
        tv.setLineSpacing(0f, 1.15f)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topDp)
        parent.addView(tv, lp)
        return tv
    }

    /** 1 hang gom chu trai + chu phai, kem 1 thanh tien do tô mau ben duoi. */
    private fun addBarRow(parent: LinearLayout, left: String, right: String, percent: Int, color: Int, topDp: Int) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val l = TextView(this)
        l.text = left
        l.textSize = 15f
        l.setTextColor(binding.tvHubTitle.textColors)
        row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val r = TextView(this)
        r.text = right
        r.textSize = 15f
        r.setTypeface(r.typeface, Typeface.BOLD)
        r.setTextColor(color)
        row.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rowLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rowLp.topMargin = dp(topDp)
        parent.addView(row, rowLp)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progress = percent.coerceIn(0, 100)
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf((color and 0x00FFFFFF) or 0x33000000)
        val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        barLp.topMargin = dp(4)
        parent.addView(bar, barLp)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val ARROW_OPEN = "▾"
        private const val ARROW_CLOSED = "▸"
        private const val KEY_GEMS = "gems"
        private const val KEY_QUEST = "quest"
        private const val KEY_TEAM = "team"
        private const val KEY_BATTLES = "battles"
        private const val KEY_ZOO = "zoo"
        private const val KEY_WEAPONS = "weapons"
        private const val KEY_INVENTORY = "inventory"
    }
}
