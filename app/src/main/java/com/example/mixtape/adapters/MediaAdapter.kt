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

        // Set icon based on media type
        when (item) {
            is MediaItem.SongItem -> holder.icon.setImageResource(R.drawable.outline_music_note_2_24)
            is MediaItem.VideoItem -> holder.icon.setImageResource(R.drawable.outline_videocam_24)
        }

        holder.title.text = item.title
        holder.artist.text = item.artist
        holder.album.text = item.album
        holder.duration.text = item.getDurationFormatted()

        // Set up tags RecyclerView
        holder.tagsRecycler.layoutManager = LinearLayoutManager(
            holder.itemView.context, 
            LinearLayoutManager.HORIZONTAL, 
            false
        )
        holder.tagsRecycler.adapter = TagChipAdapter(item.tags)

        // Handle click
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
