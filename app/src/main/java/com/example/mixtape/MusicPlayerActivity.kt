package com.example.mixtape

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.*
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

            musicService?.setListener(this@MusicPlayerActivity)

            // Hand the playlist to the service only on the very first connect.
            // If the service is already playing (e.g. activity re-created after rotation),
            // we just sync our UI to the current service state instead.
            if (musicService?.isPlaying() == false && !servicePreviouslyRunning) {
                musicService?.initPlaylist(mySongs, startPosition)
            } else {
                syncUiToServiceState()
            }

            startSeekbarUpdater()
            attachVisualizer()
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

        // Start the service (keeps it alive past unbind) and bind to it
        val serviceIntent = Intent(this, MusicPlayerService::class.java)
        startService(serviceIntent)

        // servicePreviouslyRunning = true if the activity is being re-created
        // (e.g. rotation) while the service is already playing.
        servicePreviouslyRunning = savedInstanceState?.getBoolean("service_running", false) ?: false

        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
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
            attachVisualizer()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(seekbarRunnable)
        // Do NOT stop the service here — it keeps playing in the background.
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        musicService?.setListener(null)
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
        txtSName.text = songName
        txtSStop.text = createTime(musicService?.getDuration() ?: 0)
        seekbar.max  = musicService?.getDuration() ?: 0
        seekbar.progress = 0
        startAnimation(imageView)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        buttonPlay.setBackgroundResource(
            if (isPlaying) R.drawable.baseline_pause_24 else R.drawable.baseline_play_arrow_24
        )
    }

    override fun onRepeatChanged(isRepeat: Boolean) {
        updateRepeatButtonVisual(isRepeat)
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

        // Pre-populate the song name immediately so there's no blank flash
        // before the service connects.
        txtSName.text = intent.getStringExtra("songName") ?: mySongs[startPosition].name
        txtSName.isSelected = true  // enables marquee scrolling
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
            val service = musicService ?: return@setOnClickListener
            service.seekTo(service.getCurrentPosition() + 10_000)
        }

        buttonFastRewind.setOnClickListener {
            val service = musicService ?: return@setOnClickListener
            service.seekTo((service.getCurrentPosition() - 10_000).coerceAtLeast(0))
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
        if (pos < mySongs.size) {
            txtSName.text = mySongs[pos].name
        }
        seekbar.max      = service.getDuration()
        seekbar.progress = service.getCurrentPosition()
        txtSStop.text    = createTime(service.getDuration())
        txtSStart.text   = createTime(service.getCurrentPosition())
        onPlaybackStateChanged(service.isPlaying())
        updateRepeatButtonVisual(service.isRepeat())
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
     * Requires RECORD_AUDIO permission (request at runtime before calling this).
     */
    private fun attachVisualizer() {
        val sessionId = musicService?.getAudioSessionId() ?: -1
        visualizer.setAudioSessionId(sessionId)
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
}