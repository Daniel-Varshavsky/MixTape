package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.example.mixtape.model.MediaItem
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.button.MaterialButton

/**
 * A complex adapter for the "Edit Playlist" screen. 
 * 
 * Key Features:
 * 1. Supports staging: Changes to tags are staged locally until 'Apply' is clicked.
 * 2. Nested RecyclerViews: Uses FlexboxLayoutManagers to display tags as wrapping chips.
 * 3. Bidirectional Tag Management: Adding a tag removes it from 'Available' and adds to 'Current'.
 */
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
        val album: TextView = view.findViewById(R.id.mediaAlbum)
        val btnRemove: MaterialButton = view.findViewById(R.id.btnRemove)
        val currentTagsRecycler: RecyclerView = view.findViewById(R.id.currentTagsRecycler)
        val btnAddTags: MaterialButton = view.findViewById(R.id.btnAddTags)
        val addTagsSection: View = view.findViewById(R.id.addTagsSection)
        val availableTagsRecycler: RecyclerView = view.findViewById(R.id.availableTagsRecycler)
        val btnCancelAddTags: MaterialButton = view.findViewById(R.id.btnCancelAddTags)
        val btnApplyTags: MaterialButton = view.findViewById(R.id.btnApplyTags)

        /** Track original state to allow 'Cancel' to revert tag changes for this specific item. */
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

        // UI Setup
        when (item) {
            is MediaItem.SongItem -> holder.icon.setImageResource(R.drawable.sharp_music_note_2_24)
            is MediaItem.VideoItem -> holder.icon.setImageResource(R.drawable.outline_videocam_24)
        }

        holder.title.text = item.title
        holder.title.isSelected = true // Required for marquee effect
        holder.artist.text = item.artist
        holder.album.text = item.album

        // Snapshot original state for the 'Cancel' functionality
        holder.originalTags = item.tags.toList()

        setupCurrentTagsRecycler(holder, item)
        setupAvailableTagsRecycler(holder, item)

        holder.btnRemove.setOnClickListener {
            onItemRemoved(item)
        }

        // --- Add Tags UI Flow ---

        holder.btnAddTags.setOnClickListener {
            holder.btnAddTags.visibility = View.GONE
            holder.addTagsSection.visibility = View.VISIBLE
            updateAvailableTagsForItem(holder, item)
        }

        holder.btnCancelAddTags.setOnClickListener {
            // Revert item state to the last saved/original state
            item.tags.clear()
            item.tags.addAll(holder.originalTags)

            holder.btnAddTags.visibility = View.VISIBLE
            holder.addTagsSection.visibility = View.GONE

            holder.currentTagsAdapter?.notifyDataSetChanged()
            updateAvailableTagsForItem(holder, item)
        }

        holder.btnApplyTags.setOnClickListener {
            // Stage changes to the parent fragment/activity
            onItemTagsChanged(item, item.tags.toList())

            // Update baseline so subsequent cancels revert to this new state
            holder.originalTags = item.tags.toList()

            holder.btnAddTags.visibility = View.VISIBLE
            holder.addTagsSection.visibility = View.GONE
        }

        // Reset visibility state for recycled views
        holder.btnAddTags.visibility = View.VISIBLE
        holder.addTagsSection.visibility = View.GONE
    }

    private fun setupCurrentTagsRecycler(holder: ViewHolder, item: MediaItem) {
        holder.currentTagsRecycler.layoutManager =
            FlexboxLayoutManager(holder.itemView.context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
                justifyContent = JustifyContent.FLEX_START
            }

        holder.currentTagsAdapter = RemovableTagChipAdapter(item.tags) { tagToRemove ->
            val wasOriginalTag = holder.originalTags.contains(tagToRemove)
            item.tags.remove(tagToRemove)

            if (wasOriginalTag) {
                // If an existing tag is removed, stage that deletion immediately 
                // to maintain consistency with the cloud-sync logic.
                holder.originalTags = holder.originalTags.filter { it != tagToRemove }
                onItemTagsChanged(item, holder.originalTags.toList())
            }

            holder.currentTagsAdapter?.notifyDataSetChanged()
            updateAvailableTagsForItem(holder, item)
        }

        holder.currentTagsRecycler.adapter = holder.currentTagsAdapter
    }

    private fun setupAvailableTagsRecycler(holder: ViewHolder, item: MediaItem) {
        holder.availableTagsRecycler.layoutManager =
            FlexboxLayoutManager(holder.itemView.context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
                justifyContent = JustifyContent.FLEX_START
            }

        holder.availableTagsAdapter = ClickableTagChipAdapter { selectedTag ->
            if (!item.tags.contains(selectedTag)) {
                item.tags.add(selectedTag)
                holder.currentTagsAdapter?.notifyDataSetChanged()
                updateAvailableTagsForItem(holder, item)
            }
        }

        holder.availableTagsRecycler.adapter = holder.availableTagsAdapter
        updateAvailableTagsForItem(holder, item)
    }

    /**
     * Filters the global available tags to only show those not yet assigned to this item.
     */
    private fun updateAvailableTagsForItem(holder: ViewHolder, item: MediaItem) {
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
