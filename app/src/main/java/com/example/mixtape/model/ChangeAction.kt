package com.example.mixtape.model

enum class ChangeAction {
    DELETE_COMPLETELY,     // Delete file from Storage and all playlists (staged)
    UPDATE_TAGS           // Update tags (staged)
    // Note: DELETE_FROM_PLAYLIST removed - happens immediately, not staged
}
