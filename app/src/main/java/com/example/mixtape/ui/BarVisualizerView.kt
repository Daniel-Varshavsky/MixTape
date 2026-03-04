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
 * BarVisualizerView is a custom View that renders a frequency-based bar visualizer
 * using the Android Visualizer API. It processes FFT (Fast Fourier Transform) data
 * from a MediaPlayer session to create a dynamic animation.
 */
class BarVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "BarVisualizerView"
    }

    // --- Visual Configuration ---
    private val barColor  = Color.parseColor("#FF362E")
    private val barCount  = 64          // Total number of vertical bars to draw
    private val barRadiusPx = 6f       // Corner radius for the rounded bars
    private val gapRatio  = 0.35f      // Percentage of slot width dedicated to spacing between bars

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        style = Paint.Style.FILL
    }

    private var visualizer: Visualizer? = null
    private var fftBytes: ByteArray = ByteArray(0)
    private val rect = RectF()

    /**
     * Attaches the visualizer to a specific audio session.
     * @param sessionId The audio session ID from MediaPlayer. If -1, the visualizer is released.
     */
    fun setAudioSessionId(sessionId: Int) {
        release()
        if (sessionId == -1) {
            Log.w(TAG, "Invalid audio session ID: $sessionId")
            return
        }

        try {
            Log.d(TAG, "Initializing Visualizer for session: $sessionId")

            visualizer = Visualizer(sessionId).apply {
                val captureSizeRange = Visualizer.getCaptureSizeRange()
                if (captureSizeRange.isEmpty()) {
                    Log.e(TAG, "Visualizer API not supported on this device.")
                    return@setAudioSessionId
                }

                // Use the maximum supported capture size for better resolution
                captureSize = captureSizeRange[1]

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, sr: Int) {
                            // Waveform data is ignored in favor of frequency-domain (FFT) data
                        }

                        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, sr: Int) {
                            try {
                                // Capture a copy of the frequency data and trigger a redraw
                                fftBytes = fft.copyOf()
                                post { invalidate() } 
                            } catch (e: Exception) {
                                Log.w(TAG, "Error processing FFT capture: ${e.message}")
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    /* waveform= */ false,
                    /* fft=      */ true
                )

                enabled = true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for RECORD_AUDIO: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize visualizer: ${e.message}", e)
        }
    }

    /**
     * Releases system resources used by the Visualizer. 
     * Must be called when the activity or fragment is destroyed.
     */
    fun release() {
        try {
            visualizer?.let { viz ->
                if (viz.enabled) viz.enabled = false
                viz.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during resource cleanup: ${e.message}")
        } finally {
            visualizer = null
            fftBytes = ByteArray(0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        if (fftBytes.isEmpty()) {
            drawSilence(canvas, w, h)
            return
        }

        // Calculate dimensions based on view width and bar configuration
        val slotW   = w / barCount
        val barW    = slotW * (1f - gapRatio)
        val halfGap = (slotW - barW) / 2f

        // FFT output contains complex pairs; magnitude = sqrt(re^2 + im^2)
        // We only map the lower half of the FFT bins as they contain most musical info
        val usableBins = (fftBytes.size / 2).coerceAtLeast(1)

        for (i in 0 until barCount) {
            // Map the bar index to an FFT bin with a slight bias towards lower frequencies
            val binIndex = (i.toFloat() / barCount * usableBins).toInt().coerceIn(0, usableBins - 1)
            val re = fftBytes[binIndex * 2].toInt()
            val im = if (binIndex * 2 + 1 < fftBytes.size) fftBytes[binIndex * 2 + 1].toInt() else 0
            val magnitude = Math.sqrt((re * re + im * im).toDouble()).toFloat()

            // Normalize magnitude (FFT bytes typically range up to 128)
            val normalized = (magnitude / 128f).coerceIn(0f, 1f)

            // Calculate vertical position (bars grow upwards from the bottom)
            val barH = (normalized * h).coerceAtLeast(4f) // Ensure bars have a minimum visible height

            val left   = i * slotW + halfGap
            val right  = left + barW
            val top    = h - barH
            val bottom = h

            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, barRadiusPx, barRadiusPx, paint)
        }
    }

    /**
     * Renders a static baseline when no audio is being processed.
     */
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
