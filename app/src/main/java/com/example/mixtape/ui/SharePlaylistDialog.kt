package com.example.mixtape.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.mixtape.R
import com.example.mixtape.utilities.FirebaseRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch

/**
 * SharePlaylistDialog allows users to share their playlists with others. 
 * It supports two methods:
 * 1. Generating a 6-character unique share code.
 * 2. Direct sharing with another user via their email address.
 */
class SharePlaylistDialog : DialogFragment() {

    private lateinit var repository: FirebaseRepository
    private var playlistId: String = ""
    private var playlistName: String = ""

    companion object {
        /**
         * Factory method to create a new instance with the target playlist's metadata.
         */
        fun newInstance(playlistId: String, playlistName: String): SharePlaylistDialog {
            val dialog = SharePlaylistDialog()
            dialog.playlistId = playlistId
            dialog.playlistName = playlistName
            return dialog
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_share_playlist)

        // Set dialog width to 90% of screen width
        dialog.window?.setLayout(
            (requireContext().resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        repository = FirebaseRepository()
        setupViews(dialog)

        return dialog
    }

    private fun setupViews(dialog: Dialog) {
        val playlistTitle = dialog.findViewById<MaterialTextView>(R.id.playlistTitle)
        val shareCodeText = dialog.findViewById<MaterialTextView>(R.id.shareCodeText)
        val btnGenerateCode = dialog.findViewById<MaterialButton>(R.id.btnGenerateCode)
        val btnCopyCode = dialog.findViewById<MaterialButton>(R.id.btnCopyCode)
        val userEmailInput = dialog.findViewById<EditText>(R.id.userEmailInput)
        val btnShareWithUser = dialog.findViewById<MaterialButton>(R.id.btnShareWithUser)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.btnClose)

        playlistTitle.text = "Share: $playlistName"

        btnGenerateCode.setOnClickListener {
            generateShareCode(shareCodeText, btnGenerateCode, btnCopyCode)
        }

        btnCopyCode.setOnClickListener {
            copyCodeToClipboard(shareCodeText.text.toString())
        }

        btnShareWithUser.setOnClickListener {
            val email = userEmailInput.text.toString().trim()
            if (email.isNotEmpty()) {
                shareWithUser(email, btnShareWithUser)
                userEmailInput.text?.clear()
            } else {
                userEmailInput.error = "Email required"
            }
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        // The copy button is only visible once a code has been generated
        btnCopyCode.visibility = View.GONE
    }

    /**
     * Requests a new share code from the repository and updates the UI.
     */
    private fun generateShareCode(
        shareCodeText: MaterialTextView, 
        generateButton: MaterialButton,
        copyButton: MaterialButton
    ) {
        generateButton.isEnabled = false
        generateButton.text = "Generating..."

        lifecycleScope.launch {
            try {
                val result = repository.generateShareCode(playlistId)
                
                result.onSuccess { shareCode ->
                    shareCodeText.text = shareCode
                    shareCodeText.visibility = View.VISIBLE
                    copyButton.visibility = View.VISIBLE
                    
                    Toast.makeText(
                        requireContext(),
                        "Share code generated!",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { exception ->
                    Toast.makeText(
                        requireContext(),
                        "Error generating code: ${exception.message}",
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
                generateButton.isEnabled = true
                generateButton.text = "Generate New Code"
            }
        }
    }

    /**
     * Attempts to find a user by email and grant them access to this playlist.
     */
    private fun shareWithUser(email: String, shareButton: MaterialButton) {
        shareButton.isEnabled = false
        shareButton.text = "Sharing..."

        lifecycleScope.launch {
            try {
                // First, resolve the email to a User ID
                val userResult = repository.findUserByEmail(email)
                
                userResult.onSuccess { user ->
                    // Grant access to the resolved user
                    val shareResult = repository.sharePlaylistWithUser(playlistId, user.id, "")
                    
                    shareResult.onSuccess {
                        Toast.makeText(
                            requireContext(),
                            "Playlist shared successfully with $email",
                            Toast.LENGTH_SHORT
                        ).show()
                        dismiss() 
                    }.onFailure { e ->
                        Toast.makeText(
                            requireContext(),
                            "Error sharing: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { e ->
                    val errorMsg = if (e.message == "User not found") "No user found with that email" else e.message
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Unexpected error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                shareButton.isEnabled = true
                shareButton.text = "Share with User"
            }
        }
    }

    /**
     * Helper to copy the generated share code to the system clipboard for easy sharing.
     */
    private fun copyCodeToClipboard(shareCode: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("MixTape Share Code", shareCode)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(
            requireContext(),
            "Share code copied to clipboard!",
            Toast.LENGTH_SHORT
        ).show()
    }
}
