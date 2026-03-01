package com.example.mixtape.utils

import android.util.Log
import com.example.mixtape.model.Video
import com.example.mixtape.model.Song
import java.net.URL
import java.net.HttpURLConnection

/**
 * Utility class for debugging Firebase Storage URL issues.
 * Use this to validate and test your Firebase URLs before attempting playback.
 */
object FirebaseUrlDebugger {
    private const val TAG = "FirebaseUrlDebugger"

    /**
     * Comprehensive validation of a Firebase Storage URL
     */
    fun validateFirebaseUrl(url: String, mediaTitle: String): ValidationResult {
        Log.d(TAG, "=== Validating URL for '$mediaTitle' ===")
        Log.d(TAG, "URL: '$url'")

        // Check 1: Empty or null
        if (url.isBlank()) {
            Log.e(TAG, "❌ URL is empty or blank")
            return ValidationResult.Invalid("URL is empty")
        }

        // Check 2: Proper protocol
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            Log.e(TAG, "❌ URL doesn't start with http:// or https://")
            return ValidationResult.Invalid("Invalid protocol: ${url.substring(0, minOf(50, url.length))}")
        }

        // Check 3: Firebase Storage domain
        if (!url.contains("firebasestorage.googleapis.com")) {
            Log.w(TAG, "⚠️  URL is not from Firebase Storage")
            // Not necessarily invalid, but worth noting
        }

        // Check 4: URL structure
        try {
            val urlObj = URL(url)
            Log.d(TAG, "✅ URL structure is valid")
            Log.d(TAG, "   Host: ${urlObj.host}")
            Log.d(TAG, "   Path: ${urlObj.path}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ URL structure is malformed: ${e.message}")
            return ValidationResult.Invalid("Malformed URL: ${e.message}")
        }

        // Check 5: Network connectivity test (optional - can be slow)
        Log.d(TAG, "✅ URL appears valid")
        return ValidationResult.Valid
    }

    /**
     * Test network connectivity to a Firebase URL (use sparingly - makes network call)
     */
    fun testUrlConnectivity(url: String): ConnectivityResult {
        return try {
            Log.d(TAG, "Testing network connectivity to: $url")
            val urlConnection = URL(url).openConnection() as HttpURLConnection
            urlConnection.requestMethod = "HEAD" // Just check if URL exists
            urlConnection.connectTimeout = 10000 // 10 seconds
            urlConnection.readTimeout = 10000

            val responseCode = urlConnection.responseCode
            urlConnection.disconnect()

            when (responseCode) {
                200 -> {
                    Log.d(TAG, "✅ URL is accessible (HTTP 200)")
                    ConnectivityResult.Success
                }
                403 -> {
                    Log.e(TAG, "❌ URL access denied (HTTP 403) - URL may be expired")
                    ConnectivityResult.Error("Access denied - URL may be expired")
                }
                404 -> {
                    Log.e(TAG, "❌ URL not found (HTTP 404)")
                    ConnectivityResult.Error("File not found")
                }
                else -> {
                    Log.e(TAG, "❌ Unexpected response code: $responseCode")
                    ConnectivityResult.Error("HTTP $responseCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error: ${e.message}")
            ConnectivityResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Debug all videos in a playlist
     */
    fun debugVideoPlaylist(videos: List<Video>) {
        Log.d(TAG, "\n" + "=".repeat(50))
        Log.d(TAG, "🎬 DEBUGGING ${videos.size} VIDEOS")
        Log.d(TAG, "=".repeat(50))

        videos.forEachIndexed { index, video ->
            Log.d(TAG, "\n📹 Video $index: '${video.title}'")
            Log.d(TAG, "   ID: ${video.id}")
            Log.d(TAG, "   Artist: ${video.artist}")

            val result = validateFirebaseUrl(video.storageUrl, video.title)
            when (result) {
                is ValidationResult.Valid -> Log.d(TAG, "   ✅ URL appears valid")
                is ValidationResult.Invalid -> Log.e(TAG, "   ❌ ${result.reason}")
            }
        }

        val validUrls = videos.count { video -> validateFirebaseUrl(video.storageUrl, video.title) is ValidationResult.Valid }
        val invalidUrls = videos.size - validUrls

        Log.d(TAG, "\n📊 SUMMARY:")
        Log.d(TAG, "   ✅ Valid URLs: $validUrls")
        Log.d(TAG, "   ❌ Invalid URLs: $invalidUrls")
        Log.d(TAG, "=".repeat(50) + "\n")
    }

    /**
     * Debug all songs in a playlist
     */
    fun debugSongPlaylist(songs: List<Song>) {
        Log.d(TAG, "\n" + "=".repeat(50))
        Log.d(TAG, "🎵 DEBUGGING ${songs.size} SONGS")
        Log.d(TAG, "=".repeat(50))

        songs.forEachIndexed { index, song ->
            Log.d(TAG, "\n🎵 Song $index: '${song.title}'")
            Log.d(TAG, "   ID: ${song.id}")
            Log.d(TAG, "   Artist: ${song.artist}")

            val result = validateFirebaseUrl(song.storageUrl, song.title)
            when (result) {
                is ValidationResult.Valid -> Log.d(TAG, "   ✅ URL appears valid")
                is ValidationResult.Invalid -> Log.e(TAG, "   ❌ ${result.reason}")
            }
        }

        val validUrls = songs.count { song -> validateFirebaseUrl(song.storageUrl, song.title) is ValidationResult.Valid }
        val invalidUrls = songs.size - validUrls

        Log.d(TAG, "\n📊 SUMMARY:")
        Log.d(TAG, "   ✅ Valid URLs: $validUrls")
        Log.d(TAG, "   ❌ Invalid URLs: $invalidUrls")
        Log.d(TAG, "=".repeat(50) + "\n")
    }

    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    sealed class ConnectivityResult {
        object Success : ConnectivityResult()
        data class Error(val reason: String) : ConnectivityResult()
    }
}