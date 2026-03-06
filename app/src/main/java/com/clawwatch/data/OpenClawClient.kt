package com.clawwatch.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val TAG = "OpenClawClient"

enum class ConnectionState { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED }

class OpenClawClient {

    constructor(context: Context) {
        appContext = context.applicationContext
        connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallbacks()
    }

    private val appContext: Context
    private val connectivityManager: ConnectivityManager
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _chatResponse = MutableSharedFlow<ChatResponseEvent>(extraBufferCapacity = 64)
    val chatResponse: SharedFlow<ChatResponseEvent> = _chatResponse

    private var webSocket: WebSocket? = null
    private val pendingRpc = mutableMapOf<String, CompletableDeferred<InboundFrame>>()

    private var gatewayUrl: String = ""
    private var authToken: String = ""
    private val activeRunIds = mutableSetOf<String>()
    private val subscribedSessionKeys = mutableSetOf<String>()

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var shouldReconnect = false
    @Volatile
    private var connectionGeneration = 0
    @Volatile
    private var connectInFlight = false
    @Volatile
    private var hasInternetNetwork = false
    @Volatile
    private var networkRequestRegistered = false

    private val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build()

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "default network available: $network")
            hasInternetNetwork = true
            scheduleReconnect(immediate = true)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            hasInternetNetwork = usable
            if (usable) {
                Log.d(TAG, "default network validated: $network")
                scheduleReconnect(immediate = true)
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "default network lost: $network")
            hasInternetNetwork = false
        }
    }

    private val bringUpNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "requested network available: $network")
            hasInternetNetwork = true
            scheduleReconnect(immediate = true)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "requested network lost: $network")
            hasInternetNetwork = false
        }
    }

    fun connect(url: String, token: String) {
        Log.d(TAG, "connect() called")
        disconnect()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        gatewayUrl = url.trimEnd('/')
        authToken = token
        shouldReconnect = true
        reconnectAttempt = 0
        connectionGeneration++
        connectInFlight = false
        requestInternetTransport()
        doConnect(connectionGeneration)
    }

    fun disconnect() {
        shouldReconnect = false
        connectionGeneration++
        connectInFlight = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        scope.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
        pendingRpc.values.forEach { it.cancel() }
        pendingRpc.clear()
        activeRunIds.clear()
    }

    /**
     * Subscribe to a specific session key so the gateway routes its broadcast
     * events (deltas, partials, finals) to this client connection.
     */
    fun subscribeToSession(sessionKey: String) {
        subscribedSessionKeys.add(sessionKey)
        Log.d(TAG, "subscribeToSession: $sessionKey (will be sent on connect)")
        sendSubscription(sessionKey)
    }

    private fun sendSubscription(sessionKey: String) {
        if (_connectionState.value != ConnectionState.CONNECTED) return

        val id = UUID.randomUUID().toString()
        val params = buildJsonObject {
            put("key", sessionKey)
        }
        val request = RequestFrame(id = id, method = "sessions.subscribe", params = params)
        val requestJson = json.encodeToString(RequestFrame.serializer(), request)
        webSocket?.send(requestJson)
    }

    /**
     * Send a chat message. Returns the runId on success.
     * The actual response text arrives via [chatResponse] as streaming events.
     */
    suspend fun sendChat(message: String): Result<String> = runCatching {
        val id = UUID.randomUUID().toString()
        val params = json.encodeToJsonElement(
            ChatSendParams.serializer(),
            ChatSendParams(
                sessionKey = "agent:main:watch",
                message = message,
                idempotencyKey = UUID.randomUUID().toString(),
            )
        ).jsonObject

        val response = sendRpc(id, Methods.CHAT_SEND, params)
        if (response.ok == true) {
            val runId = response.payload?.jsonObject?.get("runId")?.jsonPrimitive?.content ?: ""
            if (runId.isNotBlank()) {
                activeRunIds.add(runId)
                Log.d(TAG, "sendChat() runId=$runId")
            }
            runId
        } else {
            throw Exception(response.error?.message ?: "Unknown RPC error")
        }
    }

    /**
     * Compact the session transcript via sessions.compact RPC.
     * This is NOT a chat command — it's a direct gateway method.
     */
    suspend fun compactSession(): Result<Boolean> = runCatching {
        val id = UUID.randomUUID().toString()
        val params = buildJsonObject {
            put("key", "agent:main:watch")
        }

        val response = sendRpc(id, "sessions.compact", params)
        if (response.ok == true) {
            val compacted = response.payload?.jsonObject?.get("compacted")?.jsonPrimitive?.boolean ?: false
            Log.d(TAG, "compactSession() result: compacted=$compacted")
            compacted
        } else {
            throw Exception(response.error?.message ?: "Compact RPC error")
        }
    }

    /** Patch the session model override on connect */
    private fun patchSessionModel(sessionKey: String = "agent:main:watch") {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        scope.launch {
            try {
                delay(1000) // Wait for connection to fully settle
                if (_connectionState.value != ConnectionState.CONNECTED) return@launch
                val id = UUID.randomUUID().toString()
                val params = buildJsonObject {
                    put("key", sessionKey)
                    put("model", "openai-codex/gpt-5.1-codex-mini")
                }
                Log.d(TAG, "patchSessionModel() sending for $sessionKey")
                val response = sendRpc(id, "sessions.patch", params)
                if (response.ok == true) {
                    Log.d(TAG, "patchSessionModel() OK – payload: ${response.payload}")
                } else {
                    Log.e(TAG, "patchSessionModel() FAILED: ${response.error?.message} code=${response.error?.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "patchSessionModel() error: ${e.message}")
            }
        }
    }

    private fun doConnect(generation: Int = connectionGeneration) {
        if (!shouldReconnect || gatewayUrl.isBlank() || authToken.isBlank()) return
        if (generation != connectionGeneration) return
        if (connectInFlight) {
            Log.d(TAG, "doConnect() skipped; connection already in flight")
            return
        }

        connectInFlight = true
        _connectionState.value = ConnectionState.CONNECTING

        val normalized = gatewayUrl.trim()
        val wsUrl = when {
            normalized.startsWith("ws://", ignoreCase = true) -> "ws://" + normalized.substring(5)
            normalized.startsWith("wss://", ignoreCase = true) -> "wss://" + normalized.substring(6)
            normalized.startsWith("http://", ignoreCase = true) -> "ws://" + normalized.substring(7)
            normalized.startsWith("https://", ignoreCase = true) -> "wss://" + normalized.substring(8)
            else -> "ws://$normalized"
        }

        // Build HTTP origin from the WS URL (gateway checks Origin header)
        val httpOrigin = wsUrl.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://")

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", httpOrigin)
            .build()

        Log.d(TAG, "doConnect() opening WebSocket")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "onOpen() WebSocket connected")
                _connectionState.value = ConnectionState.HANDSHAKING
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "onMessage() received ${text.length} chars")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosing() code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosed() code=$code reason=$reason")
                handleDisconnect("closed code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "onFailure() ${t.message}", t)
                handleDisconnect("failure: ${t.message}")
            }
        })
    }

    private fun handleMessage(text: String) {
        val frame = try {
            json.decodeFromString<InboundFrame>(text)
        } catch (e: Exception) {
            Log.e(TAG, "Frame decode error: ${e.message}")
            scope.launch { _chatResponse.emit(ChatResponseEvent.Final(activeRunIds.firstOrNull() ?: "", "Parse Frame Error: ${e.message}")) }
            return
        }

        when (frame.type) {
            "event" -> handleEvent(frame)
            "res" -> handleResponse(frame)
        }
    }

    private fun handleEvent(frame: InboundFrame) {
        when (frame.event) {
            Events.CONNECT_CHALLENGE -> handleChallenge()
            Events.CHAT -> handleChatEvent(frame)
        }
    }

    private fun handleChallenge() {
        Log.d(TAG, "handleChallenge() sending connect request")
        val connectId = UUID.randomUUID().toString()
        val connectParams = json.encodeToJsonElement(
            ConnectParams.serializer(),
            ConnectParams(
                client = ClientInfo(),
                auth = if (authToken.isNotBlank()) AuthInfo(token = authToken) else null,
            )
        ).jsonObject

        val request = RequestFrame(id = connectId, method = Methods.CONNECT, params = connectParams)
        val requestJson = json.encodeToString(RequestFrame.serializer(), request)

        // Track the connect request to handle the hello-ok response
        val deferred = CompletableDeferred<InboundFrame>()
        pendingRpc[connectId] = deferred
        webSocket?.send(requestJson)

        scope.launch {
            try {
                val response = withTimeout(15_000) { deferred.await() }
                if (response.ok == true) {
                    Log.d(TAG, "handleChallenge() hello-ok received, CONNECTED")
                    connectInFlight = false
                    _connectionState.value = ConnectionState.CONNECTED
                    // Re-subscribe to tracking keys upon connection
                    subscribedSessionKeys.forEach { sendSubscription(it) }
                    patchSessionModel()
                } else {
                    Log.e(TAG, "handleChallenge() connect rejected: ${response.error?.message}")
                    failConnection("connect rejected: ${response.error?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleChallenge() timeout/error: ${e.message}")
                failConnection("handshake timeout/error: ${e.message}")
            }
        }
    }

    private fun handleChatEvent(frame: InboundFrame) {
        val payload = frame.payload ?: return
        val chatPayload = try {
            json.decodeFromJsonElement(ChatEventPayload.serializer(), payload)
        } catch (e: Exception) {
            Log.e(TAG, "ChatPayload decode error: ${e.message}")
            scope.launch { _chatResponse.emit(ChatResponseEvent.Final(activeRunIds.firstOrNull() ?: "", "Parse Chat Error: ${e.message}")) }
            return
        }

        val runId = chatPayload.runId ?: return
        val sessionKey = chatPayload.sessionKey

        val isOurRun = runId in activeRunIds
        val isOurSession = sessionKey != null && sessionKey in subscribedSessionKeys

        if (!isOurRun && !isOurSession) {
            Log.d(TAG, "Ignoring chat event: runId=$runId sessionKey=$sessionKey")
            return
        }

        Log.d(TAG, "Chat event: state=${chatPayload.state} runId=$runId sessionKey=$sessionKey")

        when (chatPayload.state) {
            "delta" -> {
                val text = chatPayload.message?.content?.firstOrNull()?.text ?: return
                scope.launch { _chatResponse.emit(ChatResponseEvent.Delta(runId, text)) }
            }
            "final" -> {
                val text = chatPayload.message?.content?.firstOrNull()?.text ?: ""
                scope.launch { _chatResponse.emit(ChatResponseEvent.Final(runId, text)) }
                activeRunIds.remove(runId)
            }
            "error" -> {
                val errMsg = chatPayload.errorMessage ?: "Unknown error"
                Log.e(TAG, "Chat error for runId=$runId: $errMsg")
                scope.launch { _chatResponse.emit(ChatResponseEvent.Error(runId, errMsg)) }
                activeRunIds.remove(runId)
            }
        }
    }

    private fun handleResponse(frame: InboundFrame) {
        val id = frame.id ?: return
        pendingRpc.remove(id)?.complete(frame)
    }

    private suspend fun sendRpc(id: String, method: String, params: JsonObject): InboundFrame {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            throw Exception("Not connected")
        }
        val request = RequestFrame(id = id, method = method, params = params)
        val deferred = CompletableDeferred<InboundFrame>()
        pendingRpc[id] = deferred

        val text = json.encodeToString(RequestFrame.serializer(), request)
        webSocket?.send(text) ?: throw Exception("WebSocket unavailable")

        return withTimeout(30_000) { deferred.await() }
    }

    private fun handleDisconnect(reason: String) {
        Log.d(TAG, "handleDisconnect(): $reason")
        val runsToEmit = activeRunIds.toList()

        connectInFlight = false
        _connectionState.value = ConnectionState.DISCONNECTED
        webSocket = null
        pendingRpc.values.forEach { it.cancel() }
        pendingRpc.clear()
        activeRunIds.clear()

        if (runsToEmit.isNotEmpty()) {
            scope.launch { 
                runsToEmit.forEach { runId ->
                    _chatResponse.emit(ChatResponseEvent.Final(runId, "Error: Disconnected from Gateway"))
                }
            }
        }

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun failConnection(reason: String) {
        Log.w(TAG, "failConnection(): $reason")
        webSocket?.cancel()
        handleDisconnect(reason)
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (!shouldReconnect || gatewayUrl.isBlank() || authToken.isBlank()) return

        reconnectJob?.cancel()
        val generation = connectionGeneration
        reconnectJob = scope.launch {
            val delayMs = if (immediate) {
                0L
            } else {
                min(1000L * (1L shl reconnectAttempt.coerceAtMost(5)), 30_000L)
            }
            if (!immediate) {
                reconnectAttempt++
            }
            if (delayMs > 0L) {
                Log.d(TAG, "scheduleReconnect() waiting ${delayMs}ms before retry")
                delay(delayMs)
            }
            if (shouldReconnect && generation == connectionGeneration && _connectionState.value == ConnectionState.DISCONNECTED) {
                requestInternetTransport()
                doConnect(generation)
            }
        }
    }

    private fun registerNetworkCallbacks() {
        hasInternetNetwork = currentNetworkIsUsable()
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)
        }.onFailure {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}")
        }
        requestInternetTransport()
    }

    private fun requestInternetTransport() {
        if (networkRequestRegistered) return
        runCatching {
            connectivityManager.requestNetwork(networkRequest, bringUpNetworkCallback)
            networkRequestRegistered = true
        }.onFailure {
            Log.w(TAG, "requestNetwork failed: ${it.message}")
        }
    }

    private fun currentNetworkIsUsable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
