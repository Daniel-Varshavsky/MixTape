package com.example.mixtape.model

/**
 * A sealed class representing the core polymorphic media type in the application.
 * 
 * By using a sealed class, we ensure type safety when handling different media types 
 * (Songs vs Videos) within the same RecyclerView or playback queue. This allows the 
 * UI to remain agnostic of the specific media implementation while still accessing 
 * shared properties like title, artist, and tags.
 */
sealed class MediaItem {
    abstract val id: String 
    abstract val title: String
    abstract val artist: String
    abstract val album: String
    abstract val durationSeconds: Int
    abstract val tags: MutableList<String>

    abstract fun getDurationFormatted(): String

    /**
     * Wrapper for the Song data model to conform to the MediaItem interface.
     */
    data class SongItem(val song: Song) : MediaItem() {
        override val id: String get() = song.id
        override val title: String get() = song.title
        override val artist: String get() = song.artist
        override val album: String get() = song.album
        override val durationSeconds: Int get() = song.durationSeconds
        override val tags: MutableList<String> get() = song.tags

        override fun getDurationFormatted(): String = song.durationFormatted.ifEmpty {
            song.formatDuration()
        }
    }

    /**
     * Wrapper for the Video data model to conform to the MediaItem interface.
     */
    data class VideoItem(val video: Video) : MediaItem() {
        override val id: String get() = video.id
        override val title: String get() = video.title
        override val artist: String get() = video.artist
        override val album: String get() = video.album
        override val durationSeconds: Int get() = video.durationSeconds
        override val tags: MutableList<String> get() = video.tags

        override fun getDurationFormatted(): String = video.durationFormatted.ifEmpty {
            video.formatDuration()
        }
    }
}
