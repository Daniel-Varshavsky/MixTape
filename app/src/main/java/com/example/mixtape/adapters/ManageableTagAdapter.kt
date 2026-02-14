package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.google.android.material.chip.Chip

class ManageableTagAdapter(
    private val onDeleteTag: (String) -> Unit
) : RecyclerView.Adapter<ManageableTagAdapter.ViewHolder>() {

    private val tags = mutableListOf<String>()

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chipContainer = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter_tag_chip, parent, false)

        val chip = if (chipContainer is Chip) chipContainer else chipContainer.findViewById(R.id.chip)
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.chip.text = tag

        // Make it non-clickable (not checkable)
        holder.chip.isCheckable = false
        holder.chip.isClickable = false
        holder.chip.isFocusable = false

        // Add close icon (delete functionality)
        holder.chip.isCloseIconVisible = true

        // Set close icon to white trash can
        holder.chip.setCloseIconResource(R.drawable.outline_delete_24)
        holder.chip.setCloseIconTintResource(R.color.white)
        holder.chip.closeIconSize = 18f * holder.chip.context.resources.displayMetrics.density

        holder.chip.setOnCloseIconClickListener {
            onDeleteTag(tag)
        }
    }

    override fun getItemCount() = tags.size

    fun updateTags(newTags: List<String>) {
        tags.clear()
        tags.addAll(newTags)
        notifyDataSetChanged()
    }

    fun removeTag(tag: String) {
        val position = tags.indexOf(tag)
        if (position != -1) {
            tags.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun addTag(tag: String) {
        if (!tags.contains(tag)) {
            tags.add(tag)
            notifyItemInserted(tags.size - 1)
        }
    }
}