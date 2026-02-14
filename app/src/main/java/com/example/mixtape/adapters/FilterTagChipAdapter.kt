package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.google.android.material.chip.Chip

class FilterTagChipAdapter(
    private val onTagSelectionChanged: (String, Boolean) -> Unit
) : RecyclerView.Adapter<FilterTagChipAdapter.ViewHolder>() {

    private val tags = mutableListOf<String>()
    private val selectedTags = mutableSetOf<String>()

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chipContainer = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter_tag_chip, parent, false)

        val chip = chipContainer.findViewById<Chip>(R.id.chip)
            ?: chipContainer as Chip

        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]

        holder.chip.setOnCheckedChangeListener(null) // IMPORTANT: Clear listener before setting state
        holder.chip.text = tag

        val isSelected = selectedTags.contains(tag)
        holder.chip.isChecked = isSelected
        updateChipAppearance(holder.chip, isSelected)

        holder.chip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedTags.add(tag)
            else selectedTags.remove(tag)

            updateChipAppearance(holder.chip, isChecked)
            onTagSelectionChanged(tag, isChecked)
        }
    }

    private fun updateChipAppearance(chip: Chip, isSelected: Boolean) {
        val color = if (isSelected)
            chip.context.getColorStateList(R.color.red_15)
        else
            chip.context.getColorStateList(R.color.black)

        chip.chipBackgroundColor = color
    }

    override fun getItemCount() = tags.size

    fun updateTags(newTags: List<String>) {
        tags.clear()
        tags.addAll(newTags)

        // Remove selections that no longer exist
        selectedTags.retainAll(newTags.toSet())

        notifyDataSetChanged()
    }

    fun getSelectedTags(): Set<String> = selectedTags.toSet()

    fun clearSelection() {
        selectedTags.clear()
        notifyDataSetChanged()
    }
}