package com.example.mixtape.model

/**
 * Data model for a user's profile in Firestore.
 * 
 * Tracks basic authentication info (email, display name) and user-specific 
 * application state, such as the global list of tags they have created. 
 * This allows for tag auto-completion and filtering across the entire library.
 */
data class UserProfile(
    /** The unique Firebase Auth UID for this user. */
    val id: String = "", 
    
    val email: String = "",
    val displayName: String = "",

    /** 
     * A master list of all unique tags the user has applied to any media item.
     * This is synced automatically when media is uploaded or edited.
     */
    val globalTags: List<String> = emptyList(),

    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null
) {
    /** Required no-argument constructor for Firestore. */
    constructor() : this(
        id = "",
        email = "",
        displayName = "",
        globalTags = emptyList(),
        createdAt = null,
        updatedAt = null
    )
}
