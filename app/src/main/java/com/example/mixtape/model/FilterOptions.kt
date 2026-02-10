package com.example.mixtape.model

data class FilterOptions(
    var titleFilter: String = "",
    var artistFilter: String = "",
    var albumFilter: String = "",
    var tagFilter: String = "",
    var minDuration: Int = 0, // seconds
    var maxDuration: Int = Int.MAX_VALUE // seconds
) {
    fun isEmpty(): Boolean {
        return titleFilter.isBlank() && 
               artistFilter.isBlank() && 
               albumFilter.isBlank() && 
               tagFilter.isBlank() && 
               minDuration == 0 && 
               maxDuration == Int.MAX_VALUE
    }
}
