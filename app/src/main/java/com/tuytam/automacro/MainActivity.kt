package com.tuytam.automacro

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.tuytam.automacro.data.SampleScripts
import com.tuytam.automacro.databinding.ActivityMainBinding
import com.tuytam.automacro.service.AutoAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvScripts.layoutManager = LinearLayoutManager(this)
        // TODO: gan Adapter khi co man hinh tao kich ban

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnRunSample.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
            } else {
                service.runScript(SampleScripts.sendMessageExample)
                Toast.makeText(this, "Đang chạy kịch bản mẫu...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabAddScript.setOnClickListener {
            // TODO: mo man hinh tao kich ban moi (se them sau)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = getString(
            if (enabled) R.string.status_service_enabled else R.string.status_service_disabled
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${AutoAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
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
