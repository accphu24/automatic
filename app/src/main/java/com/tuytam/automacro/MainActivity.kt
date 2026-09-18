package com.tuytam.automacro

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tuytam.automacro.data.AppDatabase
import com.tuytam.automacro.data.SampleScripts
import com.tuytam.automacro.data.ScriptRepository
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

        binding.fabAddScript.setOnClickListener {
            startActivity(Intent(this, ScriptEditorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun runScript(name: String, steps: List<com.tuytam.automacro.data.ScriptStep>) {
        val service = AutoAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
        } else {
            service.runScript(steps)
            Toast.makeText(this, "Đang chạy \"$name\"...", Toast.LENGTH_SHORT).show()
        }
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
