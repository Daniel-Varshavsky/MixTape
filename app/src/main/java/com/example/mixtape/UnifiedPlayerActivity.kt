package com.example.mixtape

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import android.widget.VideoView
import android.widget.RelativeLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import com.example.mixtape.service.UnifiedPlayerService
import com.example.mixtape.ui.BarVisualizerView
import java.io.File
import kotlin.math.abs

// UPDATED IMPORTS for Material Design components
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.LinearLayoutCompat
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.button.MaterialButton

/**
 * UnifiedPlayerActivity - COMPLETE MODERNIZED VERSION
 *
 * UNIFIED PLAYER: Combines both audio and video playback in a single activity!
 *
 * KEY FEATURES:
 * - Single activity handles both audio and video content seamlessly
 * - Dynamic UI switching: Audio layout with visualizer OR Video layout with overlay controls
 * - MusicPlayerService integration for consistent background audio (both audio files and video audio)
 * - VideoView handles video visuals, service handles audio for perfect sync
 * - Automatic layout switching based on current media type
 * - All modern Material Design components
 * - Background video audio playback when app is minimized
 *
 * ARCHITECTURE:
 * 1. Service handles ALL audio (pure audio files + video audio tracks)
 * 2. VideoView handles video visuals (synced with service audio)
 * 3. Single activity with two layout modes that switch dynamically
 * 4. Seamless transitions between audio and video content in playlists
 * 5. Perfect synchronization between video and audio via callbacks
 */
@UnstableApi
class UnifiedPlayerActivity : AppCompatActivity(), UnifiedPlayerService.PlayerListener {

