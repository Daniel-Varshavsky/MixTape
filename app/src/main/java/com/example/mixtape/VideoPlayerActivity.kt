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
 * FIXED VERSION: Handles clean stop-and-start transitions between video and audio.
 * No more complex seamless transition logic - just clean handoffs.
 *
 * Features:
 * - Fullscreen video playback with auto-hiding controls
 * - Landscape mode automatically goes fullscreen
 * - Uses MusicPlayerService for navigation but controls its own VideoView playback
 * - Clean transitions to MusicPlayerActivity when audio is encountered
 * - Notification controls work for navigation
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
    private var mediaTypes: ArrayList<String>? = null
    private var startPosition: Int = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MusicPlayerService.ServiceBinder
            musicService = b.getService()
            isBound = true

            musicService?.addListener(this@VideoPlayerActivity)

            // Initialize service playlist for navigation without starting audio playback
            musicService?.initPlaylistWithoutAutoplay(myVideos, startPosition, videoTitles, videoArtists, mediaTypes)

            // Start playing the current video
            playCurrentVideo()

            startSeekbarUpdater()
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
        Log.d(TAG, "Media changed to position $position: $songName")

        // Update video name display
        val displayTitle = if (!videoTitles.isNullOrEmpty() && position < videoTitles!!.size) {
            videoTitles!![position]
        } else {
            songName
        }
        txtVideoName.text = displayTitle

        // Start playing the new video (service coordinates position but we handle video playback)
        playCurrentVideo()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        // For video player, we control our own play/pause state through VideoView
        // This callback is mainly for synchronization with service state
        Log.d(TAG, "Service playback state changed: $isPlaying")
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
    }

    /**
     * NEW: Handle request to switch activities when audio content is encountered.
     */
    override fun onRequestActivitySwitch(position: Int, mediaType: String) {
        Log.d(TAG, "Activity switch requested for $mediaType at position $position")

        if (mediaType == "audio") {
            // Transition to MusicPlayerActivity
            transitionToMusicPlayer(position)
        }
        // If mediaType is "video", we should already be in the correct activity
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

        videoTitles = bundle.getStringArrayList("videoTitles")
        videoArtists = bundle.getStringArrayList("videoArtists")
        mediaTypes = bundle.getStringArrayList("mediaTypes")

        // Pre-populate the video name
        val displayTitle = if (!videoTitles.isNullOrEmpty() && startPosition < videoTitles!!.size) {
            videoTitles!![startPosition]
        } else {
            intent.getStringExtra("videoName") ?: myVideos[startPosition].nameWithoutExtension
        }

        txtVideoName.text = displayTitle
        txtVideoName.isSelected = true

        Log.d(TAG, "Intent read: ${myVideos.size} media items, starting at position $startPosition")
    }

    private fun setupVideoView() {
        videoView.setOnPreparedListener { mediaPlayer ->
            Log.d(TAG, "Video prepared successfully")
            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mediaPlayer.isLooping = false // Service handles looping logic
            buttonPlay.setImageResource(R.drawable.baseline_pause_24)
        }

        videoView.setOnCompletionListener {
            Log.d(TAG, "Video completed")
            buttonPlay.setImageResource(R.drawable.baseline_play_arrow_24)
            // Let the service handle next item logic
            musicService?.playNext()
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "VideoView error: what=$what, extra=$extra")
            buttonPlay.setImageResource(R.drawable.baseline_play_arrow_24)

            Toast.makeText(this, "Video error - skipping to next", Toast.LENGTH_SHORT).show()

            // Skip to next item after error
            handler.postDelayed({
                musicService?.playNext()
            }, 2000)

            true // Error handled
        }

        // Show controls on video tap
        videoView.setOnClickListener {
            toggleControls()
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
                videoView.seekTo(progress)
                Log.d(TAG, "Video seeked to ${progress}ms")
                handler.post(seekbarRunnable)
                resetHideControlsTimer()
            }
        })

        // Button click listeners
        buttonPlay.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                buttonPlay.setImageResource(R.drawable.baseline_play_arrow_24)
                Log.d(TAG, "Video paused via button")
            } else {
                videoView.start()
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

        controlsOverlay.setOnClickListener {
            resetHideControlsTimer()
        }
    }

    private fun setupFullscreenHandling() {
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                showControls()
            }
        }
    }

    private fun setupBackPressHandling() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (resources.configuration.orientation) {
                    Configuration.ORIENTATION_LANDSCAPE -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
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

            // Make sure service audio is stopped to prevent conflicts
            musicService?.stopCurrentPlayback()

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
            videoView.start()

        } catch (e: Exception) {
            Log.e(TAG, "Error playing video: ${e.message}", e)
            Toast.makeText(this, "Error loading video: ${e.message}", Toast.LENGTH_SHORT).show()

            // Skip to next on error
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
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun resetHideControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
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

        val displayTitle = if (!videoTitles.isNullOrEmpty() && pos < videoTitles!!.size) {
            videoTitles!![pos]
        } else if (pos < myVideos.size) {
            myVideos[pos].nameWithoutExtension
        } else {
            "Unknown Video"
        }

        txtVideoName.text = displayTitle
        updateSeekbar()
        updateRepeatButtonVisual(service.isRepeat())
    }

    private fun updateSeekbar() {
        val videoDuration = videoView.duration
        val videoPosition = videoView.currentPosition

        if (videoDuration > 0) {
            seekbar.max = videoDuration
            seekbar.progress = videoPosition
            txtVideoStart.text = createTime(videoPosition)
            txtVideoStop.text = createTime(videoDuration)
        } else {
            // Fallback if video not ready yet
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
     * Clean transition to MusicPlayerActivity when audio content is encountered.
     */
    private fun transitionToMusicPlayer(position: Int) {
        try {
            val intent = Intent(this, MusicPlayerActivity::class.java).apply {
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
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning to music player: ${e.message}", e)
            Toast.makeText(this, "Error opening music player", Toast.LENGTH_SHORT).show()
        }
    }
}