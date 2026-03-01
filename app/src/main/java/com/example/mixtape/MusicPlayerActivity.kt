package com.example.mixtape

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.*
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.example.mixtape.service.MusicPlayerService
import com.example.mixtape.ui.BarVisualizerView
import com.example.mixtape.R
import java.io.File

/**
 * MusicPlayerActivity
 *
 * FIXED VERSION: Handles clean stop-and-start transitions between audio and video.
 * No more complex seamless transition logic - just clean handoffs.
 *
 * Responsibilities:
 *  - Bind to MusicPlayerService and delegate all playback calls to it.
 *  - Update the UI in response to service callbacks (PlayerListener).
 *  - Handle activity switches when video content is encountered.
 *  - Show/hide the repeat button state.
 *
 * The activity no longer tries to handle mixed media seamlessly -
 * when video is encountered, it cleanly transitions to VideoPlayerActivity.
 *
 * FIX APPLIED: The seekbarRunnable now updates both txtSStart AND txtSStop continuously,
 * ensuring the duration display is always up-to-date, similar to VideoPlayerActivity.
 */
@UnstableApi
class MusicPlayerActivity : AppCompatActivity(), MusicPlayerService.PlayerListener {

    companion object {
        private const val TAG = "MusicPlayerActivity"
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 1001
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Views
    // ─────────────────────────────────────────────────────────────────────────

    private lateinit var buttonPlay: ImageButton
    private lateinit var buttonNext: ImageButton
    private lateinit var buttonPrevious: ImageButton
    private lateinit var buttonFastForward: ImageButton
    private lateinit var buttonFastRewind: ImageButton
    private lateinit var buttonRepeat: ImageButton
    private lateinit var txtSName: TextView
    private lateinit var txtSStart: TextView
    private lateinit var txtSStop: TextView
    private lateinit var seekbar: SeekBar
    private lateinit var visualizer: BarVisualizerView
    private lateinit var imageView: ImageView

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

            musicService?.addListener(this@MusicPlayerActivity)

            // Check if service was already running (e.g. from previous activity)
            if (servicePreviouslyRunning) {
                Log.d(TAG, "Service was already running, syncing UI state")
                syncUiToServiceState()
            } else {
                // Initialize playlist and start playing
                Log.d(TAG, "Initializing fresh playlist")
                musicService?.initPlaylist(mySongs, startPosition, songTitles, songArtists, mediaTypes)
            }

            startSeekbarUpdater()

            // Attach visualizer after a delay to ensure MediaPlayer is ready
            handler.postDelayed({
                attachVisualizer()
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

    private lateinit var mySongs: ArrayList<File>
    private var songTitles: ArrayList<String>? = null
    private var songArtists: ArrayList<String>? = null
    private var mediaTypes: ArrayList<String>? = null
    private var startPosition: Int = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Seekbar / time handler - FIXED VERSION
    // ─────────────────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    /**
     * FIXED: Now updates both txtSStart AND txtSStop, but safely checks MediaPlayer state
     * to prevent triggering errors that cause songs to skip.
     */
    private val seekbarRunnable = object : Runnable {
        override fun run() {
            val service = musicService ?: return
            val currentPos = service.getCurrentPosition()

            // Only get duration if the service indicates it's playing (MediaPlayer is ready)
            val duration = if (service.isPlaying() || currentPos > 0) {
                service.getDuration()
            } else {
                // MediaPlayer not ready yet, don't call getDuration()
                0
            }

            seekbar.progress = currentPos
            if (duration > 0) {
                seekbar.max = duration
                txtSStop.text = createTime(duration)
            }
            txtSStart.text = createTime(currentPos)

            handler.postDelayed(this, 500)
        }
    }

    // Flag that survives configuration changes
    private var servicePreviouslyRunning = false

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_player)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViews()
        readIntent()
        setupSeekbar()
        setupButtons()

        // Request RECORD_AUDIO permission for visualizer
        requestAudioPermissionIfNeeded()

        // Start the service (keeps it alive past unbind) and bind to it
        val serviceIntent = Intent(this, MusicPlayerService::class.java)
        startService(serviceIntent)

        servicePreviouslyRunning = savedInstanceState?.getBoolean("service_running", false) ?: false

        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
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
                    if (isBound) {
                        handler.postDelayed({ attachVisualizer() }, 500)
                    }
                } else {
                    Log.w(TAG, "RECORD_AUDIO permission denied - visualizer will not work")
                }
            }
        }
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
            handler.postDelayed({
                attachVisualizer()
            }, 200)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(seekbarRunnable)
        // Do NOT stop the service here — it keeps playing in the background.
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PlayerListener callbacks (called from MusicPlayerService on main thread)
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSongChanged(position: Int, songName: String) {
        Log.d(TAG, "Song changed to position $position: $songName")

        // Update UI
        txtSName.text = songName
        txtSStop.text = createTime(musicService?.getDuration() ?: 0)
        seekbar.max = musicService?.getDuration() ?: 0
        seekbar.progress = 0
        startAnimation(imageView)

        // Re-attach visualizer when song changes to get new audio session
        handler.postDelayed({
            attachVisualizer()
        }, 300)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        buttonPlay.setBackgroundResource(
            if (isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
        )
        Log.d(TAG, "Playback state changed: ${if (isPlaying) "playing" else "paused"}")
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
        Log.d(TAG, "Repeat changed: $isRepeat")
    }

    /**
     * NEW: Handle request to switch activities when video content is encountered.
     */
    override fun onRequestActivitySwitch(position: Int, mediaType: String) {
        Log.d(TAG, "Activity switch requested for $mediaType at position $position")

        if (mediaType == "video") {
            // Transition to VideoPlayerActivity
            transitionToVideoPlayer(position)
        }
        // If mediaType is "audio", we should already be in the correct activity
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun findViews() {
        buttonPlay        = findViewById(R.id.playButton)
        buttonNext        = findViewById(R.id.buttonNext)
        buttonPrevious    = findViewById(R.id.buttonPrevious)
        buttonFastForward = findViewById(R.id.buttonFastForward)
        buttonFastRewind  = findViewById(R.id.buttonFastRewind)
        buttonRepeat      = findViewById(R.id.buttonRepeat)
        txtSName          = findViewById(R.id.txtSN)
        txtSStart         = findViewById(R.id.txtStart)
        txtSStop          = findViewById(R.id.txtStop)
        seekbar           = findViewById(R.id.seekbar)
        visualizer        = findViewById(R.id.blast)
        imageView         = findViewById(R.id.imageView)
    }

    private fun readIntent() {
        val bundle = intent.extras!!
        val paths = bundle.getStringArrayList("songs")!!
        mySongs = ArrayList(paths.map { File(it) })
        startPosition = bundle.getInt("pos", 0)

        // Get song titles, artists, and media types if provided
        songTitles = bundle.getStringArrayList("songTitles")
        songArtists = bundle.getStringArrayList("songArtists")
        mediaTypes = bundle.getStringArrayList("mediaTypes")

        // Pre-populate the song name immediately
        val displayTitle = if (!songTitles.isNullOrEmpty() && startPosition < songTitles!!.size) {
            songTitles!![startPosition]
        } else {
            intent.getStringExtra("songName") ?: mySongs[startPosition].name
        }

        txtSName.text = displayTitle
        txtSName.isSelected = true  // enables marquee scrolling

        Log.d(TAG, "Intent read: ${mySongs.size} media items, starting at position $startPosition")
    }

    private fun setupSeekbar() {
        seekbar.getProgressDrawable().setColorFilter(
            resources.getColor(R.color.red, theme),
            PorterDuff.Mode.MULTIPLY
        )
        seekbar.thumb.setTint(ContextCompat.getColor(this, R.color.red))

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

    private fun setupButtons() {
        buttonPlay.setOnClickListener {
            musicService?.togglePlayPause()
        }

        buttonNext.setOnClickListener {
            musicService?.playNext()
        }

        buttonPrevious.setOnClickListener {
            musicService?.playPrevious()
        }

        buttonFastForward.setOnClickListener {
            musicService?.fastForward()
        }

        buttonFastRewind.setOnClickListener {
            musicService?.fastRewind()
        }

        buttonRepeat.setOnClickListener {
            musicService?.toggleRepeat()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Syncs all UI elements to the service's current state (e.g. after rotation). */
    private fun syncUiToServiceState() {
        val service = musicService ?: return
        val pos = service.getSongPosition()

        val displayTitle = if (!songTitles.isNullOrEmpty() && pos < songTitles!!.size) {
            songTitles!![pos]
        } else if (pos < mySongs.size) {
            mySongs[pos].nameWithoutExtension
        } else {
            "Unknown Song"
        }

        txtSName.text = displayTitle
        seekbar.max      = service.getDuration()
        seekbar.progress = service.getCurrentPosition()
        txtSStop.text    = createTime(service.getDuration())
        txtSStart.text   = createTime(service.getCurrentPosition())
        onPlaybackStateChanged(service.isPlaying())
        updateRepeatButtonVisual(service.isRepeat())

        Log.d(TAG, "Synced UI state - displaying title: '$displayTitle' for position $pos")
    }

    private fun updateRepeatButtonVisual(isRepeat: Boolean) {
        buttonRepeat.setBackgroundResource(
            if (isRepeat) R.drawable.baseline_repeat_24 else R.drawable.outline_repeat_24
        )
    }

    private fun startSeekbarUpdater() {
        handler.removeCallbacks(seekbarRunnable)
        handler.post(seekbarRunnable)
    }

    private fun attachVisualizer() {
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

    fun startAnimation(view: View) {
        val rotate = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
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

    /**
     * Clean transition to VideoPlayerActivity when video content is encountered.
     */
    private fun transitionToVideoPlayer(position: Int) {
        try {
            Log.d(TAG, "Creating intent for VideoPlayerActivity transition...")

            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                val filePaths = mySongs.map { it.absolutePath }
                putExtra("videos", ArrayList(filePaths)) // VideoPlayerActivity expects "videos"
                putExtra("videoTitles", songTitles)
                putExtra("videoArtists", songArtists)
                putExtra("mediaTypes", mediaTypes)
                putExtra("pos", position) // Start from the current position
                putExtra("videoName", if (!songTitles.isNullOrEmpty() && position < songTitles!!.size) {
                    songTitles!![position]
                } else {
                    "Video ${position + 1}"
                })
            }

            Log.d(TAG, "Starting VideoPlayerActivity at position $position")
            startActivity(intent)

            // Close this activity so the user doesn't return to audio player when pressing back
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning to video player: ${e.message}", e)
            Toast.makeText(this, "Error opening video player", Toast.LENGTH_SHORT).show()
        }
    }
}