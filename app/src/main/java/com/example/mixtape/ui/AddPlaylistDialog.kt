package com.example.mixtape.ui

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.mixtape.R
import com.example.mixtape.utilities.FirebaseRepository
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class AddPlaylistDialog : DialogFragment() {

    private lateinit var repository: FirebaseRepository
    private var onPlaylistCreated: ((String) -> Unit)? = null

    companion object {
        fun newInstance(onPlaylistCreated: (String) -> Unit): AddPlaylistDialog {
            val dialog = AddPlaylistDialog()
            dialog.onPlaylistCreated = onPlaylistCreated
            return dialog
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_add_playlist)

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        repository = FirebaseRepository()

        val cancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val create = dialog.findViewById<MaterialButton>(R.id.btnCreate)
        val join = dialog.findViewById<MaterialButton>(R.id.btnJoin)
        val nameInput = dialog.findViewById<EditText>(R.id.playlistNameInput)
        val shareCodeInput = dialog.findViewById<EditText>(R.id.shareCodeInput)

        cancel.setOnClickListener {
            dismiss()
        }

        create.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isNotEmpty()) {
                createPlaylist(name, create)
            } else {
                nameInput.error = "Playlist name required"
            }
        }

        join.setOnClickListener {
            val code = shareCodeInput.text.toString().trim().uppercase()
            if (code.length == 6) {
                joinPlaylist(code, join)
            } else {
                shareCodeInput.error = "6-character code required"
            }
        }

        return dialog
    }

    private fun createPlaylist(name: String, createButton: MaterialButton) {
        createButton.isEnabled = false
        createButton.text = "Creating..."

        lifecycleScope.launch {
            try {
                val result = repository.createPlaylist(
                    name = name,
                    description = "",
                    playlistTags = emptyList(),
                    isPublic = false
                )

                result.onSuccess { playlist ->
                    Toast.makeText(
                        requireContext(),
                        "Playlist '$name' created successfully!",
                        Toast.LENGTH_SHORT
                    ).show()

                    onPlaylistCreated?.invoke(playlist.id)
                    dismiss()
                }.onFailure { exception ->
                    Toast.makeText(
                        requireContext(),
                        "Error creating playlist: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                createButton.isEnabled = true
                createButton.text = "Create New Playlist"
            }
        }
    }

    private fun joinPlaylist(shareCode: String, joinButton: MaterialButton) {
        joinButton.isEnabled = false
        joinButton.text = "Joining..."

        lifecycleScope.launch {
            try {
                val result = repository.joinPlaylistByShareCode(shareCode)

                result.onSuccess { playlist ->
                    Toast.makeText(
                        requireContext(),
                        "Successfully joined '${playlist.name}'!",
                        Toast.LENGTH_SHORT
                    ).show()

                    onPlaylistCreated?.invoke(playlist.id)
                    dismiss()
                }.onFailure { exception ->
                    Toast.makeText(
                        requireContext(),
                        "Error joining: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                joinButton.isEnabled = true
                joinButton.text = "Join Playlist"
            }
        }
    }
}
