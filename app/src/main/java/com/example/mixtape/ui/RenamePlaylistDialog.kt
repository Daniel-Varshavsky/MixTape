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

/**
 * RenamePlaylistDialog provides a simple interface for users to change the name
 * of an existing playlist. It handles the Firestore update and notifies the 
 * parent activity upon success.
 */
class RenamePlaylistDialog : DialogFragment() {

    private lateinit var repository: FirebaseRepository
    private var playlistId: String = ""
    private var currentName: String = ""
    private var onPlaylistRenamed: ((String, String) -> Unit)? = null

    companion object {
        /**
         * Factory method to create a new instance with the required metadata and a completion callback.
         */
        fun newInstance(
            playlistId: String, 
            currentName: String, 
            onPlaylistRenamed: (String, String) -> Unit
        ): RenamePlaylistDialog {
            val dialog = RenamePlaylistDialog()
            dialog.playlistId = playlistId
            dialog.currentName = currentName
            dialog.onPlaylistRenamed = onPlaylistRenamed
            return dialog
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_rename_playlist)

        // Set dialog width to 90% of screen width
        dialog.window?.setLayout(
            (requireContext().resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        repository = FirebaseRepository()

        val input = dialog.findViewById<EditText>(R.id.playlistNameInput)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = dialog.findViewById<MaterialButton>(R.id.btnSave)

        input.setText(currentName)
        input.setSelectAllOnFocus(true)

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnSave.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty() && newName != currentName) {
                renamePlaylist(newName, btnSave)
            } else if (newName.isEmpty()) {
                input.error = "Playlist name required"
            } else {
                dismiss() // Name unchanged, nothing to save
            }
        }

        // Auto-focus the input and display the soft keyboard for immediate user entry
        input.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) 
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        return dialog
    }

    /**
     * Executes the rename operation in Firebase and triggers the callback if successful.
     */
    private fun renamePlaylist(newName: String, saveButton: MaterialButton) {
        saveButton.isEnabled = false
        saveButton.text = "Saving..."

        lifecycleScope.launch {
            try {
                // Update playlist in Firestore
                val result = repository.updatePlaylistName(playlistId, newName)
                
                result.onSuccess {
                    Toast.makeText(
                        requireContext(),
                        "Playlist renamed to '$newName'",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    onPlaylistRenamed?.invoke(playlistId, newName)
                    dismiss()
                }.onFailure { exception ->
                    Toast.makeText(
                        requireContext(),
                        "Error renaming playlist: ${exception.message}",
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
                saveButton.isEnabled = true
                saveButton.text = "Save"
            }
        }
    }
}
