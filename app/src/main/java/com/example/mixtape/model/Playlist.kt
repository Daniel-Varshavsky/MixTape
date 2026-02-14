package com.example.mixtape.model

data class Playlist(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val ownerId: String = "",
    val songIds: List<String> = emptyList(),
    val videoIds: List<String> = emptyList(),
    val playlistTags: List<String> = emptyList(),
    val sharedWithUsers: List<String> = emptyList(),
    val shareCode: String = "",
    val allowCopying: Boolean = true,
    val ownerVisible: Boolean = true,

    // These are stored as actual fields in your Firestore (not computed)
    val songs: Int = 0,
    val videos: Int = 0,
    val totalItems: Int = 0,
    val shareCount: Int = 0,
    val shared: Boolean = false,

    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,
    val lastSharedAt: com.google.firebase.Timestamp? = null
) {
    // No-argument constructor for Firestore
    constructor() : this(
        id = "",
        name = "",
        description = "",
        ownerId = "",
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

    // Helper methods - use simple names that don't conflict
    fun isSharedWith(userId: String): Boolean = sharedWithUsers.contains(userId)
    fun hasShares(): Boolean = shared
}