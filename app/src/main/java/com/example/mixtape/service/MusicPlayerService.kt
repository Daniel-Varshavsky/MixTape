package com.example.mixtape.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.example.mixtape.R
import com.example.mixtape.MusicPlayerActivity
import com.example.mixtape.VideoPlayerActivity
import java.io.File

/**
 * MusicPlayerService
 *
 * ENHANCED VERSION: Now supports background video audio playback!
 *
 * NEW FEATURES:
 * - Video audio can continue playing when app is minimized
 * - MediaPlayer handles audio for both audio files AND video files
 * - VideoPlayerActivity uses service for audio, VideoView for visuals only
 * - Seamless background playback for mixed audio/video playlists
 *
 * The service now manages audio for ALL media types:
 * - Pure audio files: Normal MediaPlayer behavior
 * - Video files: MediaPlayer extracts and plays audio only, VideoView handles visuals
 * - Background mode: Audio continues regardless of activity state
 *
 * Interaction flow:
 *   Activity  ──bind──►  Service (controls MediaPlayer audio for ALL media)
 *   Service  ──callbacks──►  Activity (UI updates via Listener interface)
 *   VideoPlayerActivity ──sync──► Service (position synchronization for video+audio)
 *   Notification buttons  ──PendingIntent──►  Service (ACTION_* intent actions)
 */

@UnstableApi
class MusicPlayerService : Service() {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID        = "MusicPlayerServiceChannel"
        const val NOTIFICATION_ID   = 1
        private const val TAG = "MusicPlayerService"

        // Intent actions sent by notification buttons
        const val ACTION_PLAY_PAUSE   = "com.example.mixtape.PLAY_PAUSE"
        const val ACTION_NEXT         = "com.example.mixtape.NEXT"
        const val ACTION_PREVIOUS     = "com.example.mixtape.PREVIOUS"
        const val ACTION_FAST_FORWARD = "com.example.mixtape.FAST_FORWARD"
        const val ACTION_FAST_REWIND  = "com.example.mixtape.FAST_REWIND"
        const val ACTION_REPEAT       = "com.example.mixtape.REPEAT"
        const val ACTION_STOP         = "com.example.mixtape.STOP"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSession

    /** The flat song list passed in by the Activity. */
    private var songs: ArrayList<File> = arrayListOf()
    /** Song titles extracted from File names for display. */
    private var songTitles: ArrayList<String> = arrayListOf()
    /** Media types (audio/video) for navigation decisions. */
    private var mediaTypes: ArrayList<String> = arrayListOf()
    private var currentPosition: Int = 0
    private var isRepeatOn: Boolean = false

    /** Track which activity context we're currently in to handle transitions properly */
    private var currentActivityContext: String = "unknown" // "audio", "video", or "unknown"

    /** NEW: Track current media type being played for proper notification display */
    private var currentMediaType: String = "audio"

    /** Callback interface so the bound Activity can react to service-driven events. */
    interface PlayerListener {
        fun onSongChanged(position: Int, songName: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onRepeatChanged(isRepeat: Boolean)
        fun onRequestActivitySwitch(position: Int, mediaType: String)  // Request activity switch
        fun onVideoPositionUpdate(position: Int, duration: Int)  // NEW: For video sync
    }

    private val listeners = mutableSetOf<PlayerListener>()

    // ─────────────────────────────────────────────────────────────────────────
    // Binder
    // ─────────────────────────────────────────────────────────────────────────

    inner class ServiceBinder : Binder() {
        fun getService(): MusicPlayerService = this@MusicPlayerService
    }

    private val binder = ServiceBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
    }

