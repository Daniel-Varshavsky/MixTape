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
import java.io.File

/**
 * MusicPlayerService
 *
 * A foreground Service that owns the MediaPlayer and exposes playback controls
 * to bound clients (MusicPlayerActivity) through the ServiceBinder.
 *
 * The notification shows persistent media controls (previous, play/pause, next, repeat)
 * that work even when the app is in the background or the screen is off.
 *
 * Interaction flow:
 *   Activity  ──bind──►  Service (controls MediaPlayer + MediaSession)
 *   Service  ──callbacks──►  Activity (UI updates via Listener interface)
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

        // Intent actions sent by notification buttons
        const val ACTION_PLAY_PAUSE = "com.example.mixtape.PLAY_PAUSE"
        const val ACTION_NEXT       = "com.example.mixtape.NEXT"
        const val ACTION_PREVIOUS   = "com.example.mixtape.PREVIOUS"
        const val ACTION_REPEAT     = "com.example.mixtape.REPEAT"
        const val ACTION_STOP       = "com.example.mixtape.STOP"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────


    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSession

    /** The flat song list passed in by the Activity. */
    private var songs: ArrayList<File> = arrayListOf()
    private var currentPosition: Int = 0
    private var isRepeatOn: Boolean = false

    /** Callback interface so the bound Activity can react to service-driven events. */
    interface PlayerListener {
        fun onSongChanged(position: Int, songName: String)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onRepeatChanged(isRepeat: Boolean)
    }

    private var listener: PlayerListener? = null

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
        // Media3's MediaSession automatically routes hardware/Bluetooth media button
        // events — no manual handleIntent() call needed.
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT       -> playNext()
            ACTION_PREVIOUS   -> playPrevious()
            ACTION_REPEAT     -> toggleRepeat()
            ACTION_STOP       -> stopSelf()
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

    fun setListener(l: PlayerListener?) {
        listener = l
    }

    fun initPlaylist(songList: ArrayList<File>, startPosition: Int) {
        songs = songList
        currentPosition = startPosition
        playCurrent()
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
        } else {
            mp.start()
        }
        listener?.onPlaybackStateChanged(mp.isPlaying)
        invalidateMediaSessionState()
        updateNotification()
    }

    fun playNext() {
        currentPosition = (currentPosition + 1) % songs.size
        playCurrent()
    }

    fun playPrevious() {
        currentPosition = if (currentPosition - 1 < 0) songs.size - 1 else currentPosition - 1
        playCurrent()
    }

    fun toggleRepeat() {
        isRepeatOn = !isRepeatOn
        mediaPlayer?.isLooping = isRepeatOn
        listener?.onRepeatChanged(isRepeatOn)
        invalidateMediaSessionState()
        updateNotification()
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

    // ─────────────────────────────────────────────────────────────────────────
    // Internal playback
    // ─────────────────────────────────────────────────────────────────────────

    private fun playCurrent() {
        if (songs.isEmpty()) return

        releasePlayer()

        val file = songs[currentPosition]
        val uri  = Uri.fromFile(file)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(applicationContext, uri)
            prepare()
            isLooping = isRepeatOn
            start()

            setOnCompletionListener {
                if (!isRepeatOn) playNext()
                // If repeat is on, MediaPlayer loops automatically via isLooping = true
            }
        }

        // Notify the bound Activity
        listener?.onSongChanged(currentPosition, file.name)
        listener?.onPlaybackStateChanged(true)

        invalidateMediaSessionState()
        updateNotification()

        // Promote to foreground so Android won't kill us mid-song
        startForeground(NOTIFICATION_ID, buildNotification())
    }

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
     *
     * We advertise PLAY_PAUSE, SEEK_TO_NEXT, and SEEK_TO_PREVIOUS so that
     * hardware/Bluetooth buttons and the notification MediaStyle work correctly.
     *
     * Note: SimpleBasePlayer is @UnstableApi — the @UnstableApi on MusicPlayerService
     * covers this entire file.
     */
    @UnstableApi
    private inner class MusicPlayerStub : androidx.media3.common.SimpleBasePlayer(mainLooper) {

        /** Call this whenever MediaPlayer state changes so Media3 re-reads [getState]. */
        fun notifyStateChanged() = invalidateState()

        override fun getState(): State {
            val playbackState = if (mediaPlayer != null) Player.STATE_READY else Player.STATE_IDLE

            // Duration lives on MediaItemData, not State.Builder directly.
            // Build a one-item playlist entry so Media3 can expose duration
            // to the notification and lock-screen seek bar.
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
                listener?.onPlaybackStateChanged(true)
            } else {
                mediaPlayer?.pause()
                listener?.onPlaybackStateChanged(false)
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
     * Builds a media-style notification with five actions:
     *   [Previous] [Play/Pause] [Next] [Repeat] [✕ Stop]
     *
     * Each button fires an explicit Intent to this service so it works
     * even when the app process is not in the foreground.
     */
    private fun buildNotification(): Notification {
        val songName = songs.getOrNull(currentPosition)?.name ?: "Unknown"
        val playing  = isPlaying()

        // ── Pending intents for each button ──────────────────────────────────
        fun actionPendingIntent(action: String): PendingIntent {
            val i = Intent(this, MusicPlayerService::class.java).apply { this.action = action }
            return PendingIntent.getService(
                this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Tapping the notification body reopens the player Activity
        val openAppIntent = Intent(this, MusicPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repeatIcon = if (isRepeatOn) R.drawable.baseline_repeat_24 else R.drawable.outline_repeat_24
        val playPauseIcon = if (playing) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_library_music_24)
            .setContentTitle("Now Playing")
            .setContentText(songName)
            .setContentIntent(openAppPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)  // sticky only while playing; dismissible when paused
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(mediaSession)
                    .setShowActionsInCompactView(0, 1, 2) // previous, play/pause, next in compact
            )
            .addAction(R.drawable.baseline_skip_previous_24, "Previous",  actionPendingIntent(ACTION_PREVIOUS))
            .addAction(playPauseIcon,                "Play/Pause", actionPendingIntent(ACTION_PLAY_PAUSE))
            .addAction(R.drawable.baseline_skip_next_24,     "Next",       actionPendingIntent(ACTION_NEXT))
            .addAction(repeatIcon,                   "Repeat",     actionPendingIntent(ACTION_REPEAT))
            .addAction(R.drawable.outline_close_24,          "Stop",       actionPendingIntent(ACTION_STOP))
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }
}