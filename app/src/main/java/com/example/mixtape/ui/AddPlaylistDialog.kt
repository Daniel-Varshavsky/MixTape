package com.example.mixtape.ui

import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import com.example.mixtape.R
import com.google.android.material.button.MaterialButton

class AddPlaylistDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_add_playlist)

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val cancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val create = dialog.findViewById<MaterialButton>(R.id.btnCreate)
        val input = dialog.findViewById<EditText>(R.id.playlistNameInput)

        cancel.setOnClickListener {
            dismiss()
        }

        create.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                // TODO: send result back to activity / ViewModel
                dismiss()
            } else {
                input.error = "Playlist name required"
            }
        }

        return dialog
    }
}