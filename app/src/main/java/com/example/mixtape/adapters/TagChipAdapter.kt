package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.google.android.material.chip.Chip

/**
 * A read-only adapter for displaying tags as non-interactive chips.
 * This is primarily used within the main MediaAdapter to show tags under each song/video.
 */
class TagChipAdapter(
    private val tags: List<String>
) : RecyclerView.Adapter<TagChipAdapter.ViewHolder>() {

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Inflates the standard tag chip layout
        val chipContainer = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_chip, parent, false)
        val chip = chipContainer.findViewById<Chip>(R.id.chip)
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.chip.text = tags[position]
        
        // Ensure chips in this context are not clickable or removable
        holder.chip.isClickable = false
        holder.chip.isCheckable = false
        holder.chip.isCloseIconVisible = false
    }

    override fun getItemCount() = tags.size
}
