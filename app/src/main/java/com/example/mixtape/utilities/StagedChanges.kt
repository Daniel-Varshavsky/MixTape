package com.example.mixtape.utilities

import com.example.mixtape.model.ChangeAction
import com.example.mixtape.model.MediaItemChange
import com.example.mixtape.model.PendingMediaUpload

// Container for all staged changes in the edit dialog
// Only contains changes that need to be saved when user clicks "Save Changes"
data class StagedChanges(
    val pendingUploads: MutableList<PendingMediaUpload> = mutableListOf(),
    val mediaItemChanges: MutableList<MediaItemChange> = mutableListOf()
) {
    fun isEmpty(): Boolean {
        return pendingUploads.isEmpty() && mediaItemChanges.isEmpty()
    }

    fun hasChanges(): Boolean = !isEmpty()

    fun getChangeCount(): Int {
        return pendingUploads.size + mediaItemChanges.size
    }

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

        return parts.joinToString(", ")
    }
}