package com.example.mixtape.model

enum class ChangeAction {
    DELETE_COMPLETELY,     // Delete file from Storage and all playlists
    REMOVE_FROM_PLAYLIST,  // Just remove from this playlist (keep file)
    UPDATE_TAGS           // Update tags
}
