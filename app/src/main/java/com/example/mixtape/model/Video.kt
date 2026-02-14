package com.example.mixtape.model

data class Video(
    val id: String = "", // Firestore document ID
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val tags: MutableList<String> = mutableListOf(),

    // Firebase Storage references
    val storageUrl: String = "", // Firebase Storage download URL
    val storagePath: String = "", // Storage path like "users/userId/videos/videoId.mp4"
    val thumbnailUrl: String = "", // Thumbnail image URL
    val thumbnailPath: String = "", // Thumbnail storage path

    // Video-specific metadata
    val uploadedBy: String = "", // User ID who uploaded this
    val fileSize: Long = 0, // File size in bytes
    val mimeType: String = "video/mp4", // video/mp4, video/webm, etc.
    val resolution: String = "", // "720p", "1080p", etc.
    val isPublic: Boolean = false, // Can other users access this video

    // Timestamps
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,

    // These computed fields are stored in Firestore - use these directly
    val durationFormatted: String = "",
    val fileSizeFormatted: String = ""
) {
    // No-argument constructor for Firestore
    constructor() : this(
        id = "",
        title = "",
        artist = "",
        album = "",
        durationSeconds = 0,
        tags = mutableListOf(),
        storageUrl = "",
        storagePath = "",
        thumbnailUrl = "",
        thumbnailPath = "",
        uploadedBy = "",
        fileSize = 0,
        mimeType = "video/mp4",
        resolution = "",
        isPublic = false,
        createdAt = null,
        updatedAt = null,
        durationFormatted = "",
        fileSizeFormatted = ""
    )

    // Helper methods with idiomatic Kotlin using ifEmpty
    fun formatDuration(): String {
        return durationFormatted.ifEmpty {
            // Fallback calculation if stored value is empty
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    fun formatFileSize(): String {
        return fileSizeFormatted.ifEmpty {
            // Fallback calculation if stored value is empty
            when {
                fileSize < 1024 -> "${fileSize} B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
                fileSize < 1024 * 1024 * 1024 -> "${"%.1f".format(fileSize / (1024.0 * 1024.0))} MB"
                else -> "${"%.1f".format(fileSize / (1024.0 * 1024.0 * 1024.0))} GB"
            }
        }
    }
}