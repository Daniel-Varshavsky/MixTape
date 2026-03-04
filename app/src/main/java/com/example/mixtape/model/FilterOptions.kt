package com.example.mixtape.model

/**
 * Data class encapsulating the filtering criteria for the media library.
 * 
 * Used by the sidebar to filter the main media list by text fields (title, artist, album), 
 * tags, or duration ranges.
 */
data class FilterOptions(
    var titleFilter: String = "",
    var artistFilter: String = "",
    var albumFilter: String = "",
    var tagFilter: String = "",
    var minDuration: Int = 0, // in seconds
    var maxDuration: Int = Int.MAX_VALUE // in seconds
) {
    /** 
     * Returns true if no filters are currently active, 
     * indicating that the full library should be displayed. 
     */
    fun isEmpty(): Boolean {
        return titleFilter.isBlank() && 
               artistFilter.isBlank() && 
               albumFilter.isBlank() && 
               tagFilter.isBlank() && 
               minDuration == 0 && 
               maxDuration == Int.MAX_VALUE
    }
}
