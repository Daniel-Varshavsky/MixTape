package com.example.mixtape.model

/**
 * Defines the criteria for sorting media items in the UI.
 */
enum class SortBy {
    /** The original order in which items were added to the playlist. */
    ENTRY_ORDER, 
    
    /** Alphabetical sorting by the media title. */
    TITLE, 
    
    /** Alphabetical sorting by the artist name. */
    ARTIST, 
    
    /** Alphabetical sorting by the album name. */
    ALBUM
}
