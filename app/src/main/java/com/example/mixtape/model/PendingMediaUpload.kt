package com.example.mixtape.model

import android.net.Uri

/**
 * Represents a media item that has been selected from the device's local storage 
 * but has not yet been uploaded to Firebase.
 * 
 * This model holds the temporary [Uri] and metadata extracted by the 
 * MediaMetadataExtractor, allowing the UI to display the item in the 
 * "staged" state before the user commits the upload.
 */
data class PendingMediaUpload(
    /** A temporary UUID used to track this item in the UI before it gets a Firestore ID. */
    val tempId: String, 
    
    /** The local content URI of the file on the device. */
    val uri: Uri, 
    
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val tags: MutableList<String>,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isAudio: Boolean,
    val isVideo: Boolean
)
