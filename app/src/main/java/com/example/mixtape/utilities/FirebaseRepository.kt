package com.example.mixtape.utilities

import android.net.Uri
import android.util.Log
import com.example.mixtape.model.Playlist
import com.example.mixtape.model.Song
import com.example.mixtape.model.Video
import com.example.mixtape.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

/**
 * Central repository for all Firebase operations including Firestore, Storage, and Auth.
 * Handles user profiles, media uploads, playlist management, and sharing functionality.
 */
class FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val userId: String?
        get() = auth.currentUser?.uid

    fun getCurrentUserId(): String? = userId

    // --- Storage Reference Helpers ---

    private fun getUserSongsRef(): StorageReference? {
        return userId?.let { uid ->
            storage.reference.child("users/$uid/songs")
        }
    }

    private fun getUserVideosRef(): StorageReference? {
        return userId?.let { uid ->
            storage.reference.child("users/$uid/videos")
        }
    }

    // --- Firestore Collection References ---
    private val playlistsCollection = firestore.collection("playlists")
    private val songsCollection = firestore.collection("songs")
    private val videosCollection = firestore.collection("videos")
    private val usersCollection = firestore.collection("users")

    // --- User Profile Operations ---

    /**
     * Fetches the current user's profile. If it doesn't exist or is incomplete,
     * it initializes/updates it using Firebase Auth data.
     */
    suspend fun getUserProfile(): Result<UserProfile> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val doc = usersCollection.document(currentUid).get().await()

            if (doc.exists()) {
                var profile = doc.toObject(UserProfile::class.java)?.copy(id = doc.id)
                    ?: throw Exception("User profile not found")
                
                // Sync display name and email from Auth if missing in Firestore
                if (profile.displayName.isEmpty() || profile.email.isEmpty()) {
                    val authUser = auth.currentUser
                    val fallbackName = authUser?.displayName?.takeIf { it.isNotEmpty() } 
                        ?: authUser?.email?.substringBefore("@") 
                        ?: "User"
                    
                    val updatedProfile = profile.copy(
                        displayName = if (profile.displayName.isEmpty()) fallbackName else profile.displayName,
                        email = if (profile.email.isEmpty()) authUser?.email ?: "" else profile.email
                    )
                    
                    updateUserProfile(updatedProfile)
                    profile = updatedProfile
                }
                
                Result.success(profile)
            } else {
                // Initialize new profile for first-time login
                createUserProfileIfNotExists(currentUid)
                val authUser = auth.currentUser
                val name = authUser?.displayName?.takeIf { it.isNotEmpty() } 
                    ?: authUser?.email?.substringBefore("@") 
                    ?: "User"
                
                val basicProfile = UserProfile(
                    id = currentUid,
                    email = authUser?.email ?: "",
                    displayName = name,
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
            val currentUid = userId ?: throw Exception("User not authenticated")
            usersCollection.document(currentUid).set(profile, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Global Tag Operations ---

    /**
     * Adds a tag to the user's global tag list for quick filtering/auto-complete.
     */
    suspend fun addGlobalTag(tag: String): Result<Unit> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val userRef = usersCollection.document(currentUid)

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
            val currentUid = userId ?: throw Exception("User not authenticated")
            val userRef = usersCollection.document(currentUid)

            val userData = mapOf(
                "globalTags" to FieldValue.arrayRemove(tag),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            userRef.set(userData, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error removing global tag: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getGlobalTags(): Result<List<String>> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val doc = usersCollection.document(currentUid).get().await()

            if (doc.exists()) {
                val profile = doc.toObject(UserProfile::class.java)
                Result.success(profile?.globalTags ?: emptyList())
            } else {
                createUserProfileIfNotExists(currentUid)
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error getting global tags: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Internal helper to ensure a user profile exists before attempting updates.
     */
    private suspend fun createUserProfileIfNotExists(uid: String) {
        try {
            val doc = usersCollection.document(uid).get().await()
            if (!doc.exists()) {
                val user = auth.currentUser
                val name = user?.displayName?.takeIf { it.isNotEmpty() } 
                    ?: user?.email?.substringBefore("@") 
                    ?: "User"
                
                val userData = mutableMapOf(
                    "id" to uid,
                    "email" to (user?.email ?: ""),
                    "displayName" to name,
                    "globalTags" to emptyList<String>(),
                    "createdAt" to com.google.firebase.Timestamp.now(),
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
                usersCollection.document(uid).set(userData, SetOptions.merge()).await()
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error creating user profile: ${e.message}", e)
        }
    }

    /**
     * Automatically adds new tags to the user's global list when they are added to media.
     */
    private suspend fun ensureTagsInGlobal(tags: List<String>, targetUid: String? = null) {
        if (tags.isEmpty()) return

        try {
            val uid = targetUid ?: userId ?: return
            createUserProfileIfNotExists(uid)
            
            val userRef = usersCollection.document(uid)
            val userData = mapOf(
                "globalTags" to FieldValue.arrayUnion(*tags.toTypedArray()),
                "updatedAt" to com.google.firebase.Timestamp.now()
            )

            userRef.set(userData, SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error ensuring tags in global: ${e.message}", e)
        }
    }

    // --- Media Operations (Song/Video) ---

    /**
     * Uploads a song file to Storage and creates its metadata document in Firestore.
     */
    suspend fun uploadSong(
        fileUri: Uri,
        title: String,
        artist: String,
        album: String,
        duration: Int,
        tags: List<String>
    ): Result<Song> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val songId = UUID.randomUUID().toString()
            val fileName = "${songId}.mp3"
            val storageRef = getUserSongsRef()?.child(fileName) ?: throw Exception("Storage reference failed")

            val uploadTask = storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await()

            ensureTagsInGlobal(tags)

            val song = Song(
                id = songId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
                tags = tags.toMutableList(),
                storageUrl = downloadUrl.toString(),
                storagePath = "users/$currentUid/songs/$fileName",
                uploadedBy = currentUid,
                fileSize = uploadTask.metadata?.sizeBytes ?: 0,
                mimeType = "audio/mpeg",
                isPublic = false,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),
                durationFormatted = "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                fileSizeFormatted = formatFileSize(uploadTask.metadata?.sizeBytes ?: 0)
            )

            songsCollection.document(songId).set(song).await()
            Result.success(song)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uploads a video file to Storage and creates its metadata document in Firestore.
     */
    suspend fun uploadVideo(
        fileUri: Uri,
        title: String,
        artist: String,
        album: String,
        duration: Int,
        tags: List<String>
    ): Result<Video> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val videoId = UUID.randomUUID().toString()
            val fileName = "${videoId}.mp4"
            val storageRef = getUserVideosRef()?.child(fileName) ?: throw Exception("Storage reference failed")

            val uploadTask = storageRef.putFile(fileUri).await()
            val downloadUrl = storageRef.downloadUrl.await()

            ensureTagsInGlobal(tags)

            val video = Video(
                id = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
                tags = tags.toMutableList(),
                storageUrl = downloadUrl.toString(),
                storagePath = "users/$currentUid/videos/$fileName",
                uploadedBy = currentUid,
                fileSize = uploadTask.metadata?.sizeBytes ?: 0,
                mimeType = "video/mp4",
                isPublic = false,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),
                durationFormatted = "${duration / 60}:${(duration % 60).toString().padStart(2, '0')}",
                fileSizeFormatted = formatFileSize(uploadTask.metadata?.sizeBytes ?: 0)
            )

            videosCollection.document(videoId).set(video).await()
            Result.success(video)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error uploading video: ${e.message}", e)
            Result.failure(e)
        }
    }

    // --- Playlist Management ---

    /**
     * Creates a new playlist document. Does not add media items initially.
     */
    suspend fun createPlaylist(
        name: String,
        description: String = "",
        playlistTags: List<String> = emptyList(),
        isPublic: Boolean = false,
        originalOwnerId: String = ""
    ): Result<Playlist> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val playlistId = UUID.randomUUID().toString()

            val playlist = Playlist(
                id = playlistId,
                name = name,
                description = description,
                ownerId = currentUid,
                originalOwnerId = originalOwnerId.ifEmpty { currentUid },
                songIds = emptyList(),
                videoIds = emptyList(),
                playlistTags = playlistTags,
                sharedWithUsers = emptyList(),
                shareCode = "",
                allowCopying = true,
                ownerVisible = true,
                songs = 0,
                videos = 0,
                totalItems = 0,
                shareCount = 0,
                shared = false,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),
                lastSharedAt = null
            )

            playlistsCollection.document(playlistId).set(playlist).await()
            Result.success(playlist)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error creating playlist: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Internal helper to copy a file within Firebase Storage by downloading and re-uploading.
     */
    private suspend fun copyStorageFile(oldPath: String, newPath: String) {
        if (oldPath.isEmpty()) return
        try {
            val oldRef = storage.reference.child(oldPath)
            val newRef = storage.reference.child(newPath)

            val localFile = File.createTempFile("temp_media_copy", null)
            oldRef.getFile(localFile).await()
            newRef.putFile(Uri.fromFile(localFile)).await()
            localFile.delete()
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error copying storage file from $oldPath to $newPath: ${e.message}")
            throw e
        }
    }

    /**
     * Creates a deep copy of a playlist for another user. This includes duplicating
     * all media metadata and physical files in Storage to ensure data ownership.
     */
    private suspend fun createCopyOfPlaylist(
        originalPlaylist: Playlist,
        targetUserId: String,
        newDescription: String
    ): Result<Playlist> {
        return try {
            val newSongIds = mutableListOf<String>()
            val newVideoIds = mutableListOf<String>()

            // Duplicate all songs for the new owner
            for (songId in originalPlaylist.songIds) {
                val songDoc = songsCollection.document(songId).get().await()
                val originalSong = songDoc.toObject(Song::class.java)
                if (originalSong != null) {
                    val newSongId = UUID.randomUUID().toString()
                    val fileName = "${newSongId}.mp3"
                    val newStoragePath = "users/$targetUserId/songs/$fileName"
                    
                    try {
                        copyStorageFile(originalSong.storagePath, newStoragePath)
                        val newDownloadUrl = storage.reference.child(newStoragePath).downloadUrl.await().toString()
                        
                        val newSong = originalSong.copy(
                            id = newSongId,
                            uploadedBy = targetUserId,
                            storagePath = newStoragePath,
                            storageUrl = newDownloadUrl,
                            createdAt = com.google.firebase.Timestamp.now(),
                            updatedAt = com.google.firebase.Timestamp.now()
                        )
                        songsCollection.document(newSongId).set(newSong).await()
                        newSongIds.add(newSongId)
                    } catch (e: Exception) {
                        Log.e("FirebaseRepository", "Failed to copy song $songId: ${e.message}")
                    }
                }
            }

            // Duplicate all videos for the new owner
            for (videoId in originalPlaylist.videoIds) {
                val videoDoc = videosCollection.document(videoId).get().await()
                val originalVideo = videoDoc.toObject(Video::class.java)
                if (originalVideo != null) {
                    val newVideoId = UUID.randomUUID().toString()
                    val fileName = "${newVideoId}.mp4"
                    val newStoragePath = "users/$targetUserId/videos/$fileName"
                    
                    try {
                        copyStorageFile(originalVideo.storagePath, newStoragePath)
                        val newDownloadUrl = storage.reference.child(newStoragePath).downloadUrl.await().toString()
                        
                        var newThumbPath = ""
                        var newThumbUrl = ""
                        if (originalVideo.thumbnailPath.isNotEmpty()) {
                            newThumbPath = "users/$targetUserId/videos/${newVideoId}_thumb.jpg"
                            copyStorageFile(originalVideo.thumbnailPath, newThumbPath)
                            newThumbUrl = storage.reference.child(newThumbPath).downloadUrl.await().toString()
                        }

                        val newVideo = originalVideo.copy(
                            id = newVideoId,
                            uploadedBy = targetUserId,
                            storagePath = newStoragePath,
                            storageUrl = newDownloadUrl,
                            thumbnailPath = newThumbPath,
                            thumbnailUrl = newThumbUrl,
                            createdAt = com.google.firebase.Timestamp.now(),
                            updatedAt = com.google.firebase.Timestamp.now()
                        )
                        videosCollection.document(newVideoId).set(newVideo).await()
                        newVideoIds.add(newVideoId)
                    } catch (e: Exception) {
                        Log.e("FirebaseRepository", "Failed to copy video $videoId: ${e.message}")
                    }
                }
            }

            // Create the duplicate playlist document
            val playlistId = UUID.randomUUID().toString()
            val copiedPlaylist = Playlist(
                id = playlistId,
                name = originalPlaylist.name,
                description = newDescription,
                ownerId = targetUserId,
                originalOwnerId = originalPlaylist.originalOwnerId.ifEmpty { originalPlaylist.ownerId },
                songIds = newSongIds,
                videoIds = newVideoIds,
                playlistTags = originalPlaylist.playlistTags,
                sharedWithUsers = emptyList(),
                shareCode = "",
                allowCopying = true,
                ownerVisible = true,
                songs = newSongIds.size,
                videos = newVideoIds.size,
                totalItems = newSongIds.size + newVideoIds.size,
                shareCount = 0,
                shared = false,
                createdAt = com.google.firebase.Timestamp.now(),
                updatedAt = com.google.firebase.Timestamp.now(),
                lastSharedAt = null
            )

            playlistsCollection.document(playlistId).set(copiedPlaylist).await()
            updateUserTagsFromPlaylist(copiedPlaylist, targetUserId)
            Result.success(copiedPlaylist)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error creating copy of playlist: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates the target user's global tags based on the tags found in a new playlist.
     */
    private suspend fun updateUserTagsFromPlaylist(playlist: Playlist, targetUserId: String) {
        try {
            val allTags = mutableSetOf<String>()
            allTags.addAll(playlist.playlistTags)
            
            // Collect tags from all media in the playlist
            if (playlist.songIds.isNotEmpty()) {
                playlist.songIds.chunked(10).forEach { ids ->
                    val songDocs = songsCollection.whereIn("id", ids).get().await()
                    songDocs.documents.forEach { doc ->
                        val song = doc.toObject(Song::class.java)
                        song?.tags?.let { allTags.addAll(it) }
                    }
                }
            }
            
            if (playlist.videoIds.isNotEmpty()) {
                playlist.videoIds.chunked(10).forEach { ids ->
                    val videoDocs = videosCollection.whereIn("id", ids).get().await()
                    videoDocs.documents.forEach { doc ->
                        val video = doc.toObject(Video::class.java)
                        video?.tags?.let { allTags.addAll(it) }
                    }
                }
            }
            
            if (allTags.isNotEmpty()) {
                ensureTagsInGlobal(allTags.toList(), targetUserId)
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating user tags: ${e.message}", e)
        }
    }

    suspend fun getUserPlaylists(): Result<List<Playlist>> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val querySnapshot = playlistsCollection
                .whereEqualTo("ownerId", currentUid)
                .get()
                .await()

            val playlists = querySnapshot.documents.mapNotNull { doc ->
                try {
                    val playlist = doc.toObject(Playlist::class.java)
                    playlist?.copy(id = doc.id)
                } catch (e: Exception) {
                    Log.e("FirebaseRepository", "Error parsing playlist ${doc.id}: ${e.message}")
                    null
                }
            }.filter { it.ownerVisible }

            Result.success(playlists)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error loading playlists: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getUserSongs(): Result<List<Song>> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val querySnapshot = songsCollection
                .whereEqualTo("uploadedBy", currentUid)
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
            val currentUid = userId ?: throw Exception("User not authenticated")
            val querySnapshot = videosCollection
                .whereEqualTo("uploadedBy", currentUid)
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

    /**
     * Fetches a playlist and all its associated media items (Songs and Videos).
     */
    suspend fun getPlaylistWithMedia(playlistId: String): Result<Triple<Playlist, List<Song>, List<Video>>> {
        return try {
            val playlistDoc = playlistsCollection.document(playlistId).get().await()
            val playlist = playlistDoc.toObject(Playlist::class.java)?.copy(id = playlistDoc.id)
                ?: throw Exception("Playlist not found")

            val songs = if (playlist.songIds.isNotEmpty()) {
                val songDocs = songsCollection.whereIn("id", playlist.songIds).get().await()
                songDocs.documents.mapNotNull { doc ->
                    doc.toObject(Song::class.java)?.copy(id = doc.id)
                }
            } else {
                emptyList()
            }

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

                updatePlaylistCounts(playlistId)
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

                updatePlaylistCounts(playlistId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Sharing Functionality ---

    /**
     * Direct sharing: creates a copy of the playlist for the target user.
     */
    suspend fun sharePlaylistWithUser(
        playlistId: String,
        targetUserId: String,
        message: String = ""
    ): Result<Unit> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            if (playlist.ownerId != currentUid) {
                throw Exception("You can only share your own playlists")
            }

            val senderProfile = getUserProfile().getOrThrow()
            createCopyOfPlaylist(
                playlist, 
                targetUserId, 
                "Shared by ${senderProfile.displayName}" + if (message.isNotEmpty()) ": $message" else ""
            ).getOrThrow()
            
            val updatedSharedUsers = playlist.sharedWithUsers.toMutableList()
            if (!updatedSharedUsers.contains(targetUserId)) {
                updatedSharedUsers.add(targetUserId)

                playlistRef.update(
                    mapOf(
                        "sharedWithUsers" to updatedSharedUsers,
                        "lastSharedAt" to com.google.firebase.Timestamp.now(),
                        "updatedAt" to com.google.firebase.Timestamp.now(),
                        "shared" to true
                    )
                ).await()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Share code generation: enables any user to join a playlist if they have the 6-digit code.
     */
    suspend fun generateShareCode(playlistId: String): Result<String> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            if (playlist.ownerId != currentUid) {
                throw Exception("You can only generate share codes for your own playlists")
            }

            val shareCode = generateUniqueShareCode()

            playlistRef.update(
                mapOf(
                    "shareCode" to shareCode,
                    "lastSharedAt" to com.google.firebase.Timestamp.now(),
                    "updatedAt" to com.google.firebase.Timestamp.now(),
                    "shared" to true
                )
            ).await()

            Result.success(shareCode)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinPlaylistByShareCode(shareCode: String): Result<Playlist> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")

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

            if (playlist.ownerId == currentUid) {
                throw Exception("You cannot join your own playlist")
            }

            val senderProfile = usersCollection.document(playlist.ownerId).get().await()
                .toObject(UserProfile::class.java)
            
            val copiedPlaylist = createCopyOfPlaylist(
                playlist,
                currentUid,
                "Joined via share code from ${senderProfile?.displayName ?: "Unknown User"}"
            ).getOrThrow()

            Result.success(copiedPlaylist)
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

            val existing = playlistsCollection
                .whereEqualTo("shareCode", shareCode)
                .get()
                .await()

            isUnique = existing.isEmpty
        } while (!isUnique)

        return shareCode
    }

    // --- Update & Delete Operations ---

    suspend fun updatePlaylistName(playlistId: String, newName: String): Result<Unit> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val playlistRef = playlistsCollection.document(playlistId)

            val playlist = playlistRef.get().await().toObject(Playlist::class.java)
                ?: throw Exception("Playlist not found")

            if (playlist.ownerId != currentUid) {
                throw Exception("You can only edit your own playlists")
            }

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

    suspend fun isPlaylistOwnedByCurrentUser(playlistId: String): Result<Boolean> {
        return try {
            val currentUid = userId ?: throw Exception("User not authenticated")
            val playlistDoc = playlistsCollection.document(playlistId).get().await()
            if (!playlistDoc.exists()) {
                return Result.failure(Exception("Playlist not found"))
            }

            val playlist = playlistDoc.toObject(Playlist::class.java)
            val isOwned = playlist?.ownerId == currentUid
            Result.success(isOwned)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        return try {
            playlistsCollection.document(playlistId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting playlist: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Checks if a media item is referenced by any other playlist (e.g., shared copies).
     * Prevents deleting files from Storage if other users are still using them.
     */
    private suspend fun isMediaUsedByAnyone(mediaId: String, isVideo: Boolean): Boolean {
        return try {
            val fieldName = if (isVideo) "videoIds" else "songIds"
            val querySnapshot = playlistsCollection
                .whereArrayContains(fieldName, mediaId)
                .get()
                .await()
            !querySnapshot.isEmpty
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error checking media usage: ${e.message}")
            true
        }
    }

    private suspend fun deleteMediaCompletelyInternal(mediaId: String, isVideo: Boolean) {
        if (isVideo) {
            val videoDoc = videosCollection.document(mediaId).get().await()
            val video = videoDoc.toObject(Video::class.java)
            if (video != null) {
                if (video.storagePath.isNotEmpty()) {
                    try { storage.reference.child(video.storagePath).delete().await() } catch (e: Exception) {}
                }
                if (video.thumbnailPath.isNotEmpty()) {
                    try { storage.reference.child(video.thumbnailPath).delete().await() } catch (e: Exception) {}
                }
                videosCollection.document(mediaId).delete().await()
            }
        } else {
            val songDoc = songsCollection.document(mediaId).get().await()
            val song = songDoc.toObject(Song::class.java)
            if (song != null) {
                if (song.storagePath.isNotEmpty()) {
                    try { storage.reference.child(song.storagePath).delete().await() } catch (e: Exception) {}
                }
                songsCollection.document(mediaId).delete().await()
            }
        }
    }

    suspend fun getUserPlaylistsIncludingShared(): Result<List<Playlist>> {
        return getUserPlaylists()
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
                ?: throw Exception("User data find failed")

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

            // If this was the last reference to the song, delete it from the cloud
            if (!isMediaUsedByAnyone(songId, isVideo = false)) {
                deleteMediaCompletelyInternal(songId, isVideo = false)
            }

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

            if (!isMediaUsedByAnyone(videoId, isVideo = true)) {
                deleteMediaCompletelyInternal(videoId, isVideo = true)
            }

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
            ensureTagsInGlobal(newTags)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun updatePlaylistCounts(playlistId: String) {
        try {
            val playlistRef = playlistsCollection.document(playlistId)
            val playlist = playlistRef.get().await().toObject(Playlist::class.java)

            if (playlist != null) {
                val newSongCount = playlist.songIds.size
                val newVideoCount = playlist.videoIds.size
                val newTotalItems = newSongCount + newVideoCount

                playlistRef.update(
                    mapOf(
                        "songs" to newSongCount,
                        "videos" to newVideoCount,
                        "totalItems" to newTotalItems,
                        "updatedAt" to com.google.firebase.Timestamp.now()
                    )
                ).await()
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

    /**
     * Deletes a song from all of the user's playlists and removes it from Storage
     * if no other copies (from sharing) exist.
     */
    suspend fun deleteSongCompletely(songId: String): Result<Unit> {
        return try {
            removeFromAllPlaylists(songId, isVideo = false)
            if (!isMediaUsedByAnyone(songId, isVideo = false)) {
                deleteMediaCompletelyInternal(songId, isVideo = false)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting song: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteVideoCompletely(videoId: String): Result<Unit> {
        return try {
            removeFromAllPlaylists(videoId, isVideo = true)
            if (!isMediaUsedByAnyone(videoId, isVideo = true)) {
                deleteMediaCompletelyInternal(videoId, isVideo = true)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting video: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun removeFromAllPlaylists(mediaId: String, isVideo: Boolean) {
        try {
            val currentUid = userId ?: return
            val userPlaylists = playlistsCollection
                .whereEqualTo("ownerId", currentUid)
                .get()
                .await()

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
                        updatePlaylistCounts(playlistDoc.id)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error removing from all playlists: ${e.message}", e)
        }
    }
}
