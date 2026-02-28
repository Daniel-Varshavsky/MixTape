package com.example.mixtape

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import com.example.mixtape.service.MusicPlayerService
import com.example.mixtape.R
import java.io.File

/**
 * VideoPlayerActivity
 *
 * Features:
 * - Fullscreen video playback with auto-hiding controls
 * - Landscape mode automatically goes fullscreen
 * - Uses the same MusicPlayerService for audio consistency
 * - Background audio playback when minimized
 * - Notification controls work seamlessly
 * - Same control scheme as MusicPlayerActivity
 */
@UnstableApi
class VideoPlayerActivity : AppCompatActivity(), MusicPlayerService.PlayerListener {

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val CONTROLS_HIDE_DELAY = 3000L // 3 seconds
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var videoView: VideoView
    private lateinit var controlsOverlay: ViewGroup
    private lateinit var buttonPlay: ImageButton
    private lateinit var buttonNext: ImageButton
    private lateinit var buttonPrevious: ImageButton
    private lateinit var buttonFastForward: ImageButton
    private lateinit var buttonFastRewind: ImageButton
    private lateinit var buttonRepeat: ImageButton
    private lateinit var buttonFullscreen: ImageButton
    private lateinit var txtVideoName: TextView
    private lateinit var txtVideoStart: TextView
    private lateinit var txtVideoStop: TextView
    private lateinit var seekbar: SeekBar
    private lateinit var topControls: ViewGroup
    private lateinit var bottomControls: ViewGroup

    // ─────────────────────────────────────────────────────────────────────────
    // Service binding & data
    // ─────────────────────────────────────────────────────────────────────────

    private var musicService: MusicPlayerService? = null
    private var isBound = false

    private lateinit var myVideos: ArrayList<File>
    private var videoTitles: ArrayList<String>? = null
    private var videoArtists: ArrayList<String>? = null
    private var mediaTypes: ArrayList<String>? = null  // NEW: Track audio vs video
    private var startPosition: Int = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MusicPlayerService.ServiceBinder
            musicService = b.getService()
            isBound = true

            musicService?.addListener(this@VideoPlayerActivity)

            // Initialize playlist if not already playing
            if (musicService?.isPlaying() == false && !servicePreviouslyRunning) {
                // For videos, initialize the playlist but DON'T start playback
                // VideoPlayerActivity will handle playback independently
                musicService?.initPlaylistWithoutAutoplay(myVideos, startPosition, videoTitles, videoArtists, mediaTypes)
                Log.d(TAG, "Initialized service playlist without autoplay for video control")
                playCurrentVideo()
            } else {
                // Service is already playing something else - pause it and take control
                musicService?.pause()
                Log.d(TAG, "Paused existing service playback to avoid conflicts")
                syncUiToServiceState()
                playCurrentVideo()
            }

            startSeekbarUpdater()

