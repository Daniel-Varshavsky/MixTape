package com.example.mixtape.model

data class SharedPlaylist(
    val id: String = "", // Unique ID for this sharing instance
    val originalPlaylistId: String = "", // Reference to the original playlist
    val recipientUserId: String = "", // Who received this playlist
    val senderUserId: String = "", // Who shared this playlist
    val senderName: String = "", // Display name of sender
    
    // Sharing metadata
    val shareMethod: ShareMethod = ShareMethod.DIRECT, // How was this shared?
    val shareCode: String = "", // If shared via code
    val shareMessage: String = "", // Optional message from sender
    
    // Recipient actions
    val hasBeenViewed: Boolean = false, // Has recipient opened it?
    val hasBeenCopied: Boolean = false, // Did recipient copy to their own playlists?
    val copiedPlaylistId: String = "", // If copied, reference to the new playlist
    
    // Timestamps
    val sharedAt: com.google.firebase.Timestamp? = null,
    val viewedAt: com.google.firebase.Timestamp? = null,
    val copiedAt: com.google.firebase.Timestamp? = null
)
