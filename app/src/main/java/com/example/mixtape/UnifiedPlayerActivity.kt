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

// UPDATED IMPORTS for Material Design components and ConstraintLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.button.MaterialButton

/**
 * UnifiedPlayerActivity - COMPLETE MODERNIZED VERSION
 *
 * UNIFIED PLAYER: Combines both audio and video playback in a single activity!
 */
@UnstableApi
class UnifiedPlayerActivity : AppCompatActivity(), UnifiedPlayerService.PlayerListener {

    companion object {
        private const val TAG = "UnifiedPlayerActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 1001
        private const val CONTROLS_HIDE_DELAY = 3000L
    }

    // Views
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
    private lateinit var videoButtonsContainer: ConstraintLayout

    // Shared button references
    private lateinit var buttonPlay: MaterialButton
    private lateinit var buttonNext: MaterialButton
    private lateinit var buttonPrevious: MaterialButton
    private lateinit var buttonFastForward: MaterialButton
    private lateinit var buttonFastRewind: MaterialButton
    private lateinit var buttonRepeat: MaterialButton
    private lateinit var buttonAutoplay: MaterialButton

    private var musicService: UnifiedPlayerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as UnifiedPlayerService.ServiceBinder
            musicService = b.getService()
            isBound = true
            musicService?.addListener(this@UnifiedPlayerActivity)

            if (servicePreviouslyRunning) {
                syncUiToServiceState()
                showCorrectLayoutForCurrentMedia()
            } else {
                musicService?.initPlaylist(myMedia, startPosition, mediaTitles, mediaArtists, mediaTypes)
            }

            startSeekbarUpdater()
            handler.postDelayed({
                if (getCurrentMediaType() == "audio") attachVisualizer()
            }, 500)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    private lateinit var myMedia: ArrayList<File>
    private var mediaTitles: ArrayList<String>? = null
    private var mediaArtists: ArrayList<String>? = null
    private var mediaTypes: ArrayList<String>? = null
    private var startPosition: Int = 0

    private var currentMediaType: String = "audio"
    private var controlsVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private var servicePreviouslyRunning = false
    private var isMovingForward = true

