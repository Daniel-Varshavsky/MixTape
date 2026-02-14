package com.example.mixtape.model

data class UserProfile(
    val id: String = "", // User ID (same as Firebase Auth UID)
    val email: String = "",
    val displayName: String = "",

    // Global user tags - all tags this user has ever created
    val globalTags: List<String> = emptyList(), // e.g., ["rock", "classic", "workout", "chill", "party"]

    // Timestamps
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null
) {
    // No-argument constructor for Firestore
    constructor() : this(
        id = "",
        email = "",
        displayName = "",
        globalTags = emptyList(),
        createdAt = null,
        updatedAt = null
    )
}