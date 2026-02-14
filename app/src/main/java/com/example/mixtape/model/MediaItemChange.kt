package com.example.mixtape.model

// Represents changes to existing media items
data class MediaItemChange(
    val mediaItemId: String, // Existing media item ID
    val action: ChangeAction,
    val newTags: List<String>? = null // For tag updates
)
