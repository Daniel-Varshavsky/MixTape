package com.example.mixtape.utilities

import android.net.Uri
import android.util.Log
import com.example.mixtape.model.Playlist
import com.example.mixtape.model.ShareMethod
import com.example.mixtape.model.Song
import com.example.mixtape.model.Video
import com.example.mixtape.model.UserProfile
import com.example.mixtape.model.SharedPlaylist
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
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

            if (doc.exists()) {
                val profile = doc.toObject(UserProfile::class.java)?.copy(id = doc.id)
                    ?: throw Exception("User profile not found")
                Result.success(profile)
            } else {
                // Create basic profile if it doesn't exist
                createUserProfileIfNotExists(userId)
                val basicProfile = UserProfile(
                    id = userId,
                    email = auth.currentUser?.email ?: "",
                    displayName = auth.currentUser?.displayName ?: auth.currentUser?.email?.substringBefore("@") ?: "User",
                    globalTags = emptyList(),
                    createdAt = com.google.firebase.Timestamp.now(),
                    updatedAt = com.google.firebase.Timestamp.now()
                )
                Result.success(basicProfile)
            }
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

            // Use set with merge to create document if it doesn't exist
            val userData = mapOf(
                "globalTags" to FieldValue.arrayUnion(tag),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            userRef.set(userData, SetOptions.merge()).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding global tag: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun removeGlobalTag(tag: String): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val userRef = usersCollection.document(userId)

            // Use set with merge to avoid issues with missing documents
            val userData = mapOf(
                "globalTags" to FieldValue.arrayRemove(tag),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            userRef.set(userData, SetOptions.merge()).await()

            // TODO: Optionally remove this tag from all user's songs, videos, and playlists

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error removing global tag: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getGlobalTags(): Result<List<String>> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val doc = usersCollection.document(userId).get().await()

            if (doc.exists()) {
                val profile = doc.toObject(UserProfile::class.java)
                Result.success(profile?.globalTags ?: emptyList())
            } else {
                // If user document doesn't exist, create it with empty tags
                Log.d("FirebaseRepository", "User document doesn't exist, creating with empty tags")
                createUserProfileIfNotExists(userId)
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error getting global tags: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Helper method to create user profile if it doesn't exist
    private suspend fun createUserProfileIfNotExists(userId: String) {
        try {
            val user = auth.currentUser
            val userData = mapOf(
                "id" to userId,
                "email" to (user?.email ?: ""),
                "displayName" to (user?.displayName ?: user?.email?.substringBefore("@") ?: "User"),
                "globalTags" to emptyList<String>(),
                "createdAt" to com.google.firebase.Timestamp.now(),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            usersCollection.document(userId).set(userData, SetOptions.merge()).await()
            Log.d("FirebaseRepository", "Created user profile for: $userId")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error creating user profile: ${e.message}", e)
        }
    }

    // Automatically add tags to global list when they're used
    private suspend fun ensureTagsInGlobal(tags: List<String>) {
        if (tags.isEmpty()) return

        try {
            val userId = currentUserId ?: return
            val userRef = usersCollection.document(userId)

            Log.d("FirebaseRepository", "Adding tags to global: $tags")

            val userData = mapOf(
                "globalTags" to FieldValue.arrayUnion(*tags.toTypedArray()),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            userRef.set(userData, SetOptions.merge()).await()
            Log.d("FirebaseRepository", "Successfully added tags to global")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error ensuring tags in global: ${e.message}", e)
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

            // Create song document with computed fields
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
                isPublic = false,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),

                // Save computed fields to Firestore for consistency
                durationFormatted = "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                fileSizeFormatted = formatFileSize(uploadTask.metadata?.sizeBytes ?: 0)
            )

            // Save to Firestore
            songsCollection.document(songId).set(song).await()

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

            Log.d("FirebaseRepository", "Starting video upload: $title")

            // Upload file to Storage
            val uploadTask = storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await()

            Log.d("FirebaseRepository", "Video uploaded to storage, getting download URL")

            // Automatically add tags to global tags
            ensureTagsInGlobal(tags)

            // Create video document with computed fields
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
                isPublic = false,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),

                // Save computed fields to Firestore for consistency
                durationFormatted = "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                fileSizeFormatted = formatFileSize(uploadTask.metadata?.sizeBytes ?: 0)
            )

            // Save to Firestore
            videosCollection.document(videoId).set(video).await()

            Log.d("FirebaseRepository", "Video saved to Firestore with ID: $videoId")

            Result.success(video)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error uploading video: ${e.message}", e)
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

            // Create playlist object that matches your Firestore structure exactly
            val playlist = Playlist(
                id = playlistId,
                name = name,
                description = description,
                ownerId = userId,
                songIds = emptyList(),
                videoIds = emptyList(),
                playlistTags = playlistTags,
                sharedWithUsers = emptyList(),
                shareCode = "",
                allowCopying = true,
                ownerVisible = true, // Note: using ownerVisible not isOwnerVisible

                // Set the computed fields explicitly to match your DB
                songs = 0,
                videos = 0,
                totalItems = 0,
                shareCount = 0,
                shared = false,

                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),
                lastSharedAt = null
            )

            // Save to Firestore
            playlistsCollection.document(playlistId).set(playlist).await()

            Result.success(playlist)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error creating playlist: ${e.message}", e)
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
                try {
                    // Get the data and manually set the ID since it's not stored in the document
                    val playlist = doc.toObject(Playlist::class.java)
                    playlist?.copy(id = doc.id)
                } catch (e: Exception) {
                    Log.e("FirebaseRepository", "Error parsing playlist ${doc.id}: ${e.message}")
                    null
                }
            }

            Result.success(playlists)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error loading playlists: ${e.message}", e)
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

                // Update playlist counts
                updatePlaylistCounts(playlistId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addVideoToPlaylist(playlistId: String, videoId: String): Result<Unit> {
        return try {
            Log.d("FirebaseRepository", "Adding video $videoId to playlist $playlistId")

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

                Log.d("FirebaseRepository", "Video added to playlist, updating counts...")

                // Update playlist counts
                updatePlaylistCounts(playlistId)

                Log.d("FirebaseRepository", "Playlist counts updated successfully")
            } else {
                Log.d("FirebaseRepository", "Video already in playlist")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding video to playlist: ${e.message}", e)
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

    suspend fun updatePlaylistName(playlistId: String, newName: String): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)

            // Check if playlist exists and user owns it
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            if (playlist.ownerId != userId) {
                throw Exception("You can only edit your own playlists")
            }

            // Update the name
            playlistRef.update(
                mapOf(
                    "name" to newName,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        return try {
            val userId = currentUserId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)

            // Check if playlist exists and user owns it
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            if (playlist.ownerId != userId) {
                throw Exception("You can only delete your own playlists")
            }

            // Delete the playlist
            playlistRef.delete().await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun findUserByEmail(email: String): Result<UserProfile> {
        return try {
            val querySnapshot = usersCollection
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                throw Exception("User not found")
            }

            val userDoc = querySnapshot.documents.first()
            val user = userDoc.toObject(UserProfile::class.java)?.copy(id = userDoc.id)
                ?: throw Exception("User data not found")

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String): Result<Unit> {
        return try {
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            val updatedSongIds = playlist.songIds.toMutableList()
            updatedSongIds.remove(songId)

            playlistRef.update(
                mapOf(
                    "songIds" to updatedSongIds,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeVideoFromPlaylist(playlistId: String, videoId: String): Result<Unit> {
        return try {
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            val updatedVideoIds = playlist.videoIds.toMutableList()
            updatedVideoIds.remove(videoId)

            playlistRef.update(
                mapOf(
                    "videoIds" to updatedVideoIds,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSongTags(songId: String, newTags: List<String>): Result<Unit> {
        return try {
            val songRef = songsCollection.document(songId)

            songRef.update(
                mapOf(
                    "tags" to newTags,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()

            // Automatically add new tags to global tags
            ensureTagsInGlobal(newTags)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateVideoTags(videoId: String, newTags: List<String>): Result<Unit> {
        return try {
            val videoRef = videosCollection.document(videoId)

            videoRef.update(
                mapOf(
                    "tags" to newTags,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            ).await()

            // Automatically add new tags to global tags
            ensureTagsInGlobal(newTags)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun updatePlaylistCounts(playlistId: String) {
        try {
            Log.d("FirebaseRepository", "Updating counts for playlist: $playlistId")

            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)

            if (playlist != null) {
                val newSongCount = playlist.songIds.size
                val newVideoCount = playlist.videoIds.size
                val newTotalItems = newSongCount + newVideoCount

                Log.d("FirebaseRepository", "Counts - Songs: $newSongCount, Videos: $newVideoCount, Total: $newTotalItems")

                playlistRef.update(
                    mapOf(
                        "songs" to newSongCount,
                        "videos" to newVideoCount,
                        "totalItems" to newTotalItems,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()

                Log.d("FirebaseRepository", "Playlist counts updated successfully")
            } else {
                Log.e("FirebaseRepository", "Playlist not found when updating counts")
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating playlist counts: ${e.message}", e)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    suspend fun deleteSongCompletely(songId: String): Result<Unit> {
        return try {
            Log.d("FirebaseRepository", "Completely deleting song: $songId")

            // Step 1: Get song metadata to find storage path
            val songDoc = songsCollection.document(songId).get().await()
            val song = songDoc.toObject(Song::class.java)

            if (song != null) {
                // Step 2: Delete file from Storage
                if (song.storagePath.isNotEmpty()) {
                    try {
                        val storageRef = storage.reference.child(song.storagePath)
                        storageRef.delete().await()
                        Log.d("FirebaseRepository", "Deleted song file from Storage: ${song.storagePath}")
                    } catch (e: Exception) {
                        Log.w("FirebaseRepository", "Could not delete file from Storage: ${e.message}")
                        // Continue with database deletion even if file deletion fails
                    }
                }

                // Step 3: Remove from all playlists that contain it
                removeFromAllPlaylists(songId, isVideo = false)

                // Step 4: Delete metadata from Firestore
                songsCollection.document(songId).delete().await()
                Log.d("FirebaseRepository", "Deleted song metadata from Firestore")
            } else {
                Log.w("FirebaseRepository", "Song not found: $songId")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error completely deleting song: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteVideoCompletely(videoId: String): Result<Unit> {
        return try {
            Log.d("FirebaseRepository", "Completely deleting video: $videoId")

            // Step 1: Get video metadata to find storage paths
            val videoDoc = videosCollection.document(videoId).get().await()
            val video = videoDoc.toObject(Video::class.java)

            if (video != null) {
                // Step 2: Delete main video file from Storage
                if (video.storagePath.isNotEmpty()) {
                    try {
                        val storageRef = storage.reference.child(video.storagePath)
                        storageRef.delete().await()
                        Log.d("FirebaseRepository", "Deleted video file from Storage: ${video.storagePath}")
                    } catch (e: Exception) {
                        Log.w("FirebaseRepository", "Could not delete video file from Storage: ${e.message}")
                    }
                }

                // Step 3: Delete thumbnail if it exists
                if (video.thumbnailPath.isNotEmpty()) {
                    try {
                        val thumbnailRef = storage.reference.child(video.thumbnailPath)
                        thumbnailRef.delete().await()
                        Log.d("FirebaseRepository", "Deleted thumbnail from Storage: ${video.thumbnailPath}")
                    } catch (e: Exception) {
                        Log.w("FirebaseRepository", "Could not delete thumbnail from Storage: ${e.message}")
                    }
                }

                // Step 4: Remove from all playlists that contain it
                removeFromAllPlaylists(videoId, isVideo = true)

                // Step 5: Delete metadata from Firestore
                videosCollection.document(videoId).delete().await()
                Log.d("FirebaseRepository", "Deleted video metadata from Firestore")
            } else {
                Log.w("FirebaseRepository", "Video not found: $videoId")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error completely deleting video: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Helper method to remove a song/video from all playlists that contain it
    private suspend fun removeFromAllPlaylists(mediaId: String, isVideo: Boolean) {
        try {
            val userId = currentUserId ?: return

            // Get all user playlists
            val userPlaylists = playlistsCollection
                .whereEqualTo("ownerId", userId)
                .get()
                .await()

            // Check each playlist and remove the media item if present
            for (playlistDoc in userPlaylists.documents) {
                val playlist = playlistDoc.toObject(Playlist::class.java)
                if (playlist != null) {
                    var needsUpdate = false
                    val updatedSongIds = playlist.songIds.toMutableList()
                    val updatedVideoIds = playlist.videoIds.toMutableList()

                    if (isVideo && updatedVideoIds.contains(mediaId)) {
                        updatedVideoIds.remove(mediaId)
                        needsUpdate = true
                    } else if (!isVideo && updatedSongIds.contains(mediaId)) {
                        updatedSongIds.remove(mediaId)
                        needsUpdate = true
                    }

                    if (needsUpdate) {
                        playlistDoc.reference.update(
                            mapOf(
                                "songIds" to updatedSongIds,
                                "videoIds" to updatedVideoIds,
                                "updatedAt" to com.google.firebase.Timestamp.now()
                            )
                        ).await()

                        // Update playlist counts
                        updatePlaylistCounts(playlistDoc.id)

                        Log.d("FirebaseRepository", "Removed media $mediaId from playlist ${playlist.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error removing from all playlists: ${e.message}", e)
        }
    }
}