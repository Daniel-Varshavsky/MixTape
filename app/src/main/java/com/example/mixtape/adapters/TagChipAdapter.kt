package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.google.android.material.chip.Chip

class TagChipAdapter(
    private val tags: List<String>
) : RecyclerView.Adapter<TagChipAdapter.ViewHolder>() {

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag_chip, parent, false) as Chip
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.chip.text = tags[position]
    }

    override fun getItemCount() = tags.size
}
