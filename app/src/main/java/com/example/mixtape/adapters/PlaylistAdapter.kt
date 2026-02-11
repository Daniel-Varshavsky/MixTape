package com.example.mixtape.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.R
import com.example.mixtape.model.Playlist
import com.google.android.material.button.MaterialButton

class PlaylistAdapter(
    private val playlists: List<Playlist>,
    private val onPlaylistClick: (Playlist) -> Unit = {}
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

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
        val p = playlists[position]

        holder.name.text = p.name
        holder.info.text = "${p.songs + p.videos} items"
        holder.songs.text = "${p.songs} songs"
        holder.videos.text = "${p.videos} videos"

        // Handle playlist click (open playlist)
        holder.itemView.setOnClickListener {
            onPlaylistClick(p)
        }

        // Handle edit playlist name
        holder.btnEditPlaylistName.setOnClickListener {
            showRenameDialog(holder.itemView.context, p.name) { newName ->
                // TODO: Update playlist name in database/storage
                holder.name.text = newName
            }
        }

        holder.btnShare.setOnClickListener {
            // TODO: Implement share functionality
        }

        holder.btnDelete.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setMessage("Are you sure you want to delete this playlist?")
                .setPositiveButton("Yes") { dialog, _ ->
                    // TODO: Implement delete functionality
                    dialog.dismiss()
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showRenameDialog(context: android.content.Context, currentName: String, onRenamed: (String) -> Unit) {
        // Using custom dialog for better control and consistency
        showCustomRenameDialog(context, currentName, onRenamed)
    }

    private fun showCustomRenameDialog(context: android.content.Context, currentName: String, onRenamed: (String) -> Unit) {
        val dialog = android.app.Dialog(context)
        dialog.setContentView(R.layout.dialog_rename_playlist)

        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val input = dialog.findViewById<EditText>(R.id.playlistNameInput)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = dialog.findViewById<MaterialButton>(R.id.btnSave)

        input.setText(currentName)
        input.setSelectAllOnFocus(true)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty() && newName != currentName) {
                onRenamed(newName)
            }
            dialog.dismiss()
        }

        dialog.show()

        // Focus the input and show keyboard
        input.requestFocus()
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun getItemCount() = playlists.size
}