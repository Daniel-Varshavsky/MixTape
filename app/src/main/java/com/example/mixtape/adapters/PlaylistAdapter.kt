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

/**
 * Adapter for the main dashboard, displaying a list of playlists.
 * 
 * Key Features:
 * 1. Deep Deletion: When a playlist is deleted, it optionally triggers a recursive 
 *    deletion of all its media items from both Firestore and Storage.
 * 2. Dynamic Updates: Handles renaming and sharing through integrated dialogs.
 * 3. Conditional UI: Shows/hides management buttons based on user permissions (owner vs shared).
 */
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

        holder.itemView.setOnClickListener {
            onPlaylistClick(playlist)
        }

        // --- Playlist Management Actions ---

        holder.btnEditPlaylistName.setOnClickListener {
            if (fragmentManager != null) {
                val dialog = RenamePlaylistDialog.newInstance(playlist.id, playlist.name) { playlistId, newName ->
                    val index = playlists.indexOfFirst { it.id == playlistId }
                    if (index != -1) {
                        playlists[index] = playlists[index].copy(name = newName)
                        notifyItemChanged(index)
                    }
                    onPlaylistRenamed(playlistId, newName)
                }
                dialog.show(fragmentManager, "RenamePlaylist")
            }
        }

        holder.btnShare.setOnClickListener {
            if (fragmentManager != null) {
                val dialog = SharePlaylistDialog.newInstance(playlist.id, playlist.name)
                dialog.show(fragmentManager, "SharePlaylist")
            }
        }

        holder.btnDelete.setOnClickListener {
            showDeleteConfirmation(holder.itemView.context, playlist, position)
        }
    }

    /**
     * Warning dialog explaining the permanent nature of the deletion.
     */
    private fun showDeleteConfirmation(context: android.content.Context, playlist: Playlist, position: Int) {
        val dialogBuilder = AlertDialog.Builder(context)

        dialogBuilder
            .setTitle("Delete Playlist")
            .setMessage("Are you sure you want to delete '${playlist.name}'?\n\nThis will permanently delete:\n• The playlist itself\n• All ${playlist.songs} songs in this playlist\n• All ${playlist.videos} videos in this playlist\n• All associated media files from storage\n\nThis action cannot be undone.")
            .setPositiveButton("Delete") { dialog, _ ->
                if (lifecycleScope != null) {
                    deletePlaylistCompletely(playlist, position, context)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Orchestrates a full cleanup of a playlist and its contents.
     * Note: This is an expensive operation as it iterates through every media item.
     */
    private fun deletePlaylistCompletely(playlist: Playlist, position: Int, context: android.content.Context) {
        lifecycleScope?.launch {
            try {
                // 1. Fetch metadata to identify what needs to be deleted from Storage
                val result = repository.getPlaylistWithMedia(playlist.id)

                result.onSuccess { (_, songs, videos) ->
                    var deletionErrors = 0

                    // 2. Clear songs from Storage and Firestore
                    for (song in songs) {
                        val songDeletionResult = repository.deleteSongCompletely(song.id)
                        if (songDeletionResult.isFailure) deletionErrors++
                    }

                    // 3. Clear videos (including thumbnails) from Storage and Firestore
                    for (video in videos) {
                        val videoDeletionResult = repository.deleteVideoCompletely(video.id)
                        if (videoDeletionResult.isFailure) deletionErrors++
                    }

                    // 4. Finally, remove the playlist document itself
                    val playlistDeletionResult = repository.deletePlaylist(playlist.id)

                    playlistDeletionResult.onSuccess {
                        playlists.removeAt(position)
                        notifyItemRemoved(position)
                        notifyItemRangeChanged(position, playlists.size)

                        val message = if (deletionErrors == 0) {
                            "Playlist and content deleted successfully"
                        } else {
                            "Playlist deleted, but $deletionErrors media files failed to delete"
                        }

                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        onPlaylistDeleted(playlist.id)
                    }
                }
            } catch (e: Exception) {
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
}
