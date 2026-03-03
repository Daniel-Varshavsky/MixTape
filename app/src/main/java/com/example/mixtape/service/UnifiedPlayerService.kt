package com.example.mixtape.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import com.example.mixtape.UnifiedPlayerActivity
import java.io.File

/**
 * MusicPlayerService - UPDATED FOR UNIFIED PLAYER
 *
 * ENHANCED VERSION: Now supports background video audio playback with UnifiedPlayerActivity!
 */

@UnstableApi
class UnifiedPlayerService : Service() {

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
        const val ACTION_AUTOPLAY     = "com.example.mixtape.AUTOPLAY"
        const val ACTION_STOP         = "com.example.mixtape.STOP"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var media3Player: MusicPlayerStub? = null

    private var songs: ArrayList<File> = arrayListOf()
    private var songTitles: ArrayList<String> = arrayListOf()
    private var mediaTypes: ArrayList<String> = arrayListOf()
    private var currentPosition: Int = 0
    private var isRepeatOn: Boolean = false
    private var isAutoplayOn: Boolean = true

    private var currentActivityContext: String = "unified"
    private var currentMediaType: String = "audio"

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isPositionUpdateRunning = false

    private val videoPositionUpdateRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val position = mp.currentPosition
                val duration = mp.duration

                listeners.forEach {
                    it.onVideoPositionUpdate(position, duration)
                }

