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
 * Responsibilities:
 *  - Bind to MusicPlayerService and delegate all playback calls to it.
 *  - Update the UI in response to service callbacks (PlayerListener).
 *  - Handle seekbar dragging and time labels.
 *  - Show/hide the repeat button state.
 *
 * The activity no longer owns a MediaPlayer or posts notifications itself —
 * all of that lives in MusicPlayerService.
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
    private var isInitialSetup = true  // NEW: Track if we're still in initial setup

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as MusicPlayerService.ServiceBinder
            musicService = b.getService()
            isBound = true

            musicService?.addListener(this@MusicPlayerActivity)

            // Hand the playlist to the service only on the very first connect.
            // If the service is already playing (e.g. activity re-created after rotation),
            // we just sync our UI to the current service state instead.
            if (musicService?.isPlaying() == false && !servicePreviouslyRunning) {
                musicService?.initPlaylist(mySongs, startPosition, songTitles, songArtists, mediaTypes)
            } else {
                syncUiToServiceState()
            }

            startSeekbarUpdater()
            // Delay visualizer attachment to ensure MediaPlayer is ready
            handler.postDelayed({
                attachVisualizer()
            }, 500)

            // Mark initial setup as complete after a short delay
            handler.postDelayed({
                isInitialSetup = false
                Log.d(TAG, "Initial setup complete - transitions now enabled")
            }, 1000)
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
    private var mediaTypes: ArrayList<String>? = null  // NEW: Track audio vs video
    private var startPosition: Int = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Seekbar / time handler
    // ─────────────────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private val seekbarRunnable = object : Runnable {
        override fun run() {
            val service = musicService ?: return
            seekbar.progress = service.getCurrentPosition()
            txtSStart.text = createTime(service.getCurrentPosition())
            handler.postDelayed(this, 500)
        }
    }

    // Flag that survives configuration changes — tells us the service is already
    // running so we don't restart playback from scratch.
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

        // servicePreviouslyRunning = true if the activity is being re-created
        // (e.g. rotation) while the service is already playing.
        servicePreviouslyRunning = savedInstanceState?.getBoolean("service_running", false) ?: false

        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Request RECORD_AUDIO permission for the visualizer.
     */
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
                    // Try to attach visualizer if service is connected
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
            // Re-attach visualizer when resuming
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
        Log.d(TAG, "=== MusicPlayerActivity.onSongChanged ===")
        Log.d(TAG, "Position: $position, songName: '$songName'")
        Log.d(TAG, "isInitialSetup: $isInitialSetup")
        Log.d(TAG, "mediaTypes size: ${mediaTypes?.size ?: 0}")
        Log.d(TAG, "mediaTypes content: ${mediaTypes?.joinToString(", ") ?: "null"}")



        // Don't transition during initial setup - prevents unwanted switches
        if (isInitialSetup) {
            Log.d(TAG, "🛡️ Still in initial setup - BLOCKING transition logic")
            // Update UI but don't transition
            txtSName.text = songName
            return
        }

        // NOW CHECK: What type of media should we be playing?
        if (!mediaTypes.isNullOrEmpty() && position < mediaTypes!!.size) {
            val currentMediaType = mediaTypes!![position]
            Log.d(TAG, "Current media type at position $position: '$currentMediaType'")

            if (currentMediaType == "video") {
                // This should be played in VideoPlayerActivity - transition there
                Log.d(TAG, "🎬 Current item is VIDEO - should be in VideoPlayerActivity, transitioning...")
                transitionToVideoPlayer(position)
                return
            } else if (currentMediaType == "audio") {
                // This should stay in MusicPlayerActivity - play the audio
                Log.d(TAG, "🎵 Current item is AUDIO - correct player, playing audio...")
            }
        } else {
            Log.w(TAG, "mediaTypes is null/empty or position out of bounds - assuming audio")
        }

        // Continue with normal audio playback
        txtSName.text = songName
        txtSStop.text = createTime(musicService?.getDuration() ?: 0)
        seekbar.max  = musicService?.getDuration() ?: 0
        seekbar.progress = 0
        startAnimation(imageView)

        Log.d(TAG, "Song updated: $songName at position $position")

        // Re-attach visualizer when song changes to get new audio session
        handler.postDelayed({
            attachVisualizer()
        }, 300)

        Log.d(TAG, "=== End MusicPlayerActivity.onSongChanged ===")
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
        // File is not Parcelable, so the playlist is passed as absolute path strings
        // and reconstructed as File objects here.
        val paths = bundle.getStringArrayList("songs")!!
        mySongs = ArrayList(paths.map { File(it) })
        startPosition = bundle.getInt("pos", 0)

        // Get song titles, artists, and media types if provided
        val songTitles = bundle.getStringArrayList("songTitles")
        val songArtists = bundle.getStringArrayList("songArtists")
        mediaTypes = bundle.getStringArrayList("mediaTypes")  // NEW: Track media types

        // Store for passing to service
        this.songTitles = songTitles
        this.songArtists = songArtists

        // Pre-populate the song name immediately so there's no blank flash
        // before the service connects.
        val displayTitle = if (!songTitles.isNullOrEmpty() && startPosition < songTitles.size) {
            songTitles[startPosition]
        } else {
            intent.getStringExtra("songName") ?: mySongs[startPosition].name
        }

        txtSName.text = displayTitle
        txtSName.isSelected = true  // enables marquee scrolling

        Log.d(TAG, "Intent read: ${mySongs.size} media items, starting at position $startPosition")
        Log.d(TAG, "Song titles provided: ${songTitles?.joinToString(", ") ?: "None"}")
        Log.d(TAG, "Media types provided: ${mediaTypes?.joinToString(", ") ?: "None"}")
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
                // Pause the auto-updater while the user is scrubbing
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

        // Use the proper song title from our stored titles, not the filename
        val displayTitle = if (!songTitles.isNullOrEmpty() && pos < songTitles!!.size) {
            songTitles!![pos]
        } else if (pos < mySongs.size) {
            // Fallback to filename if no titles available
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

    /**
     * Attaches [BarVisualizerView] to the service's MediaPlayer audio session.
     * Safe to call multiple times — the view releases its previous Visualizer first.
     * Requires RECORD_AUDIO permission.
     */
    private fun attachVisualizer() {
        try {
            // Check if we have RECORD_AUDIO permission
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
                // Retry after a short delay if music is not playing yet
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
     * Seamlessly transition to VideoPlayerActivity when the current media is a video.
     * Preserves the current playlist position and state.
     */
    private fun transitionToVideoPlayer(position: Int) {
        try {
            Log.d(TAG, "Creating intent for VideoPlayerActivity transition...")

            val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                // Pass all the same data that MusicPlayerActivity received
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
            Log.d(TAG, "Playlist size: ${mySongs.size}, Media types: ${mediaTypes?.joinToString(", ") ?: "null"}")

            startActivity(intent)

            // Close this activity so the user doesn't return to audio player when pressing back
            finish()

        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "VideoPlayerActivity class not found - is it registered in AndroidManifest.xml?", e)
            Toast.makeText(this, "Video player not available", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error transitioning to video player: ${e.message}", e)
            Toast.makeText(this, "Error opening video player", Toast.LENGTH_SHORT).show()
        }
    }
}