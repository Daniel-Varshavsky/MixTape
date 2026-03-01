package com.example.mixtape

import android.Manifest
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
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
import kotlin.math.abs

/**
 * ENHANCED VideoPlayerActivity
 *
 * NEW FEATURES:
 * - Video audio continues playing when app is minimized via MusicPlayerService
 * - Service handles audio, VideoView handles visuals with synchronization
 * - Seamless background audio for video content
 * - Perfect sync between audio (service) and video (VideoView)
 *
 * KEY ARCHITECTURE:
 * 1. MusicPlayerService provides audio playback (continues in background)
 * 2. VideoView provides visual playback (pauses when backgrounded)
 * 3. Synchronization via onVideoPositionUpdate callback
 * 4. All playback controls route through service for consistency
 */
@UnstableApi
class VideoPlayerActivity : AppCompatActivity(), MusicPlayerService.PlayerListener {

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val CONTROLS_HIDE_DELAY = 3000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var videoView: VideoView
    private lateinit var controlsOverlay: RelativeLayout
    private lateinit var topControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
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

    // ─────────────────────────────────────────────────────────────────────────
    // Service binding
    // ─────────────────────────────────────────────────────────────────────────

    private var musicService: MusicPlayerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MusicPlayerService.ServiceBinder
            musicService = b.getService()
            isBound = true

            musicService?.addListener(this@VideoPlayerActivity)

            // Initialize service playlist for navigation without starting audio playback
            musicService?.initPlaylistWithoutAutoplay(myVideos, startPosition, videoTitles, videoArtists, mediaTypes)

            // Start playing the current video audio through service
            musicService?.playCurrentVideoAudio()

            // Start playing the current video visuals through VideoView
            playCurrentVideoVisual()

            startSeekbarUpdater()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data from Intent
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var myVideos: ArrayList<File>
    private var videoTitles: ArrayList<String>? = null
    private var videoArtists: ArrayList<String>? = null
    private var mediaTypes: ArrayList<String>? = null
    private var startPosition: Int = 0

    // ─────────────────────────────────────────────────────────────────────────
    // UI State
    // ─────────────────────────────────────────────────────────────────────────

    private var controlsVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private var servicePreviouslyRunning = false

    // ─────────────────────────────────────────────────────────────────────────
    // UI update runnables
    // ─────────────────────────────────────────────────────────────────────────

    private val seekbarRunnable = object : Runnable {
        override fun run() {
            // Position updates now come from onVideoPositionUpdate callback
            // This runnable just ensures UI stays responsive
            handler.postDelayed(this, 500)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideControls()
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

            // Resume VideoView to sync with ongoing service audio
            if (musicService?.isPlaying() == true) {
                videoView.start()
                // VideoView will sync its position via onVideoPositionUpdate callbacks
            }
        }
        handleOrientationChange()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(seekbarRunnable)
        handler.removeCallbacks(hideControlsRunnable)

        // CRITICAL: VideoView pauses but SERVICE CONTINUES PLAYING AUDIO in background!
        // This is the key feature - video audio continues when app is minimized
        Log.d(TAG, "Activity paused - video visual stopped but audio continues in background")
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
    // PlayerListener callbacks (from MusicPlayerService)
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

        // Start playing the new video visual (service handles audio)
        playCurrentVideoVisual()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        // Update play/pause button to reflect service state
        val iconRes = if (isPlaying) {
            R.drawable.baseline_pause_24
        } else {
            R.drawable.baseline_play_arrow_24
        }
        buttonPlay.setImageResource(iconRes)

        // Sync VideoView state with service
        if (isPlaying && !videoView.isPlaying) {
            videoView.start()
        } else if (!isPlaying && videoView.isPlaying) {
            videoView.pause()
        }

        Log.d(TAG, "Playback state changed: ${if (isPlaying) "playing" else "paused"}")
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
    }

    override fun onRequestActivitySwitch(position: Int, mediaType: String) {
        Log.d(TAG, "Activity switch requested for $mediaType at position $position")

        if (mediaType == "audio") {
            // Transition to MusicPlayerActivity
            transitionToMusicPlayer(position)
        }
        // If mediaType is "video", we should already be in the correct activity
    }

    /**
     * NEW: Critical callback for video/audio synchronization
     * This keeps VideoView visuals in sync with service audio
     */
    override fun onVideoPositionUpdate(position: Int, duration: Int) {
        // Sync VideoView with service audio position
        val videoPosition = videoView.currentPosition
        val positionDiff = abs(videoPosition - position)

        // If positions are significantly out of sync (> 1 second), seek VideoView
        if (positionDiff > 1000) {
            Log.d(TAG, "Syncing video position: video=$videoPosition, audio=$position")
            videoView.seekTo(position)
        }

        // Update UI elements
        seekbar.max = duration
        seekbar.progress = position
        txtVideoStart.text = createTime(position)
        txtVideoStop.text = createTime(duration)

        // Start VideoView if it's not playing but service is
        if (musicService?.isPlaying() == true && !videoView.isPlaying) {
            videoView.start()
        }
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

            // Optional: Reduce VideoView volume since service handles audio
            // mediaPlayer.setVolume(0.1f, 0.1f) // Low volume, not muted
        }

        videoView.setOnCompletionListener {
            Log.d(TAG, "Video visual completed")
            // Service handles completion logic when audio finishes
            // Don't call musicService?.playNext() here to avoid double-triggering
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "VideoView error: what=$what, extra=$extra")
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
                // Seek both service audio and VideoView
                musicService?.seekTo(progress)
                videoView.seekTo(progress)
                Log.d(TAG, "Video and audio seeked to ${progress}ms")
                handler.post(seekbarRunnable)
                resetHideControlsTimer()
            }
        })

        // ENHANCED: All playback controls route through service
        buttonPlay.setOnClickListener {
            musicService?.togglePlayPause()
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
            musicService?.fastForward()
            // Also fast forward VideoView to stay in sync
            val newPosition = videoView.currentPosition + 10000
            videoView.seekTo(newPosition)
            resetHideControlsTimer()
        }

        buttonFastRewind.setOnClickListener {
            musicService?.fastRewind()
            // Also rewind VideoView to stay in sync
            val newPosition = (videoView.currentPosition - 10000).coerceAtLeast(0)
            videoView.seekTo(newPosition)
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

    /**
     * ENHANCED: Play video visuals only - audio is handled by service
     */
    private fun playCurrentVideoVisual() {
        if (myVideos.isEmpty()) return

        val position = musicService?.getSongPosition() ?: startPosition
        if (position >= myVideos.size) return

        val videoFile = myVideos[position]

        try {
            Log.d(TAG, "Playing video visual at position $position: ${videoFile.name}")

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

            // Don't auto-start VideoView - wait for service sync via onVideoPositionUpdate

        } catch (e: Exception) {
            Log.e(TAG, "Error playing video visual: ${e.message}", e)
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
        updateRepeatButtonVisual(service.isRepeat())

        // Position updates will come from onVideoPositionUpdate callback
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