            // Mark initial setup as complete after a longer delay for videos
            // Videos need more time to establish stable playback
            handler.postDelayed({
                isInitialSetup = false
                Log.d(TAG, "VideoPlayerActivity initial setup complete - transitions now enabled")
            }, 3000) // Extended to 3 seconds for video stability
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control visibility & timing
    // ─────────────────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var servicePreviouslyRunning = false
    private var isInitialSetup = true  // NEW: Track if we're still in initial setup
    private var isVideoPlaying = false // NEW: Track if video is actively playing

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val seekbarRunnable = object : Runnable {
        override fun run() {
            updateSeekbar()
            handler.postDelayed(this, 500)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        // Hide action bar
        supportActionBar?.hide()

        findViews()
        readIntent()
        setupVideoView()
        setupControls()
        setupFullscreenHandling()
        setupBackPressHandling()

        // Start and bind to service
        val serviceIntent = Intent(this, MusicPlayerService::class.java)
        startService(serviceIntent)

        servicePreviouslyRunning = savedInstanceState?.getBoolean("service_running", false) ?: false
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Start with controls visible
        showControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("service_running", isBound && (musicService?.isPlaying() == true))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (isBound) {
            startSeekbarUpdater()
            syncUiToServiceState()
            playCurrentVideo()
        }
        handleOrientationChange()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(seekbarRunnable)
        handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        musicService?.removeListener(this)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleOrientationChange()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PlayerListener callbacks
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSongChanged(position: Int, songName: String) {
        Log.d(TAG, "=== VideoPlayerActivity.onSongChanged ===")
        Log.d(TAG, "Position: $position, SongName: '$songName'")
        Log.d(TAG, "isInitialSetup: $isInitialSetup")
        Log.d(TAG, "isVideoPlaying: $isVideoPlaying")
        Log.d(TAG, "mediaTypes size: ${mediaTypes?.size ?: 0}")
        Log.d(TAG, "mediaTypes content: ${mediaTypes?.joinToString(", ") ?: "null"}")

        // Don't transition during initial setup - prevents unwanted switches
        if (isInitialSetup) {
            Log.d(TAG, "🛡️  Still in initial setup - BLOCKING transition logic")
            // Update video name but don't transition
            val displayTitle = if (!videoTitles.isNullOrEmpty() && position < videoTitles!!.size) {
                videoTitles!![position]
            } else {
                songName
            }
            txtVideoName.text = displayTitle
            return
        }

        // Additional safety check: don't transition if we're currently playing video successfully
        if (isVideoPlaying) {
            Log.d(TAG, "🎬 Video is actively playing - BLOCKING transition to avoid interruption")
            // Update title but stay in video player
            val displayTitle = if (!videoTitles.isNullOrEmpty() && position < videoTitles!!.size) {
                videoTitles!![position]
            } else {
                songName
            }
            txtVideoName.text = displayTitle
            return
        }

        // NOW CHECK: What type of media should we be playing?
        if (!mediaTypes.isNullOrEmpty() && position < mediaTypes!!.size) {
            val currentMediaType = mediaTypes!![position]
            Log.d(TAG, "Current media type at position $position: '$currentMediaType'")

            if (currentMediaType == "audio") {
                // This should be played in MusicPlayerActivity - transition there
                Log.d(TAG, "🎵 Current item is AUDIO - should be in MusicPlayerActivity, transitioning...")
                transitionToMusicPlayer(position)
                return
            } else if (currentMediaType == "video") {
                // This should stay in VideoPlayerActivity - play the video
                Log.d(TAG, "🎬 Current item is VIDEO - correct player, playing video...")
            }
        } else {
            Log.w(TAG, "mediaTypes is null/empty or position out of bounds - assuming video")
        }

        // Update video name display and continue with video playback
        val displayTitle = if (!videoTitles.isNullOrEmpty() && position < videoTitles!!.size) {
            videoTitles!![position]
        } else {
            songName
        }

        txtVideoName.text = displayTitle
        playCurrentVideo()

        Log.d(TAG, "Video updated: $displayTitle at position $position")
        Log.d(TAG, "=== End VideoPlayerActivity.onSongChanged ===")
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        // Update play button based on VideoView state, not service state
        val videoIsPlaying = videoView.isPlaying
        buttonPlay.setImageResource(
            if (videoIsPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
        )

        Log.d(TAG, "Play button updated - VideoView playing: $videoIsPlaying, Service playing: $isPlaying")
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup methods
    // ─────────────────────────────────────────────────────────────────────────

    private fun findViews() {
        videoView         = findViewById(R.id.videoView)
        controlsOverlay   = findViewById(R.id.controlsOverlay)
        topControls       = findViewById(R.id.topControls)
        bottomControls    = findViewById(R.id.bottomControls)
        buttonPlay        = findViewById(R.id.buttonPlay)
        buttonNext        = findViewById(R.id.buttonNext)
        buttonPrevious    = findViewById(R.id.buttonPrevious)
        buttonFastForward = findViewById(R.id.buttonFastForward)
        buttonFastRewind  = findViewById(R.id.buttonFastRewind)
        buttonRepeat      = findViewById(R.id.buttonRepeat)
        buttonFullscreen  = findViewById(R.id.buttonFullscreen)
        txtVideoName      = findViewById(R.id.txtVideoName)
        txtVideoStart     = findViewById(R.id.txtVideoStart)
        txtVideoStop      = findViewById(R.id.txtVideoStop)
        seekbar           = findViewById(R.id.videoSeekbar)
    }

    private fun readIntent() {
        val bundle = intent.extras!!
        val paths = bundle.getStringArrayList("videos")!!
        myVideos = ArrayList(paths.map { File(it) })
        startPosition = bundle.getInt("pos", 0)

        // Get video titles, artists, and media types if provided
        videoTitles = bundle.getStringArrayList("videoTitles")
        videoArtists = bundle.getStringArrayList("videoArtists")
        mediaTypes = bundle.getStringArrayList("mediaTypes")  // NEW: Track media types

        // Pre-populate the video name
        val displayTitle = if (!videoTitles.isNullOrEmpty() && startPosition < videoTitles!!.size) {
            videoTitles!![startPosition]
        } else {
            intent.getStringExtra("videoName") ?: myVideos[startPosition].nameWithoutExtension
        }

        txtVideoName.text = displayTitle
        txtVideoName.isSelected = true // Enable marquee scrolling

        Log.d(TAG, "Intent read: ${myVideos.size} media items, starting at position $startPosition")
        Log.d(TAG, "Media types provided: ${mediaTypes?.joinToString(", ") ?: "None"}")
    }

    private fun setupVideoView() {
        // Set up video view with proper scaling
        videoView.setOnPreparedListener { mediaPlayer ->
            Log.d(TAG, "Video prepared successfully")
            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mediaPlayer.isLooping = false // Service handles looping
            isVideoPlaying = true
            buttonPlay.setImageResource(R.drawable.baseline_pause_24) // Show pause when playing
            Log.d(TAG, "Video prepared - play button set to pause icon")
        }

        videoView.setOnCompletionListener {
            Log.d(TAG, "Video completed")
            isVideoPlaying = false
            buttonPlay.setImageResource(R.drawable.baseline_play_arrow_24) // Show play when stopped
            // Let the service handle next video
            musicService?.playNext()
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "VideoView error: what=$what, extra=$extra")
            isVideoPlaying = false
            buttonPlay.setImageResource(R.drawable.baseline_play_arrow_24) // Show play on error

            val errorMessage = when (what) {
                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Media server error"
                MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Unknown media error"
                else -> "Media error: $what"
            }

            val extraMessage = when (extra) {
                MediaPlayer.MEDIA_ERROR_IO -> "Network/IO error"
                MediaPlayer.MEDIA_ERROR_MALFORMED -> "Invalid video format"
                MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "Unsupported video format"
                MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "Network timeout"
                else -> "Error code: $extra"
            }

            Log.e(TAG, "VideoView error details: $errorMessage - $extraMessage")
            Toast.makeText(this, "Video error: $extraMessage", Toast.LENGTH_LONG).show()

            // Try to skip to next video after a delay
            handler.postDelayed({
                Log.d(TAG, "Skipping to next video due to error")
                musicService?.playNext()
            }, 2000)

            true // Error handled
        }

        // Show controls on video tap - ensure this always works
        videoView.setOnClickListener {
            Log.d(TAG, "VideoView clicked")
            toggleControls()
        }

        // Also handle touch events directly as backup
        videoView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "VideoView touched")
                toggleControls()
            }
            false // Let other touch handling continue
        }
    }