    companion object {
        private const val TAG = "UnifiedPlayerActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 1001
        private const val CONTROLS_HIDE_DELAY = 3000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Views - Since layouts share button IDs, we reference them as needed
    private lateinit var audioPlayerLayout: LinearLayoutCompat
    private lateinit var audioSongTitle: MaterialTextView
    private lateinit var audioTimeStart: MaterialTextView
    private lateinit var audioTimeStop: MaterialTextView
    private lateinit var audioSeekbar: SeekBar
    private lateinit var visualizer: BarVisualizerView
    private lateinit var audioImageView: AppCompatImageView

    // Video Layout Views
    private lateinit var videoPlayerLayout: RelativeLayout
    private lateinit var videoView: VideoView
    private lateinit var controlsOverlay: RelativeLayout
    private lateinit var topControls: LinearLayoutCompat
    private lateinit var bottomControls: LinearLayoutCompat
    private lateinit var buttonFullscreen: MaterialButton
    private lateinit var videoTitle: MaterialTextView
    private lateinit var videoSeekbar: SeekBar
    private lateinit var videoTimeStart: MaterialTextView
    private lateinit var videoTimeStop: MaterialTextView

    // Shared button references (will point to different buttons based on current layout)
    private lateinit var buttonPlay: MaterialButton
    private lateinit var buttonNext: MaterialButton
    private lateinit var buttonPrevious: MaterialButton
    private lateinit var buttonFastForward: MaterialButton
    private lateinit var buttonFastRewind: MaterialButton
    private lateinit var buttonRepeat: MaterialButton

    // ─────────────────────────────────────────────────────────────────────────
    // Service binding
    // ─────────────────────────────────────────────────────────────────────────

    private var musicService: UnifiedPlayerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as UnifiedPlayerService.ServiceBinder
            musicService = b.getService()
            isBound = true

            musicService?.addListener(this@UnifiedPlayerActivity)

            // Check if service was already running (e.g. from previous activity)
            if (servicePreviouslyRunning) {
                Log.d(TAG, "Service was already running, syncing UI state")
                syncUiToServiceState()
                showCorrectLayoutForCurrentMedia()
            } else {
                // Initialize playlist and start playing
                Log.d(TAG, "Initializing fresh playlist")
                musicService?.initPlaylist(myMedia, startPosition, mediaTitles, mediaArtists, mediaTypes)
            }

            startSeekbarUpdater()

            // Attach visualizer after a delay to ensure MediaPlayer is ready
            handler.postDelayed({
                if (getCurrentMediaType() == "audio") {
                    attachVisualizer()
                }
            }, 500)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data from Intent
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var myMedia: ArrayList<File>
    private var mediaTitles: ArrayList<String>? = null
    private var mediaArtists: ArrayList<String>? = null
    private var mediaTypes: ArrayList<String>? = null
    private var startPosition: Int = 0

    // ─────────────────────────────────────────────────────────────────────────
    // UI State
    // ─────────────────────────────────────────────────────────────────────────

    private var currentMediaType: String = "audio"
    private var controlsVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private var servicePreviouslyRunning = false
    private var isMovingForward = true

    // ─────────────────────────────────────────────────────────────────────────
    // UI update runnables
    // ─────────────────────────────────────────────────────────────────────────

    private val seekbarRunnable = object : Runnable {
        override fun run() {
            // Position updates now come from service and onVideoPositionUpdate callback
            // This runnable ensures UI stays responsive even if callbacks are delayed
            val service = musicService ?: return
            val currentPos = service.getCurrentPosition()

            // Only get duration if the service indicates it's playing (MediaPlayer is ready)
            val duration = if (service.isPlaying() || currentPos > 0) {
                service.getDuration()
            } else {
                0
            }

            // Update appropriate seekbar and time displays based on current layout
            if (currentMediaType == "audio") {
                audioSeekbar.progress = currentPos
                if (duration > 0) {
                    audioSeekbar.max = duration
                    audioTimeStop.text = createTime(duration)
                }
                audioTimeStart.text = createTime(currentPos)
            } else if (currentMediaType == "video") {
                // Update video UI here for better reliability after pause/unpause
                videoSeekbar.progress = currentPos
                videoTimeStart.text = createTime(currentPos)
                if (duration > 0) {
                    videoSeekbar.max = duration
                    videoTimeStop.text = createTime(duration)
                }
            }

            handler.postDelayed(this, 500)
        }
    }

    private val hideControlsRunnable = Runnable {
        hideVideoControls()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unified_player)

        supportActionBar?.hide()

        findViews()
        readIntent()
        setupAudioControls()
        setupVideoControls()
        setupFullscreenHandling()
        setupBackPressHandling()

        // Request RECORD_AUDIO permission for visualizer
        requestAudioPermissionIfNeeded()

        // Start the service and bind to it
        val serviceIntent = Intent(this, UnifiedPlayerService::class.java)
        startService(serviceIntent)

        servicePreviouslyRunning = savedInstanceState?.getBoolean("service_running", false) ?: false
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Start with audio layout visible (will be corrected by showCorrectLayoutForCurrentMedia)
        showAudioLayout()
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
            showCorrectLayoutForCurrentMedia()

            // Resume video playback if we're in video mode
            if (currentMediaType == "video" && musicService?.isPlaying() == true) {
                videoView.start()
            }

            // Reattach visualizer if in audio mode
            if (currentMediaType == "audio") {
                handler.postDelayed({
                    attachVisualizer()
                }, 200)
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
        visualizer.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleOrientationChange()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PlayerListener callbacks (called from MusicPlayerService on main thread)
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSongChanged(position: Int, songName: String) {
        Log.d(TAG, "Media changed to position $position: $songName")

        // Determine the media type at this position
        currentMediaType = getCurrentMediaType()

        // Update UI for both layouts
        audioSongTitle.text = songName
        videoTitle.text = songName

        // Show correct layout for current media type
        showCorrectLayoutForCurrentMedia()

        // Update seekbar and time displays
        val duration = musicService?.getDuration() ?: 0
        audioTimeStop.text = createTime(duration)
        audioSeekbar.max = duration
        audioSeekbar.progress = 0

        videoTimeStop.text = createTime(duration)
        videoSeekbar.max = duration
        videoSeekbar.progress = 0

        if (currentMediaType == "audio") {
            startAnimation(audioImageView, isMovingForward)
            isMovingForward = true // Reset to true for natural playlist progression
            // Re-attach visualizer when song changes
            handler.postDelayed({
                attachVisualizer()
            }, 300)
        } else {
            // Start playing the video visual (service handles audio)
            playCurrentVideoVisual()
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val iconRes = if (isPlaying) {
            R.drawable.baseline_pause_24
        } else {
            R.drawable.baseline_play_arrow_24
        }

        // Update the current play button (audio or video depending on current layout)
        buttonPlay.setIconResource(iconRes)

        // Sync VideoView state with service when in video mode
        if (currentMediaType == "video") {
            if (isPlaying && !videoView.isPlaying) {
                videoView.start()
            } else if (!isPlaying && videoView.isPlaying) {
                videoView.pause()
            }
        }

        Log.d(TAG, "Playback state changed: ${if (isPlaying) "playing" else "paused"}")
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
        Log.d(TAG, "Repeat changed: $isRepeat")
    }

    override fun onRequestActivitySwitch(position: Int, mediaType: String) {
        // No need to switch activities - we handle both types in this unified activity!
        Log.d(TAG, "Media type switching to: $mediaType (handled internally)")
        currentMediaType = mediaType
        showCorrectLayoutForCurrentMedia()
    }

    override fun onVideoPositionUpdate(position: Int, duration: Int) {
        if (currentMediaType != "video") return

        // Sync VideoView with service audio position
        val videoPosition = videoView.currentPosition
        val positionDiff = abs(videoPosition - position)

        // If positions are significantly out of sync (> 1 second), seek VideoView
        if (positionDiff > 1000) {
            Log.d(TAG, "Syncing video position: video=$videoPosition, audio=$position")
            videoView.seekTo(position)
        }

        // Update video UI elements (redundant with seekbarRunnable but good for immediate sync)
        videoSeekbar.max = duration
        videoSeekbar.progress = position
        videoTimeStart.text = createTime(position)
        videoTimeStop.text = createTime(duration)

        // Start VideoView if it's not playing but service is
        if (musicService?.isPlaying() == true && !videoView.isPlaying) {
            videoView.start()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout switching
    // ─────────────────────────────────────────────────────────────────────────

    private fun showCorrectLayoutForCurrentMedia() {
        currentMediaType = getCurrentMediaType()

        Log.d(TAG, "Showing layout for media type: $currentMediaType")

        if (currentMediaType == "video") {
            showVideoLayout()
        } else {
            showAudioLayout()
        }
    }

    private fun showAudioLayout() {
        Log.d(TAG, "Switching to audio layout")
        // Lock orientation to portrait for audio
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        audioPlayerLayout.visibility = View.VISIBLE
        videoPlayerLayout.visibility = View.GONE
        currentMediaType = "audio"

        // Set button references to audio layout buttons
        setButtonReferencesForAudio()

        // Update play button icon immediately
        musicService?.let {
            onPlaybackStateChanged(it.isPlaying())
            updateRepeatButtonVisual(it.isRepeat())
        }

        // Set activity context for service
        musicService?.setActivityContext("unified")

        // Show action bar in audio mode
        supportActionBar?.show()

        // Attach visualizer
        handler.postDelayed({
            attachVisualizer()
        }, 200)
    }

    private fun showVideoLayout() {
        Log.d(TAG, "Switching to video layout")
        // Unlock orientation for video (allow sensor rotation)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        audioPlayerLayout.visibility = View.GONE
        videoPlayerLayout.visibility = View.VISIBLE
        currentMediaType = "video"

        // Set button references to video layout buttons
        setButtonReferencesForVideo()

        // Update play button icon immediately
        musicService?.let {
            onPlaybackStateChanged(it.isPlaying())
            updateRepeatButtonVisual(it.isRepeat())
        }

        // Set activity context for service
        musicService?.setActivityContext("unified")

        // Hide action bar in video mode
        supportActionBar?.hide()

        // Start with controls visible
        showVideoControls()

        // Start playing current video visual
        playCurrentVideoVisual()
    }

    private fun getCurrentMediaType(): String {
        val currentPos = musicService?.getSongPosition() ?: startPosition
        return if (currentPos < (mediaTypes?.size ?: 0)) {
            mediaTypes?.get(currentPos) ?: "audio"
        } else {
            "audio" // Default fallback
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup methods - UPDATED for Material Design components
    // ─────────────────────────────────────────────────────────────────────────

    private fun findViews() {
        // Audio layout views
        audioPlayerLayout = findViewById(R.id.audioPlayerLayout)
        audioSongTitle    = findViewById(R.id.audioSongTitle)
        audioTimeStart    = findViewById(R.id.audioTimeStart)
        audioTimeStop     = findViewById(R.id.audioTimeStop)
        audioSeekbar      = findViewById(R.id.audioSeekbar)
        visualizer        = findViewById(R.id.audioVisualizer)
        audioImageView    = findViewById(R.id.audioImageView)

        // Video layout views with debugging
        videoPlayerLayout = findViewById(R.id.videoPlayerLayout)
        videoView         = findViewById(R.id.videoView)
        controlsOverlay   = findViewById(R.id.videoControlsOverlay)
        topControls       = findViewById(R.id.videoTopControls)
        bottomControls    = findViewById(R.id.videoBottomControls)
        buttonFullscreen  = findViewById(R.id.buttonFullscreen)
        videoTitle        = findViewById(R.id.videoTitle)
        videoSeekbar      = findViewById(R.id.videoSeekbar)
        videoTimeStart    = findViewById(R.id.videoTimeStart)
        videoTimeStop     = findViewById(R.id.videoTimeStop)

        // We'll set the button references when switching layouts
        initializeButtonReferences()
    }

    private fun initializeButtonReferences() {
        // Start with audio layout buttons
        setButtonReferencesForAudio()
    }

    private fun setButtonReferencesForAudio() {
        buttonPlay        = audioPlayerLayout.findViewById(R.id.buttonPlay)
        buttonNext        = audioPlayerLayout.findViewById(R.id.buttonNext)
        buttonPrevious    = audioPlayerLayout.findViewById(R.id.buttonPrevious)
        buttonFastForward = audioPlayerLayout.findViewById(R.id.buttonFastForward)
        buttonFastRewind  = audioPlayerLayout.findViewById(R.id.buttonFastRewind)
        buttonRepeat      = audioPlayerLayout.findViewById(R.id.buttonRepeat)

        Log.d(TAG, "Audio buttons set up")

        // Set up audio-specific listeners
        buttonPlay.setOnClickListener { musicService?.togglePlayPause() }
        buttonNext.setOnClickListener {
            isMovingForward = true
            musicService?.playNext()
        }
        buttonPrevious.setOnClickListener {
            isMovingForward = false
            musicService?.playPrevious()
        }
        buttonFastForward.setOnClickListener { musicService?.fastForward() }
        buttonFastRewind.setOnClickListener { musicService?.fastRewind() }
        buttonRepeat.setOnClickListener { musicService?.toggleRepeat() }
    }

    private fun setButtonReferencesForVideo() {
        // Find video layout buttons using the correct IDs from XML
        buttonPlay        = videoPlayerLayout.findViewById(R.id.videoButtonPlay)
        buttonNext        = videoPlayerLayout.findViewById(R.id.videoButtonNext)
        buttonPrevious    = videoPlayerLayout.findViewById(R.id.videoButtonPrevious)
        buttonFastForward = videoPlayerLayout.findViewById(R.id.videoButtonFastForward)
        buttonFastRewind  = videoPlayerLayout.findViewById(R.id.videoButtonFastRewind)
        buttonRepeat      = videoPlayerLayout.findViewById(R.id.videoButtonRepeat)

        Log.d(TAG, "Video buttons set up with video-specific IDs")

        // Set up video-specific listeners (include hide timer reset)
        buttonPlay.setOnClickListener {
            musicService?.togglePlayPause()
            resetHideControlsTimer()
        }
        buttonNext.setOnClickListener {
            isMovingForward = true
            musicService?.playNext()
            resetHideControlsTimer()
        }
        buttonPrevious.setOnClickListener {
            isMovingForward = false
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
    }

    private fun readIntent() {
        val bundle = intent.extras!!

        // Handle different intent extra names for backward compatibility
        val paths = bundle.getStringArrayList("songs") ?: bundle.getStringArrayList("videos")!!
        myMedia = ArrayList(paths.map { File(it) })
        startPosition = bundle.getInt("pos", 0)

        // Get media titles, artists, and types if provided
        mediaTitles = bundle.getStringArrayList("songTitles") ?: bundle.getStringArrayList("videoTitles")
        mediaArtists = bundle.getStringArrayList("songArtists") ?: bundle.getStringArrayList("videoArtists")
        mediaTypes = bundle.getStringArrayList("mediaTypes")

        // Pre-populate the media name immediately
        val displayTitle = if (!mediaTitles.isNullOrEmpty() && startPosition < mediaTitles!!.size) {
            mediaTitles!![startPosition]
        } else {
            intent.getStringExtra("songName") ?: intent.getStringExtra("videoName") ?: myMedia[startPosition].name
        }

        audioSongTitle.text = displayTitle
        audioSongTitle.isSelected = true  // enables marquee scrolling
        videoTitle.text = displayTitle
        videoTitle.isSelected = true

        // Determine starting media type
        currentMediaType = if (startPosition < (mediaTypes?.size ?: 0)) {
            mediaTypes?.get(startPosition) ?: "audio"
        } else {
            "audio"
        }

        Log.d(TAG, "Intent read: ${myMedia.size} media items, starting at position $startPosition, type: $currentMediaType")
    }

    private fun setupAudioControls() {
        // Audio seekbar setup
        audioSeekbar.progressDrawable.setColorFilter(
            resources.getColor(R.color.red, theme),
            PorterDuff.Mode.MULTIPLY
        )
        audioSeekbar.thumb.setTint(ContextCompat.getColor(this, R.color.red))

        audioSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar) {
                handler.removeCallbacks(seekbarRunnable)
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                musicService?.seekTo(bar.progress)
                handler.post(seekbarRunnable)
            }
        })
    }

    private fun setupVideoControls() {
        setupVideoView()

        // Video seekbar setup
        videoSeekbar.progressDrawable.setColorFilter(
            ContextCompat.getColor(this, R.color.red),
            PorterDuff.Mode.SRC_IN
        )
        videoSeekbar.thumb.setTint(ContextCompat.getColor(this, R.color.red))

        videoSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

        buttonFullscreen.setOnClickListener {
            toggleFullscreen()
            resetHideControlsTimer()
        }

        controlsOverlay.setOnClickListener {
            toggleVideoControls()
        }
    }

    private fun setupVideoView() {
        videoView.setOnPreparedListener { mediaPlayer ->
            Log.d(TAG, "Video prepared successfully")
            // Mute VideoView audio because MusicPlayerService handles audio for background playback support
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            mediaPlayer.isLooping = false // Service handles looping logic
        }

        videoView.setOnCompletionListener {
            Log.d(TAG, "Video visual completed")
            // Service handles completion logic when audio finishes
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
            toggleVideoControls()
        }
    }

    private fun setupFullscreenHandling() {
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                if (currentMediaType == "video") {
                    showVideoControls()
                }
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
                        // Stop music when returning to previous activity
                        musicService?.stopCurrentPlayback()
                        musicService?.stopSelf()
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

    private fun playCurrentVideoVisual() {
        if (myMedia.isEmpty()) return

        val position = musicService?.getSongPosition() ?: startPosition
        if (position >= myMedia.size) return

        val videoFile = myMedia[position]

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
    // Video controls visibility management
    // ─────────────────────────────────────────────────────────────────────────

    private fun showVideoControls() {
        if (currentMediaType != "video" || controlsVisible) return

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

    private fun hideVideoControls() {
        if (currentMediaType != "video" || !controlsVisible) return

        controlsOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                controlsOverlay.visibility = View.GONE
            }
            .start()

        controlsVisible = false
    }

    private fun toggleVideoControls() {
        if (currentMediaType != "video") return

        if (controlsVisible) {
            hideVideoControls()
        } else {
            showVideoControls()
        }
    }

    private fun resetHideControlsTimer() {
        if (currentMediaType != "video") return

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
                buttonFullscreen.setIconResource(R.drawable.baseline_fullscreen_exit_24)
            }
            Configuration.ORIENTATION_PORTRAIT -> {
                exitFullscreen()
                buttonFullscreen.setIconResource(R.drawable.baseline_fullscreen_24)
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

        // Only show action bar in audio mode
        if (currentMediaType == "audio") {
            supportActionBar?.show()
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

        val displayTitle = if (!mediaTitles.isNullOrEmpty() && pos < mediaTitles!!.size) {
            mediaTitles!![pos]
        } else if (pos < myMedia.size) {
            myMedia[pos].nameWithoutExtension
        } else {
            "Unknown Media"
        }

        audioSongTitle.text = displayTitle
        videoTitle.text = displayTitle

        val duration = service.getDuration()
        val currentPos = service.getCurrentPosition()

        audioSeekbar.max = duration
        audioSeekbar.progress = currentPos
        audioTimeStop.text = createTime(duration)
        audioTimeStart.text = createTime(currentPos)

        videoSeekbar.max = duration
        videoSeekbar.progress = currentPos
        videoTimeStop.text = createTime(duration)
        videoTimeStart.text = createTime(currentPos)

        onPlaybackStateChanged(service.isPlaying())
        updateRepeatButtonVisual(service.isRepeat())

        Log.d(TAG, "Synced UI state - displaying title: '$displayTitle' for position $pos")
    }

    private fun requestAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {

            Log.d(TAG, "Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "RECORD_AUDIO permission already granted")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            RECORD_AUDIO_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "RECORD_AUDIO permission granted")
                    if (isBound && currentMediaType == "audio") {
                        handler.postDelayed({ attachVisualizer() }, 500)
                    }
                } else {
                    Log.w(TAG, "RECORD_AUDIO permission denied - visualizer will not work")
                }
            }
        }
    }

    private fun startSeekbarUpdater() {
        handler.removeCallbacks(seekbarRunnable)
        handler.post(seekbarRunnable)
    }

    private fun updateRepeatButtonVisual(isRepeat: Boolean) {
        val tintColor = if (isRepeat) R.color.red else R.color.white
        buttonRepeat.iconTint = resources.getColorStateList(tintColor, theme)
        Log.d(TAG, "Repeat button tint updated: $isRepeat")
    }

    private fun attachVisualizer() {
        if (currentMediaType != "audio") return

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO permission not granted - visualizer disabled")
                return
            }

            val sessionId = musicService?.getAudioSessionId() ?: -1
            Log.d(TAG, "Attaching visualizer with audio session ID: $sessionId")

            if (sessionId != -1 && musicService?.isPlaying() == true) {
                visualizer.setAudioSessionId(sessionId)
                Log.d(TAG, "Visualizer attached successfully")
            } else {
                Log.w(TAG, "Cannot attach visualizer - invalid session ID or not playing")
                if (sessionId != -1) {
                    handler.postDelayed({
                        attachVisualizer()
                    }, 1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching visualizer: ${e.message}", e)
        }
    }

    private fun startAnimation(view: View, isForward: Boolean = true) {
        val endRotation = if (isForward) 360f else -360f
        val rotate = ObjectAnimator.ofFloat(view, "rotation", 0f, endRotation).apply {
            duration = 1000
        }
        AnimatorSet().apply {
            playTogether(rotate)
            start()
        }
    }

    private fun createTime(duration: Int): String {
        val min = duration / 1000 / 60
        val sec = duration / 1000 % 60
        return "$min:${if (sec < 10) "0$sec" else "$sec"}"
    }
}
