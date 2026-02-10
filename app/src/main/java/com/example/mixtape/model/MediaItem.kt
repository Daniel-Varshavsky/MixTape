package com.example.mixtape.model

sealed class MediaItem {
    abstract val id: Int
    abstract val title: String
    abstract val artist: String
    abstract val album: String
    abstract val durationSeconds: Int
    abstract val tags: MutableList<String>
    
    abstract fun getDurationFormatted(): String
    
    data class SongItem(val song: Song) : MediaItem() {
        override val id: Int get() = song.id
        override val title: String get() = song.title
        override val artist: String get() = song.artist
        override val album: String get() = song.album
        override val durationSeconds: Int get() = song.durationSeconds
        override val tags: MutableList<String> get() = song.tags
        override fun getDurationFormatted(): String = song.getDurationFormatted()
    }
    
    data class VideoItem(val video: Video) : MediaItem() {
        override val id: Int get() = video.id
        override val title: String get() = video.title
        override val artist: String get() = video.artist
        override val album: String get() = video.album
        override val durationSeconds: Int get() = video.durationSeconds
        override val tags: MutableList<String> get() = video.tags
        override fun getDurationFormatted(): String = video.getDurationFormatted()
    }
}
