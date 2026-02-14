package com.example.mixtape.model

import android.net.Uri

// Represents a pending upload that hasn't been saved to Firebase yet
data class PendingMediaUpload(
    val tempId: String, // Temporary ID for UI purposes
    val uri: Uri, // File URI
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