    /**
     * Called every time the Activity (re)starts the service or a notification
     * button fires an ACTION_* intent directly at the service.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE   -> togglePlayPause()
            ACTION_NEXT         -> playNext()
            ACTION_PREVIOUS     -> playPrevious()
            ACTION_FAST_FORWARD -> fastForward()
            ACTION_FAST_REWIND  -> fastRewind()
            ACTION_REPEAT       -> toggleRepeat()
            ACTION_STOP         -> stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        mediaSession.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API (called by bound Activity)
    // ─────────────────────────────────────────────────────────────────────────

    fun addListener(l: PlayerListener) {
        listeners.add(l)
    }

    fun removeListener(l: PlayerListener) {
        listeners.remove(l)
    }

    /**
     * Initialize the service with a playlist and start playing audio immediately.
     * This is used when starting fresh from PlaylistActivity.
     */
    fun initPlaylist(
        songList: ArrayList<File>,
        startPosition: Int,
        songTitles: ArrayList<String>? = null,
        songArtists: ArrayList<String>? = null,
        mediaTypes: ArrayList<String>? = null
    ) {
        Log.d(TAG, "initPlaylist called with ${songList.size} items at position $startPosition")

        setupPlaylistData(songList, startPosition, songTitles, songArtists, mediaTypes)

        // Check if the starting position is audio or video
        val startingMediaType = getCurrentMediaType()
        Log.d(TAG, "Starting media type: $startingMediaType")

        if (startingMediaType == "audio") {
            // Start playing audio immediately
            currentActivityContext = "audio"
            playCurrentAudio()
        } else {
            // Starting position is video - notify activity to switch
            Log.d(TAG, "Starting position is video, requesting activity switch")
            notifyActivitySwitchRequest()
        }
    }

    /**
     * Initialize playlist without starting playback.
     * Used when activities need to coordinate the playlist but handle their own playback.
     */
    fun initPlaylistWithoutAutoplay(
        songList: ArrayList<File>,
        startPosition: Int,
        songTitles: ArrayList<String>? = null,
        songArtists: ArrayList<String>? = null,
        mediaTypes: ArrayList<String>? = null
    ) {
        Log.d(TAG, "initPlaylistWithoutAutoplay called")
        setupPlaylistData(songList, startPosition, songTitles, songArtists, mediaTypes)

        // This is called by VideoPlayerActivity, so set video context
        currentActivityContext = "video"

        // Just notify listeners of the current song without starting playback
        val songTitle = if (currentPosition < this.songTitles.size) {
            this.songTitles[currentPosition]
        } else {
            "Unknown"
        }
        listeners.forEach {
            it.onSongChanged(currentPosition, songTitle)
        }
    }

    /**
     * Navigate to the next item in the playlist.
     * Stops current playback and determines if activity switch is needed.
     */
    fun playNext() {
        Log.d(TAG, "playNext called")

        // Stop current playback completely
        stopCurrentPlayback()

        // Move to next position
        currentPosition = (currentPosition + 1) % songs.size
        Log.d(TAG, "Moved to position $currentPosition")

        handlePositionChange()
    }

    /**
     * Navigate to the previous item in the playlist.
     * Stops current playback and determines if activity switch is needed.
     */
    fun playPrevious() {
        Log.d(TAG, "playPrevious called")

        // Stop current playback completely
        stopCurrentPlayback()

        // Move to previous position
        currentPosition = if (currentPosition - 1 < 0) songs.size - 1 else currentPosition - 1
        Log.d(TAG, "Moved to position $currentPosition")

        handlePositionChange()
    }

    /**
     * ENHANCED: Handle what happens when position changes.
     * Uses activity context tracking to properly determine when to transition vs play.
     * Now supports both audio files and video audio playback.
     */
    private fun handlePositionChange() {
        val mediaType = getCurrentMediaType()
        val songTitle = if (currentPosition < this.songTitles.size) this.songTitles[currentPosition] else "Unknown"

        Log.d(TAG, "Position $currentPosition is $mediaType: $songTitle")
        currentMediaType = mediaType

        // Always notify listeners of the position change first
        listeners.forEach {
            it.onSongChanged(currentPosition, songTitle)
        }

        if (mediaType == "audio") {
            // Check if we're already in audio context or need to transition from video
            if (currentActivityContext == "audio") {
                // We're already in audio context (MusicPlayerActivity), start playing normally
                Log.d(TAG, "Playing audio at position $currentPosition (in audio context)")
                playCurrentAudio()
            } else {
                // We're in video context, need to transition to MusicPlayerActivity
                Log.d(TAG, "Transitioning from video to audio context - requesting activity switch")
                notifyActivitySwitchRequest()
            }
        } else {
            // This is video - check if we're already in video context
            if (currentActivityContext == "video") {
                // We're already in video context, start playing video audio
                Log.d(TAG, "Playing video audio at position $currentPosition (in video context)")
                playCurrentVideoAudio()
            } else {
                // Need to transition to VideoPlayerActivity
                Log.d(TAG, "Transitioning to video context - requesting activity switch")
                currentActivityContext = "video"
                notifyActivitySwitchRequest()
            }
        }
    }

