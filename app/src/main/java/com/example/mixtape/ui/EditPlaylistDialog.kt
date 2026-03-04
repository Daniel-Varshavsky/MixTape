package com.example.mixtape.ui

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
import com.example.mixtape.R
import java.util.UUID

/**
 * EditPlaylistDialog allows users to modify an existing playlist. 
 * Features include:
 * - Adding new audio/video files via a system file picker.
 * - Removing existing items (either deleting completely if the user is the owner, or just unlinking).
 * - Modifying tags for individual media items.
 * - Staging all changes locally and applying them in bulk to Firebase.
 */
class EditPlaylistDialog : DialogFragment() {

    private var playlistName: String = ""
    private var playlistId: String = ""
    private var originalOwnerId: String = "" 
    private var originalMediaItems: MutableList<MediaItem> = mutableListOf()
    private var displayedItems: MutableList<MediaItem> = mutableListOf()
    private var availableTags: List<String> = emptyList()
    
    private lateinit var editableMediaAdapter: EditableMediaAdapter
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var repository: FirebaseRepository
    private lateinit var btnAddContent: MaterialButton
    private lateinit var btnSaveChanges: MaterialButton

    // Holds all pending modifications until 'Save Changes' is pressed
    private val stagedChanges = StagedChanges()

    private var onPlaylistUpdated: (() -> Unit)? = null

