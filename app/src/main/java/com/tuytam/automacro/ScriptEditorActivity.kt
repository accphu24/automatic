package com.tuytam.automacro

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tuytam.automacro.data.AppDatabase
import com.tuytam.automacro.data.ScriptJson
import com.tuytam.automacro.data.ScriptRepository
import com.tuytam.automacro.data.ScriptStep
import com.tuytam.automacro.data.StepType
import com.tuytam.automacro.data.summarizeStep
import com.tuytam.automacro.databinding.ActivityScriptEditorBinding
import com.tuytam.automacro.databinding.ItemStepRowBinding
import kotlinx.coroutines.launch

/**
 * Man hinh tao kich ban moi: dat ten, them tung buoc bang hop thoai
 * (khong can go code), bam vao 1 buoc da them de sua lai (ke ca doi
 * "neu loi thi nhay toi buoc nao" sang mot buoc them SAU do).
 *
 * Neu duoc mo tu ban GHI (AutoAccessibilityService.startRecording), man hinh
 * se tu nap san cac buoc da ghi duoc qua extra EXTRA_RECORDED_STEPS_JSON.
 *
 * Luu y pham vi hien tai: chi tao kich ban MOI, chua ho tro mo lai
 * 1 kich ban DA LUU tu truoc de sua (se lam o buoc sau neu can).
 */
class ScriptEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORDED_STEPS_JSON = "recorded_steps_json"
    }

    private lateinit var binding: ActivityScriptEditorBinding
    private val repository by lazy { ScriptRepository(AppDatabase.getInstance(this).scriptDao()) }
    private val steps = mutableListOf<ScriptStep>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.title_script_editor)

        val recordedJson = intent.getStringExtra(EXTRA_RECORDED_STEPS_JSON)
        if (!recordedJson.isNullOrBlank()) {
            val loaded = ScriptJson.jsonToSteps(recordedJson)
            steps.addAll(loaded)
            refreshStepList()
            if (loaded.isNotEmpty()) {
                Toast.makeText(this, "Đã nạp ${loaded.size} bước từ bản ghi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_recording_empty), Toast.LENGTH_LONG).show()
            }
        }

        binding.btnAddStep.setOnClickListener { showAddStepDialog() }

        binding.btnSaveScript.setOnClickListener {
            val name = binding.etScriptName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_need_script_name), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (steps.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_need_at_least_one_step), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                repository.saveNewScript(name, steps.toList())
                finish()
            }
        }
    }

    private fun showAddStepDialog() {
        val types = listOf(
            StepType.OPEN_APP to getString(R.string.step_type_open_app),
            StepType.WAIT to getString(R.string.step_type_wait),
            StepType.TAP to getString(R.string.step_type_tap),
            StepType.SWIPE to getString(R.string.step_type_swipe),
            StepType.TYPE_TEXT to getString(R.string.step_type_type_text),
            StepType.CHECK_TEXT to getString(R.string.step_type_check_text),
            StepType.CHECK_EXISTS to getString(R.string.step_type_check_exists),
            StepType.NOTIFY to getString(R.string.step_type_notify)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.title_choose_step_type)
            .setItems(types.map { it.second }.toTypedArray()) { _, which ->
                showStepFormDialog(types[which].first, existingStep = null, editIndex = null)
            }
            .show()
    }

    private fun showStepFormDialog(type: StepType, existingStep: ScriptStep?, editIndex: Int?) {
        val inflater = LayoutInflater.from(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val typeLayoutRes = when (type) {
            StepType.OPEN_APP -> R.layout.dialog_step_open_app
            StepType.WAIT -> R.layout.dialog_step_wait
            StepType.TAP -> R.layout.dialog_step_tap
            StepType.SWIPE -> R.layout.dialog_step_swipe
            StepType.TYPE_TEXT -> R.layout.dialog_step_type_text
            StepType.CHECK_TEXT -> R.layout.dialog_step_check_text
            StepType.CHECK_EXISTS -> R.layout.dialog_step_check_exists
            StepType.NOTIFY -> R.layout.dialog_step_notify
        }
        val typeView = inflater.inflate(typeLayoutRes, outer, false)
        outer.addView(typeView)

        val onFailView = inflater.inflate(R.layout.dialog_on_fail_section, outer, false)
        outer.addView(onFailView)

        val byOptions = listOf("text" to getString(R.string.by_text), "viewId" to getString(R.string.by_view_id))
        val spinnerByView: Spinner? = typeView.findViewById(R.id.spinnerBy)
        spinnerByView?.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, byOptions.map { it.second })

        // Neu dang SUA 1 buoc co san (hoac buoc do den tu ban ghi): dien lai gia tri cu vao form
        existingStep?.let { step ->
            when (type) {
                StepType.OPEN_APP ->
                    typeView.findViewById<EditText>(R.id.etPackageName).setText(step.params["packageName"])
                StepType.WAIT ->
                    typeView.findViewById<EditText>(R.id.etSeconds).setText(step.params["seconds"])
                StepType.TAP -> {
                    typeView.findViewById<EditText>(R.id.etValue).setText(step.params["value"])
                    typeView.findViewById<EditText>(R.id.etFallbackX).setText(step.params["fallbackX"])
                    typeView.findViewById<EditText>(R.id.etFallbackY).setText(step.params["fallbackY"])
                    val idx = byOptions.indexOfFirst { it.first == step.params["by"] }
                    if (idx >= 0) spinnerByView?.setSelection(idx)
                }
                StepType.SWIPE -> {
                    typeView.findViewById<EditText>(R.id.etFromX).setText(step.params["fromX"])
                    typeView.findViewById<EditText>(R.id.etFromY).setText(step.params["fromY"])
                    typeView.findViewById<EditText>(R.id.etToX).setText(step.params["toX"])
                    typeView.findViewById<EditText>(R.id.etToY).setText(step.params["toY"])
                }
                StepType.TYPE_TEXT ->
                    typeView.findViewById<EditText>(R.id.etTypeText).setText(step.params["text"])
                StepType.CHECK_TEXT -> {
                    typeView.findViewById<EditText>(R.id.etValue).setText(step.params["value"])
                    typeView.findViewById<EditText>(R.id.etExpected).setText(step.params["expected"])
                    val idx = byOptions.indexOfFirst { it.first == step.params["by"] }
                    if (idx >= 0) spinnerByView?.setSelection(idx)
                }
                StepType.CHECK_EXISTS -> {
                    typeView.findViewById<EditText>(R.id.etValue).setText(step.params["value"])
                    val idx = byOptions.indexOfFirst { it.first == step.params["by"] }
                    if (idx >= 0) spinnerByView?.setSelection(idx)
                }
                StepType.NOTIFY -> {
                    typeView.findViewById<CheckBox>(R.id.cbVibrate).isChecked = step.params["vibrate"] != "false"
                    typeView.findViewById<CheckBox>(R.id.cbSound).isChecked = step.params["sound"] != "false"
                    typeView.findViewById<EditText>(R.id.etMessage).setText(step.params["message"])
                }
            }
        }

        // Danh sach "neu loi thi..." la TOAN BO cac buoc hien co, tru chinh buoc dang sua
        val otherSteps = steps.filterIndexed { idx, _ -> idx != editIndex }
        val onFailChoices = mutableListOf(getString(R.string.on_fail_stop))
        onFailChoices.addAll(otherSteps.map { "${getString(R.string.on_fail_prefix)} ${summarizeStep(it)}" })
        val spinnerOnFail = onFailView.findViewById<Spinner>(R.id.spinnerOnFail)
        spinnerOnFail.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, onFailChoices)
        val currentFailSelection = otherSteps.indexOfFirst { it.id == existingStep?.onFail }
        if (currentFailSelection >= 0) spinnerOnFail.setSelection(currentFailSelection + 1)

        AlertDialog.Builder(this)
            .setTitle(if (editIndex != null) R.string.title_edit_step else R.string.title_step_details)
            .setView(outer)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val onFailPos = spinnerOnFail.selectedItemPosition
                val onFailStepId = if (onFailPos == 0) null else otherSteps[onFailPos - 1].id

                val params = mutableMapOf<String, String>()
                when (type) {
                    StepType.OPEN_APP -> {
                        params["packageName"] =
                            typeView.findViewById<EditText>(R.id.etPackageName).text.toString().trim()
                    }
                    StepType.WAIT -> {
                        val secs = typeView.findViewById<EditText>(R.id.etSeconds).text.toString().trim()
                        params["seconds"] = secs.ifBlank { "1" }
                    }
                    StepType.TAP -> {
                        val bySpinner = typeView.findViewById<Spinner>(R.id.spinnerBy)
                        params["by"] = byOptions[bySpinner.selectedItemPosition].first
                        params["value"] = typeView.findViewById<EditText>(R.id.etValue).text.toString().trim()
                        val fx = typeView.findViewById<EditText>(R.id.etFallbackX).text.toString().trim()
                        val fy = typeView.findViewById<EditText>(R.id.etFallbackY).text.toString().trim()
                        if (fx.isNotBlank()) params["fallbackX"] = fx
                        if (fy.isNotBlank()) params["fallbackY"] = fy
                    }
                    StepType.SWIPE -> {
                        params["fromX"] = typeView.findViewById<EditText>(R.id.etFromX).text.toString().trim()
                        params["fromY"] = typeView.findViewById<EditText>(R.id.etFromY).text.toString().trim()
                        params["toX"] = typeView.findViewById<EditText>(R.id.etToX).text.toString().trim()
                        params["toY"] = typeView.findViewById<EditText>(R.id.etToY).text.toString().trim()
                    }
                    StepType.TYPE_TEXT -> {
                        params["text"] = typeView.findViewById<EditText>(R.id.etTypeText).text.toString()
                    }
                    StepType.CHECK_TEXT -> {
                        val bySpinner = typeView.findViewById<Spinner>(R.id.spinnerBy)
                        params["by"] = byOptions[bySpinner.selectedItemPosition].first
                        params["value"] = typeView.findViewById<EditText>(R.id.etValue).text.toString().trim()
                        params["expected"] = typeView.findViewById<EditText>(R.id.etExpected).text.toString().trim()
                    }
                    StepType.CHECK_EXISTS -> {
                        val bySpinner = typeView.findViewById<Spinner>(R.id.spinnerBy)
                        params["by"] = byOptions[bySpinner.selectedItemPosition].first
                        params["value"] = typeView.findViewById<EditText>(R.id.etValue).text.toString().trim()
                    }
                    StepType.NOTIFY -> {
                        params["vibrate"] = typeView.findViewById<CheckBox>(R.id.cbVibrate).isChecked.toString()
                        params["sound"] = typeView.findViewById<CheckBox>(R.id.cbSound).isChecked.toString()
                        params["message"] = typeView.findViewById<EditText>(R.id.etMessage).text.toString().trim()
                    }
                }

                val stepId = existingStep?.id ?: "s${System.currentTimeMillis()}"
                val newStep = ScriptStep(id = stepId, type = type, params = params, onFail = onFailStepId)

                if (editIndex != null) {
                    steps[editIndex] = newStep
                } else {
                    steps.add(newStep)
                }
                refreshStepList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun refreshStepList() {
        binding.containerSteps.removeAllViews()
        steps.forEachIndexed { index, step ->
            val row = ItemStepRowBinding.inflate(layoutInflater, binding.containerSteps, false)
            val failNote = step.onFail?.let { failId ->
                steps.find { it.id == failId }?.let { "  → ${getString(R.string.on_fail_note_prefix)}: ${summarizeStep(it)}" }
            } ?: ""
            row.tvStepSummary.text = "${index + 1}. ${summarizeStep(step)}$failNote"
            row.root.setOnClickListener {
                showStepFormDialog(step.type, existingStep = step, editIndex = index)
            }
            row.btnDeleteStep.setOnClickListener {
                steps.removeAt(index)
                refreshStepList()
            }
            binding.containerSteps.addView(row.root)
        }
    }
}
