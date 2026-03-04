package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.google.android.material.chip.Chip

/**
 * Adapter for tags that can be removed via a close icon.
 * Used in the "Edit Playlist" screen to remove tags currently assigned to a media item.
 */
class RemovableTagChipAdapter(
    private val tags: MutableList<String>,
    private val onTagRemove: (String) -> Unit
) : RecyclerView.Adapter<RemovableTagChipAdapter.ViewHolder>() {

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chipContainer = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_chip, parent, false)
        val chip = if (chipContainer is Chip) chipContainer else chipContainer.findViewById(R.id.chip)
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.chip.text = tag
        
        // Configuration: Static display but with an active close icon
        holder.chip.isCloseIconVisible = true
        holder.chip.setCloseIconTintResource(R.color.white)
        
        // Consistent sizing using screen density
        val density = holder.chip.context.resources.displayMetrics.density
        holder.chip.closeIconSize = 24f * density

        holder.chip.setOnCloseIconClickListener {
            onTagRemove(tag)
        }
    }

    override fun getItemCount() = tags.size
}
