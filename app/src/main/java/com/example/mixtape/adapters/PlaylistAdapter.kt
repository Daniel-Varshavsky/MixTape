package com.example.mixtape.adapters

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.example.mixtape.model.Playlist
import com.example.mixtape.utilities.FirebaseRepository
import com.example.mixtape.ui.RenamePlaylistDialog
import com.example.mixtape.ui.SharePlaylistDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class PlaylistAdapter(
    private val playlists: MutableList<Playlist>,
    private val onPlaylistClick: (Playlist) -> Unit = {},
    private val lifecycleScope: LifecycleCoroutineScope? = null,
    private val fragmentManager: androidx.fragment.app.FragmentManager? = null,
    private val onPlaylistDeleted: (String) -> Unit = {},
    private val onPlaylistRenamed: (String, String) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private val repository = FirebaseRepository()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlistName)
        val info: TextView = view.findViewById(R.id.playlistInfo)
        val songs: TextView = view.findViewById(R.id.songCount)
        val videos: TextView = view.findViewById(R.id.videoCount)
        val btnEditPlaylistName: MaterialButton = view.findViewById(R.id.btnEditPlaylistName)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)
        val btnShare: MaterialButton = view.findViewById(R.id.btnShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]

        holder.name.text = playlist.name
        holder.info.text = "${playlist.totalItems} items"
        holder.songs.text = "${playlist.songs} songs"
        holder.videos.text = "${playlist.videos} videos"

        // Handle playlist click (open playlist)
        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        // Handle edit playlist name - only if fragmentManager is available
        holder.btnEditPlaylistName.setOnClickListener {
            if (fragmentManager != null) {
                val dialog = RenamePlaylistDialog.newInstance(
                    playlist.id,
                    playlist.name
                ) { playlistId, newName ->
                    // Update local data
                    val index = playlists.indexOfFirst { it.id == playlistId }
                    if (index != -1) {
                        playlists[index] = playlists[index].copy(name = newName)
                        notifyItemChanged(index)
                    }
                    onPlaylistRenamed(playlistId, newName)
                }
                dialog.show(fragmentManager, "RenamePlaylist")
            } else {
                Toast.makeText(
                    holder.itemView.context,
                    "Rename functionality will be implemented later",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Handle share - only if fragmentManager is available
        holder.btnShare.setOnClickListener {
            if (fragmentManager != null) {
                val dialog = SharePlaylistDialog.newInstance(playlist.id, playlist.name)
                dialog.show(fragmentManager, "SharePlaylist")
            } else {
                Toast.makeText(
                    holder.itemView.context,
                    "Share functionality will be implemented later",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Handle delete
        holder.btnDelete.setOnClickListener {
            showDeleteConfirmation(holder.itemView.context, playlist, position)
        }
    }

    private fun showDeleteConfirmation(context: android.content.Context, playlist: Playlist, position: Int) {
        AlertDialog.Builder(context)
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete '${playlist.name}'?\n\nThis will permanently delete:\n• The playlist itself\n• All ${playlist.songs} songs in this playlist\n• All ${playlist.videos} videos in this playlist\n• All associated media files from storage\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { dialog, _ ->
                if (lifecycleScope != null) {
                    deletePlaylistCompletely(playlist, position, context)
                } else {
                    Toast.makeText(
                        context,
                        "Delete functionality will be implemented later",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun deletePlaylistCompletely(playlist: Playlist, position: Int, context: android.content.Context) {
        lifecycleScope?.launch {
            try {
                // Step 1: Get all songs and videos in this playlist before deleting
                val result = repository.getPlaylistWithMedia(playlist.id)

                result.onSuccess { (playlistData, songs, videos) ->
                    var deletionErrors = 0

                    // Step 2: Delete all songs completely (from database and storage)
                    for (song in songs) {
                        val songDeletionResult = repository.deleteSongCompletely(song.id)
                        if (songDeletionResult.isFailure) {
                            deletionErrors++
                            Log.e("PlaylistAdapter", "Failed to delete song ${song.id}: ${songDeletionResult.exceptionOrNull()?.message}")
                        }
                    }

                    // Step 3: Delete all videos completely (from database and storage)
                    for (video in videos) {
                        val videoDeletionResult = repository.deleteVideoCompletely(video.id)
                        if (videoDeletionResult.isFailure) {
                            deletionErrors++
                            Log.e("PlaylistAdapter", "Failed to delete video ${video.id}: ${videoDeletionResult.exceptionOrNull()?.message}")
                        }
                    }

                    // Step 4: Delete the playlist itself
                    val playlistDeletionResult = repository.deletePlaylist(playlist.id)

                    playlistDeletionResult.onSuccess {
                        // Remove from local list
                        playlists.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, playlists.size)

                        // Show result to user
                        val message = if (deletionErrors == 0) {
                            "Playlist '${playlist.name}' and all its content deleted successfully"
                        } else {
                            "Playlist '${playlist.name}' deleted, but $deletionErrors media files failed to delete"
                        }

                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

                        Log.d("PlaylistAdapter", "Playlist deletion complete: ${songs.size + videos.size} media items processed, $deletionErrors errors")

                        // Notify parent activity
                        onPlaylistDeleted(playlist.id)

                    }.onFailure { exception ->
                        Toast.makeText(
                            context,
                            "Error deleting playlist: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.e("PlaylistAdapter", "Error deleting playlist: ${exception.message}", exception)
                    }

                }.onFailure { exception ->
                    Toast.makeText(
                        context,
                        "Error loading playlist content for deletion: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e("PlaylistAdapter", "Error loading playlist for deletion: ${exception.message}", exception)
                }

            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                Log.e("PlaylistAdapter", "Exception in deletePlaylistCompletely: ${e.message}", e)
            }
        }
    }

    override fun getItemCount() = playlists.size

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        playlists.clear()
        playlists.addAll(newPlaylists)
        notifyDataSetChanged()
    }

    fun addPlaylist(playlist: Playlist) {
        playlists.add(0, playlist) // Add at the beginning
        notifyItemInserted(0)
    }
}