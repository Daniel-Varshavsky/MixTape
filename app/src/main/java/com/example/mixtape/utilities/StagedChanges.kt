package com.example.mixtape.utilities

import com.example.mixtape.model.ChangeAction
import com.example.mixtape.model.MediaItemChange
import com.example.mixtape.model.PendingMediaUpload

/**
 * A data container used to implement a "staging" pattern for playlist edits.
 * 
 * Instead of applying changes to Firebase immediately as the user interacts with the UI, 
 * changes are collected here. This allows the user to undo or cancel their session 
 * without triggering unnecessary network calls or leaving the database in a partial state.
 */
data class StagedChanges(
    /** Items that have been picked from local storage but not yet uploaded to Firebase. */
    val pendingUploads: MutableList<PendingMediaUpload> = mutableListOf(),
    
    /** 
     * Modifications to existing cloud items (e.g., tag updates or complete deletions).
     * This follows a command-like pattern to track intended side effects.
     */
    val mediaItemChanges: MutableList<MediaItemChange> = mutableListOf()
) {
    /** Checks if there are no modifications staged. */
    fun isEmpty(): Boolean {
        return pendingUploads.isEmpty() && mediaItemChanges.isEmpty()
    }

    /** Returns true if the user has performed any actions that require a save. */
    fun hasChanges(): Boolean = !isEmpty()

    /** Returns the total count of distinct operations pending. */
    fun getChangeCount(): Int {
        return pendingUploads.size + mediaItemChanges.size
    }

    /**
     * Generates a human-readable summary of the staged changes.
     * Useful for confirmation dialogs or debug logging.
     */
    fun getDescription(): String {
        val parts = mutableListOf<String>()

        if (pendingUploads.isNotEmpty()) {
            parts.add("${pendingUploads.size} upload(s)")
        }

        val completeDeletions = mediaItemChanges.count { it.action == ChangeAction.DELETE_COMPLETELY }
        if (completeDeletions > 0) {
            parts.add("$completeDeletions complete deletion(s)")
        }

        val tagUpdates = mediaItemChanges.count { it.action == ChangeAction.UPDATE_TAGS }
        if (tagUpdates > 0) {
            parts.add("$tagUpdates tag update(s)")
        }

        return if (parts.isEmpty()) "No changes" else parts.joinToString(", ")
    }
}
