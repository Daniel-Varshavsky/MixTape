package com.example.mixtape.model

/**
 * Data model for an audio track (Song) stored in Firestore.
 * 
 * Contains references to physical files in Firebase Storage and extensive metadata 
 * extracted during the upload process. Uses pre-formatted fields for duration 
 * and file size to ensure UI performance when displaying lists.
 */
data class Song(
    val id: String = "", 
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Int = 0,
    val tags: MutableList<String> = mutableListOf(),

    // --- Firebase Storage References ---
    /** The publicly accessible download URL for the audio file. */
    val storageUrl: String = "", 
    
    /** The internal path in Storage (e.g., "users/uid/songs/id.mp3"). */
    val storagePath: String = "", 

    // --- Media Metadata ---
    val uploadedBy: String = "", 
    val fileSize: Long = 0, 
    val mimeType: String = "audio/mpeg", 
    val isPublic: Boolean = false, 

    // --- Timestamps ---
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,

    // --- Display Helpers (Stored in Cloud) ---
    /** Pre-calculated duration (e.g., "3:45") for immediate UI display. */
    val durationFormatted: String = "",
    
    /** Pre-calculated size (e.g., "4.2 MB") for immediate UI display. */
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
        uploadedBy = "",
        fileSize = 0,
        mimeType = "audio/mpeg",
        isPublic = false,
        createdAt = null,
        updatedAt = null,
        durationFormatted = "",
        fileSizeFormatted = ""
    )

    /**
     * Fallback formatter for duration if the cloud-stored string is missing.
     */
    fun formatDuration(): String {
        return durationFormatted.ifEmpty {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }

    /**
     * Fallback formatter for file size if the cloud-stored string is missing.
     */
    fun formatFileSize(): String {
        return fileSizeFormatted.ifEmpty {
            when {
                fileSize < 1024 -> "${fileSize} B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024} KB"
                else -> "${"%.1f".format(fileSize / (1024.0 * 1024.0))} MB"
            }
        }
    }
}
