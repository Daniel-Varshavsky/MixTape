package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.example.mixtape.model.Playlist

class PlaylistAdapter(private val playlists: List<Playlist>) :
    RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlistName)
        val info: TextView = view.findViewById(R.id.playlistInfo)
        val songs: TextView = view.findViewById(R.id.songCount)
        val videos: TextView = view.findViewById(R.id.videoCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = playlists[position]
        holder.name.text = p.name
        holder.info.text = "${p.songs + p.videos} items"
        holder.songs.text = "${p.songs} songs"
        holder.videos.text = "${p.videos} videos"
    }

    override fun getItemCount() = playlists.size
}
