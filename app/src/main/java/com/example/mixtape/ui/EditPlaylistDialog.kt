package com.example.mixtape

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.MediaAdapter
import com.example.mixtape.model.MediaItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

class EditPlaylistDialog : DialogFragment() {

    private var playlistName: String = ""
    private var mediaItems: MutableList<MediaItem> = mutableListOf()
    private lateinit var mediaAdapter: MediaAdapter

    companion object {
        fun newInstance(playlistName: String, mediaItems: List<MediaItem>): EditPlaylistDialog {
            val fragment = EditPlaylistDialog()
            val args = Bundle()
            args.putString("PLAYLIST_NAME", playlistName)
            fragment.arguments = args
            fragment.mediaItems.addAll(mediaItems)
            return fragment
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_edit_playlist)

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.8).toInt()
        )

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        playlistName = arguments?.getString("PLAYLIST_NAME") ?: "Playlist"

        setupDialog(dialog)

        return dialog
    }

    private fun setupDialog(dialog: Dialog) {
        val dialogTitle = dialog.findViewById<MaterialTextView>(R.id.dialogTitle)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.btnCloseDialog)
        val btnAddContent = dialog.findViewById<MaterialButton>(R.id.btnAddContent)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSaveChanges = dialog.findViewById<MaterialButton>(R.id.btnSaveChanges)
        val itemsRecycler = dialog.findViewById<RecyclerView>(R.id.editItemsRecycler)

        dialogTitle.text = "Edit: $playlistName"

        // Setup RecyclerView with current items
        itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        mediaAdapter = MediaAdapter(mediaItems) { mediaItem ->
            // Handle item click in edit mode - show options to remove/edit tags
            showMediaItemOptions(mediaItem)
        }
        itemsRecycler.adapter = mediaAdapter

        btnClose.setOnClickListener {
            dismiss()
        }

        btnAddContent.setOnClickListener {
            // TODO: Open a dialog to add new songs/videos
            // For now, just show a placeholder message
            android.widget.Toast.makeText(
                requireContext(),
                "Add content functionality will be implemented later",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnSaveChanges.setOnClickListener {
            // TODO: Save changes to the playlist
            android.widget.Toast.makeText(
                requireContext(),
                "Changes saved",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            dismiss()
        }
    }

    private fun showMediaItemOptions(mediaItem: MediaItem) {
        // TODO: Show options to remove item or edit tags
        android.widget.Toast.makeText(
            requireContext(),
            "Options for ${mediaItem.title} (remove/edit tags)",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}