package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.example.mixtape.model.MediaItem
import com.google.android.material.button.MaterialButton

class EditableMediaAdapter(
    private var mediaItems: MutableList<MediaItem>,
    private var availableTags: List<String>,
    private val onItemRemoved: (MediaItem) -> Unit,
    private val onItemTagsChanged: (MediaItem, List<String>) -> Unit
) : RecyclerView.Adapter<EditableMediaAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: AppCompatImageView = view.findViewById(R.id.mediaIcon)
        val title: TextView = view.findViewById(R.id.mediaTitle)
        val artist: TextView = view.findViewById(R.id.mediaArtist)
        val btnRemove: MaterialButton = view.findViewById(R.id.btnRemove)
        val currentTagsRecycler: RecyclerView = view.findViewById(R.id.currentTagsRecycler)
        val btnAddTags: MaterialButton = view.findViewById(R.id.btnAddTags)
        val addTagsSection: View = view.findViewById(R.id.addTagsSection)
        val availableTagsRecycler: RecyclerView = view.findViewById(R.id.availableTagsRecycler)
        val btnCancelAddTags: MaterialButton = view.findViewById(R.id.btnCancelAddTags)
        val btnApplyTags: MaterialButton = view.findViewById(R.id.btnApplyTags)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_editable_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mediaItems[position]

        // Set icon
        when (item) {
            is MediaItem.SongItem -> holder.icon.setImageResource(R.drawable.sharp_music_note_2_24)
            is MediaItem.VideoItem -> holder.icon.setImageResource(R.drawable.outline_videocam_24)
        }

        holder.title.text = item.title
        holder.artist.text = item.artist

        // -----------------------------
        // Current Tags Recycler
        // -----------------------------
        holder.currentTagsRecycler.layoutManager =
            LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)

        val currentTagsAdapter = RemovableTagChipAdapter(item.tags) { tagToRemove ->
            item.tags.remove(tagToRemove)
            onItemTagsChanged(item, item.tags)
            notifyItemChanged(position)
        }

        holder.currentTagsRecycler.adapter = currentTagsAdapter

        // -----------------------------
        // Available Tags Recycler
        // -----------------------------
        holder.availableTagsRecycler.layoutManager =
            GridLayoutManager(holder.itemView.context, 3)

        val availableTagsAdapter = FilterTagChipAdapter { tag, isSelected ->
            // Selection handled internally.
            // We only read selected tags when Apply is pressed.
        }

        holder.availableTagsRecycler.adapter = availableTagsAdapter
        availableTagsAdapter.updateTags(availableTags)

        // -----------------------------
        // Remove Item
        // -----------------------------
        holder.btnRemove.setOnClickListener {
            onItemRemoved(item)
        }

        // -----------------------------
        // Toggle Add Tags Section
        // -----------------------------
        holder.btnAddTags.setOnClickListener {
            holder.btnAddTags.visibility = View.GONE
            holder.addTagsSection.visibility = View.VISIBLE
        }

        holder.btnCancelAddTags.setOnClickListener {
            holder.btnAddTags.visibility = View.VISIBLE
            holder.addTagsSection.visibility = View.GONE
            availableTagsAdapter.clearSelection()
        }

        holder.btnApplyTags.setOnClickListener {
            val selectedTags = availableTagsAdapter.getSelectedTags()

            selectedTags.forEach { tag ->
                if (!item.tags.contains(tag)) {
                    item.tags.add(tag)
                }
            }

            onItemTagsChanged(item, item.tags)

            holder.btnAddTags.visibility = View.VISIBLE
            holder.addTagsSection.visibility = View.GONE
            availableTagsAdapter.clearSelection()

            notifyItemChanged(position)
        }

        // Default state
        holder.btnAddTags.visibility = View.VISIBLE
        holder.addTagsSection.visibility = View.GONE
    }

    override fun getItemCount() = mediaItems.size

    fun removeItem(item: MediaItem) {
        val position = mediaItems.indexOf(item)
        if (position != -1) {
            mediaItems.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun updateAvailableTags(newTags: List<String>) {
        availableTags = newTags
        notifyDataSetChanged()
    }
}