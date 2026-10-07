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
import android.widget.Toast
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