                handler.postDelayed(this, 500)
            } else {
                Log.d(TAG, "Stopping video position updates (player null or paused)")
                isPositionUpdateRunning = false
            }
        }
    }

    interface PlayerListener {
        fun onSongChanged(position: Int, songName: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onRepeatChanged(isRepeat: Boolean)
        fun onAutoplayChanged(isAutoplay: Boolean)
        fun onRequestActivitySwitch(position: Int, mediaType: String)
        fun onVideoPositionUpdate(position: Int, duration: Int)
    }

    private val listeners = mutableSetOf<PlayerListener>()

    // ─────────────────────────────────────────────────────────────────────────
    // Binder
    // ─────────────────────────────────────────────────────────────────────────

    inner class ServiceBinder : Binder() {
        fun getService(): UnifiedPlayerService = this@UnifiedPlayerService
    }

    private val binder = ServiceBinder()

    override fun onBind(intent: Intent): IBinder = binder

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE   -> togglePlayPause()
            ACTION_NEXT         -> playNext()
            ACTION_PREVIOUS     -> playPrevious()
            ACTION_FAST_FORWARD -> fastForward()
            ACTION_FAST_REWIND  -> fastRewind()
            ACTION_REPEAT       -> toggleRepeat()
            ACTION_AUTOPLAY     -> toggleAutoplay()
            ACTION_STOP         -> {
                stopCurrentPlayback()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() - cleaning up resources")
        stopCurrentPlayback()
        handler.removeCallbacks(videoPositionUpdateRunnable)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun addListener(l: PlayerListener) {
        if (!listeners.contains(l)) {
            listeners.add(l)
        }
    }

    fun removeListener(l: PlayerListener) {
        listeners.remove(l)
    }

    fun initPlaylist(
        songList: ArrayList<File>,
        startPosition: Int,
        songTitles: ArrayList<String>? = null,
        songArtists: ArrayList<String>? = null,
        mediaTypes: ArrayList<String>? = null
    ) {
        Log.d(TAG, "initPlaylist called at position $startPosition")
        setupPlaylistData(songList, startPosition, songTitles, songArtists, mediaTypes)

        val songTitle = if (currentPosition < this.songTitles.size) this.songTitles[currentPosition] else "Unknown"
        listeners.forEach { it.onSongChanged(currentPosition, songTitle) }

        val startingMediaType = getCurrentMediaType()
        currentActivityContext = "unified"

        if (startingMediaType == "audio") {
            playCurrentAudio()
        } else {
            playCurrentVideoAudio()
        }
    }

    fun initPlaylistWithoutAutoplay(
        songList: ArrayList<File>,
        startPosition: Int,
        songTitles: ArrayList<String>? = null,
        songArtists: ArrayList<String>? = null,
        mediaTypes: ArrayList<String>? = null
    ) {
        setupPlaylistData(songList, startPosition, songTitles, songArtists, mediaTypes)
        currentActivityContext = "unified"
        val songTitle = if (currentPosition < this.songTitles.size) this.songTitles[currentPosition] else "Unknown"
        listeners.forEach { it.onSongChanged(currentPosition, songTitle) }
    }

    fun playNext() {
        stopCurrentPlaybackInternal()
        currentPosition = (currentPosition + 1) % songs.size
        handlePositionChange()
    }

    fun playPrevious() {
        stopCurrentPlaybackInternal()
        currentPosition = if (currentPosition - 1 < 0) songs.size - 1 else currentPosition - 1
        handlePositionChange()
    }

    private fun handlePositionChange() {
        val mediaType = getCurrentMediaType()
        val songTitle = if (currentPosition < this.songTitles.size) this.songTitles[currentPosition] else "Unknown"
        currentMediaType = mediaType

        listeners.forEach { it.onSongChanged(currentPosition, songTitle) }

        if (mediaType == "audio") {
            playCurrentAudio()
        } else {
            playCurrentVideoAudio()
        }
    }

    private fun stopCurrentPlaybackInternal() {
        handler.removeCallbacks(videoPositionUpdateRunnable)
        isPositionUpdateRunning = false
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping/releasing player: ${e.message}")
            }
        }
        mediaPlayer = null
    }

    /**
     * Public API to completely stop playback and remove the notification.
     * This is intended to be called when the user is done with playback entirely.
     */
    fun stopCurrentPlayback() {
        Log.d(TAG, "stopCurrentPlayback() - completely stopping music and notification")
        
        // 1. Notify listeners first
        listeners.forEach { it.onPlaybackStateChanged(false) }
        
        // 2. Stop player
        stopCurrentPlaybackInternal()
        
        // 3. Update Media3 player state to IDLE (if session exists)
        invalidateMediaSessionState()
        
        // 4. Remove service from foreground state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        
        // 5. Forcefully cancel the notification via NotificationManager just in case
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        // 6. Release MediaSession to ensure media controls are removed from system (Android 11+)
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing media session: ${e.message}")
        }
        mediaSession = null
        media3Player = null
        
        Log.d(TAG, "Notification and MediaSession removal completed")
    }

    fun playCurrentAudio() {
        currentActivityContext = "unified"
        currentMediaType = "audio"
        startMediaPlayerPlayback()
    }

    fun playCurrentVideoAudio() {
        currentActivityContext = "unified"
        currentMediaType = "video"
        startMediaPlayerPlayback()
    }

    private fun startMediaPlayerPlayback() {
        releasePlayer()
        val file = if (currentPosition < songs.size) songs[currentPosition] else return

        val uri = if (file.exists() && file.length() < 1000) {
            try {
                val urlContent = file.readText().trim()
                if (urlContent.startsWith("http")) Uri.parse(urlContent) else return
            } catch (e: Exception) { return }
        } else if (file.exists()) {
            Uri.fromFile(file)
        } else { return }

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )

            try {
                setDataSource(applicationContext, uri)
                if (uri.toString().startsWith("http")) {
                    setOnPreparedListener { mp ->
                        mp.isLooping = isRepeatOn
                        mp.start()
                        notifyPlaybackStarted()
                    }
                    prepareAsync()
                } else {
                    prepare()
                    isLooping = isRepeatOn
                    start()
                    notifyPlaybackStarted()
                }

                setOnCompletionListener {
                    if (isRepeatOn) {
                        if (currentMediaType == "video") {
                            playCurrentVideoAudio()
                        } else {
                            playCurrentAudio()
                        }
                    } else if (isAutoplayOn) {
                        playNext()
                    } else {
                        // Track finished and autoplay is OFF
                        listeners.forEach { it.onPlaybackStateChanged(false) }

                        invalidateMediaSessionState()
                        updateNotification()

                        // Optional but recommended: reset to beginning
                        try {
                            seekTo(0)
                        } catch (_: Exception) {}
                    }
                }
                setOnErrorListener { _, _, _ -> playNext(); true }

            } catch (e: Exception) {
                releasePlayer()
                playNext()
            }
        }
    }

    private fun notifyPlaybackStarted() {
        listeners.forEach { it.onPlaybackStateChanged(true) }
        setupMediaSession()
        invalidateMediaSessionState()
        updateNotification()
        if (currentMediaType == "video") startVideoPositionUpdates()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun startVideoPositionUpdates() {
        if (currentMediaType != "video" || isPositionUpdateRunning) return
        isPositionUpdateRunning = true
        handler.post(videoPositionUpdateRunnable)
    }

    private fun setupPlaylistData(
        songList: ArrayList<File>,
        startPosition: Int,
        songTitles: ArrayList<String>?,
        songArtists: ArrayList<String>?,
        mediaTypes: ArrayList<String>?
    ) {
        songs = songList
        currentPosition = startPosition
        this.songTitles.clear()
        if (!songTitles.isNullOrEmpty() && songTitles.size == songList.size) {
            this.songTitles.addAll(songTitles)
        } else {
            songList.forEach { this.songTitles.add(it.nameWithoutExtension) }
        }
        this.mediaTypes.clear()
        if (mediaTypes != null && mediaTypes.size == songList.size) {
            this.mediaTypes.addAll(mediaTypes)
        } else {
            this.mediaTypes.addAll(List(songList.size) { "audio" })
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
        } else {
            mp.start()
            if (currentMediaType == "video") startVideoPositionUpdates()
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        listeners.forEach { it.onPlaybackStateChanged(mp.isPlaying) }
        invalidateMediaSessionState()
        updateNotification()
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            listeners.forEach { it.onPlaybackStateChanged(false) }
            invalidateMediaSessionState()
            updateNotification()
        }
    }

    fun play() {
        val mp = mediaPlayer ?: return
        if (!mp.isPlaying) {
            mp.start()
            if (currentMediaType == "video") startVideoPositionUpdates()
            startForeground(NOTIFICATION_ID, buildNotification())
            listeners.forEach { it.onPlaybackStateChanged(true) }
            invalidateMediaSessionState()
            updateNotification()
        }
    }

    fun toggleRepeat() {
        isRepeatOn = !isRepeatOn
        mediaPlayer?.isLooping = isRepeatOn
        listeners.forEach { it.onRepeatChanged(isRepeatOn) }
        invalidateMediaSessionState()
        updateNotification()
    }

    fun toggleAutoplay() {
        isAutoplayOn = !isAutoplayOn
        listeners.forEach { it.onAutoplayChanged(isAutoplayOn) }
    }

    fun fastForward() {
        mediaPlayer?.let { it.seekTo(it.currentPosition + 10_000) }
    }

    fun fastRewind() {
        mediaPlayer?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
    }

    fun seekTo(ms: Int) {
        mediaPlayer?.seekTo(ms)
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false
    fun isRepeat(): Boolean = isRepeatOn
    fun isAutoplay(): Boolean = isAutoplayOn
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getSongPosition(): Int = currentPosition
    fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: -1
    fun getCurrentMediaType(): String = if (currentPosition < mediaTypes.size) mediaTypes[currentPosition] else "audio"
    fun setActivityContext(context: String) { currentActivityContext = "unified" }

    private fun releasePlayer() {
        handler.removeCallbacks(videoPositionUpdateRunnable)
        isPositionUpdateRunning = false
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error in releasePlayer: ${e.message}")
            }
        }
        mediaPlayer = null
    }

    private fun setupMediaSession() {
        if (mediaSession != null) return
        media3Player = MusicPlayerStub()
        mediaSession = MediaSession.Builder(this, media3Player!!).build()
    }

    @UnstableApi
    private inner class MusicPlayerStub : androidx.media3.common.SimpleBasePlayer(mainLooper) {
        fun notifyStateChanged() = invalidateState()
        override fun getState(): State {
            val playbackState = if (mediaPlayer != null) Player.STATE_READY else Player.STATE_IDLE
            val currentItem = SimpleBasePlayer.MediaItemData.Builder(0)
                .setDurationUs(mediaPlayer?.duration?.let { it.toLong() * 1_000L } ?: C.TIME_UNSET)
                .build()
            return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE
                ).build())
                .setPlayWhenReady(mediaPlayer?.isPlaying ?: false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playbackState)
                .setPlaylist(listOf(currentItem))
                .setContentPositionMs { mediaPlayer?.currentPosition?.toLong() ?: 0L }
                .setRepeatMode(if (isRepeatOn) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF)
                .build()
        }
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) play() else pause()
            return Futures.immediateVoidFuture()
        }
        fun handleSeekToNextMediaItem(): ListenableFuture<*> { playNext(); return Futures.immediateVoidFuture() }
        fun handleSeekToPreviousMediaItem(): ListenableFuture<*> { playPrevious(); return Futures.immediateVoidFuture() }
        override fun handleSeek(idx: Int, pos: Long, cmd: Int): ListenableFuture<*> {
            seekTo(pos.toInt()); return Futures.immediateVoidFuture()
        }
    }

    private fun invalidateMediaSessionState() {
        media3Player?.notifyStateChanged()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Music Player", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val songName = if (currentPosition < this.songTitles.size) this.songTitles[currentPosition] else songs.getOrNull(currentPosition)?.name ?: "Unknown"
        val playing = isPlaying()
        val contentTitle = if (currentMediaType == "video") "Now Watching" else "Now Playing"
        fun actionPendingIntent(action: String): PendingIntent {
            val i = Intent(this, UnifiedPlayerService::class.java).apply { this.action = action }
            return PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val openAppIntent = Intent(this, UnifiedPlayerActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val openAppPending = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playPauseIcon = if (playing) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_library_music_24)
            .setContentTitle(contentTitle).setContentText(songName).setContentIntent(openAppPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setOngoing(playing)
            
        mediaSession?.let {
            builder.setStyle(MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(1, 2, 3))
        }
        
        builder.addAction(R.drawable.baseline_skip_previous_24, "Previous", actionPendingIntent(ACTION_PREVIOUS))
            .addAction(R.drawable.baseline_fast_rewind_24, "Fast Rewind", actionPendingIntent(ACTION_FAST_REWIND))
            .addAction(playPauseIcon, "Play/Pause", actionPendingIntent(ACTION_PLAY_PAUSE))
            .addAction(R.drawable.baseline_fast_forward_24, "Fast Forward", actionPendingIntent(ACTION_FAST_FORWARD))
            .addAction(R.drawable.baseline_skip_next_24, "Next", actionPendingIntent(ACTION_NEXT))
            
        return builder.build()
    }

    private fun updateNotification() {
        if (mediaPlayer != null) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }
}
