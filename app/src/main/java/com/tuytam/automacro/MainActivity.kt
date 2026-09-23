package com.tuytam.automacro

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tuytam.automacro.data.AppDatabase
import com.tuytam.automacro.data.OwoTrackerPrefs
import com.tuytam.automacro.data.SampleScripts
import com.tuytam.automacro.data.ScriptRepository
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.databinding.ActivityMainBinding
import com.tuytam.automacro.service.AutoAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { ScriptRepository(AppDatabase.getInstance(this).scriptDao()) }
    private lateinit var adapter: ScriptListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ScriptListAdapter(
            onRun = { item -> runScript(item.name, item.steps) },
            onDelete = { item ->
                lifecycleScope.launch { repository.deleteScript(item) }
            }
        )
        binding.rvScripts.layoutManager = LinearLayoutManager(this)
        binding.rvScripts.adapter = adapter

        lifecycleScope.launch {
            repository.observeScripts().collect { list ->
                adapter.submitList(list)
            }
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnRunSample.setOnClickListener {
            runScript("kịch bản mẫu", SampleScripts.sendMessageExample)
        }

        binding.btnRecordScript.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
            } else {
                service.startRecording()
                Toast.makeText(this, getString(R.string.toast_recording_started), Toast.LENGTH_LONG).show()
            }
        }

        binding.btnOwoTrackerSettings.setOnClickListener { showOwoTrackerSettingsDialog() }

        binding.fabAddScript.setOnClickListener {
            startActivity(Intent(this, ScriptEditorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun runScript(name: String, steps: List<ScriptStep>) {
        val service = AutoAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
        } else {
            service.runScript(steps)
            Toast.makeText(this, "Đang chạy \"$name\"...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOwoTrackerSettingsDialog() {
        val current = OwoTrackerPrefs.load(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_owo_tracker_settings, null)
        val etUrl = view.findViewById<EditText>(R.id.etOwoApiUrl)
        val etToken = view.findViewById<EditText>(R.id.etOwoApiToken)
        val cbEnabled = view.findViewById<CheckBox>(R.id.cbOwoSyncEnabled)

        etUrl.setText(current.apiUrl)
        etToken.setText(current.token)
        cbEnabled.isChecked = current.enabled

        AlertDialog.Builder(this)
            .setTitle(R.string.title_owo_tracker_settings)
            .setView(view)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val url = etUrl.text.toString().trim()
                val token = etToken.text.toString().trim()
                val enabled = cbEnabled.isChecked

                OwoTrackerPrefs.save(this, enabled, url, token)

                val service = AutoAccessibilityService.instance
                if (service == null) {
                    Toast.makeText(this, getString(R.string.toast_owo_settings_saved_no_service), Toast.LENGTH_LONG).show()
                } else {
                    service.applySyncSettings(enabled, url, token)
                    Toast.makeText(this, getString(R.string.toast_owo_settings_saved), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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
