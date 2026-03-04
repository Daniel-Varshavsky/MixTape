package com.example.mixtape.model

/**
 * Represents a staged modification to an existing media item in the database.
 * 
 * Part of the "Staged Changes" system, this class bundles the item ID with the 
 * intended action and any new data (like updated tags) so it can be applied 
 * in a single batch operation.
 */
data class MediaItemChange(
    /** The unique Firestore document ID of the media item being modified. */
    val mediaItemId: String, 
    
    /** The type of modification to perform (e.g., Tag Update, Deletion). */
    val action: ChangeAction,
    
    /** The new list of tags to be applied if the action is [ChangeAction.UPDATE_TAGS]. */
    val newTags: List<String>? = null 
)
