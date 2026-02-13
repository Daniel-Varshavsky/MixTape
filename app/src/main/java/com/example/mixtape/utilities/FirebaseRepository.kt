package com.example.mixtape.repository

import android.net.Uri
import com.example.mixtape.model.Playlist
import com.example.mixtape.model.ShareMethod
import com.example.mixtape.model.Song
import com.example.mixtape.model.Video
import com.example.mixtape.model.UserProfile
import com.example.mixtape.model.SharedPlaylist
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirebaseRepository {
    
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    
    private val currentUserId: String?
        get() = auth.currentUser?.uid
    
    // Storage references
    private fun getUserSongsRef(): StorageReference? {
        return currentUserId?.let { userId ->
            storage.reference.child("users/$userId/songs")
        }
    }
    
    private fun getUserVideosRef(): StorageReference? {
        return currentUserId?.let { userId ->
            storage.reference.child("users/$userId/videos")
        }
    }
    
    // Firestore collections
    private val playlistsCollection = firestore.collection("playlists")
    private val songsCollection = firestore.collection("songs")
    private val videosCollection = firestore.collection("videos")
    private val usersCollection = firestore.collection("users")
    
    // USER PROFILE OPERATIONS
    suspend fun getUserProfile(): Result<UserProfile> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val doc = usersCollection.document(userId).get().await()
            val profile = doc.toObject(UserProfile::class.java)?.copy(id = doc.id)
                ?: throw Exception("User profile not found")
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun updateUserProfile(profile: UserProfile): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            usersCollection.document(userId).set(profile).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // GLOBAL TAG OPERATIONS
    suspend fun addGlobalTag(tag: String): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val userRef = usersCollection.document(userId)
            
            // Add tag to user's global tags if it doesn't exist
            userRef.update(
                mapOf(
                    "globalTags" to FieldValue.arrayUnion(tag),
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun removeGlobalTag(tag: String): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val userRef = usersCollection.document(userId)
            
            // Remove tag from user's global tags
            userRef.update(
                mapOf(
                    "globalTags" to FieldValue.arrayRemove(tag),
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
            
            // TODO: Optionally remove this tag from all user's songs, videos, and playlists
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getGlobalTags(): Result<List<String>> {
        return try {
            val profile = getUserProfile().getOrThrow()
            Result.success(profile.globalTags)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Automatically add tags to global list when they're used
    private suspend fun ensureTagsInGlobal(tags: List<String>) {
        try {
            val userId = currentUserId ?: return
            val userRef = usersCollection.document(userId)
            
            userRef.update(
                mapOf(
                    "globalTags" to FieldValue.arrayUnion(*tags.toTypedArray()),
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
        } catch (e: Exception) {
            // Silently fail - this is not critical
        }
    }
    
    // SONG OPERATIONS
    suspend fun uploadSong(
        fileUri: Uri,
        title: String,
        artist: String,
        album: String,
        duration: Int,
        tags: List<String>
    ): Result<Song> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val songId = UUID.randomUUID().toString()
            val fileName = "${songId}.mp3"
            val storageRef = getUserSongsRef()?.child(fileName) ?: throw Exception("Storage reference failed")
            
            // Upload file to Storage
            val uploadTask = storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await()
            
            // Automatically add tags to global tags
            ensureTagsInGlobal(tags)
            
            // Create song document
            val song = Song(
                id = songId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
                tags = tags.toMutableList(),
                storageUrl = downloadUrl.toString(),
                storagePath = "users/$userId/songs/$fileName",
                uploadedBy = userId,
                fileSize = uploadTask.metadata?.sizeBytes ?: 0,
                mimeType = "audio/mpeg",
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now()
            )
            
            // Save to Firestore
            songsCollection.document(songId).set(song).await()
            
            // Update user statistics
            updateUserStats()
            
            Result.success(song)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // VIDEO OPERATIONS
    suspend fun uploadVideo(
        fileUri: Uri,
        title: String,
        artist: String,
        album: String,
        duration: Int,
        tags: List<String>
    ): Result<Video> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val videoId = UUID.randomUUID().toString()
            val fileName = "${videoId}.mp4"
            val storageRef = getUserVideosRef()?.child(fileName) ?: throw Exception("Storage reference failed")
            
            // Upload file to Storage
            val uploadTask = storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await()
            
            // Automatically add tags to global tags
            ensureTagsInGlobal(tags)
            
            // Create video document
            val video = Video(
                id = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
                tags = tags.toMutableList(),
                storageUrl = downloadUrl.toString(),
                storagePath = "users/$userId/videos/$fileName",
                uploadedBy = userId,
                fileSize = uploadTask.metadata?.sizeBytes ?: 0,
                mimeType = "video/mp4",
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now()
            )
            
            // Save to Firestore
            videosCollection.document(videoId).set(video).await()
            
            // Update user statistics
            updateUserStats()
            
            Result.success(video)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // PLAYLIST OPERATIONS
    suspend fun createPlaylist(
        name: String,
        description: String = "",
        playlistTags: List<String> = emptyList(),
        isPublic: Boolean = false
    ): Result<Playlist> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val playlistId = UUID.randomUUID().toString()
            
            // Add playlist tags to global tags
            ensureTagsInGlobal(playlistTags)
            
            val playlist = Playlist(
                id = playlistId,
                name = name,
                description = description,
                ownerId = userId,
                playlistTags = playlistTags,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now()
            )
            
            playlistsCollection.document(playlistId).set(playlist).await()
            
            // Update user statistics
            updateUserStats()
            
            Result.success(playlist)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getUserPlaylists(): Result<List<Playlist>> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val querySnapshot = playlistsCollection
                .whereEqualTo("ownerId", userId)
                .get()
                .await()
            
            val playlists = querySnapshot.documents.mapNotNull { doc ->
                doc.toObject(Playlist::class.java)?.copy(id = doc.id)
            }
            
            Result.success(playlists)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getUserSongs(): Result<List<Song>> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val querySnapshot = songsCollection
                .whereEqualTo("uploadedBy", userId)
                .get()
                .await()
            
            val songs = querySnapshot.documents.mapNotNull { doc ->
                doc.toObject(Song::class.java)?.copy(id = doc.id)
            }
            
            Result.success(songs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getUserVideos(): Result<List<Video>> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val querySnapshot = videosCollection
                .whereEqualTo("uploadedBy", userId)
                .get()
                .await()
            
            val videos = querySnapshot.documents.mapNotNull { doc ->
                doc.toObject(Video::class.java)?.copy(id = doc.id)
            }
            
            Result.success(videos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Update user statistics (songs, videos, playlists count, storage used)
    private suspend fun updateUserStats() {
        try {
            val userId = currentUserId ?: return
            val userRef = usersCollection.document(userId)
            
            // Get current counts
            val songs = getUserSongs().getOrNull() ?: emptyList()
            val videos = getUserVideos().getOrNull() ?: emptyList()
            val playlists = getUserPlaylists().getOrNull() ?: emptyList()
            
            // Calculate storage used
            val storageUsed = songs.sumOf { it.fileSize } + videos.sumOf { it.fileSize }
            
            userRef.update(
                mapOf(
                    "totalSongs" to songs.size,
                    "totalVideos" to videos.size,
                    "totalPlaylists" to playlists.size,
                    "storageUsed" to storageUsed,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()
        } catch (e: Exception) {
            // Silently fail - statistics are not critical
        }
    }
    
    // Get complete playlist with all songs and videos
    suspend fun getPlaylistWithMedia(playlistId: String): Result<Triple<Playlist, List<Song>, List<Video>>> {
        return try {
            val playlistDoc = playlistsCollection.document(playlistId).get().await()
            val playlist = playlistDoc.toObject(Playlist::class.java)?.copy(id = playlistDoc.id)
                ?: throw Exception("Playlist not found")
            
            // Get all songs
            val songs = if (playlist.songIds.isNotEmpty()) {
                val songDocs = songsCollection.whereIn("id", playlist.songIds).get().await()
                songDocs.documents.mapNotNull { doc ->
                    doc.toObject(Song::class.java)?.copy(id = doc.id)
                }
            } else {
                emptyList()
            }
            
            // Get all videos
            val videos = if (playlist.videoIds.isNotEmpty()) {
                val videoDocs = videosCollection.whereIn("id", playlist.videoIds).get().await()
                videoDocs.documents.mapNotNull { doc ->
                    doc.toObject(Video::class.java)?.copy(id = doc.id)
                }
            } else {
                emptyList()
            }
            
            Result.success(Triple(playlist, songs, videos))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun addSongToPlaylist(playlistId: String, songId: String): Result<Unit> {
        return try {
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")
            
            val updatedSongIds = playlist.songIds.toMutableList()
            if (!updatedSongIds.contains(songId)) {
                updatedSongIds.add(songId)
                
                playlistRef.update(
                    mapOf(
                        "songIds" to updatedSongIds,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun addVideoToPlaylist(playlistId: String, videoId: String): Result<Unit> {
        return try {
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")
            
            val updatedVideoIds = playlist.videoIds.toMutableList()
            if (!updatedVideoIds.contains(videoId)) {
                updatedVideoIds.add(videoId)
                
                playlistRef.update(
                    mapOf(
                        "videoIds" to updatedVideoIds,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sharePlaylistWithUser(
        playlistId: String,
        targetUserId: String,
        message: String = ""
    ): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            // Check if user owns the playlist
            if (playlist.ownerId != userId) {
                throw Exception("You can only share your own playlists")
            }

            // Add target user to shared list
            val updatedSharedUsers = playlist.sharedWithUsers.toMutableList()
            if (!updatedSharedUsers.contains(targetUserId)) {
                updatedSharedUsers.add(targetUserId)

                // Update playlist
                playlistRef.update(
                    mapOf(
                        "sharedWithUsers" to updatedSharedUsers,
                        "lastSharedAt" to com.google.firebase.Timestamp.now(),
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()

                // Create shared playlist record
                val senderProfile = getUserProfile().getOrThrow()
                val sharedPlaylist = SharedPlaylist(
                    id = UUID.randomUUID().toString(),
                    originalPlaylistId = playlistId,
                    recipientUserId = targetUserId,
                    senderUserId = userId,
                    senderName = senderProfile.displayName,
                    shareMethod = ShareMethod.DIRECT,
                    shareMessage = message,
                    sharedAt = com.google.firebase.Timestamp.now()
                )

                firestore.collection("shared_playlists")
                    .document(sharedPlaylist.id)
                    .set(sharedPlaylist)
                    .await()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateShareCode(playlistId: String): Result<String> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            // Check if user owns the playlist
            if (playlist.ownerId != userId) {
                throw Exception("You can only generate share codes for your own playlists")
            }

            // Generate unique 6-character code
            val shareCode = generateUniqueShareCode()

            playlistRef.update(
                mapOf(
                    "shareCode" to shareCode,
                    "lastSharedAt" to com.google.firebase.Timestamp.now(),
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()

            Result.success(shareCode)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinPlaylistByShareCode(shareCode: String): Result<Playlist> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")

            // Find playlist with this share code
            val querySnapshot = playlistsCollection
                .whereEqualTo("shareCode", shareCode)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                throw Exception("Invalid share code")
            }

            val playlistDoc = querySnapshot.documents.first()
            val playlist = playlistDoc.toObject(Playlist::class.java)?.copy(id = playlistDoc.id)
                ?: throw Exception("Playlist not found")

            // Check if user is not the owner
            if (playlist.ownerId == userId) {
                throw Exception("You cannot join your own playlist")
            }

            // Create shared playlist record
            val senderProfile = usersCollection.document(playlist.ownerId).get().await()
                .toObject(UserProfile::class.java)

            val sharedPlaylist = SharedPlaylist(
                id = UUID.randomUUID().toString(),
                originalPlaylistId = playlist.id,
                recipientUserId = userId,
                senderUserId = playlist.ownerId,
                senderName = senderProfile?.displayName ?: "Unknown User",
                shareMethod = ShareMethod.SHARE_CODE,
                shareCode = shareCode,
                sharedAt = com.google.firebase.Timestamp.now()
            )

            firestore.collection("shared_playlists")
                .document(sharedPlaylist.id)
                .set(sharedPlaylist)
                .await()

            Result.success(playlist)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun copySharedPlaylist(sharedPlaylistId: String, newPlaylistName: String): Result<Playlist> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")

            // Get shared playlist record
            val sharedPlaylistDoc = firestore.collection("shared_playlists")
                .document(sharedPlaylistId)
                .get()
                .await()

            val sharedPlaylist = sharedPlaylistDoc.toObject(SharedPlaylist::class.java)
                ?: throw Exception("Shared playlist not found")

            // Check if user is the recipient
            if (sharedPlaylist.recipientUserId != userId) {
                throw Exception("You can only copy playlists shared with you")
            }

            // Get original playlist
            val originalPlaylist = playlistsCollection.document(sharedPlaylist.originalPlaylistId)
                .get()
                .await()
                .toObject(Playlist::class.java)
                ?: throw Exception("Original playlist not found")

            // Create new playlist as copy
            val copiedPlaylist = createPlaylist(
                name = newPlaylistName,
                description = "Copied from ${sharedPlaylist.senderName}",
                playlistTags = originalPlaylist.playlistTags
            ).getOrThrow()

            // Copy all songs and videos to new playlist
            originalPlaylist.songIds.forEach { songId ->
                addSongToPlaylist(copiedPlaylist.id, songId).getOrThrow()
            }

            originalPlaylist.videoIds.forEach { videoId ->
                addVideoToPlaylist(copiedPlaylist.id, videoId).getOrThrow()
            }

            // Update shared playlist record
            firestore.collection("shared_playlists")
                .document(sharedPlaylistId)
                .update(
                    mapOf(
                        "hasBeenCopied" to true,
                        "copiedPlaylistId" to copiedPlaylist.id,
                        "copiedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()

            Result.success(copiedPlaylist)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSharedWithMePlaylists(): Result<List<SharedPlaylist>> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")

            val querySnapshot = firestore.collection("shared_playlists")
                .whereEqualTo("recipientUserId", userId)
                .get()
                .await()

            val sharedPlaylists = querySnapshot.documents.mapNotNull { doc ->
                doc.toObject(SharedPlaylist::class.java)?.copy(id = doc.id)
            }

            Result.success(sharedPlaylists)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun generateUniqueShareCode(): String {
        val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        var shareCode: String
        var isUnique = false

        do {
            shareCode = (1..6)
                .map { characters.random() }
                .joinToString("")

            // Check if code already exists
            val existing = playlistsCollection
                .whereEqualTo("shareCode", shareCode)
                .get()
                .await()

            isUnique = existing.isEmpty
        } while (!isUnique)

        return shareCode
    }
}
