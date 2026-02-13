package com.example.mixtape.model

data class UserProfile(
    val id: String = "", // User ID (same as Firebase Auth UID)
    val email: String = "",
    val displayName: String = "",
    
    // Global user tags - all tags this user has ever created
    val globalTags: List<String> = emptyList(), // e.g., ["rock", "classic", "workout", "chill", "party"]
    
    // User preferences
    val defaultPrivacy: Boolean = false, // Default privacy setting for new uploads
    val storageUsed: Long = 0, // Total storage used in bytes
    val maxStorage: Long = 104857600, // Storage limit (100MB default)
    
    // Social features
    val followingUsers: List<String> = emptyList(), // User IDs this user follows
    val followers: List<String> = emptyList(), // User IDs following this user
    val publicPlaylistIds: List<String> = emptyList(), // Playlists this user made public
    
    // Statistics
    val totalSongs: Int = 0,
    val totalVideos: Int = 0,
    val totalPlaylists: Int = 0,
    
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null
) {
    fun getStorageUsedFormatted(): String {
        return when {
            storageUsed < 1024 -> "${storageUsed} B"
            storageUsed < 1024 * 1024 -> "${storageUsed / 1024} KB"
            else -> "${"%.1f".format(storageUsed / (1024.0 * 1024.0))} MB"
        }
    }
    
    fun getStoragePercentageUsed(): Float {
        return (storageUsed.toFloat() / maxStorage.toFloat()) * 100f
    }
    
    fun isStorageFull(): Boolean {
        return storageUsed >= maxStorage
    }
}
