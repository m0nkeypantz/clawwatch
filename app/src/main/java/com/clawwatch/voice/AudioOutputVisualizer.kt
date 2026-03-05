package com.clawwatch.voice

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Captures the device's audio output levels using Android's Visualizer API,
 * providing a normalized RMS value (0f..1f) that drives the AI waveform.
 */
class AudioOutputVisualizer {

    private val _rms = MutableStateFlow(0f)
    val rms: StateFlow<Float> = _rms

    private var visualizer: Visualizer? = null

    /**
     * Start capturing audio output. Uses session 0 (global mix).
     * Requires RECORD_AUDIO permission.
     */
    fun start() {
        stop()
        try {
            val viz = Visualizer(0) // session 0 = global audio output
            viz.captureSize = Visualizer.getCaptureSizeRange()[0] // smallest buffer for speed
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform == null || waveform.isEmpty()) {
                            _rms.value = 0f
                            return
                        }
                        // Calculate RMS from the waveform (unsigned bytes, 128 = silence)
                        var sumSquares = 0.0
                        for (b in waveform) {
                            val sample = (b.toInt() and 0xFF) - 128
                            sumSquares += sample * sample
                        }
                        val rmsValue = Math.sqrt(sumSquares / waveform.size).toFloat()
                        // Normalize: max possible RMS is 128, typical speech is 10-60
                        _rms.value = (rmsValue / 80f).coerceIn(0f, 1f)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        // Not used
                    }
                },
                Visualizer.getMaxCaptureRate(), // capture as fast as possible
                true,  // waveform
                false  // no FFT
            )
            viz.enabled = true
            visualizer = viz
        } catch (e: Exception) {
            // Visualizer may fail on some devices (e.g. missing permission, no audio session)
            _rms.value = 0f
        }
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
        _rms.value = 0f
    }
}
