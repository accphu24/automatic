package com.tuytam.automacro

import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuytam.automacro.data.HubFormat
import com.tuytam.automacro.data.HubResponse
import com.tuytam.automacro.data.HubResult
import com.tuytam.automacro.data.OwoTrackerApi
import com.tuytam.automacro.data.OwoTrackerPrefs
import com.tuytam.automacro.databinding.ActivityHubBinding
import com.tuytam.automacro.databinding.ItemHubSectionBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Man hinh Hub: hien du lieu moi nhat owo-tracker da doc duoc tu tin nhan
 * OwO (gem, HuntBot, quest, doi hinh, tran dau, pet, vu khi, kho do).
 * - Tu lay lai du lieu moi 30 giay khi dang mo man hinh nay.
 * - Moi giay chi cap nhat dong ho dem nguoc HuntBot + chu "X phut truoc".
 * - Cham vao the Zoo / Vu khi / Kho do de mo rong xem chi tiet.
 */
class HubActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHubBinding

    private var hub: HubResponse? = null
    private var fetchedAtMs: Long = 0L
    private val expanded = mutableSetOf<String>()

    private var fetchJob: Job? = null
    private var tickJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.secDaily.tvSectionTitle.setText(R.string.hub_section_daily)
        binding.secHuntbot.tvSectionTitle.setText(R.string.hub_section_huntbot)
        binding.secCowoncy.tvSectionTitle.setText(R.string.hub_section_cowoncy)
        binding.secGems.tvSectionTitle.setText(R.string.hub_section_gems)
        binding.secQuest.tvSectionTitle.setText(R.string.hub_section_quest)
        binding.secTeam.tvSectionTitle.setText(R.string.hub_section_team)
        binding.secBattles.tvSectionTitle.setText(R.string.hub_section_battles)
        binding.secZoo.tvSectionTitle.setText(R.string.hub_section_zoo)
        binding.secWeapons.tvSectionTitle.setText(R.string.hub_section_weapons)
        binding.secInventory.tvSectionTitle.setText(R.string.hub_section_inventory)

        binding.btnHubRefresh.setOnClickListener {
            lifecycleScope.launch { loadHub() }
        }
        binding.secZoo.root.setOnClickListener { toggleExpanded(KEY_ZOO) }
        binding.secWeapons.root.setOnClickListener { toggleExpanded(KEY_WEAPONS) }
        binding.secInventory.root.setOnClickListener { toggleExpanded(KEY_INVENTORY) }
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

    private fun toggleExpanded(key: String) {
        if (!expanded.add(key)) expanded.remove(key)
        renderBodies()
    }

    /** Ve lai noi dung cac muc — chi khi co du lieu moi hoac khi cham mo rong/thu gon. */
    private fun renderBodies() {
        val h = hub ?: return
        binding.secCowoncy.tvSectionBody.text = HubFormat.cowoncy(h.cowoncy)
        binding.secGems.tvSectionBody.text = HubFormat.gems(h.gems)
        binding.secTeam.tvSectionBody.text = HubFormat.team(h.team)
        binding.secBattles.tvSectionBody.text = HubFormat.battles(h.battles)
        binding.secZoo.tvSectionBody.text = HubFormat.zoo(h.zoo, KEY_ZOO in expanded)
        binding.secWeapons.tvSectionBody.text = HubFormat.weapons(h.weapons, KEY_WEAPONS in expanded)
        binding.secInventory.tvSectionBody.text = HubFormat.inventory(h.inventory, KEY_INVENTORY in expanded)
    }

    /** Phan phu thuoc thoi gian: dem nguoc (Daily, HuntBot, Quest) va chu "X phut truoc" cua moi muc. */
    private fun renderTimed() {
        val h = hub ?: return
        val elapsed = (SystemClock.elapsedRealtime() - fetchedAtMs) / 1000L

        binding.secDaily.tvSectionBody.text = HubFormat.daily(h.daily, elapsed)
        binding.secHuntbot.tvSectionBody.text = HubFormat.huntbot(h.huntbot, elapsed)
        binding.secQuest.tvSectionBody.text = HubFormat.quest(h.quest, elapsed)

        setAge(binding.secDaily, h.daily?.ageSeconds, elapsed)
        setAge(binding.secHuntbot, h.huntbot?.ageSeconds, elapsed)
        setAge(binding.secCowoncy, h.cowoncy?.ageSeconds, elapsed)
        setAge(binding.secGems, HubFormat.gemsAgeSeconds(h.gems), elapsed)
        setAge(binding.secQuest, h.quest?.ageSeconds, elapsed)
        setAge(binding.secTeam, h.team?.ageSeconds, elapsed)
        setAge(binding.secBattles, h.battles?.ageSeconds, elapsed)
        setAge(binding.secZoo, h.zoo?.ageSeconds, elapsed)
        setAge(binding.secWeapons, h.weapons?.ageSeconds, elapsed)
        setAge(binding.secInventory, h.inventory?.ageSeconds, elapsed)
    }

    private fun setAge(section: ItemHubSectionBinding, ageSeconds: Long?, elapsedSec: Long) {
        section.tvSectionAge.text = if (ageSeconds == null) "" else HubFormat.age(ageSeconds + elapsedSec)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        private const val KEY_ZOO = "zoo"
        private const val KEY_WEAPONS = "weapons"
        private const val KEY_INVENTORY = "inventory"
    }
}
