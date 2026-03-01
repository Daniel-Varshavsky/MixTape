package com.example.mixtape.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * A drop-in bar visualizer that uses Android's built-in [Visualizer] API —
 * no third-party library required.
 *
 * Updated with better error handling for security exceptions and permission issues.
 */
class BarVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "BarVisualizerView"
    }

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
        if (sessionId == -1) {
            Log.w(TAG, "Invalid audio session ID: $sessionId")
            return
        }

        try {
            Log.d(TAG, "Attempting to create Visualizer with session ID: $sessionId")

            visualizer = Visualizer(sessionId).apply {
                // Check if Visualizer is supported
                val captureSizeRange = Visualizer.getCaptureSizeRange()
                if (captureSizeRange.isEmpty()) {
                    Log.e(TAG, "Visualizer not supported - no capture size range")
                    return@setAudioSessionId
                }

                captureSize = captureSizeRange[1]
                Log.d(TAG, "Capture size set to: $captureSize")

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, sr: Int) {
                            // Not used
                        }

                        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                            try {
                                fftBytes = fft.copyOf()
                                post { invalidate() } // Update UI on main thread
                            } catch (e: Exception) {
                                Log.w(TAG, "Error in FFT data capture: ${e.message}")
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    /* waveform= */ false,
                    /* fft=      */ true
                )

                enabled = true
                Log.d(TAG, "Visualizer enabled successfully")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception creating Visualizer - check RECORD_AUDIO permission: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "Visualizer not supported on this device: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Illegal state when creating Visualizer: ${e.message}")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Runtime exception creating Visualizer: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error creating Visualizer: ${e.message}", e)
        }
    }

    /**
     * Release the underlying [Visualizer]. Call from Activity.onDestroy().
     */
    fun release() {
        try {
            visualizer?.let { viz ->
                if (viz.enabled) {
                    viz.enabled = false
                }
                viz.release()
                Log.d(TAG, "Visualizer released successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Visualizer: ${e.message}")
        } finally {
            visualizer = null
            fftBytes = ByteArray(0)
        }
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