    private fun setupControls() {
        // Seekbar setup
        seekbar.progressDrawable.setColorFilter(
            ContextCompat.getColor(this, R.color.red),
            PorterDuff.Mode.SRC_IN
        )
        seekbar.thumb.setTint(ContextCompat.getColor(this, R.color.red))

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(seekbarRunnable)
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                // Seek VideoView directly for videos
                videoView.seekTo(progress)
                Log.d(TAG, "Video seeked to ${progress}ms")
                handler.post(seekbarRunnable)
                resetHideControlsTimer()
            }
        })

        // Button click listeners
        buttonPlay.setOnClickListener {
            // For videos, control VideoView directly instead of service
            if (videoView.isPlaying) {
                videoView.pause()
                isVideoPlaying = false
                buttonPlay.setImageResource(R.drawable.baseline_play_arrow_24)
                Log.d(TAG, "Video paused via button")
            } else {
                videoView.start()
                isVideoPlaying = true
                buttonPlay.setImageResource(R.drawable.baseline_pause_24)
                Log.d(TAG, "Video started via button")
            }
            resetHideControlsTimer()
        }

        buttonNext.setOnClickListener {
            Log.d(TAG, "Next button pressed")
            musicService?.playNext()
            resetHideControlsTimer()
        }

        buttonPrevious.setOnClickListener {
            Log.d(TAG, "Previous button pressed")
            musicService?.playPrevious()
            resetHideControlsTimer()
        }

        buttonFastForward.setOnClickListener {
            val newPosition = videoView.currentPosition + 10000
            videoView.seekTo(newPosition)
            Log.d(TAG, "Video fast forward to ${newPosition}ms")
            resetHideControlsTimer()
        }

        buttonFastRewind.setOnClickListener {
            val newPosition = (videoView.currentPosition - 10000).coerceAtLeast(0)
            videoView.seekTo(newPosition)
            Log.d(TAG, "Video fast rewind to ${newPosition}ms")
            resetHideControlsTimer()
        }

        buttonRepeat.setOnClickListener {
            musicService?.toggleRepeat()
            resetHideControlsTimer()
        }

        buttonFullscreen.setOnClickListener {
            toggleFullscreen()
            resetHideControlsTimer()
        }

        // Show controls when any control is touched
        controlsOverlay.setOnClickListener {
            resetHideControlsTimer()
        }
    }

    private fun setupFullscreenHandling() {
        // Handle system UI visibility
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                // System bars are visible
                showControls()
            }
        }
    }

    private fun setupBackPressHandling() {
        // Modern back gesture handling
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (resources.configuration.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> {
                        // In landscape, first go to portrait
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    else -> {
                        // In portrait, exit normally
                        isEnabled = false // Disable this callback
                        onBackPressedDispatcher.onBackPressed() // Let system handle it
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Video playback
    // ─────────────────────────────────────────────────────────────────────────

    private fun playCurrentVideo() {
        if (myVideos.isEmpty()) return

        val position = musicService?.getSongPosition() ?: startPosition
        if (position >= myVideos.size) return

        val videoFile = myVideos[position]

        try {
            Log.d(TAG, "Attempting to play video at position $position: ${videoFile.name}")

            // CRITICAL: Completely stop the music service to prevent background audio
            musicService?.pause()
            musicService?.let { service ->
                // Force stop any current MediaPlayer instance in service
                if (service.isPlaying()) {
                    service.pause()
                    Log.d(TAG, "Force stopped service MediaPlayer")
                }
            }

            // Determine URI (Firebase URL or local file)
            val uri = if (videoFile.exists() && videoFile.length() < 1000) {
                // Placeholder file containing Firebase URL
                val urlContent = videoFile.readText().trim()
                Log.d(TAG, "Read URL content: '$urlContent'")

                if (urlContent.startsWith("http://") || urlContent.startsWith("https://")) {
                    val parsedUri = Uri.parse(urlContent)
                    Log.d(TAG, "Using Firebase Storage URL: $parsedUri")
                    parsedUri
                } else {
                    Log.e(TAG, "Invalid URL content in placeholder file: '$urlContent'")
                    Toast.makeText(this, "Invalid video URL", Toast.LENGTH_SHORT).show()
                    return
                }
            } else if (videoFile.exists()) {
                Log.d(TAG, "Using local video file: ${videoFile.absolutePath}")
                Uri.fromFile(videoFile)
            } else {
                Log.e(TAG, "Video file doesn't exist: ${videoFile.absolutePath}")
                Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d(TAG, "Setting VideoView URI: $uri")
            videoView.setVideoURI(uri)

            // Start video playback immediately (VideoView has exclusive control)
            videoView.start()
            isVideoPlaying = true // Mark as playing to block transitions
            buttonPlay.setImageResource(R.drawable.baseline_pause_24) // Show pause icon
            Log.d(TAG, "Started video playback with exclusive control")

        } catch (e: Exception) {
            Log.e(TAG, "Error playing video: ${e.message}", e)
            Toast.makeText(this, "Error loading video: ${e.message}", Toast.LENGTH_SHORT).show()

            // Try to skip to next video on error
            handler.postDelayed({
                musicService?.playNext()
            }, 1000)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Controls visibility management
    // ─────────────────────────────────────────────────────────────────────────

    private fun showControls() {
        if (controlsVisible) return

        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.alpha = 0f

        controlsOverlay.animate()
            .alpha(1f)
            .setDuration(200)
            .start()

        controlsVisible = true

        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
    }

    private fun hideControls() {
        if (!controlsVisible) return

        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                controlsOverlay.visibility = View.GONE
            }
            .start()

        controlsVisible = false
    }

    private fun toggleControls() {
        videoView.setOnClickListener {
            if (controlsVisible)
                hideControls()
            else
                showControls()
        }
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
            Log.d(TAG, "Hide controls timer reset - will hide in ${CONTROLS_HIDE_DELAY}ms")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fullscreen handling
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleOrientationChange() {
        when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                enterFullscreen()
                buttonFullscreen.setImageResource(R.drawable.baseline_fullscreen_exit_24)
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                exitFullscreen()
                buttonFullscreen.setImageResource(R.drawable.baseline_fullscreen_24)
            }
        }
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun enterFullscreen() {
        hideSystemBars()
        supportActionBar?.hide()
    }

    private fun exitFullscreen() {
        if (controlsVisible) {
            showSystemBars()
        }
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────────────────

    private fun syncUiToServiceState() {
        val service = musicService ?: return
        val pos = service.getSongPosition()

        // Use proper video titles
        val displayTitle = if (!videoTitles.isNullOrEmpty() && pos < videoTitles!!.size) {
            videoTitles!![pos]
        } else if (pos < myVideos.size) {
            myVideos[pos].nameWithoutExtension
        } else {
            "Unknown Video"
        }

        txtVideoName.text = displayTitle
        updateSeekbar()
        onPlaybackStateChanged(service.isPlaying())
        updateRepeatButtonVisual(service.isRepeat())
    }

    private fun updateSeekbar() {
        val videoDuration = videoView.duration
        val videoPosition = videoView.currentPosition

        // Use video timing for UI updates (VideoView is the source of truth for videos)
        if (videoDuration > 0) {
            seekbar.max = videoDuration
            seekbar.progress = videoPosition
            txtVideoStart.text = createTime(videoPosition)
            txtVideoStop.text = createTime(videoDuration)
        } else {
            // Fallback to service timing if video not ready yet
            val service = musicService
            if (service != null) {
                seekbar.max = service.getDuration()
                seekbar.progress = service.getCurrentPosition()
                txtVideoStart.text = createTime(service.getCurrentPosition())
                txtVideoStop.text = createTime(service.getDuration())
            }
        }
    }

    private fun startSeekbarUpdater() {
        handler.removeCallbacks(seekbarRunnable)
        handler.post(seekbarRunnable)
    }

    private fun updateRepeatButtonVisual(isRepeat: Boolean) {
        buttonRepeat.setImageResource(
            if (isRepeat) R.drawable.baseline_repeat_24 else R.drawable.outline_repeat_24
        )
    }

    private fun createTime(duration: Int): String {
        val min = duration / 1000 / 60
        val sec = duration / 1000 % 60
        return "$min:${if (sec < 10) "0$sec" else "$sec"}"
    }

    /**
     * Seamlessly transition to MusicPlayerActivity when the current media is audio.
     * Preserves the current playlist position and state.
     */
    private fun transitionToMusicPlayer(position: Int) {
        try {
            val intent = Intent(this, MusicPlayerActivity::class.java).apply {
                // Pass all the same data that VideoPlayerActivity received
                val filePaths = myVideos.map { it.absolutePath }
                putExtra("songs", ArrayList(filePaths)) // MusicPlayerActivity expects "songs"
                putExtra("songTitles", videoTitles)
                putExtra("songArtists", videoArtists)
                putExtra("mediaTypes", mediaTypes)
                putExtra("pos", position) // Start from the current position
                putExtra("songName", if (!videoTitles.isNullOrEmpty() && position < videoTitles!!.size) {
                    videoTitles!![position]
                } else {
                    "Song ${position + 1}"
                })
            }

            Log.d(TAG, "Transitioning to MusicPlayerActivity at position $position")
            startActivity(intent)

            // Close this activity so the user doesn't return to video player when pressing back
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning to music player: ${e.message}", e)
        }
    }
}