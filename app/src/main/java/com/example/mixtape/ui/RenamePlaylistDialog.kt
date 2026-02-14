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

class RenamePlaylistDialog : DialogFragment() {

    private lateinit var repository: FirebaseRepository
    private var playlistId: String = ""
    private var currentName: String = ""
    private var onPlaylistRenamed: ((String, String) -> Unit)? = null

    companion object {
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
                dismiss() // Name unchanged
            }
        }

        dialog.show()

        // Focus the input and show keyboard
        input.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) 
                as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        return dialog
    }

    private fun renamePlaylist(newName: String, saveButton: MaterialButton) {
        saveButton.isEnabled = false
        saveButton.text = "Saving..."

        lifecycleScope.launch {
            try {
                // Update playlist in Firestore
                val result = repository.updatePlaylistName(playlistId, newName)
                
                result.onSuccess {
                    saveButton.isEnabled = true
                    saveButton.text = "Save"
                    
                    Toast.makeText(
                        requireContext(),
                        "Playlist renamed to '$newName'",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Notify parent activity
                    onPlaylistRenamed?.invoke(playlistId, newName)
                    
                    dismiss()
                }.onFailure { exception ->
                    saveButton.isEnabled = true
                    saveButton.text = "Save"
                    
                    Toast.makeText(
                        requireContext(),
                        "Error renaming playlist: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                saveButton.isEnabled = true
                saveButton.text = "Save"
                
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
