package com.example.mixtape.utilities

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.mixtape.model.Song
import com.example.mixtape.model.Video

object MediaMetadataExtractor {

    private const val TAG = "MediaMetadataExtractor"

    /**
     * Extract metadata and create a Song object from an audio file URI
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

                // Extract genre and add to tags
                val tags = mutableListOf<String>()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                if (!genre.isNullOrBlank()) {
                    // Clean up genre (sometimes it comes with extra info)
                    val cleanGenre = cleanGenre(genre)
                    if (cleanGenre.isNotBlank()) {
                        tags.add(cleanGenre)
                        Log.d(TAG, "Found genre for song: $cleanGenre")
                    }
                }

                Log.d(TAG, "Extracted song: $title by $artist (Genre: ${tags.joinToString()})")

                // Return Song object with metadata (will be given ID when uploaded to Firebase)
                Song(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = duration,
                    tags = tags, // Contains genre if found
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
     * Extract metadata and create a Video object from a video file URI
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

                // Extract genre for videos too (some video formats support this)
                val tags = mutableListOf<String>()
                val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                if (!genre.isNullOrBlank()) {
                    val cleanGenre = cleanGenre(genre)
                    if (cleanGenre.isNotBlank()) {
                        tags.add(cleanGenre)
                        Log.d(TAG, "Found genre for video: $cleanGenre")
                    }
                }

                Log.d(TAG, "Extracted video: $title by $artist (Genre: ${tags.joinToString()})")

                // Return Video object with metadata (will be given ID when uploaded to Firebase)
                Video(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = duration,
                    tags = tags, // Contains genre if found
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
     * Clean up genre string - sometimes it comes with parentheses, numbers, or extra formatting
     */
    private fun cleanGenre(genre: String): String {
        return genre
            .trim()
            .removePrefix("(")
            .removeSuffix(")")
            .replace(Regex("^\\d+\\s*"), "") // Remove leading numbers like "32 Rock" -> "Rock"
            .replace(Regex("\\s*\\(\\d+\\)"), "") // Remove numbered parentheses like "Rock (32)" -> "Rock"
            .trim()
            .lowercase() // Convert to lowercase for consistency
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } // Capitalize first letter
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