    private val seekbarRunnable = object : Runnable {
        override fun run() {
            val service = musicService ?: return
            val currentPos = service.getCurrentPosition()
            val duration = if (service.isPlaying() || currentPos > 0) service.getDuration() else 0

            if (currentMediaType == "audio") {
                audioSeekbar.progress = currentPos
                if (duration > 0) {
                    audioSeekbar.max = duration
                    audioTimeStop.text = createTime(duration)
                }
                audioTimeStart.text = createTime(currentPos)
            } else if (currentMediaType == "video") {
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

    private val hideControlsRunnable = Runnable { hideVideoControls() }

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

        requestAudioPermissionIfNeeded()

        val serviceIntent = Intent(this, UnifiedPlayerService::class.java)
        startService(serviceIntent)
        servicePreviouslyRunning = savedInstanceState?.getBoolean("service_running", false) ?: false
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

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
            if (currentMediaType == "video" && musicService?.isPlaying() == true) videoView.start()
            if (currentMediaType == "audio") handler.postDelayed({ attachVisualizer() }, 200)
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

    override fun onSongChanged(position: Int, songName: String) {
        currentMediaType = getCurrentMediaType()
        audioSongTitle.text = songName
        videoTitle.text = songName
        showCorrectLayoutForCurrentMedia()

        val duration = musicService?.getDuration() ?: 0
        audioTimeStop.text = createTime(duration)
        audioSeekbar.max = duration
        audioSeekbar.progress = 0
        videoTimeStop.text = createTime(duration)
        videoSeekbar.max = duration
        videoSeekbar.progress = 0

        if (currentMediaType == "audio") {
            startAnimation(audioImageView, isMovingForward)
            isMovingForward = true
            handler.postDelayed({ attachVisualizer() }, 300)
        } else {
            playCurrentVideoVisual()
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        val iconRes = if (isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
        buttonPlay.setIconResource(iconRes)
        if (currentMediaType == "video") {
            if (isPlaying && !videoView.isPlaying) videoView.start()
            else if (!isPlaying && videoView.isPlaying) videoView.pause()
        }
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
    }

    override fun onAutoplayChanged(isAutoplay: Boolean) {
        updateAutoplayButtonVisual(isAutoplay)
    }

    override fun onRequestActivitySwitch(position: Int, mediaType: String) {
        currentMediaType = mediaType
        showCorrectLayoutForCurrentMedia()
    }

    override fun onVideoPositionUpdate(position: Int, duration: Int) {
        if (currentMediaType != "video") return
        if (abs(videoView.currentPosition - position) > 1000) videoView.seekTo(position)
        videoSeekbar.max = duration
        videoSeekbar.progress = position
        videoTimeStart.text = createTime(position)
        videoTimeStop.text = createTime(duration)
        if (musicService?.isPlaying() == true && !videoView.isPlaying) videoView.start()
    }

    private fun showCorrectLayoutForCurrentMedia() {
        currentMediaType = getCurrentMediaType()
        if (currentMediaType == "video") showVideoLayout() else showAudioLayout()
    }

    private fun showAudioLayout() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        audioPlayerLayout.visibility = View.VISIBLE
        videoPlayerLayout.visibility = View.GONE
        setButtonReferencesForAudio()
        musicService?.let {
            onPlaybackStateChanged(it.isPlaying())
            updateRepeatButtonVisual(it.isRepeat())
            updateAutoplayButtonVisual(it.isAutoplay())
            it.setActivityContext("unified")
        }
        supportActionBar?.show()
        handler.postDelayed({ attachVisualizer() }, 200)
    }

    private fun showVideoLayout() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        audioPlayerLayout.visibility = View.GONE
        videoPlayerLayout.visibility = View.VISIBLE
        setButtonReferencesForVideo()
        musicService?.let {
            onPlaybackStateChanged(it.isPlaying())
            updateRepeatButtonVisual(it.isRepeat())
            updateAutoplayButtonVisual(it.isAutoplay())
            it.setActivityContext("unified")
        }
        supportActionBar?.hide()
        showVideoControls()
        playCurrentVideoVisual()
        handleOrientationChange() // Force layout check for video buttons
    }

    private fun getCurrentMediaType(): String {
        val currentPos = musicService?.getSongPosition() ?: startPosition
        return if (currentPos < (mediaTypes?.size ?: 0)) mediaTypes?.get(currentPos) ?: "audio" else "audio"
    }

    private fun findViews() {
        audioPlayerLayout = findViewById(R.id.audioPlayerLayout)
        audioSongTitle    = findViewById(R.id.audioSongTitle)
        audioTimeStart    = findViewById(R.id.audioTimeStart)
        audioTimeStop     = findViewById(R.id.audioTimeStop)
        audioSeekbar      = findViewById(R.id.audioSeekbar)
        visualizer        = findViewById(R.id.audioVisualizer)
        audioImageView    = findViewById(R.id.audioImageView)

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
        videoButtonsContainer = findViewById(R.id.videoButtonsContainer)
    }

    private fun setButtonReferencesForAudio() {
        buttonPlay        = audioPlayerLayout.findViewById(R.id.buttonPlay)
        buttonNext        = audioPlayerLayout.findViewById(R.id.buttonNext)
        buttonPrevious    = audioPlayerLayout.findViewById(R.id.buttonPrevious)
        buttonFastForward = audioPlayerLayout.findViewById(R.id.buttonFastForward)
        buttonFastRewind  = audioPlayerLayout.findViewById(R.id.buttonFastRewind)
        buttonRepeat      = audioPlayerLayout.findViewById(R.id.buttonRepeat)
        buttonAutoplay    = audioPlayerLayout.findViewById(R.id.buttonAutoplay)

        buttonPlay.setOnClickListener { musicService?.togglePlayPause() }
        buttonNext.setOnClickListener { isMovingForward = true; musicService?.playNext() }
        buttonPrevious.setOnClickListener { isMovingForward = false; musicService?.playPrevious() }
        buttonFastForward.setOnClickListener { musicService?.fastForward() }
        buttonFastRewind.setOnClickListener { musicService?.fastRewind() }
        buttonRepeat.setOnClickListener { musicService?.toggleRepeat() }
        buttonAutoplay.setOnClickListener { musicService?.toggleAutoplay() }
    }

    private fun setButtonReferencesForVideo() {
        buttonPlay        = videoPlayerLayout.findViewById(R.id.videoButtonPlay)
        buttonNext        = videoPlayerLayout.findViewById(R.id.videoButtonNext)
        buttonPrevious    = videoPlayerLayout.findViewById(R.id.videoButtonPrevious)
        buttonFastForward = videoPlayerLayout.findViewById(R.id.videoButtonFastForward)
        buttonFastRewind  = videoPlayerLayout.findViewById(R.id.videoButtonFastRewind)
        buttonRepeat      = videoPlayerLayout.findViewById(R.id.videoButtonRepeat)
        buttonAutoplay    = videoPlayerLayout.findViewById(R.id.videoButtonAutoplay)

        buttonPlay.setOnClickListener { musicService?.togglePlayPause(); resetHideControlsTimer() }
        buttonNext.setOnClickListener { isMovingForward = true; musicService?.playNext(); resetHideControlsTimer() }
        buttonPrevious.setOnClickListener { isMovingForward = false; musicService?.playPrevious(); resetHideControlsTimer() }
        buttonFastForward.setOnClickListener {
            musicService?.fastForward()
            videoView.seekTo(videoView.currentPosition + 10000)
            resetHideControlsTimer()
        }
        buttonFastRewind.setOnClickListener {
            musicService?.fastRewind()
            videoView.seekTo((videoView.currentPosition - 10000).coerceAtLeast(0))
            resetHideControlsTimer()
        }
        buttonRepeat.setOnClickListener { musicService?.toggleRepeat(); resetHideControlsTimer() }
        buttonAutoplay.setOnClickListener { musicService?.toggleAutoplay(); resetHideControlsTimer() }
    }

    private fun readIntent() {
        val bundle = intent.extras ?: return
        val paths = bundle.getStringArrayList("songs") ?: bundle.getStringArrayList("videos")!!
        myMedia = ArrayList(paths.map { File(it) })
        startPosition = bundle.getInt("pos", 0)
        mediaTitles = bundle.getStringArrayList("songTitles") ?: bundle.getStringArrayList("videoTitles")
        mediaArtists = bundle.getStringArrayList("songArtists") ?: bundle.getStringArrayList("videoArtists")
        mediaTypes = bundle.getStringArrayList("mediaTypes")

        val displayTitle = if (!mediaTitles.isNullOrEmpty() && startPosition < mediaTitles!!.size) mediaTitles!![startPosition]
        else intent.getStringExtra("songName") ?: intent.getStringExtra("videoName") ?: myMedia[startPosition].name

        audioSongTitle.text = displayTitle
        audioSongTitle.isSelected = true
        videoTitle.text = displayTitle
        videoTitle.isSelected = true
        currentMediaType = if (startPosition < (mediaTypes?.size ?: 0)) mediaTypes?.get(startPosition) ?: "audio" else "audio"
    }

    private fun setupAudioControls() {
        audioSeekbar.progressDrawable.setColorFilter(resources.getColor(R.color.red, theme), PorterDuff.Mode.MULTIPLY)
        audioSeekbar.thumb.setTint(ContextCompat.getColor(this, R.color.red))
        audioSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(bar: SeekBar) { handler.removeCallbacks(seekbarRunnable) }
            override fun onStopTrackingTouch(bar: SeekBar) { musicService?.seekTo(bar.progress); handler.post(seekbarRunnable) }
        })
    }

    private fun setupVideoControls() {
        setupVideoView()
        videoSeekbar.progressDrawable.setColorFilter(ContextCompat.getColor(this, R.color.red), PorterDuff.Mode.SRC_IN)
        videoSeekbar.thumb.setTint(ContextCompat.getColor(this, R.color.red))
        videoSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(seekbarRunnable)
                handler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                musicService?.seekTo(progress)
                videoView.seekTo(progress)
                handler.post(seekbarRunnable)
                resetHideControlsTimer()
            }
        })
        buttonFullscreen.setOnClickListener { toggleFullscreen(); resetHideControlsTimer() }
        controlsOverlay.setOnClickListener { toggleVideoControls() }
    }

    private fun setupVideoView() {
        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        }
        videoView.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "Video error - skipping", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ musicService?.playNext() }, 2000)
            true
        }
        videoView.setOnClickListener { toggleVideoControls() }
    }

    private fun setupFullscreenHandling() {
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 && currentMediaType == "video") showVideoControls()
        }
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    musicService?.stopCurrentPlayback()
                    musicService?.stopSelf()
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun playCurrentVideoVisual() {
        if (myMedia.isEmpty()) return
        val pos = musicService?.getSongPosition() ?: startPosition
        if (pos >= myMedia.size) return
        val videoFile = myMedia[pos]
        try {
            val uri = if (videoFile.exists() && videoFile.length() < 1000) Uri.parse(videoFile.readText().trim())
            else if (videoFile.exists()) Uri.fromFile(videoFile)
            else return
            videoView.setVideoURI(uri)
        } catch (e: Exception) {
            handler.postDelayed({ musicService?.playNext() }, 1000)
        }
    }

    private fun showVideoControls() {
        if (currentMediaType != "video" || controlsVisible) return
        controlsOverlay.visibility = View.VISIBLE
        controlsOverlay.alpha = 0f
        controlsOverlay.animate().alpha(1f).setDuration(200).start()
        controlsVisible = true
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
    }

    private fun hideVideoControls() {
        if (currentMediaType != "video" || !controlsVisible) return
        controlsOverlay.animate().alpha(0f).setDuration(200).withEndAction { controlsOverlay.visibility = View.GONE }.start()
        controlsVisible = false
    }

    private fun toggleVideoControls() {
        if (currentMediaType != "video") return
        if (controlsVisible) hideVideoControls() else showVideoControls()
    }

    private fun resetHideControlsTimer() {
        if (currentMediaType != "video") return
        handler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible) handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY)
    }

    private fun handleOrientationChange() {
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            enterFullscreen()
            buttonFullscreen.setIconResource(R.drawable.baseline_fullscreen_exit_24)
        } else {
            exitFullscreen()
            buttonFullscreen.setIconResource(R.drawable.baseline_fullscreen_24)
        }
        
        // Dynamically update video button positions
        if (currentMediaType == "video") {
            updateVideoButtonLayout(orientation)
        }
    }

    private fun updateVideoButtonLayout(orientation: Int) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(videoButtonsContainer)

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // PORTRAIT: Position Repeat below Rewind and Play
            constraintSet.clear(R.id.videoButtonRepeat, ConstraintSet.END)
            constraintSet.clear(R.id.videoButtonRepeat, ConstraintSet.TOP)
            constraintSet.clear(R.id.videoButtonRepeat, ConstraintSet.BOTTOM)
            constraintSet.clear(R.id.videoButtonAutoplay, ConstraintSet.START)
            constraintSet.clear(R.id.videoButtonAutoplay, ConstraintSet.TOP)
            constraintSet.clear(R.id.videoButtonAutoplay, ConstraintSet.BOTTOM)

            constraintSet.connect(R.id.videoButtonRepeat, ConstraintSet.START, R.id.videoButtonFastRewind, ConstraintSet.END)
            constraintSet.connect(R.id.videoButtonRepeat, ConstraintSet.END, R.id.videoButtonPlay, ConstraintSet.START)
            constraintSet.connect(R.id.videoButtonRepeat, ConstraintSet.TOP, R.id.videoButtonPlay, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.videoButtonAutoplay, ConstraintSet.START, R.id.videoButtonPlay, ConstraintSet.END)
            constraintSet.connect(R.id.videoButtonAutoplay, ConstraintSet.END, R.id.videoButtonFastForward, ConstraintSet.START)
            constraintSet.connect(R.id.videoButtonAutoplay, ConstraintSet.TOP, R.id.videoButtonPlay, ConstraintSet.BOTTOM)
            
            // Re-apply vertical centering or margins if needed
            constraintSet.setMargin(R.id.videoButtonRepeat, ConstraintSet.TOP, (12 * resources.displayMetrics.density).toInt())
            constraintSet.setMargin(R.id.videoButtonAutoplay, ConstraintSet.TOP, (12 * resources.displayMetrics.density).toInt())

            Log.d(TAG, "Video buttons set to Portrait layout")
        } else {
            // LANDSCAPE: Restore horizontal row (Repeat to the left of Previous)
            constraintSet.clear(R.id.videoButtonRepeat, ConstraintSet.START)
            constraintSet.clear(R.id.videoButtonRepeat, ConstraintSet.TOP)
            constraintSet.clear(R.id.videoButtonRepeat, ConstraintSet.BOTTOM)
            constraintSet.clear(R.id.videoButtonAutoplay, ConstraintSet.END)
            constraintSet.clear(R.id.videoButtonAutoplay, ConstraintSet.TOP)
            constraintSet.clear(R.id.videoButtonAutoplay, ConstraintSet.BOTTOM)

            constraintSet.connect(R.id.videoButtonRepeat, ConstraintSet.END, R.id.videoButtonPrevious, ConstraintSet.START)
            constraintSet.connect(R.id.videoButtonRepeat, ConstraintSet.TOP, R.id.videoButtonPlay, ConstraintSet.TOP)
            constraintSet.connect(R.id.videoButtonRepeat, ConstraintSet.BOTTOM, R.id.videoButtonPlay, ConstraintSet.BOTTOM)
            constraintSet.connect(R.id.videoButtonAutoplay, ConstraintSet.START, R.id.videoButtonNext, ConstraintSet.END)
            constraintSet.connect(R.id.videoButtonAutoplay, ConstraintSet.TOP, R.id.videoButtonPlay, ConstraintSet.TOP)
            constraintSet.connect(R.id.videoButtonAutoplay, ConstraintSet.BOTTOM, R.id.videoButtonPlay, ConstraintSet.BOTTOM)
            
            constraintSet.setMargin(R.id.videoButtonRepeat, ConstraintSet.END, (8 * resources.displayMetrics.density).toInt())
            constraintSet.setMargin(R.id.videoButtonRepeat, ConstraintSet.TOP, 0)
            constraintSet.setMargin(R.id.videoButtonAutoplay, ConstraintSet.END, (8 * resources.displayMetrics.density).toInt())
            constraintSet.setMargin(R.id.videoButtonAutoplay, ConstraintSet.TOP, 0)


            Log.d(TAG, "Video buttons set to Landscape layout")
        }

        constraintSet.applyTo(videoButtonsContainer)
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun enterFullscreen() { hideSystemBars(); supportActionBar?.hide() }
    private fun exitFullscreen() { if (controlsVisible) showSystemBars(); if (currentMediaType == "audio") supportActionBar?.show() }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun syncUiToServiceState() {
        val service = musicService ?: return
        val pos = service.getSongPosition()
        val displayTitle = if (!mediaTitles.isNullOrEmpty() && pos < mediaTitles!!.size) mediaTitles!![pos]
        else if (pos < myMedia.size) myMedia[pos].nameWithoutExtension else "Unknown Media"

        audioSongTitle.text = displayTitle
        videoTitle.text = displayTitle
        val duration = service.getDuration()
        val currentPos = service.getCurrentPosition()
        audioSeekbar.max = duration; audioSeekbar.progress = currentPos
        audioTimeStop.text = createTime(duration); audioTimeStart.text = createTime(currentPos)
        videoSeekbar.max = duration; videoSeekbar.progress = currentPos
        videoTimeStop.text = createTime(duration); videoTimeStart.text = createTime(currentPos)
        onPlaybackStateChanged(service.isPlaying())
        updateRepeatButtonVisual(service.isRepeat())
        updateAutoplayButtonVisual(service.isAutoplay())
    }

    private fun requestAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (isBound && currentMediaType == "audio") handler.postDelayed({ attachVisualizer() }, 500)
        }
    }

    private fun startSeekbarUpdater() { handler.removeCallbacks(seekbarRunnable); handler.post(seekbarRunnable) }

    private fun updateRepeatButtonVisual(isRepeat: Boolean) {
        val tintColor = if (isRepeat) R.color.red else R.color.white
        buttonRepeat.iconTint = resources.getColorStateList(tintColor, theme)
    }

    private fun updateAutoplayButtonVisual(isAutoplay: Boolean) {
        val tintColor = if (isAutoplay) R.color.red else R.color.white
        buttonAutoplay.iconTint = resources.getColorStateList(tintColor, theme)
    }

    private fun attachVisualizer() {
        if (currentMediaType != "audio") return
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val sessionId = musicService?.getAudioSessionId() ?: -1
            if (sessionId != -1 && musicService?.isPlaying() == true) visualizer.setAudioSessionId(sessionId)
            else if (sessionId != -1) handler.postDelayed({ attachVisualizer() }, 1000)
        } catch (e: Exception) { Log.e(TAG, "Error attaching visualizer: ${e.message}") }
    }

    private fun startAnimation(view: View, isForward: Boolean = true) {
        ObjectAnimator.ofFloat(view, "rotation", 0f, if (isForward) 360f else -360f).apply { duration = 1000; start() }
    }

    private fun createTime(duration: Int): String {
        val min = duration / 1000 / 60; val sec = duration / 1000 % 60
        return "$min:${if (sec < 10) "0$sec" else "$sec"}"
    }
}
