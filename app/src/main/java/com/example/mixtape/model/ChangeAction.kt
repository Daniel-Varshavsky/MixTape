package com.example.mixtape.model

/**
 * Defines the types of modifications that can be staged for a media item.
 * 
 * This enum is part of the command-pattern implementation for playlist editing, 
 * allowing the UI to track different intentions before they are committed to Firebase.
 */
enum class ChangeAction {
    /** 
     * Permanent deletion of the media file from Storage and its metadata from all 
     * Firestore collections. Used when a user wants to "forget" a file entirely.
     */
    DELETE_COMPLETELY,

    /** 
     * Removes the reference to a media item from the current playlist but keeps 
     * the physical file and metadata in the user's library.
     */
    REMOVE_FROM_PLAYLIST,

    /** 
     * Indicates that only the descriptive tags associated with the media item 
     * have been modified.
     */
    UPDATE_TAGS
}
