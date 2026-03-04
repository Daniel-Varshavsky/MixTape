package com.example.mixtape.model

/**
 * Data model for a video file stored in Firestore.
 * 
 * Similar to the Song model, it tracks storage paths for both the video file 
 * and its generated thumbnail. It also stores pre-formatted strings for 
 * duration and file size to ensure smooth scrolling in lists.
 */
data class Video(
    val id: String = "", 
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val tags: MutableList<String> = mutableListOf(),

    // --- Firebase Storage References ---
    /** The download URL for the video file. */
    val storageUrl: String = "", 
    
    /** The internal Storage path for the video (e.g., "users/uid/videos/id.mp4"). */
    val storagePath: String = "", 
    
    /** The download URL for the video's preview thumbnail. */
    val thumbnailUrl: String = "", 
    
    /** The internal Storage path for the thumbnail image. */
    val thumbnailPath: String = "", 

    // --- Media Metadata ---
    val uploadedBy: String = "", 
    val fileSize: Long = 0, 
    val mimeType: String = "video/mp4", 
    val resolution: String = "", 
    val isPublic: Boolean = false, 

    // --- Timestamps ---
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,

    // --- Display Helpers (Stored in Cloud) ---
    val durationFormatted: String = "",
    val fileSizeFormatted: String = ""
) {
    /** Required no-argument constructor for Firestore. */
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

    /**
     * Fallback formatter for duration.
     */
    fun formatDuration(): String {
        return durationFormatted.ifEmpty {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    /**
     * Fallback formatter for file size, supporting KB, MB, and GB.
     */
    fun formatFileSize(): String {
        return fileSizeFormatted.ifEmpty {
            when {
                fileSize < 1024 -> "${fileSize} B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
                fileSize < 1024 * 1024 * 1024 -> "${"%.1f".format(fileSize / (1024.0 * 1024.0))} MB"
                else -> "${"%.1f".format(fileSize / (1024.0 * 1024.0 * 1024.0))} GB"
            }
        }
    }
}
