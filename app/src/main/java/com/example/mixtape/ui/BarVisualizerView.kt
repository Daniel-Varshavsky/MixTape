package com.example.mixtape.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.view.View

/**
 * A drop-in bar visualizer that uses Android's built-in [Visualizer] API —
 * no third-party library required.
 *
 * Usage in XML (replace the old com.gauravk… entry):
 *
 *   <com.example.mixtape.BarVisualizerView
 *       android:id="@+id/blast"
 *       android:layout_width="match_parent"
 *       android:layout_height="70dp"
 *       android:layout_alignParentBottom="true" />
 *
 * Usage in code (same as before, just call setAudioSessionId):
 *
 *   visualizer.setAudioSessionId(musicService.getAudioSessionId())
 *   // …and later:
 *   visualizer.release()
 */
class BarVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Tuneable appearance constants ─────────────────────────────────────────
    private val barColor  = Color.parseColor("#FF362E")
    private val barCount  = 64          // number of bars drawn
    private val barRadiusPx = 6f       // rounded-corner radius on each bar
    private val gapRatio  = 0.35f      // fraction of slot width used for the gap

    // ── Paint ─────────────────────────────────────────────────────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        style = Paint.Style.FILL
    }

    // ── Visualizer state ─────────────────────────────────────────────────────
    private var visualizer: Visualizer? = null
    private var fftBytes: ByteArray = ByteArray(0)
    private val rect = RectF()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Attach to the MediaPlayer's audio session.
     * Call this after the service connects and the player has started.
     * Safe to call multiple times — releases the old instance first.
     */
    fun setAudioSessionId(sessionId: Int) {
        release()
        if (sessionId == -1) return

        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, sr: Int) {}
                        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                            fftBytes = fft.copyOf()
                            postInvalidateOnAnimation()
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    /* waveform= */ false,
                    /* fft=      */ true
                )
                enabled = true
            }
        } catch (e: Exception) {
            // Gracefully degrade — visualizer is cosmetic only
            e.printStackTrace()
        }
    }

    /**
     * Release the underlying [Visualizer]. Call from Activity.onDestroy().
     */
    fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        fftBytes   = ByteArray(0)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val bytes = fftBytes
        if (bytes.isEmpty()) {
            drawSilence(canvas, w, h)
            return
        }

        val slotW   = w / barCount
        val barW    = slotW * (1f - gapRatio)
        val halfGap = (slotW - barW) / 2f

        // FFT output is complex pairs; magnitude of each bin = sqrt(re²+im²).
        // We only use the first (captureSize/2) bins — they map to 0..Nyquist.
        val usableBins = (bytes.size / 2).coerceAtLeast(1)

        for (i in 0 until barCount) {
            // Map bar index → FFT bin (log-ish spacing feels more musical)
            val binIndex = (i.toFloat() / barCount * usableBins).toInt().coerceIn(0, usableBins - 1)
            val re = bytes[binIndex * 2].toInt()
            val im = if (binIndex * 2 + 1 < bytes.size) bytes[binIndex * 2 + 1].toInt() else 0
            val magnitude = Math.sqrt((re * re + im * im).toDouble()).toFloat()

            // Normalise to [0..1]; FFT byte values go up to ~128
            val normalised = (magnitude / 128f).coerceIn(0f, 1f)

            // Minimum bar height so there's always a visual tick
            val barH = (normalised * h).coerceAtLeast(4f)

            val left   = i * slotW + halfGap
            val right  = left + barW
            val top    = h - barH
            val bottom = h

            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, barRadiusPx, barRadiusPx, paint)
        }
    }

    /** Draw flat stub bars when there's no audio data yet. */
    private fun drawSilence(canvas: Canvas, w: Float, h: Float) {
        val slotW   = w / barCount
        val barW    = slotW * (1f - gapRatio)
        val halfGap = (slotW - barW) / 2f
        val stubH   = 4f

        for (i in 0 until barCount) {
            val left = i * slotW + halfGap
            rect.set(left, h - stubH, left + barW, h)
            canvas.drawRoundRect(rect, barRadiusPx, barRadiusPx, paint)
        }
    }
}