    companion object {
        private const val TAG = "EditPlaylistDialog"

        /**
         * Creates a new instance of the dialog with the necessary playlist metadata.
         * Sorts items by their creation time before displaying.
         */
        fun newInstance(
            playlistId: String,
            playlistName: String,
            originalOwnerId: String,
            mediaItems: List<MediaItem>,
            availableTags: List<String>,
            onPlaylistUpdated: (() -> Unit)? = null
        ): EditPlaylistDialog {
            val fragment = EditPlaylistDialog()
            val args = Bundle()
            args.putString("PLAYLIST_ID", playlistId)
            args.putString("PLAYLIST_NAME", playlistName)
            args.putString("ORIGINAL_OWNER_ID", originalOwnerId)
            fragment.arguments = args

            val sortedMediaItems = mediaItems.sortedBy { item ->
                when (item) {
                    is MediaItem.SongItem -> item.song.createdAt?.toDate()?.time ?: 0L
                    is MediaItem.VideoItem -> item.video.createdAt?.toDate()?.time ?: 0L
                }
            }

            fragment.originalMediaItems.addAll(sortedMediaItems)
            fragment.displayedItems.addAll(sortedMediaItems)
            fragment.availableTags = availableTags.toList()
            fragment.onPlaylistUpdated = onPlaylistUpdated
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = FirebaseRepository()

        // Initialize the activity result launcher for file selection
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

        // Configure dialog sizing (90% width, 80% height)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.8).toInt()
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        playlistId = arguments?.getString("PLAYLIST_ID") ?: ""
        playlistName = arguments?.getString("PLAYLIST_NAME") ?: "Playlist"
        originalOwnerId = arguments?.getString("ORIGINAL_OWNER_ID") ?: ""

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

        itemsRecycler.layoutManager = LinearLayoutManager(requireContext())
        editableMediaAdapter = EditableMediaAdapter(
            displayedItems, 
            availableTags,
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

    /**
     * Launches the system file picker to select audio and video files.
     */
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

    /**
     * Processes files returned from the system picker and prepares them for staging.
     */
    private fun handleSelectedFiles(data: Intent?) {
        if (data == null) return
        val selectedFiles = mutableListOf<Uri>()

        data.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                selectedFiles.add(clipData.getItemAt(i).uri)
            }
        } ?: data.data?.let {
            selectedFiles.add(it)
        }

        if (selectedFiles.isEmpty()) {
            Toast.makeText(requireContext(), "No files selected", Toast.LENGTH_SHORT).show()
            return
        }

        stageFilesForUpload(selectedFiles)
    }

    /**
     * Extracts metadata from selected URIs and creates pending upload objects.
     */
    private fun stageFilesForUpload(uris: List<Uri>) {
        var successCount = 0
        var failureCount = 0

        for (uri in uris) {
            try {
                val mimeType = requireContext().contentResolver.getType(uri) ?: ""
                when {
                    mimeType.startsWith("audio/") -> {
                        val songTemplate = MediaMetadataExtractor.extractSong(requireContext(), uri)
                        if (songTemplate != null) {
                            stagePendingUpload(uri, songTemplate, true)
                            successCount++
                        } else { failureCount++ }
                    }
                    mimeType.startsWith("video/") -> {
                        val videoTemplate = MediaMetadataExtractor.extractVideo(requireContext(), uri)
                        if (videoTemplate != null) {
                            stagePendingUpload(uri, videoTemplate, false)
                            successCount++
                        } else { failureCount++ }
                    }
                    else -> failureCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file $uri: ${e.message}", e)
                failureCount++
            }
        }

        val message = when {
            successCount > 0 && failureCount == 0 -> "$successCount file(s) staged"
            successCount > 0 && failureCount > 0 -> "$successCount staged, $failureCount failed"
            else -> "No files could be processed"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        updateSaveButton()
    }

    /**
     * Adds an audio track to the local staging list and updates the UI.
     */
    private fun stagePendingUpload(uri: Uri, template: Song, isAudio: Boolean) {
        val tempId = "temp_${UUID.randomUUID()}"
        val pendingUpload = PendingMediaUpload(
            tempId = tempId, uri = uri, title = template.title,
            artist = template.artist, album = template.album,
            durationSeconds = template.durationSeconds, tags = template.tags,
            fileName = getFileName(uri), fileSize = template.fileSize,
            mimeType = template.mimeType, isAudio = isAudio, isVideo = !isAudio
        )

        stagedChanges.pendingUploads.add(pendingUpload)

        val tempMediaItem = if (isAudio) {
            MediaItem.SongItem(Song(id = tempId, title = template.title, artist = template.artist,
                album = template.album, durationSeconds = template.durationSeconds, tags = template.tags))
        } else {
            MediaItem.VideoItem(Video(id = tempId, title = template.title, artist = template.artist,
                album = template.album, durationSeconds = template.durationSeconds, tags = template.tags))
        }

        displayedItems.add(tempMediaItem)
        editableMediaAdapter.notifyItemInserted(displayedItems.size - 1)
    }

    /**
     * Adds a video to the local staging list and updates the UI.
     */
    private fun stagePendingUpload(uri: Uri, template: Video, isAudio: Boolean) {
        val tempId = "temp_${UUID.randomUUID()}"
        val pendingUpload = PendingMediaUpload(
            tempId = tempId, uri = uri, title = template.title,
            artist = template.artist, album = template.album,
            durationSeconds = template.durationSeconds, tags = template.tags,
            fileName = getFileName(uri), fileSize = template.fileSize,
            mimeType = template.mimeType, isAudio = isAudio, isVideo = !isAudio
        )

        stagedChanges.pendingUploads.add(pendingUpload)

        val tempMediaItem = MediaItem.VideoItem(Video(id = tempId, title = template.title, 
            artist = template.artist, album = template.album, durationSeconds = template.durationSeconds, 
            tags = template.tags))

        displayedItems.add(tempMediaItem)
        editableMediaAdapter.notifyItemInserted(displayedItems.size - 1)
    }

    /**
     * Marks an item for removal. If it's a new (staged) item, it's removed immediately.
     * If it's an existing item, the action depends on whether the user is the owner.
     */
    private fun stageItemRemoval(item: MediaItem) {
        if (item.id.startsWith("temp_")) {
            stagedChanges.pendingUploads.removeAll { it.tempId == item.id }
            val position = displayedItems.indexOf(item)
            if (position != -1) {
                displayedItems.removeAt(position)
                editableMediaAdapter.notifyItemRemoved(position)
            }
        } else {
            val isOriginalOwner = repository.getCurrentUserId() == originalOwnerId
            val action = if (isOriginalOwner) ChangeAction.DELETE_COMPLETELY else ChangeAction.REMOVE_FROM_PLAYLIST

            stagedChanges.mediaItemChanges.add(MediaItemChange(item.id, action))

            val position = displayedItems.indexOf(item)
            if (position != -1) {
                displayedItems.removeAt(position)
                editableMediaAdapter.notifyItemRemoved(position)
            }

            val msg = if (isOriginalOwner) "Will be deleted from cloud" else "Will be removed from playlist"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
        updateSaveButton()
    }

    /**
     * Updates the staged tags for an existing media item.
     */
    private fun stageTagUpdate(item: MediaItem, newTags: List<String>) {
        if (!item.id.startsWith("temp_")) {
            stagedChanges.mediaItemChanges.removeAll {
                it.mediaItemId == item.id && it.action == ChangeAction.UPDATE_TAGS
            }
            stagedChanges.mediaItemChanges.add(MediaItemChange(item.id, ChangeAction.UPDATE_TAGS, newTags))
            updateSaveButton()
        } else {
            stagedChanges.pendingUploads.find { it.tempId == item.id }?.let {
                it.tags.clear()
                it.tags.addAll(newTags)
            }
        }
    }

    /**
     * Executes all staged changes sequentially and updates Firebase.
     */
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

                // 1. Process New Uploads
                for (pendingUpload in stagedChanges.pendingUploads) {
                    if (uploadPendingMedia(pendingUpload)) successCount++ else failureCount++
                }

                // 2. Process Metadata/Structural Changes
                for (change in stagedChanges.mediaItemChanges) {
                    val result = when (change.action) {
                        ChangeAction.DELETE_COMPLETELY -> deleteItemCompletely(change.mediaItemId)
                        ChangeAction.REMOVE_FROM_PLAYLIST -> removeItemFromPlaylist(change.mediaItemId)
                        ChangeAction.UPDATE_TAGS -> updateExistingItemTags(change.mediaItemId, change.newTags!!)
                    }
                    if (result) successCount++ else failureCount++
                }

                activity?.runOnUiThread {
                    if (successCount > 0) {
                        onPlaylistUpdated?.invoke()
                        dismiss()
                    } else {
                        btnSaveChanges.isEnabled = true
                        btnSaveChanges.text = "Save Changes"
                        btnAddContent.isEnabled = true
                        Toast.makeText(requireContext(), "Failed to save changes", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving: ${e.message}")
            }
        }
    }

    private suspend fun uploadPendingMedia(pendingUpload: PendingMediaUpload): Boolean {
        return if (pendingUpload.isAudio) {
            repository.uploadSong(pendingUpload.uri, pendingUpload.title, pendingUpload.artist, 
                pendingUpload.album, pendingUpload.durationSeconds, pendingUpload.tags)
                .fold(onSuccess = { repository.addSongToPlaylist(playlistId, it.id).isSuccess }, onFailure = { false })
        } else {
            repository.uploadVideo(pendingUpload.uri, pendingUpload.title, pendingUpload.artist, 
                pendingUpload.album, pendingUpload.durationSeconds, pendingUpload.tags)
                .fold(onSuccess = { repository.addVideoToPlaylist(playlistId, it.id).isSuccess }, onFailure = { false })
        }
    }

    private suspend fun deleteItemCompletely(itemId: String): Boolean {
        val originalItem = originalMediaItems.find { it.id == itemId } ?: return false
        return when (originalItem) {
            is MediaItem.SongItem -> repository.deleteSongCompletely(itemId).isSuccess
            is MediaItem.VideoItem -> repository.deleteVideoCompletely(itemId).isSuccess
        }
    }

    private suspend fun removeItemFromPlaylist(itemId: String): Boolean {
        val originalItem = originalMediaItems.find { it.id == itemId } ?: return false
        return when (originalItem) {
            is MediaItem.SongItem -> repository.removeSongFromPlaylist(playlistId, itemId).isSuccess
            is MediaItem.VideoItem -> repository.removeVideoFromPlaylist(playlistId, itemId).isSuccess
        }
    }

    private suspend fun updateExistingItemTags(itemId: String, newTags: List<String>): Boolean {
        val originalItem = originalMediaItems.find { it.id == itemId } ?: return false
        return when (originalItem) {
            is MediaItem.SongItem -> repository.updateSongTags(itemId, newTags).isSuccess
            is MediaItem.VideoItem -> repository.updateVideoTags(itemId, newTags).isSuccess
        }
    }

    private fun updateSaveButton() {
        val hasChanges = stagedChanges.hasChanges()
        btnSaveChanges.isEnabled = hasChanges
        btnSaveChanges.text = if (hasChanges) "Save Changes (${stagedChanges.getChangeCount()})" else "Save Changes"
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "Unknown"
        try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx != -1) fileName = it.getString(idx) ?: "Unknown"
                }
            }
        } catch (e: Exception) { }
        return fileName
    }

    override fun onCancel(dialog: DialogInterface) { super.onCancel(dialog) }
    override fun onDismiss(dialog: DialogInterface) { super.onDismiss(dialog) }

    fun updateAvailableTags(newTags: List<String>) {
        availableTags = newTags.toList()
        if (::editableMediaAdapter.isInitialized) editableMediaAdapter.updateAvailableTags(availableTags)
    }
}
