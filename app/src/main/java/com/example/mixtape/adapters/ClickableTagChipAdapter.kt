package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.google.android.material.chip.Chip

class ClickableTagChipAdapter(
    private val onTagClick: (String) -> Unit
) : RecyclerView.Adapter<ClickableTagChipAdapter.ViewHolder>() {

    private val tags = mutableListOf<String>()

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chipContainer = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter_tag_chip, parent, false)
        val chip = chipContainer.findViewById<Chip>(R.id.chip)
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.chip.text = tag
        
        // Make it clickable but not checkable (no selection state)
        holder.chip.isCheckable = false
        holder.chip.isClickable = true
        holder.chip.isFocusable = true

        holder.chip.setOnClickListener {
            onTagClick(tag)
        }
    }

    override fun getItemCount() = tags.size

    fun updateTags(newTags: List<String>) {
        tags.clear()
        tags.addAll(newTags)
        notifyDataSetChanged()
    }
}
