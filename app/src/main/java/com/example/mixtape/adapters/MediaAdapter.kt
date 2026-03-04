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

/**
 * Standard adapter for displaying media items (Songs/Videos) in a list.
 * 
 * Key Features:
 * 1. Polymorphic Support: Handles both SongItem and VideoItem models seamlessly.
 * 2. Visual Tags: Uses a nested RecyclerView with FlexboxLayoutManager to display tags as chips.
 * 3. Marquee Support: Title TextView is configured to scroll (marquee) if the text is too long.
 */
class MediaAdapter(
    private var mediaItems: List<MediaItem>,
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: AppCompatImageView = view.findViewById(R.id.mediaIcon)
        val title: TextView = view.findViewById(R.id.mediaTitle)
        val artist: TextView = view.findViewById(R.id.mediaArtist)
        val album: TextView = view.findViewById(R.id.mediaAlbum)
        val duration: TextView = view.findViewById(R.id.mediaDuration)
        val tagsRecycler: RecyclerView = view.findViewById(R.id.tagsRecycler)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mediaItems[position]

        // Dynamic Icon assignment
        when (item) {
            is MediaItem.SongItem -> holder.icon.setImageResource(R.drawable.sharp_music_note_2_24)
            is MediaItem.VideoItem -> holder.icon.setImageResource(R.drawable.outline_videocam_24)
        }

        holder.title.text = item.title
        holder.title.isSelected = true // Trigger marquee
        
        holder.artist.text = item.artist
        holder.album.text = item.album
        holder.duration.text = item.getDurationFormatted()

        // Nested Tags Layout Setup
        holder.tagsRecycler.layoutManager = FlexboxLayoutManager(holder.itemView.context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.FLEX_START
        }
        holder.tagsRecycler.adapter = TagChipAdapter(item.tags)

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = mediaItems.size

    fun updateItems(newItems: List<MediaItem>) {
        mediaItems = newItems
        notifyDataSetChanged()
    }
}
