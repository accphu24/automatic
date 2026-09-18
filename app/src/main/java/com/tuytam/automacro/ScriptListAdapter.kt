package com.tuytam.automacro

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tuytam.automacro.data.ScriptWithSteps
import com.tuytam.automacro.databinding.ItemScriptBinding

/**
 * Hien danh sach kich ban da luu. Moi dong co nut Chay va nut Xoa.
 */
class ScriptListAdapter(
    private val onRun: (ScriptWithSteps) -> Unit,
    private val onDelete: (ScriptWithSteps) -> Unit
) : RecyclerView.Adapter<ScriptListAdapter.ScriptViewHolder>() {

    private var items: List<ScriptWithSteps> = emptyList()

    fun submitList(newItems: List<ScriptWithSteps>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val binding = ItemScriptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ScriptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ScriptViewHolder(private val binding: ItemScriptBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ScriptWithSteps) {
            binding.tvScriptName.text = item.name
            binding.tvScriptStepCount.text = "${item.steps.size} bước"
            binding.btnRunScript.setOnClickListener { onRun(item) }
            binding.btnDeleteScript.setOnClickListener { onDelete(item) }
        }
    }
}
