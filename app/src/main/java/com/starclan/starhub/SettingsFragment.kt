package com.starclan.starhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.starclan.starhub.data.OwoTrackerApi
import androidx.fragment.app.Fragment
import com.starclan.starhub.data.OwoTrackerPrefs
import com.starclan.starhub.databinding.FragmentSettingsBinding
import com.starclan.starhub.engine.DiscordSender
import com.starclan.starhub.engine.NodeDump
import com.starclan.starhub.engine.OpenResult
import com.starclan.starhub.service.AutoAccessibilityService
import com.starclan.starhub.view.FarmOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tab "Cài đặt": quyền Accessibility (bắt buộc để chạy kịch bản/theo dõi OwO) +
 * kết nối tới owo-tracker (URL, token, vị trí ô nhập tin nhắn Discord).
 * Trước đây phần owo-tracker là 1 hộp thoại (dialog); giờ nằm thẳng trong tab
 * này với nút "Lưu" riêng.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val current = OwoTrackerPrefs.load(requireContext())
        binding.etOwoApiUrl.setText(current.apiUrl)
        binding.etOwoApiToken.setText(current.token)
        binding.cbOwoSyncEnabled.isChecked = current.enabled
        binding.etDiscordLink.setText(current.discordLink)
        updateTargetsStatusText(current.hasDiscordTargets)

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnSetupDiscordTargets.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(requireContext(), getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(requireContext(), getString(R.string.toast_setup_targets_hint), Toast.LENGTH_LONG).show()
            service.setupDiscordTargets { msgX, msgY, sendX, sendY ->
                OwoTrackerPrefs.saveDiscordTargets(requireContext(), msgX, msgY, sendX, sendY)
                activity?.runOnUiThread {
                    if (_binding != null) updateTargetsStatusText(true)
                    Toast.makeText(requireContext(), getString(R.string.toast_targets_saved), Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDumpScreen.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(requireContext(), getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val appContext = requireContext().applicationContext
            Toast.makeText(appContext, appContext.getString(R.string.toast_dump_waiting), Toast.LENGTH_LONG).show()
            CoroutineScope(Dispatchers.Default).launch {
                delay(8_000)
                val text = NodeDump.dump(service)
                Handler(Looper.getMainLooper()).post {
                    val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("starhub-dump", text))
                    Toast.makeText(appContext, appContext.getString(R.string.toast_dump_done), Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnBatterySettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.toast_battery_settings_failed), Toast.LENGTH_LONG).show()
            }
        }

        binding.btnToggleHub.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(requireContext(), getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (FarmOverlay.isShown()) FarmOverlay.hide() else FarmOverlay.show(service)
        }

        binding.btnTestOpenDiscord.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(requireContext(), getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val appContext = requireContext().applicationContext
            // Dung link dang go trong o (khong can bam Luu truoc khi thu)
            val link = binding.etDiscordLink.text.toString().trim()
            Toast.makeText(appContext, appContext.getString(R.string.toast_testing_open_discord), Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Default).launch {
                val result = DiscordSender(service).openOnly(link)
                val msgRes = when (result) {
                    OpenResult.OK_CHANNEL -> R.string.result_open_ok_channel
                    OpenResult.OK_APP_ONLY -> R.string.result_open_ok_app
                    OpenResult.OK_LINK_FAILED -> R.string.result_open_link_failed
                    OpenResult.NOT_INSTALLED -> R.string.result_open_not_installed
                    OpenResult.LAUNCH_ERROR -> R.string.result_open_launch_error
                    OpenResult.NOT_FOREGROUND -> R.string.result_open_not_foreground
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, appContext.getString(msgRes), Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.btnSaveOwoSettings.setOnClickListener {
            val url = binding.etOwoApiUrl.text.toString().trim()
            val token = binding.etOwoApiToken.text.toString().trim()
            val enabled = binding.cbOwoSyncEnabled.isChecked

            OwoTrackerPrefs.saveConnection(requireContext(), enabled, url, token)
            OwoTrackerPrefs.saveDiscordLink(requireContext(), binding.etDiscordLink.text.toString())

            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(requireContext(), getString(R.string.toast_owo_settings_saved_no_service), Toast.LENGTH_LONG).show()
            } else {
                service.applySyncSettings(enabled, url, token)
                Toast.makeText(requireContext(), getString(R.string.toast_owo_settings_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        loadGemTiers()
    }

    // ----------------- Tier gem (luu tren bot) -----------------

    private fun loadGemTiers() {
        val s = OwoTrackerPrefs.load(requireContext())
        if (s.apiUrl.isBlank() || s.token.isBlank()) {
            binding.tvGemTiersStatus.text = "Nhập địa chỉ API + token ở trên rồi bấm Lưu để chỉnh phần này."
            binding.llGemTiers.removeAllViews()
            return
        }
        binding.tvGemTiersStatus.text = "Đang tải…"
        viewLifecycleOwner.lifecycleScope.launch {
            val slots = OwoTrackerApi.fetchGemTiers(s.apiUrl, s.token)
            if (_binding == null) return@launch
            if (slots == null) {
                binding.tvGemTiersStatus.text = "Không tải được cài đặt tier gem (kiểm tra mạng / bot cần bản mới)."
                return@launch
            }
            binding.tvGemTiersStatus.text = ""
            renderGemTiers(slots, s.apiUrl, s.token)
        }
    }

    private fun dpPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderGemTiers(slots: List<OwoTrackerApi.GemTierSlot>, apiUrl: String, token: String) {
        val ctx = requireContext()
        val violet = ctx.getColor(R.color.brand_violet)
        val border = ctx.getColor(R.color.border_subtle)
        val container = binding.llGemTiers
        container.removeAllViews()
        for (slot in slots) {
            val title = TextView(ctx)
            title.text = "💎 Gem " + (when (slot.slot) {
                "1" -> "Hunting (săn)"
                "3" -> "Empowering (tăng lực)"
                "4" -> "Lucky (may mắn)"
                else -> "Special (sự kiện)"
            })
            title.textSize = 14f
            title.setTypeface(title.typeface, Typeface.BOLD)
            val tlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            tlp.topMargin = dpPx(16)
            container.addView(title, tlp)

            for (chunk in slot.tiers.chunked(4)) {
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                for ((tier, enabled) in chunk) {
                    val chip = TextView(ctx)
                    chip.text = tier
                    chip.textSize = 13f
                    chip.gravity = Gravity.CENTER
                    chip.setPadding(dpPx(6), dpPx(9), dpPx(6), dpPx(9))
                    fun paint(on: Boolean) {
                        val bg = GradientDrawable()
                        bg.cornerRadius = dpPx(16).toFloat()
                        bg.setColor(if (on) violet else android.graphics.Color.TRANSPARENT)
                        bg.setStroke(dpPx(1), if (on) violet else border)
                        chip.background = bg
                        chip.setTextColor(if (on) android.graphics.Color.WHITE else binding.tvGemTiersStatus.textColors.defaultColor)
                        chip.alpha = if (on) 1f else 0.6f
                    }
                    var state = enabled
                    paint(state)
                    chip.setOnClickListener {
                        val want = !state
                        chip.isEnabled = false
                        viewLifecycleOwner.lifecycleScope.launch {
                            val res = OwoTrackerApi.setGemTier(apiUrl, token, slot.slot, tier, want)
                            if (_binding == null) return@launch
                            chip.isEnabled = true
                            if (res == null) {
                                Toast.makeText(ctx, "Không lưu được lên bot — thử lại", Toast.LENGTH_SHORT).show()
                            } else {
                                state = want
                                paint(state)
                            }
                        }
                    }
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    lp.marginEnd = dpPx(6)
                    row.addView(chip, lp)
                }
                // hang cuoi it hon 4 chip: them o trong de chip khong gian rong qua muc
                repeat(4 - chunk.size) {
                    row.addView(android.view.View(ctx), LinearLayout.LayoutParams(0, 1, 1f).also { it.marginEnd = dpPx(6) })
                }
                val rlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                rlp.topMargin = dpPx(8)
                container.addView(row, rlp)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateTargetsStatusText(hasTargets: Boolean) {
        binding.tvDiscordTargetsStatus.text = getString(
            if (hasTargets) R.string.status_discord_targets_set else R.string.status_discord_targets_not_set
        )
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = getString(
            if (enabled) R.string.status_service_enabled else R.string.status_service_disabled
        )
        binding.tvServiceStatus.setTextColor(
            requireContext().getColor(if (enabled) R.color.hub_ok else R.color.hub_bad)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val context = requireContext()
        val expectedComponentName = "${context.packageName}/${AutoAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
