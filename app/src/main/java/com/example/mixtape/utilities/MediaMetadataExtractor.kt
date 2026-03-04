package com.example.mixtape.utilities

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.mixtape.model.Song
import com.example.mixtape.model.Video

/**
 * Utility for extracting metadata (title, artist, album, duration, etc.) from local media files.
 * Uses MediaMetadataRetriever to parse file headers before uploading to Firebase.
 */
object MediaMetadataExtractor {

    private const val TAG = "MediaMetadataExtractor"

    /**
     * Extracts metadata from an audio file and maps it to a Song model.
     * Also attempts to extract and clean the 'Genre' tag to use as an initial tag.
     */
    fun extractSong(context: Context, uri: Uri): Song? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""

            if (!mimeType.startsWith("audio/")) {
                Log.w(TAG, "File is not audio: $mimeType")
                return null
            }

            val fileName = getFileName(context, uri)
            val fileSize = getFileSize(context, uri)

            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(context, uri)

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: fileName.substringBeforeLast(".")

                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "Unknown Artist"

                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?: "Unknown Album"

                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = ((durationStr?.toLongOrNull() ?: 0L) / 1000).toInt() // Convert ms to seconds

                // Extract genre and add to tags for better discoverability
                val tags = mutableListOf<String>()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                if (!genre.isNullOrBlank()) {
                    val cleanGenre = cleanGenre(genre)
                    if (cleanGenre.isNotBlank()) {
                        tags.add(cleanGenre)
                        Log.d(TAG, "Found genre for song: $cleanGenre")
                    }
                }

                Song(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = duration,
                    tags = tags,
                    fileSize = fileSize,
                    mimeType = mimeType
                )

            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing retriever: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting song metadata: ${e.message}", e)
            null
        }
    }

    /**
     * Extracts metadata from a video file and maps it to a Video model.
     */
    fun extractVideo(context: Context, uri: Uri): Video? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""

            if (!mimeType.startsWith("video/")) {
                Log.w(TAG, "File is not video: $mimeType")
                return null
            }

            val fileName = getFileName(context, uri)
            val fileSize = getFileSize(context, uri)

            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(context, uri)

                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: fileName.substringBeforeLast(".")

                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "Unknown Artist"

                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?: "Unknown Album"

                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = ((durationStr?.toLongOrNull() ?: 0L) / 1000).toInt() // Convert ms to seconds

                val tags = mutableListOf<String>()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                if (!genre.isNullOrBlank()) {
                    val cleanGenre = cleanGenre(genre)
                    if (cleanGenre.isNotBlank()) {
                        tags.add(cleanGenre)
                        Log.d(TAG, "Found genre for video: $cleanGenre")
                    }
                }

                Video(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = duration,
                    tags = tags,
                    fileSize = fileSize,
                    mimeType = mimeType
                )

            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing retriever: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting video metadata: ${e.message}", e)
            null
        }
    }

    /**
     * Sanitizes genre strings. Handles ID3v1 numeric genres (e.g., "(32)") 
     * and removes extra white spaces or formatting issues.
     */
    private fun cleanGenre(genre: String): String {
        return genre
            .trim()
            .removePrefix("(")
            .removeSuffix(")")
            .replace(Regex("^\\d+\\s*"), "") // Remove leading numbers
            .replace(Regex("\\s*\\(\\d+\\)"), "") // Remove trailing numbered parentheses
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var fileName = "Unknown File"
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = it.getString(nameIndex) ?: "Unknown File"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting file name: ${e.message}")
        }
        return fileName
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        var fileSize = 0L
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (sizeIndex != -1) {
                        fileSize = it.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting file size: ${e.message}")
        }
        return fileSize
    }
}
