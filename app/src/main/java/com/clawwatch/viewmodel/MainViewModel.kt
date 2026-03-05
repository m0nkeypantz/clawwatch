package com.clawwatch.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.clawwatch.ClawWatchApp
import com.clawwatch.data.ChatResponseEvent
import com.clawwatch.data.ConnectionState
import com.clawwatch.data.LocationProvider
import com.clawwatch.data.OpenClawClient
import com.clawwatch.data.SettingsStore
import com.clawwatch.voice.ListeningState
import com.clawwatch.voice.VoiceInput
import com.clawwatch.voice.VoiceOutput
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class UiPhase { IDLE, LISTENING, THINKING, SPEAKING, COMPACTING }

class MainViewModel(
    application: Application,
    private val settingsStore: SettingsStore,
    private val openClawClient: OpenClawClient,
    private val voiceOutput: VoiceOutput,
    private val locationProvider: LocationProvider,
) : AndroidViewModel(application) {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ClawWatchApp
                MainViewModel(
                    application = app,
                    settingsStore = app.settingsStore,
                    openClawClient = app.openClawClient,
                    voiceOutput = app.voiceOutput,
                    locationProvider = app.locationProvider,
                )
            }
        }
    }

    private val voiceInput = VoiceInput(application)

    val connectionState: StateFlow<ConnectionState> = openClawClient.connectionState
    val listeningState: StateFlow<ListeningState> = voiceInput.state

    private val _responseText = MutableStateFlow("")
    val responseText: StateFlow<String> = _responseText

    private val _uiPhase = MutableStateFlow(UiPhase.IDLE)
    val uiPhase: StateFlow<UiPhase> = _uiPhase

    private val _voiceMuted = MutableStateFlow(false)
    val voiceMuted: StateFlow<Boolean> = _voiceMuted

    private val _continuousMode = MutableStateFlow(false)
    val continuousMode: StateFlow<Boolean> = _continuousMode

    /** Tracks how far into the response text we've already queued to TTS. */
    private var spokenUpTo = 0

    /** Guard against double-sends from combine flow firing twice */
    private var messageSent = false

    /** Active timer countdown (seconds remaining, null = no timer) */
    private val _timerSeconds = MutableStateFlow<Int?>(null)
    val timerSeconds: StateFlow<Int?> = _timerSeconds
    private var timerJob: kotlinx.coroutines.Job? = null

    /** Whether we're running a /compact command */
    private var isCompacting = false

    val gatewayUrl: StateFlow<String> = settingsStore.gatewayUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val authToken: StateFlow<String> = settingsStore.authToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val useLocalTts: StateFlow<Boolean> = settingsStore.useLocalTts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val elevenLabsApiKey: StateFlow<String> = settingsStore.elevenLabsApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val elevenLabsVoiceId: StateFlow<String> = settingsStore.elevenLabsVoiceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val assistantVolume: StateFlow<Float> = settingsStore.assistantVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val alarmVolume: StateFlow<Float> = settingsStore.alarmVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val rmsFlow: StateFlow<Float> = voiceInput.rmsFlow
    val aiRmsFlow: StateFlow<Float> = voiceOutput.rmsFlow

    /** Session load: 0.0 = fresh, 1.0 = context full (estimated) */
    private val _sessionLoad = MutableStateFlow(0f)
    val sessionLoad: StateFlow<Float> = _sessionLoad
    private var cumulativeChars = 0
    private val maxContextChars = 200_000 // ~50K tokens

    /** Ambient mode state */
    private val _isAmbient = MutableStateFlow(false)
    val isAmbient: StateFlow<Boolean> = _isAmbient

    /** Audio ducking */
    private val audioManager = application.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    init {
        viewModelScope.launch {
            settingsStore.voiceMute.collect { _voiceMuted.value = it }
        }
        viewModelScope.launch {
            settingsStore.continuousMode.collect { _continuousMode.value = it }
        }
        // Audio ducking: request/abandon focus based on phase
        viewModelScope.launch {
            _uiPhase.collect { phase ->
                when (phase) {
                    UiPhase.LISTENING, UiPhase.SPEAKING -> {
                        audioManager.requestAudioFocus(audioFocusRequest)
                    }
                    UiPhase.IDLE -> {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    }
                    else -> { /* THINKING, COMPACTING: keep current state */ }
                }
            }
        }
        // Sync assistant volume setting to VoiceOutput
        viewModelScope.launch {
            settingsStore.assistantVolume.collect { vol ->
                voiceOutput.volume = vol
            }
        }
        viewModelScope.launch {
            settingsStore.useLocalTts.collect { enabled ->
                voiceOutput.setUseLocalTts(enabled)
            }
        }
        viewModelScope.launch {
            settingsStore.elevenLabsApiKey.collect { apiKey ->
                voiceOutput.setElevenLabsApiKey(apiKey)
            }
        }
        viewModelScope.launch {
            settingsStore.elevenLabsVoiceId.collect { voiceId ->
                voiceOutput.setElevenLabsVoiceId(voiceId)
            }
        }

        // Subscribe to our session key
        openClawClient.subscribeToSession("agent:main:watch")

        observeSettingsAndConnect()
        observeVoiceTranscript()
        observeSpeakingState()
        observeChatResponses()
    }

    fun toggleMute() {
        val newValue = !_voiceMuted.value
        _voiceMuted.value = newValue
        viewModelScope.launch { settingsStore.setVoiceMute(newValue) }
    }

    fun updateGatewayUrl(url: String) {
        viewModelScope.launch { settingsStore.setGatewayUrl(url) }
    }

    fun updateAuthToken(token: String) {
        viewModelScope.launch { settingsStore.setAuthToken(token) }
    }

    fun updateUseLocalTts(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setUseLocalTts(enabled) }
    }

    fun updateElevenLabsApiKey(apiKey: String) {
        viewModelScope.launch { settingsStore.setElevenLabsApiKey(apiKey) }
    }

    fun updateElevenLabsVoiceId(voiceId: String) {
        viewModelScope.launch { settingsStore.setElevenLabsVoiceId(voiceId) }
    }

    fun updateAssistantVolume(vol: Float) {
        viewModelScope.launch { settingsStore.setAssistantVolume(vol) }
    }

    fun updateAlarmVolume(vol: Float) {
        viewModelScope.launch { settingsStore.setAlarmVolume(vol) }
    }

    /** Called when the Activity resumes to ensure we hold a connection */
    fun reconnectIfNeeded() {
        if (connectionState.value == ConnectionState.DISCONNECTED) {
            android.util.Log.d("MainVM", "reconnectIfNeeded: reconnecting...")
            viewModelScope.launch {
                val url = settingsStore.gatewayUrl.first()
                val token = settingsStore.authToken.first()
                if (url.isNotBlank() && token.isNotBlank()) {
                    openClawClient.connect(url, token)
                }
            }
        }
    }

    private fun observeSettingsAndConnect() {
        viewModelScope.launch {
            combine(gatewayUrl, authToken) { url, token ->
                url to token
            }.distinctUntilChanged().collect { (url, token) ->
                if (url.isNotBlank() && token.isNotBlank()) {
                    openClawClient.connect(url, token)
                } else {
                    openClawClient.disconnect()
                }
            }
        }
    }

    private fun observeVoiceTranscript() {
        viewModelScope.launch {
            combine(voiceInput.transcript, voiceInput.state) { transcript, state ->
                transcript to state
            }.collect { (transcript, state) ->
                android.util.Log.d("MainVM", "VoiceState=$state transcript='${transcript.take(30)}' uiPhase=${_uiPhase.value}")
                when (state) {
                    ListeningState.LISTENING -> {
                        if (_uiPhase.value == UiPhase.IDLE) {
                            _uiPhase.value = UiPhase.LISTENING
                        }
                    }
                    ListeningState.PROCESSING -> {
                        if (_uiPhase.value == UiPhase.LISTENING) {
                            _uiPhase.value = UiPhase.THINKING
                        }
                    }
                    ListeningState.IDLE -> {
                        if (transcript.isNotBlank() && !messageSent && (_uiPhase.value == UiPhase.LISTENING || _uiPhase.value == UiPhase.THINKING)) {
                            if (_uiPhase.value != UiPhase.SPEAKING) {
                                android.util.Log.d("MainVM", "Sending message from voice: '${transcript.take(30)}'")
                                messageSent = true
                                sendMessage(transcript)
                            }
                        }
                        
                        if (_uiPhase.value == UiPhase.LISTENING) {
                            android.util.Log.d("MainVM", "Reverting to IDLE from LISTENING")
                            _uiPhase.value = UiPhase.IDLE
                        }
                    }
                }
            }
        }
    }

    private var continuousListenJob: kotlinx.coroutines.Job? = null

    private fun observeSpeakingState() {
        viewModelScope.launch {
            voiceOutput.isSpeaking.collect { speaking ->
                android.util.Log.d("MainVM", "isSpeaking=$speaking uiPhase=${_uiPhase.value}")
                if (speaking) {
                    // Cancel any pending auto-listen (TTS resumed between chunks)
                    continuousListenJob?.cancel()
                    continuousListenJob = null
                }
                if (!speaking && _uiPhase.value == UiPhase.SPEAKING) {
                    if (_voiceMuted.value) {
                        // In mute mode, keep SPEAKING phase so text stays visible
                    } else if (_continuousMode.value) {
                        // Continuous mode: debounce before auto-listening
                        continuousListenJob?.cancel()
                        continuousListenJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(600L)
                            android.util.Log.d("MainVM", "Speaking done (continuous) -> IDLE + auto-listen")
                            _uiPhase.value = UiPhase.IDLE
                            messageSent = false
                            voiceInput.startTapToTalk()
                        }
                    } else {
                        // Normal mode: go idle immediately
                        android.util.Log.d("MainVM", "Speaking done -> IDLE")
                        _uiPhase.value = UiPhase.IDLE
                    }
                }
            }
        }
    }

    private fun observeChatResponses() {
        viewModelScope.launch {
            openClawClient.chatResponse.collect { event ->
                android.util.Log.d("MainVM", "ChatResponse event=${event::class.simpleName} uiPhase=${_uiPhase.value}")
                when (event) {
                    is ChatResponseEvent.Delta -> {
                        val cleanText = stripTimerBlock(event.fullText)
                        _responseText.value = cleanText
                        // If compacting, stay in COMPACTING phase (don't switch to SPEAKING)
                        if (isCompacting) return@collect
                        val muted = _voiceMuted.value
                        if (_uiPhase.value == UiPhase.THINKING) {
                            android.util.Log.d("MainVM", "Delta: THINKING -> SPEAKING")
                            _uiPhase.value = UiPhase.SPEAKING
                            spokenUpTo = 0
                            if (!muted) voiceOutput.beginStream()
                        }
                        if (_uiPhase.value == UiPhase.SPEAKING && !muted) {
                            queueCompleteSentences(cleanText)
                        }
                    }
                    is ChatResponseEvent.Final -> {
                        android.util.Log.d("MainVM", "Final: uiPhase=${_uiPhase.value} text='${event.fullText.take(50)}'")
                        // Parse timer from raw text, then strip it for display/TTS
                        parseTimerFromResponse(event.fullText)
                        val cleanText = stripTimerBlock(event.fullText)
                        _responseText.value = cleanText
                        trackResponseChars(cleanText)
                        // If compacting, finish and return to IDLE
                        if (isCompacting) {
                            isCompacting = false
                            _uiPhase.value = UiPhase.IDLE
                            return@collect
                        }
                        val muted = _voiceMuted.value
                        if (_uiPhase.value == UiPhase.THINKING) {
                            _uiPhase.value = UiPhase.SPEAKING
                            spokenUpTo = 0
                            if (!muted) voiceOutput.beginStream()
                        }
                        if (_uiPhase.value == UiPhase.SPEAKING) {
                            if (!muted) {
                                val remaining = cleanText
                                    .substring(spokenUpTo.coerceAtMost(cleanText.length))
                                    .trim()
                                if (remaining.isNotBlank()) {
                                    voiceOutput.queueChunk(remaining)
                                }
                                voiceOutput.endStream()
                            } else {
                                // Muted: keep SPEAKING phase so text stays; user taps to dismiss
                            }
                        }
                    }
                    is ChatResponseEvent.Error -> {
                        android.util.Log.e("MainVM", "Chat Error: ${event.errorMessage}")
                        _responseText.value = "⚠️ ${event.errorMessage}"
                        _uiPhase.value = UiPhase.IDLE
                        isCompacting = false
                    }
                }
            }
        }
    }

    private fun queueCompleteSentences(fullText: String) {
        // Loop to queue ALL complete sentences, not just one per call
        while (spokenUpTo < fullText.length) {
            val unspoken = fullText.substring(spokenUpTo)

            // Find the first sentence-ending punctuation
            val breakIndex = unspoken.indexOfFirst {
                it == '.' || it == '!' || it == '?' || it == '\n'
            }

            if (breakIndex >= 0) {
                val chunk = unspoken.substring(0, breakIndex + 1).trim()
                val newSpokenUpTo = spokenUpTo + breakIndex + 1
                if (chunk.isNotBlank()) {
                    voiceOutput.queueChunk(chunk)
                }
                spokenUpTo = newSpokenUpTo
            } else {
                // No complete sentence yet — wait for more deltas
                break
            }
        }
    }

    fun onTapToTalk() {
        if (_uiPhase.value == UiPhase.SPEAKING || _uiPhase.value == UiPhase.THINKING) {
            openClawClient.disconnect()
            voiceOutput.stop()
            _uiPhase.value = UiPhase.IDLE
            messageSent = false
            viewModelScope.launch {
                val url = settingsStore.gatewayUrl.first()
                val token = settingsStore.authToken.first()
                openClawClient.connect(url, token)
            }
        }
        messageSent = false
        voiceInput.startTapToTalk()
    }

    /** Called when user taps to dismiss the muted text response */
    fun dismissText() {
        if (_uiPhase.value == UiPhase.SPEAKING || _responseText.value.isNotBlank()) {
            _uiPhase.value = UiPhase.IDLE
            _responseText.value = ""
        }
    }

    // ======================= MENU COMMANDS =======================

    fun toggleContinuousMode() {
        val newValue = !_continuousMode.value
        _continuousMode.value = newValue
        viewModelScope.launch { settingsStore.setContinuousMode(newValue) }
    }

    /** Send /stop to cancel the AI's current run and return to idle */
    fun onStop() {
        android.util.Log.d("MainVM", "onStop() called")
        voiceOutput.stop()
        voiceInput.cancelListening()
        _uiPhase.value = UiPhase.IDLE
        _responseText.value = ""
        messageSent = false
        isCompacting = false
        viewModelScope.launch {
            try {
                openClawClient.sendChat("/stop")
            } catch (_: Exception) { }
        }
    }

    /** Compact session context via gateway RPC */
    fun onCompact() {
        android.util.Log.d("MainVM", "onCompact() called")
        voiceOutput.stop()
        voiceInput.cancelListening()
        isCompacting = true
        _uiPhase.value = UiPhase.COMPACTING
        _responseText.value = ""
        messageSent = false
        viewModelScope.launch {
            val result = openClawClient.compactSession()
            result.onSuccess { compacted ->
                android.util.Log.d("MainVM", "compact completed: compacted=$compacted")
                isCompacting = false
                _uiPhase.value = UiPhase.IDLE
                if (compacted) {
                    cumulativeChars = (cumulativeChars * 0.4).toInt() // Reduce session load estimate
                    _sessionLoad.value = (cumulativeChars.toFloat() / maxContextChars).coerceIn(0f, 1f)
                }
            }
            result.onFailure {
                android.util.Log.e("MainVM", "compact failed: ${it.message}")
                isCompacting = false
                _uiPhase.value = UiPhase.IDLE
            }
        }
    }

    /** Start a timer for the given number of seconds */
    fun onTimerStart(totalSeconds: Int) {
        android.util.Log.d("MainVM", "onTimerStart($totalSeconds seconds)")
        timerJob?.cancel()
        _timerSeconds.value = totalSeconds
        timerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0) {
                kotlinx.coroutines.delay(1000L)
                remaining--
                _timerSeconds.value = remaining
            }
            _timerSeconds.value = null
            // Timer finished — play alarm sound at configured volume
            try {
                val alarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = android.media.RingtoneManager.getRingtone(getApplication(), alarmUri)
                if (ringtone != null) {
                    // Apply alarm volume setting
                    val vol = alarmVolume.value
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        ringtone.volume = vol
                    }
                    ringtone.play()
                    kotlinx.coroutines.delay(3000L)
                    ringtone.stop()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainVM", "Alarm sound error: ${e.message}")
            }
            // Then announce via voice
            voiceOutput.beginStream()
            voiceOutput.queueChunk("Timer's up!")
            voiceOutput.endStream()
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerSeconds.value = null
    }

    /** Strip :::timer {...} blocks from text for display/TTS */
    private val timerBlockRegex = Regex(""":::timer\s*\{[^}]*\}\s*""")
    private fun stripTimerBlock(text: String): String = text.replace(timerBlockRegex, "").trim()

    /** Parse :::timer {"minutes": X, "seconds": Y} from AI response text */
    private fun parseTimerFromResponse(text: String) {
        val regex = Regex(""":::timer\s*\{[^}]*"minutes"\s*:\s*(\d+)[^}]*"seconds"\s*:\s*(\d+)[^}]*\}""")
        val match = regex.find(text) ?: return
        val minutes = match.groupValues[1].toIntOrNull() ?: return
        val seconds = match.groupValues[2].toIntOrNull() ?: 0
        val totalSeconds = minutes * 60 + seconds
        if (totalSeconds > 0) {
            android.util.Log.d("MainVM", "AI triggered timer: ${minutes}m ${seconds}s")
            onTimerStart(totalSeconds)
        }
    }

    private fun sendMessage(text: String) {
        android.util.Log.d("MainVM", "sendMessage called: '${text.take(50)}' -> Setting THINKING")
        _uiPhase.value = UiPhase.THINKING
        _responseText.value = ""
        spokenUpTo = 0
        // Track session load
        cumulativeChars += text.length
        _sessionLoad.value = (cumulativeChars.toFloat() / maxContextChars).coerceIn(0f, 1f)
        viewModelScope.launch {
            // Inject GPS location context
            val locationTag = try { locationProvider.getLocationString() } catch (_: Exception) { null }
            val messageWithContext = if (locationTag != null) "[device:watch voice:local-tts] $locationTag $text" else "[device:watch voice:local-tts] $text"
            val result = openClawClient.sendChat(messageWithContext)
            result.onFailure { error ->
                android.util.Log.e("MainVM", "sendChat FAILED: ${error.message}")
                _responseText.value = "Error: ${error.message}"
                _uiPhase.value = UiPhase.IDLE
            }
            result.onSuccess {
                android.util.Log.d("MainVM", "sendChat SUCCESS, waiting for streaming response...")
            }
        }
    }

    fun setAmbient(ambient: Boolean) {
        _isAmbient.value = ambient
    }

    /** Called when a response is received to track session memory */
    private fun trackResponseChars(text: String) {
        cumulativeChars += text.length
        _sessionLoad.value = (cumulativeChars.toFloat() / maxContextChars).coerceIn(0f, 1f)
    }

    override fun onCleared() {
        super.onCleared()
        voiceInput.destroy()
        openClawClient.disconnect()
        voiceOutput.shutdown()
        timerJob?.cancel()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }
}
