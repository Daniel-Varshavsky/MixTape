package com.example.mixtape

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mixtape.adapters.EditableMediaAdapter
import com.example.mixtape.model.*
import com.example.mixtape.utilities.StagedChanges
import com.example.mixtape.utilities.FirebaseRepository
import com.example.mixtape.utilities.MediaMetadataExtractor
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import java.util.UUID

class EditPlaylistDialog : DialogFragment() {

    private var playlistName: String = ""
    private var playlistId: String = ""
    private var originalMediaItems: MutableList<MediaItem> = mutableListOf() // Original items from Firebase
    private var displayedItems: MutableList<MediaItem> = mutableListOf() // Items shown in UI (including pending)
    private var availableTags: List<String> = emptyList()
    private lateinit var editableMediaAdapter: EditableMediaAdapter
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var repository: FirebaseRepository
    private lateinit var btnAddContent: MaterialButton
    private lateinit var btnSaveChanges: MaterialButton

    // Staging system for changes
    private val stagedChanges = StagedChanges()

    // Callback to refresh parent activity
    private var onPlaylistUpdated: (() -> Unit)? = null

    companion object {
        private const val TAG = "EditPlaylistDialog"

        fun newInstance(
            playlistId: String,
            playlistName: String,
            mediaItems: List<MediaItem>,
            availableTags: List<String>, // This should be current global tags from PlaylistActivity
            onPlaylistUpdated: (() -> Unit)? = null
        ): EditPlaylistDialog {
            val fragment = EditPlaylistDialog()
            val args = Bundle()
            args.putString("PLAYLIST_ID", playlistId)
            args.putString("PLAYLIST_NAME", playlistName)
            fragment.arguments = args
            fragment.originalMediaItems.addAll(mediaItems)
            fragment.displayedItems.addAll(mediaItems) // Start with original items
            fragment.availableTags = availableTags.toList() // Ensure we have a copy of current global tags
            fragment.onPlaylistUpdated = onPlaylistUpdated
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = FirebaseRepository()

        // Register file picker launcher
        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                handleSelectedFiles(result.data)
            }
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

        playlistId = arguments?.getString("PLAYLIST_ID") ?: ""
        playlistName = arguments?.getString("PLAYLIST_NAME") ?: "Playlist"

        Log.d(TAG, "Dialog opened for playlist: $playlistName (ID: $playlistId)")

        setupDialog(dialog)
        return dialog
    }

    private fun setupDialog(dialog: Dialog) {
        val dialogTitle = dialog.findViewById<MaterialTextView>(R.id.dialogTitle)
        val btnClose = dialog.findViewById<MaterialButton>(R.id.btnCloseDialog)
        btnAddContent = dialog.findViewById<MaterialButton>(R.id.btnAddContent)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        btnSaveChanges = dialog.findViewById<MaterialButton>(R.id.btnSaveChanges)
        val itemsRecycler = dialog.findViewById<RecyclerView>(R.id.editItemsRecycler)

        dialogTitle.text = "Edit: $playlistName"

        // Setup RecyclerView
        itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        editableMediaAdapter = EditableMediaAdapter(
            displayedItems,
            availableTags, // Current global tags from PlaylistActivity
            onItemRemoved = { item -> stageItemRemoval(item) },
            onItemTagsChanged = { item, newTags -> stageTagUpdate(item, newTags) }
        )
        itemsRecycler.adapter = editableMediaAdapter

        btnClose.setOnClickListener { dismiss() }
        btnAddContent.setOnClickListener { openFilePicker() }
        btnCancel.setOnClickListener { dismiss() }
        btnSaveChanges.setOnClickListener { saveAllChanges() }

        updateSaveButton()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        }

