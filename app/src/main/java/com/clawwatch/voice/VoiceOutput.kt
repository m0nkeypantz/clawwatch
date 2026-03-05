package com.clawwatch.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private enum class TtsProvider {
    LOCAL,
    ELEVENLABS,
}

/**
 * Speech output for the watch app.
 * Supports local Android TTS or ElevenLabs audio fetches, selected from settings.
 */
class VoiceOutput(private val context: Context) {

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    /** Real-time RMS of the audio being played, normalized 0..1 */
    private val _rmsFlow = MutableStateFlow(0f)
    val rmsFlow: StateFlow<Float> = _rmsFlow

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(2, 60, TimeUnit.SECONDS))
        .build()

    private val fallbackVoiceId = "eXpIbVcVbLo8ZJQDlDnl"
    private val modelId = "eleven_turbo_v2_5"

    private val audioQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var streaming = false
    @Volatile private var currentPlayer: MediaPlayer? = null
    @Volatile private var currentVisualizer: Visualizer? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var prefetchedFile: File? = null
    private var prefetchJob: Job? = null

    @Volatile var volume: Float = 1.0f
    @Volatile private var provider: TtsProvider = TtsProvider.LOCAL
    @Volatile private var elevenLabsApiKey: String = ""
    @Volatile private var elevenLabsVoiceId: String = ""

    @Volatile private var textToSpeech: TextToSpeech? = null
    private var ttsInitDeferred: CompletableDeferred<Boolean>? = null

    fun setUseLocalTts(enabled: Boolean) {
        provider = if (enabled) TtsProvider.LOCAL else TtsProvider.ELEVENLABS
    }

    fun setElevenLabsApiKey(apiKey: String) {
        elevenLabsApiKey = apiKey.trim()
    }

    fun setElevenLabsVoiceId(voiceId: String) {
        elevenLabsVoiceId = voiceId.trim()
    }

    fun beginStream() {
        stop()
        streaming = true
        _isSpeaking.value = true
        startPlaybackLoop()
    }

    fun queueChunk(text: String) {
        if (text.isBlank()) return
        audioQueue.add(text)
    }

    fun endStream() {
        streaming = false
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        beginStream()
        queueChunk(text)
        endStream()
    }

    fun stop() {
        streaming = false
        audioQueue.clear()
        playbackJob?.cancel()
        playbackJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchedFile?.delete()
        prefetchedFile = null
        releaseVisualizer()
        try {
            currentPlayer?.stop()
            currentPlayer?.release()
        } catch (_: Exception) {}
        currentPlayer = null
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
        _isSpeaking.value = false
        _rmsFlow.value = 0f
        cleanupTempFiles()
    }

    fun shutdown() {
        stop()
        try {
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        textToSpeech = null
        ttsInitDeferred = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun startPlaybackLoop() {
        playbackJob = scope.launch {
            while (isActive) {
                val providerAtStart = provider
                when (providerAtStart) {
                    TtsProvider.LOCAL -> playNextLocalChunk()
                    TtsProvider.ELEVENLABS -> playNextRemoteChunk()
                }

                if (!streaming && audioQueue.isEmpty() && prefetchedFile == null) {
                    _rmsFlow.value = 0f
                    withContext(Dispatchers.Main) {
                        _isSpeaking.value = false
                    }
                    break
                }
            }
            releaseVisualizer()
        }
    }

    private suspend fun playNextLocalChunk() {
        val text = audioQueue.poll()
        if (text == null) {
            delay(80)
            return
        }
        try {
            speakLocally(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("VoiceOutput", "Local TTS error: ${e.message}")
        }
    }

    private suspend fun playNextRemoteChunk() {
        val readyFile = prefetchedFile
        if (readyFile != null && readyFile.exists() && readyFile.length() > 0L) {
            prefetchedFile = null
            launchPrefetch()
            try {
                playAudioFile(readyFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VoiceOutput", "Playback error: ${e.message}")
            }
            readyFile.delete()
            return
        }

        val text = audioQueue.poll()
        if (text == null) {
            delay(80)
            return
        }

        try {
            val file = fetchElevenLabsAudio(text)
            if (file != null) {
                launchPrefetch()
                playAudioFile(file)
                file.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("VoiceOutput", "ElevenLabs TTS error: ${e.message}")
        }
    }

    private fun launchPrefetch() {
        prefetchJob?.cancel()
        val nextText = audioQueue.poll() ?: return
        prefetchJob = scope.launch {
            try {
                val file = fetchElevenLabsAudio(nextText)
                if (file != null) {
                    prefetchedFile = file
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VoiceOutput", "Prefetch error: ${e.message}")
            }
        }
    }

    private suspend fun ensureTextToSpeech(): TextToSpeech? {
        textToSpeech?.let { return it }
        val existingDeferred = ttsInitDeferred
        if (existingDeferred != null) {
            return if (existingDeferred.await()) textToSpeech else null
        }

        val deferred = CompletableDeferred<Boolean>()
        ttsInitDeferred = deferred
        var pendingTts: TextToSpeech? = null
        pendingTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = pendingTts ?: run {
                    deferred.complete(false)
                    return@TextToSpeech
                }
                val result = tts.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    android.util.Log.e("VoiceOutput", "Local TTS language not supported")
                    deferred.complete(false)
                } else {
                    textToSpeech = tts
                    deferred.complete(true)
                }
            } else {
                android.util.Log.e("VoiceOutput", "Local TTS init failed: $status")
                deferred.complete(false)
            }
        }

        return if (deferred.await()) textToSpeech else null
    }

    private suspend fun speakLocally(text: String) {
        val tts = ensureTextToSpeech() ?: return
        suspendCancellableCoroutine<Unit> { cont ->
            val utteranceId = UUID.randomUUID().toString()
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _rmsFlow.value = 0.35f
                }

                override fun onDone(utteranceId: String?) {
                    _rmsFlow.value = 0f
                    if (cont.isActive) {
                        cont.resume(Unit)
                    }
                }

                override fun onError(utteranceId: String?) {
                    _rmsFlow.value = 0f
                    if (cont.isActive) {
                        cont.resume(Unit)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _rmsFlow.value = 0f
                    if (cont.isActive) {
                        cont.resume(Unit)
                    }
                }
            }

            cont.invokeOnCancellation {
                _rmsFlow.value = 0f
                try {
                    tts.stop()
                } catch (_: Exception) {}
            }

            try {
                tts.setSpeechRate(1.0f)
                tts.setPitch(1.0f)
                tts.setOnUtteranceProgressListener(listener)
                val params = android.os.Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                }
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (result == TextToSpeech.ERROR && cont.isActive) {
                    cont.resume(Unit)
                }
            } catch (_: Exception) {
                _rmsFlow.value = 0f
                if (cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }
    }

    private fun attachVisualizer(player: MediaPlayer) {
        try {
            val sessionId = player.audioSessionId
            val existing = currentVisualizer
            if (existing != null) {
                try {
                    existing.enabled = false
                    existing.release()
                } catch (_: Exception) {}
            }
            val viz = Visualizer(sessionId)
            viz.captureSize = Visualizer.getCaptureSizeRange()[0]
            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveform == null || waveform.isEmpty()) {
                            _rmsFlow.value = 0f
                            return
                        }
                        var sumSquares = 0.0
                        for (b in waveform) {
                            val sample = (b.toInt() and 0xFF) - 128
                            sumSquares += sample * sample
                        }
                        val rmsValue = Math.sqrt(sumSquares / waveform.size).toFloat()
                        _rmsFlow.value = (rmsValue / 80f).coerceIn(0f, 1f)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {}
                },
                Visualizer.getMaxCaptureRate(),
                true,
                false,
            )
            viz.enabled = true
            currentVisualizer = viz
        } catch (e: Exception) {
            android.util.Log.e("VoiceOutput", "Visualizer error: ${e.message}")
            _rmsFlow.value = 0f
        }
    }

    private fun releaseVisualizer() {
        try {
            currentVisualizer?.enabled = false
            currentVisualizer?.release()
        } catch (_: Exception) {}
        currentVisualizer = null
    }

    private fun fetchElevenLabsAudio(text: String): File? {
        val apiKey = elevenLabsApiKey.trim()
        if (apiKey.isBlank()) {
            android.util.Log.w("VoiceOutput", "Skipping ElevenLabs TTS: missing API key")
            return null
        }
        val voiceId = elevenLabsVoiceId.trim().ifBlank { fallbackVoiceId }

        val json = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.0)
                put("use_speaker_boost", true)
            })
        }

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?optimize_streaming_latency=4")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            android.util.Log.e("VoiceOutput", "ElevenLabs API error ${response.code}: $errorBody")
            response.close()
            return null
        }

        val tempFile = File.createTempFile("eleven_", ".mp3", context.cacheDir)
        response.body?.byteStream()?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        response.close()

        if (!tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            return null
        }
        return tempFile
    }

    private suspend fun playAudioFile(file: File) {
        suspendCancellableCoroutine<Unit> { cont ->
            val player = MediaPlayer()
            currentPlayer = player

            cont.invokeOnCancellation {
                releaseVisualizer()
                try {
                    player.stop()
                    player.release()
                } catch (_: Exception) {}
                file.delete()
            }

            try {
                player.setDataSource(file.absolutePath)
                player.setOnCompletionListener {
                    try {
                        player.release()
                    } catch (_: Exception) {}
                    currentPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                }
                player.setOnErrorListener { _, _, _ ->
                    releaseVisualizer()
                    try {
                        player.release()
                    } catch (_: Exception) {}
                    currentPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                player.prepare()
                player.setVolume(volume, volume)
                attachVisualizer(player)
                player.start()
            } catch (_: Exception) {
                releaseVisualizer()
                try {
                    player.release()
                } catch (_: Exception) {}
                currentPlayer = null
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun cleanupTempFiles() {
        try {
            context.cacheDir.listFiles()?.filter { it.name.startsWith("eleven_") }?.forEach {
                it.delete()
            }
        } catch (_: Exception) {}
    }
}
