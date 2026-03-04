package com.example.mixtape.model

/**
 * Data model for a Playlist, representing a collection of media items in Firestore.
 * 
 * This model includes both user-defined data (name, description) and cloud-managed 
 * metadata (share codes, owner IDs). It tracks both the current owner and the 
 * original creator to support the "shared copy" functionality.
 */
data class Playlist(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerId: String = "",
    
    /** Tracks the user who first created this playlist before it was shared/copied. */
    val originalOwnerId: String = "", 
    
    /** List of document IDs for songs belonging to this playlist. */
    val songIds: List<String> = emptyList(),
    
    /** List of document IDs for videos belonging to this playlist. */
    val videoIds: List<String> = emptyList(),
    
    val playlistTags: List<String> = emptyList(),
    val sharedWithUsers: List<String> = emptyList(),
    
    /** A unique 6-character code used for joining this playlist. */
    val shareCode: String = "",
    
    val allowCopying: Boolean = true,
    val ownerVisible: Boolean = true,

    // Aggregated counts (cached in Firestore to avoid expensive client-side joins)
    val songs: Int = 0,
    val videos: Int = 0,
    val totalItems: Int = 0,
    val shareCount: Int = 0,
    val shared: Boolean = false,

    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,
    val lastSharedAt: com.google.firebase.Timestamp? = null
) {
    /** Required no-argument constructor for Firestore deserialization. */
    constructor() : this(
        id = "",
        name = "",
        description = "",
        ownerId = "",
        originalOwnerId = "",
        songIds = emptyList(),
        videoIds = emptyList(),
        playlistTags = emptyList(),
        sharedWithUsers = emptyList(),
        shareCode = "",
        allowCopying = true,
        ownerVisible = true,
        songs = 0,
        videos = 0,
        totalItems = 0,
        shareCount = 0,
        shared = false,
        createdAt = null,
        updatedAt = null,
        lastSharedAt = null
    )

    fun isSharedWith(userId: String): Boolean = sharedWithUsers.contains(userId)
    fun hasShares(): Boolean = shared

    /**
     * Determines if this playlist is a copy shared with the current user.
     */
    fun isSharedTo(currentUserId: String): Boolean {
        return originalOwnerId.isNotEmpty() && originalOwnerId != currentUserId
    }
}
