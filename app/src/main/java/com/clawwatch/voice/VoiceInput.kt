package com.clawwatch.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ListeningState { IDLE, LISTENING, PROCESSING }

class VoiceInput(private val context: Context) {

    private val _state = MutableStateFlow(ListeningState.IDLE)
    val state: StateFlow<ListeningState> = _state

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript

    private val _rmsFlow = MutableStateFlow(0f)
    val rmsFlow: StateFlow<Float> = _rmsFlow

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var recognizer: SpeechRecognizer? = null

    // Whether we're in an active tap-to-talk session
    @Volatile private var isActive = false

    private val listener: RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = ListeningState.LISTENING
            _error.value = null
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _rmsFlow.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _state.value = ListeningState.PROCESSING
        }

        override fun onError(error: Int) {
            _rmsFlow.value = 0f

            if (isActive) {
                // During active session, silently restart on silence timeouts
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_CLIENT) {
                    // If we have accumulated text, emit it and finish
                    if (_transcript.value.isNotBlank()) {
                        isActive = false
                        _state.value = ListeningState.IDLE
                        return
                    }
                    // No text yet — keep listening
                    startListeningInternal()
                    return
                }
            }

            // On serious errors, recreate the recognizer
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_SERVER ||
                error == SpeechRecognizer.ERROR_AUDIO) {
                ensureRecognizer(forceRecreate = true)
            }

            isActive = false
            _state.value = ListeningState.IDLE
            _error.value = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                else -> "Recognition error ($error)"
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: return
            if (text.isNotBlank()) {
                _transcript.value = text
            }
        }

        override fun onResults(results: Bundle?) {
            _rmsFlow.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            // Emit final transcript and go idle
            isActive = false
            _state.value = ListeningState.IDLE
            if (text.isNotBlank()) {
                _transcript.value = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        // Eagerly create recognizer to absorb cold-start penalty at app launch
        ensureRecognizer()
    }

    /**
     * Create or recreate the SpeechRecognizer.
     * Called once at init and again only on serious errors.
     */
    private fun ensureRecognizer(forceRecreate: Boolean = false) {
        if (forceRecreate) {
            recognizer?.destroy()
            recognizer = null
        }
        if (recognizer == null) {
            android.util.Log.d("VoiceInput", "Creating SpeechRecognizer")
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(listener)
            recognizer = sr
        }
    }

    /**
     * Start a tap-to-talk session.
     * Listens for speech with a 3-second silence timeout.
     * Automatically stops and emits transcript when silence is detected.
     */
    fun startTapToTalk() {
        isActive = true
        _transcript.value = ""
        _error.value = null
        startListeningInternal()
    }

    /** Cancel the current listening session */
    fun cancelListening() {
        isActive = false
        recognizer?.cancel()
        _rmsFlow.value = 0f
        _state.value = ListeningState.IDLE
    }

    private fun startListeningInternal() {
        _state.value = ListeningState.LISTENING
        ensureRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 3-second silence timeout
            putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 3000L)
            putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500L)
        }

        recognizer?.startListening(intent)
    }

    fun destroy() {
        isActive = false
        recognizer?.destroy()
        recognizer = null
        _state.value = ListeningState.IDLE
    }
}