    /**
     * Completely stop current playback and release resources.
     */
    fun stopCurrentPlayback() {
        Log.d(TAG, "Stopping current playback")

        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.stop()
            }
            mp.release()
        }
        mediaPlayer = null

        // Update listeners
        listeners.forEach {
            it.onPlaybackStateChanged(false)
        }

        updateNotification()
    }

    /**
     * Start playing audio at the current position.
     * Works for both pure audio files and video audio extraction.
     */
    fun playCurrentAudio() {
        if (getCurrentMediaType() != "audio") {
            Log.w(TAG, "playCurrentAudio called but current media is not audio")
            return
        }

        Log.d(TAG, "Starting audio playback at position $currentPosition")

        // Set audio context when starting audio playback
        currentActivityContext = "audio"
        currentMediaType = "audio"

        startMediaPlayerPlayback()
    }

    /**
     * NEW: Start playing video audio at the current position.
     * The MediaPlayer handles audio while VideoPlayerActivity handles visuals.
     */
    fun playCurrentVideoAudio() {
        if (getCurrentMediaType() != "video") {
            Log.w(TAG, "playCurrentVideoAudio called but current media is not video")
            return
        }

        Log.d(TAG, "Starting video audio playback at position $currentPosition")

        // Set video context when starting video audio playback
        currentActivityContext = "video"
        currentMediaType = "video"

        startMediaPlayerPlayback()
    }

    /**
     * NEW: Common method to start MediaPlayer for both audio and video content.
     * For video files, MediaPlayer extracts audio only.
     */
    private fun startMediaPlayerPlayback() {
        // Make sure any previous player is released
        releasePlayer()

        val file = songs[currentPosition]
        val songTitle = if (currentPosition < this.songTitles.size) this.songTitles[currentPosition] else file.name

        // Determine if this is a Firebase Storage URL or a local file
        val uri = if (file.exists() && file.length() < 1000) {
            // This is likely our placeholder file containing a Firebase Storage URL
            try {
                val urlContent = file.readText().trim()
                Log.d(TAG, "Read URL content from placeholder file: '$urlContent'")

                if (urlContent.startsWith("http://") || urlContent.startsWith("https://")) {
                    val parsedUri = Uri.parse(urlContent)
                    Log.d(TAG, "Using Firebase Storage URL: $parsedUri")
                    parsedUri
                } else {
                    Log.e(TAG, "Invalid URL format in placeholder file: '$urlContent'")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading placeholder file: ${e.message}", e)
                return
            }
        } else if (file.exists()) {
            Log.d(TAG, "Using local file: ${file.absolutePath}")
            Uri.fromFile(file)
        } else {
            Log.e(TAG, "File doesn't exist: ${file.absolutePath}")
            return
        }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            try {
                setDataSource(applicationContext, uri)

                // For remote URLs, prepare async; for local files, prepare sync
                if (uri.toString().startsWith("http")) {
                    Log.d(TAG, "Preparing remote stream asynchronously...")
                    setOnPreparedListener { mp ->
                        Log.d(TAG, "Remote stream prepared, starting playback")
                        mp.isLooping = isRepeatOn
                        mp.start()

                        // Notify the bound Activity
                        listeners.forEach {
                            it.onPlaybackStateChanged(true)
                        }

                        invalidateMediaSessionState()
                        updateNotification()

                        // Start position updates for video sync
                        if (currentMediaType == "video") {
                            startVideoPositionUpdates()
                        }

                        // Promote to foreground so Android won't kill us mid-song
                        startForeground(NOTIFICATION_ID, buildNotification())
                    }

                    setOnErrorListener { mp, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        handlePlaybackError()
                        true // Handled
                    }

                    prepareAsync()
                } else {
                    prepare()
                    isLooping = isRepeatOn
                    start()

                    // Notify the bound Activity
                    listeners.forEach {
                        it.onPlaybackStateChanged(true)
                    }

                    invalidateMediaSessionState()
                    updateNotification()

                    // Start position updates for video sync
                    if (currentMediaType == "video") {
                        startVideoPositionUpdates()
                    }

                    // Promote to foreground so Android won't kill us mid-song
                    startForeground(NOTIFICATION_ID, buildNotification())
                }

                setOnCompletionListener {
                    Log.d(TAG, "${currentMediaType} playback completed")
                    if (isRepeatOn) {
                        // Repeat current item
                        Log.d(TAG, "Repeating current ${currentMediaType}")
                        if (currentMediaType == "video") {
                            playCurrentVideoAudio()
                        } else {
                            playCurrentAudio()
                        }
                    } else {
                        // Move to next item
                        Log.d(TAG, "Moving to next item after completion")
                        playNext()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up MediaPlayer: ${e.message}", e)
                releasePlayer()
                handlePlaybackError()
            }
        }
    }

    /**
     * NEW: Start position updates for video synchronization.
     * This allows VideoPlayerActivity to sync its VideoView with the audio.
     */
    private fun startVideoPositionUpdates() {
        if (currentMediaType != "video") return

        val updateRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if (mp != null && mp.isPlaying) {
                    val position = mp.currentPosition
                    val duration = mp.duration

                    listeners.forEach {
                        it.onVideoPositionUpdate(position, duration)
                    }

                    // Schedule next update
                    handler.postDelayed(this, 500)
                } else {
                    Log.d(TAG, "Stopped video position updates")
                }
            }
        }

        Log.d(TAG, "Starting video position updates")
        handler.post(updateRunnable)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Handle playback errors by moving to the next item.
     */
    private fun handlePlaybackError() {
        Log.d(TAG, "Handling playback error, moving to next item")
        playNext()
    }

    /**
     * Notify listeners that an activity switch is needed.
     */
    private fun notifyActivitySwitchRequest() {
        val mediaType = getCurrentMediaType()
        Log.d(TAG, "Notifying activity switch request for $mediaType at position $currentPosition")

        listeners.forEach {
            it.onRequestActivitySwitch(currentPosition, mediaType)
        }
    }

    /**
     * Common setup for playlist data.
     */
    private fun setupPlaylistData(
        songList: ArrayList<File>,
        startPosition: Int,
        songTitles: ArrayList<String>?,
        songArtists: ArrayList<String>?,
        mediaTypes: ArrayList<String>?
    ) {
        songs = songList
        currentPosition = startPosition

        // Use provided song titles if available, otherwise fall back to filename extraction
        this.songTitles.clear()
        if (!songTitles.isNullOrEmpty() && songTitles.size == songList.size) {
            this.songTitles.addAll(songTitles)
            Log.d(TAG, "Using provided song titles: ${songTitles.joinToString(", ")}")
        } else {
            // Fallback to filename extraction for backward compatibility
            songList.forEach { file ->
                val title = extractSongTitle(file)
                this.songTitles.add(title)
                Log.d(TAG, "Extracted title: '$title' from file: ${file.name}")
            }
        }

        // Store media types for navigation decisions
        this.mediaTypes.clear()
        if (mediaTypes != null && mediaTypes.size == songList.size) {
            this.mediaTypes.addAll(mediaTypes)
            Log.d(TAG, "Media types provided: ${mediaTypes.joinToString(", ")}")
        } else {
            // Default all to audio if no media types provided
            this.mediaTypes.addAll(List(songList.size) { "audio" })
            Log.d(TAG, "No media types provided, defaulting all to audio")
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
        } else {
            mp.start()
        }
        listeners.forEach {
            it.onPlaybackStateChanged(mp.isPlaying)
        }
        invalidateMediaSessionState()
        updateNotification()
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            listeners.forEach {
                it.onPlaybackStateChanged(false)
            }
            invalidateMediaSessionState()
            updateNotification()
        }
    }

    fun play() {
        val mp = mediaPlayer ?: return
        if (!mp.isPlaying) {
            mp.start()
            listeners.forEach {
                it.onPlaybackStateChanged(true)
            }
            invalidateMediaSessionState()
            updateNotification()
        }
    }

    fun toggleRepeat() {
        isRepeatOn = !isRepeatOn
        mediaPlayer?.isLooping = isRepeatOn
        listeners.forEach {
            it.onRepeatChanged(isRepeatOn)
        }
        invalidateMediaSessionState()
        updateNotification()
        Log.d(TAG, "Repeat toggled: $isRepeatOn")
    }

    fun fastForward() {
        val mp = mediaPlayer ?: return
        mp.seekTo(mp.currentPosition + 10_000)
    }

    fun fastRewind() {
        val mp = mediaPlayer ?: return
        mp.seekTo((mp.currentPosition - 10_000).coerceAtLeast(0))
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun isRepeat(): Boolean = isRepeatOn

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    fun getSongPosition(): Int = currentPosition

    /** Returns the MediaPlayer's audio session ID for the visualizer. -1 if not ready. */
    fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: -1

    /**
     * Get the media type of the current position.
     */
    fun getCurrentMediaType(): String {
        return if (currentPosition < mediaTypes.size) {
            mediaTypes[currentPosition]
        } else {
            "audio" // Default fallback
        }
    }

    /**
     * Set the current activity context for proper transition handling.
     */
    fun setActivityContext(context: String) {
        Log.d(TAG, "Activity context set to: $context")
        currentActivityContext = context
    }

    /**
     * Extract a proper song title from a File object.
     * Handles both local files and Firebase Storage placeholder files.
     */
    private fun extractSongTitle(file: File): String {
        return try {
            // Check if this is a placeholder file containing Firebase URL
            if (file.exists() && file.length() < 1000) {
                try {
                    val content = file.readText()
                    if (content.startsWith("http")) {
                        // This is a Firebase URL placeholder file
                        // Extract title from filename: "songId_Song_Title_Here.mp3" -> "Song Title Here"
                        val filename = file.nameWithoutExtension

                        // Remove the Firebase ID part (everything before first underscore)
                        val titlePart = if (filename.contains("_")) {
                            filename.substringAfter("_")
                        } else {
                            filename
                        }

                        // Convert underscores back to spaces and clean up
                        titlePart.replace("_", " ").trim().takeIf { it.isNotEmpty() } ?: "Unknown Song"
                    } else {
                        // Regular file, use filename
                        file.nameWithoutExtension
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading placeholder file content: ${e.message}")
                    file.nameWithoutExtension
                }
            } else {
                // Regular local file
                file.nameWithoutExtension
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting song title: ${e.message}")
            "Unknown Song"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal playback
    // ─────────────────────────────────────────────────────────────────────────

    private fun releasePlayer() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaSession
    // ─────────────────────────────────────────────────────────────────────────

    // Stored reference so we can call notifyStateChanged() from other methods.
    private lateinit var media3Player: MusicPlayerStub

    private fun setupMediaSession() {
        media3Player = MusicPlayerStub()
        mediaSession = MediaSession.Builder(this, media3Player).build()
    }

    /**
     * SimpleBasePlayer is the stable, recommended way to bridge a custom audio
     * engine to Media3. Unlike BasePlayer (unstable), it only requires you to
     * implement [getState] plus the handful of [handle*] methods for the commands
     * you advertise. Everything else is derived from the State snapshot.
     */
    @UnstableApi
    private inner class MusicPlayerStub : androidx.media3.common.SimpleBasePlayer(mainLooper) {

        /** Call this whenever MediaPlayer state changes so Media3 re-reads [getState]. */
        fun notifyStateChanged() = invalidateState()

        override fun getState(): State {
            val playbackState = if (mediaPlayer != null) Player.STATE_READY else Player.STATE_IDLE

            // Duration lives on MediaItemData, not State.Builder directly.
            val currentItem = SimpleBasePlayer.MediaItemData.Builder(/* uid = */ 0)
                .setDurationUs(
                    mediaPlayer?.duration?.let { it.toLong() * 1_000L } ?: C.TIME_UNSET
                )
                .build()

            return State.Builder()
                .setAvailableCommands(
                    Player.Commands.Builder()
                        .addAll(
                            Player.COMMAND_PLAY_PAUSE,
                            Player.COMMAND_SEEK_TO_NEXT,
                            Player.COMMAND_SEEK_TO_PREVIOUS,
                            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_GET_TIMELINE
                        )
                        .build()
                )
                .setPlayWhenReady(
                    mediaPlayer?.isPlaying ?: false,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                )
                .setPlaybackState(playbackState)
                .setPlaylist(listOf(currentItem))
                .setContentPositionMs { mediaPlayer?.currentPosition?.toLong() ?: 0L }
                .setRepeatMode(
                    if (isRepeatOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                )
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) {
                mediaPlayer?.start()
                listeners.forEach {
                    it.onPlaybackStateChanged(true)
                }
            } else {
                mediaPlayer?.pause()
                listeners.forEach {
                    it.onPlaybackStateChanged(false)
                }
            }
            updateNotification()
            return Futures.immediateVoidFuture()
        }

        fun handleSeekToNextMediaItem(): ListenableFuture<*> {
            playNext()
            return Futures.immediateVoidFuture()
        }

        fun handleSeekToPreviousMediaItem(): ListenableFuture<*> {
            playPrevious()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int
        ): ListenableFuture<*> {
            seekTo(positionMs.toInt())
            return Futures.immediateVoidFuture()
        }
    }

    /**
     * Tells SimpleBasePlayer to re-read [getState]. Call after any external
     * state change (song change, play/pause from activity, repeat toggle).
     */
    private fun invalidateMediaSessionState() {
        if (::media3Player.isInitialized) media3Player.notifyStateChanged()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW  // LOW = no sound, keeps it silent
            ).apply {
                description = "Shows playback controls for the music player"
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    /**
     * ENHANCED: Builds a media-style notification that adapts to content type.
     * Shows "Now Playing" for audio, "Now Watching" for video.
     */
    private fun buildNotification(): Notification {
        val songName = if (currentPosition < this.songTitles.size) {
            this.songTitles[currentPosition]
        } else {
            songs.getOrNull(currentPosition)?.name ?: "Unknown"
        }
        val playing = isPlaying()
        val contentTitle = when (currentMediaType) {
            "video" -> "Now Watching"
            else -> "Now Playing"
        }

        // ── Pending intents for each button ──────────────────────────────────
        fun actionPendingIntent(action: String): PendingIntent {
            val i = Intent(this, MusicPlayerService::class.java).apply { this.action = action }
            return PendingIntent.getService(
                this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Tapping the notification body reopens the appropriate Activity
        val openAppIntent = when (currentMediaType) {
            "video" -> Intent(this, VideoPlayerActivity::class.java)
            else -> Intent(this, MusicPlayerActivity::class.java)
        }.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (playing) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_library_music_24)
            .setContentTitle(contentTitle)
            .setContentText(songName)
            .setContentIntent(openAppPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)  // sticky only while playing; dismissible when paused
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowActionsInCompactView(1, 2, 3) // fast rewind, play/pause, fast forward in compact
            )
            .addAction(R.drawable.baseline_skip_previous_24, "Previous",       actionPendingIntent(ACTION_PREVIOUS))
            .addAction(R.drawable.baseline_fast_rewind_24,   "Fast Rewind",    actionPendingIntent(ACTION_FAST_REWIND))
            .addAction(playPauseIcon,                        "Play/Pause",     actionPendingIntent(ACTION_PLAY_PAUSE))
            .addAction(R.drawable.baseline_fast_forward_24,  "Fast Forward",   actionPendingIntent(ACTION_FAST_FORWARD))
            .addAction(R.drawable.baseline_skip_next_24,     "Next",           actionPendingIntent(ACTION_NEXT))
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }
}