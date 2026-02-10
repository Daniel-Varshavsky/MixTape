package com.example.mixtape.model

data class Video(
    val id: Int,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val tags: MutableList<String> = mutableListOf()
) {
    fun getDurationFormatted(): String {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
