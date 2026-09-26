package com.tuytam.automacro

import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.tuytam.automacro.data.OwoTrackerPrefs
import com.tuytam.automacro.databinding.FragmentSettingsBinding
import com.tuytam.automacro.service.AutoAccessibilityService

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

        binding.btnSaveOwoSettings.setOnClickListener {
            val url = binding.etOwoApiUrl.text.toString().trim()
            val token = binding.etOwoApiToken.text.toString().trim()
            val enabled = binding.cbOwoSyncEnabled.isChecked

            OwoTrackerPrefs.saveConnection(requireContext(), enabled, url, token)

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
