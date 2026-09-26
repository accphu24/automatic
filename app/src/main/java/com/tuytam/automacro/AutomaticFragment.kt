package com.tuytam.automacro

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tuytam.automacro.data.AppDatabase
import com.tuytam.automacro.data.SampleScripts
import com.tuytam.automacro.data.ScriptRepository
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.databinding.FragmentAutomaticBinding
import com.tuytam.automacro.service.AutoAccessibilityService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Tab "Tự động": danh sách kịch bản AutoMacro đã lưu — chạy, ghi kịch bản mới
 * (record), thêm/sửa/xoá. Day chinh la chuc nang AutoMacro goc.
 */
class AutomaticFragment : Fragment() {

    private var _binding: FragmentAutomaticBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy { ScriptRepository(AppDatabase.getInstance(requireContext()).scriptDao()) }
    private lateinit var adapter: ScriptListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAutomaticBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ScriptListAdapter(
            onRun = { item -> runScript(item.name, item.steps) },
            onDelete = { item ->
                viewLifecycleOwner.lifecycleScope.launch { repository.deleteScript(item) }
            }
        )
        binding.rvScripts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvScripts.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            repository.observeScripts().collect { list ->
                adapter.submitList(list)
                binding.tvEmptyScripts.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        binding.btnRunSample.setOnClickListener {
            runScript("kịch bản mẫu", SampleScripts.sendMessageExample)
        }

        binding.btnRecordScript.setOnClickListener {
            val service = AutoAccessibilityService.instance
            if (service == null) {
                Toast.makeText(requireContext(), getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
            } else {
                service.startRecording()
                Toast.makeText(requireContext(), getString(R.string.toast_recording_started), Toast.LENGTH_LONG).show()
            }
        }

        binding.fabAddScript.setOnClickListener {
            startActivity(Intent(requireContext(), ScriptEditorActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun runScript(name: String, steps: List<ScriptStep>) {
        val service = AutoAccessibilityService.instance
        if (service == null) {
            Toast.makeText(requireContext(), getString(R.string.toast_service_not_enabled), Toast.LENGTH_SHORT).show()
        } else {
            service.runScript(steps)
            Toast.makeText(requireContext(), "Đang chạy \"$name\"...", Toast.LENGTH_SHORT).show()
        }
    }
}
