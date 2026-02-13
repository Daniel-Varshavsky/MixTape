package com.example.mixtape.model

data class Playlist(
    val id: String = "", // Firestore document ID
    val name: String,
    val description: String = "",
    val ownerId: String = "", // User ID who created the playlist
    val songIds: List<String> = emptyList(), // References to Song documents
    val videoIds: List<String> = emptyList(), // References to Video documents

    // Playlist-specific tags (for categorizing the playlist itself)
    val playlistTags: List<String> = emptyList(), // e.g., "workout", "party", "chill"

    // Sharing system - private by default with selective sharing
    val sharedWithUsers: List<String> = emptyList(), // User IDs who have access to this playlist
    val shareCode: String = "", // Optional: unique code for easy sharing
    val allowCopying: Boolean = true, // Can shared users make their own copy?
    val isOwnerVisible: Boolean = true, // Should recipients see who shared it?

    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,
    val lastSharedAt: com.google.firebase.Timestamp? = null // Track when it was last shared
) {
    // Computed properties for UI compatibility
    val songs: Int get() = songIds.size
    val videos: Int get() = videoIds.size
    val totalItems: Int get() = songs + videos

    // Helper methods for sharing
    fun isSharedWith(userId: String): Boolean = sharedWithUsers.contains(userId)
    fun isShared(): Boolean = sharedWithUsers.isNotEmpty() || shareCode.isNotEmpty()
    fun getShareCount(): Int = sharedWithUsers.size
}