        try {
            filePickerLauncher.launch(Intent.createChooser(intent, "Select Music or Video Files"))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file picker: ${e.message}", e)
            Toast.makeText(requireContext(), "Cannot open file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedFiles(data: Intent?) {
        if (data == null) {
            Log.d(TAG, "No data from file picker")
            return
        }

        val selectedFiles = mutableListOf<Uri>()

        // Get selected file URIs
        data.clipData?.let { clipData ->
            Log.d(TAG, "Multiple files selected: ${clipData.itemCount}")
            for (i in 0 until clipData.itemCount) {
                selectedFiles.add(clipData.getItemAt(i).uri)
            }
        } ?: data.data?.let {
            Log.d(TAG, "Single file selected")
            selectedFiles.add(it)
        }

        if (selectedFiles.isEmpty()) {
            Log.w(TAG, "No files found")
            Toast.makeText(requireContext(), "No files selected", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Staging ${selectedFiles.size} files for upload")
        stageFilesForUpload(selectedFiles)
    }

    private fun stageFilesForUpload(uris: List<Uri>) {
        var successCount = 0
        var failureCount = 0

        for (uri in uris) {
            try {
                // Determine file type and extract basic info
                val mimeType = requireContext().contentResolver.getType(uri) ?: ""

                when {
                    mimeType.startsWith("audio/") -> {
                        val songTemplate = MediaMetadataExtractor.extractSong(requireContext(), uri)
                        if (songTemplate != null) {
                            stagePendingUpload(uri, songTemplate, true)
                            successCount++
                        } else {
                            failureCount++
                        }
                    }
                    mimeType.startsWith("video/") -> {
                        val videoTemplate = MediaMetadataExtractor.extractVideo(requireContext(), uri)
                        if (videoTemplate != null) {
                            stagePendingUpload(uri, videoTemplate, false)
                            successCount++
                        } else {
                            failureCount++
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unsupported file type: $mimeType")
                        failureCount++
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing file $uri: ${e.message}", e)
                failureCount++
            }
        }

        // Show result
        val message = when {
            successCount > 0 && failureCount == 0 -> "$successCount file(s) staged for upload"
            successCount > 0 && failureCount > 0 -> "$successCount staged, $failureCount failed"
            else -> "No files could be processed"
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        updateSaveButton()
    }

    private fun stagePendingUpload(uri: Uri, template: Song, isAudio: Boolean) {
        val tempId = "temp_${UUID.randomUUID()}"

        val pendingUpload = PendingMediaUpload(
            tempId = tempId,
            uri = uri,
            title = template.title,
            artist = template.artist,
            album = template.album,
            durationSeconds = template.durationSeconds,
            tags = template.tags,
            fileName = getFileName(uri),
            fileSize = template.fileSize,
            mimeType = template.mimeType,
            isAudio = isAudio,
            isVideo = !isAudio
        )

        // Add to staged uploads
        stagedChanges.pendingUploads.add(pendingUpload)

        // Create a temporary MediaItem for display
        val tempMediaItem = if (isAudio) {
            MediaItem.SongItem(
                Song(
                    id = tempId,
                    title = template.title,
                    artist = template.artist,
                    album = template.album,
                    durationSeconds = template.durationSeconds,
                    tags = template.tags
                )
            )
        } else {
            MediaItem.VideoItem(
                Video(
                    id = tempId,
                    title = template.title,
                    artist = template.artist,
                    album = template.album,
                    durationSeconds = template.durationSeconds,
                    tags = template.tags
                )
            )
        }

        // Add to displayed items
        displayedItems.add(tempMediaItem)
        editableMediaAdapter.notifyItemInserted(displayedItems.size - 1)

        Log.d(TAG, "Staged ${if (isAudio) "song" else "video"}: ${template.title}")
    }

    private fun stagePendingUpload(uri: Uri, template: Video, isAudio: Boolean) {
        val tempId = "temp_${UUID.randomUUID()}"

        val pendingUpload = PendingMediaUpload(
            tempId = tempId,
            uri = uri,
            title = template.title,
            artist = template.artist,
            album = template.album,
            durationSeconds = template.durationSeconds,
            tags = template.tags,
            fileName = getFileName(uri),
            fileSize = template.fileSize,
            mimeType = template.mimeType,
            isAudio = isAudio,
            isVideo = !isAudio
        )

        // Add to staged uploads
        stagedChanges.pendingUploads.add(pendingUpload)

        // Create a temporary MediaItem for display
        val tempMediaItem = MediaItem.VideoItem(
            Video(
                id = tempId,
                title = template.title,
                artist = template.artist,
                album = template.album,
                durationSeconds = template.durationSeconds,
                tags = template.tags
            )
        )

        // Add to displayed items
        displayedItems.add(tempMediaItem)
        editableMediaAdapter.notifyItemInserted(displayedItems.size - 1)

        Log.d(TAG, "Staged video: ${template.title}")
    }

    private fun stageItemRemoval(item: MediaItem) {
        // Check if it's a pending upload (temp ID) or existing item
        if (item.id.startsWith("temp_")) {
            // Remove from pending uploads immediately - no staging needed
            stagedChanges.pendingUploads.removeAll { it.tempId == item.id }

            // Remove from displayed items
            val position = displayedItems.indexOf(item)
            if (position != -1) {
                displayedItems.removeAt(position)
                editableMediaAdapter.notifyItemRemoved(position)
            }

            Log.d(TAG, "Removed pending upload: ${item.title}")
        } else {
            // For existing items, stage for complete deletion
            stagedChanges.mediaItemChanges.add(
                MediaItemChange(item.id, ChangeAction.DELETE_COMPLETELY)
            )

            // Remove from displayed items
            val position = displayedItems.indexOf(item)
            if (position != -1) {
                displayedItems.removeAt(position)
                editableMediaAdapter.notifyItemRemoved(position)
            }

            Log.d(TAG, "Staged complete deletion: ${item.title}")
            Toast.makeText(requireContext(), "Will be deleted when changes are saved", Toast.LENGTH_SHORT).show()
        }

        updateSaveButton()
    }

    private fun stageTagUpdate(item: MediaItem, newTags: List<String>) {
        if (!item.id.startsWith("temp_")) {
            // Only stage changes for existing items
            // Remove any existing tag update for this item
            stagedChanges.mediaItemChanges.removeAll {
                it.mediaItemId == item.id && it.action == ChangeAction.UPDATE_TAGS
            }

            // Add new tag update
            stagedChanges.mediaItemChanges.add(
                MediaItemChange(item.id, ChangeAction.UPDATE_TAGS, newTags)
            )

            Log.d(TAG, "Staged tag update for: ${item.title}")
            updateSaveButton()
        } else {
            // For pending uploads, update the tags directly in memory
            val pendingUpload = stagedChanges.pendingUploads.find { it.tempId == item.id }
            pendingUpload?.tags?.clear()
            pendingUpload?.tags?.addAll(newTags)

            Log.d(TAG, "Updated tags for pending upload: ${item.title}")
        }
    }

    private fun saveAllChanges() {
        if (!stagedChanges.hasChanges()) {
            Toast.makeText(requireContext(), "No changes to save", Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveChanges.isEnabled = false
        btnSaveChanges.text = "Saving..."
        btnAddContent.isEnabled = false

        lifecycleScope.launch {
            try {
                var successCount = 0
                var failureCount = 0

                // Process pending uploads
                for (pendingUpload in stagedChanges.pendingUploads) {
                    if (uploadPendingMedia(pendingUpload)) {
                        successCount++
                    } else {
                        failureCount++
                    }
                }

                // Process media item changes
                for (change in stagedChanges.mediaItemChanges) {
                    when (change.action) {
                        ChangeAction.DELETE_COMPLETELY -> {
                            if (deleteItemCompletely(change.mediaItemId)) {
                                successCount++
                            } else {
                                failureCount++
                            }
                        }
                        ChangeAction.UPDATE_TAGS -> {
                            if (updateExistingItemTags(change.mediaItemId, change.newTags!!)) {
                                successCount++
                            } else {
                                failureCount++
                            }
                        }
                    }
                }

                // Show results and close
                activity?.runOnUiThread {
                    val message = when {
                        successCount > 0 && failureCount == 0 -> "All changes saved successfully!"
                        successCount > 0 && failureCount > 0 -> "$successCount saved, $failureCount failed"
                        failureCount > 0 -> "Failed to save changes"
                        else -> "No changes processed"
                    }

                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

                    if (successCount > 0) {
                        onPlaylistUpdated?.invoke()
                        dismiss()
                    } else {
                        // Re-enable buttons if all failed
                        btnSaveChanges.isEnabled = true
                        btnSaveChanges.text = "Save Changes"
                        btnAddContent.isEnabled = true
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error saving changes: ${e.message}", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Error saving changes", Toast.LENGTH_LONG).show()
                    btnSaveChanges.isEnabled = true
                    btnSaveChanges.text = "Save Changes"
                    btnAddContent.isEnabled = true
                }
            }
        }
    }

    private suspend fun uploadPendingMedia(pendingUpload: PendingMediaUpload): Boolean {
        return try {
            Log.d(TAG, "Uploading pending media: ${pendingUpload.title}")

            if (pendingUpload.isAudio) {
                val result = repository.uploadSong(
                    fileUri = pendingUpload.uri,
                    title = pendingUpload.title,
                    artist = pendingUpload.artist,
                    album = pendingUpload.album,
                    duration = pendingUpload.durationSeconds,
                    tags = pendingUpload.tags
                )

                result.fold(
                    onSuccess = { song ->
                        repository.addSongToPlaylist(playlistId, song.id).isSuccess
                    },
                    onFailure = { false }
                )
            } else {
                val result = repository.uploadVideo(
                    fileUri = pendingUpload.uri,
                    title = pendingUpload.title,
                    artist = pendingUpload.artist,
                    album = pendingUpload.album,
                    duration = pendingUpload.durationSeconds,
                    tags = pendingUpload.tags
                )

                result.fold(
                    onSuccess = { video ->
                        repository.addVideoToPlaylist(playlistId, video.id).isSuccess
                    },
                    onFailure = { false }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading pending media: ${e.message}", e)
            false
        }
    }

    private suspend fun deleteItemCompletely(itemId: String): Boolean {
        return try {
            val originalItem = originalMediaItems.find { it.id == itemId }
            if (originalItem != null) {
                when (originalItem) {
                    is MediaItem.SongItem -> repository.deleteSongCompletely(itemId).isSuccess
                    is MediaItem.VideoItem -> repository.deleteVideoCompletely(itemId).isSuccess
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error completely deleting item: ${e.message}", e)
            false
        }
    }

    private suspend fun updateExistingItemTags(itemId: String, newTags: List<String>): Boolean {
        return try {
            val originalItem = originalMediaItems.find { it.id == itemId }
            if (originalItem != null) {
                when (originalItem) {
                    is MediaItem.SongItem -> repository.updateSongTags(itemId, newTags).isSuccess
                    is MediaItem.VideoItem -> repository.updateVideoTags(itemId, newTags).isSuccess
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tags: ${e.message}", e)
            false
        }
    }

    private fun updateSaveButton() {
        val hasChanges = stagedChanges.hasChanges()
        btnSaveChanges.isEnabled = hasChanges
        btnSaveChanges.text = if (hasChanges) {
            val count = stagedChanges.getChangeCount()
            "Save Changes ($count)"
        } else {
            "Save Changes"
        }
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "Unknown File"
        try {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = it.getString(nameIndex) ?: "Unknown File"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting file name: ${e.message}")
        }
        return fileName
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // When dialog is cancelled (back button, outside tap), changes are lost
        Log.d(TAG, "Dialog cancelled - staged changes discarded")
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // When dialog is dismissed without saving, changes are lost
        Log.d(TAG, "Dialog dismissed - staged changes discarded")
    }

    fun updateAvailableTags(newTags: List<String>) {
        availableTags = newTags.toList()
        if (::editableMediaAdapter.isInitialized) {
            editableMediaAdapter.updateAvailableTags(availableTags)
        }
    }
}