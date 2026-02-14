package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
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

        // Track original state for cancel functionality and staging decisions
        var originalTags: List<String> = emptyList()
        var currentTagsAdapter: RemovableTagChipAdapter? = null
        var availableTagsAdapter: ClickableTagChipAdapter? = null
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

        // FIXED: Initialize original tags immediately when binding, not just when "Add Tags" is pressed
        holder.originalTags = item.tags.toList()

        setupCurrentTagsRecycler(holder, item, position)
        setupAvailableTagsRecycler(holder, item)

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
            // Note: originalTags is already set above during binding

            holder.btnAddTags.visibility = View.GONE
            holder.addTagsSection.visibility = View.VISIBLE

            // Refresh available tags to exclude current tags
            updateAvailableTagsForItem(holder, item)
        }

        holder.btnCancelAddTags.setOnClickListener {
            // Revert to original tags
            item.tags.clear()
            item.tags.addAll(holder.originalTags)

            holder.btnAddTags.visibility = View.VISIBLE
            holder.addTagsSection.visibility = View.GONE

            // Refresh displays
            holder.currentTagsAdapter?.notifyDataSetChanged()
            updateAvailableTagsForItem(holder, item)
        }

        holder.btnApplyTags.setOnClickListener {
            // Stage the changes by calling the callback
            onItemTagsChanged(item, item.tags.toList())

            holder.btnAddTags.visibility = View.VISIBLE
            holder.addTagsSection.visibility = View.GONE
        }

        // Default state
        holder.btnAddTags.visibility = View.VISIBLE
        holder.addTagsSection.visibility = View.GONE
    }

    private fun setupCurrentTagsRecycler(holder: ViewHolder, item: MediaItem, position: Int) {
        holder.currentTagsRecycler.layoutManager =
            LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)

        holder.currentTagsAdapter = RemovableTagChipAdapter(item.tags) { tagToRemove ->
            // Check if this tag was originally on the item
            val wasOriginalTag = holder.originalTags.contains(tagToRemove)

            // Remove tag from current tags (immediate UI feedback)
            item.tags.remove(tagToRemove)

            if (wasOriginalTag) {
                // This was an original tag being removed - stage the change immediately
                onItemTagsChanged(item, item.tags.toList())
            }
            // If it wasn't an original tag, it's just a UI revert (no staging needed)

            // Refresh current tags display
            holder.currentTagsAdapter?.notifyDataSetChanged()

            // Refresh available tags to include the removed tag
            updateAvailableTagsForItem(holder, item)
        }

        holder.currentTagsRecycler.adapter = holder.currentTagsAdapter
    }

    private fun setupAvailableTagsRecycler(holder: ViewHolder, item: MediaItem) {
        holder.availableTagsRecycler.layoutManager =
            LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)

        holder.availableTagsAdapter = ClickableTagChipAdapter { selectedTag ->
            // Add tag to current tags (immediate UI feedback)
            if (!item.tags.contains(selectedTag)) {
                item.tags.add(selectedTag)

                // Refresh current tags display
                holder.currentTagsAdapter?.notifyDataSetChanged()

                // Refresh available tags to exclude the added tag
                updateAvailableTagsForItem(holder, item)
            }
        }

        holder.availableTagsRecycler.adapter = holder.availableTagsAdapter
        updateAvailableTagsForItem(holder, item)
    }

    private fun updateAvailableTagsForItem(holder: ViewHolder, item: MediaItem) {
        // Show only tags that aren't currently assigned to this item
        val availableForThisItem = availableTags.filter { !item.tags.contains(it) }
        holder.availableTagsAdapter?.updateTags(availableForThisItem)
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