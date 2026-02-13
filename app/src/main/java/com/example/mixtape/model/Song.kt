package com.example.mixtape.model

data class Song(
    val id: String = "", // Firestore document ID
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val tags: MutableList<String> = mutableListOf(),

    // Firebase Storage references
    val storageUrl: String = "", // Firebase Storage download URL
    val storagePath: String = "", // Storage path like "users/userId/songs/songId.mp3"

    // Metadata
    val uploadedBy: String = "", // User ID who uploaded this
    val fileSize: Long = 0, // File size in bytes
    val mimeType: String = "audio/mpeg", // audio/mpeg, audio/wav, etc.
    val isPublic: Boolean = false, // Can other users access this song

    // Timestamps
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null
) {
    fun getDurationFormatted(): String {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    fun getFileSizeFormatted(): String {
        return when {
            fileSize < 1024 -> "${fileSize} B"
            fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
            else -> "${"%.1f".format(fileSize / (1024.0 * 1024.0))} MB"
        }
    }
}