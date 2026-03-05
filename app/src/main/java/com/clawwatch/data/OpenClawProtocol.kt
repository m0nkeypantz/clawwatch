package com.clawwatch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * OpenClaw Gateway wire protocol (protocol version 3).
 *
 * Flow:
 *   1. Server sends event "connect.challenge" with nonce
 *   2. Client sends req method="connect" with ConnectParams
 *   3. Server responds with hello-ok payload
 *   4. Client can send RPCs (chat.send, etc.)
 *   5. Server streams events (chat delta/final, agent, etc.)
 */

// --- Wire frames ---

@Serializable
data class RequestFrame(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class InboundFrame(
    val type: String,
    val id: String? = null,
    val ok: Boolean? = null,
    val payload: JsonElement? = null,
    val error: FrameError? = null,
    val event: String? = null,
    val seq: Long? = null,
)

@Serializable
data class FrameError(
    val code: String,
    val message: String,
)

// --- Connect params (sent as req method="connect") ---

@Serializable
data class ConnectParams(
    val minProtocol: Int = PROTOCOL_VERSION,
    val maxProtocol: Int = PROTOCOL_VERSION,
    val client: ClientInfo,
    val auth: AuthInfo? = null,
    val scopes: List<String> = listOf("operator.admin", "operator.read", "operator.write"),
)

@Serializable
data class ClientInfo(
    val id: String = "openclaw-android",
    val displayName: String = "ClawWatch",
    val version: String = "0.1.0",
    val platform: String = "wearos",
    val mode: String = "webchat",
)

@Serializable
data class AuthInfo(
    val token: String? = null,
)

// --- Chat params ---

@Serializable
data class ChatSendParams(
    val sessionKey: String,
    val message: String,
    val idempotencyKey: String,
)

// --- Chat event payload ---

@Serializable
data class ChatEventPayload(
    val runId: String? = null,
    val sessionKey: String? = null,
    val state: String? = null,
    val message: ChatMessage? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String? = null,
    val content: List<ChatContentBlock>? = null,
)

@Serializable
data class ChatContentBlock(
    val type: String? = null,
    val text: String? = null,
)

// --- Constants ---

const val PROTOCOL_VERSION = 3

object Methods {
    const val CONNECT = "connect"
    const val CHAT_SEND = "chat.send"
}

object Events {
    const val CONNECT_CHALLENGE = "connect.challenge"
    const val CHAT = "chat"
    const val CHAT_DELTA = "chat.delta"
}

sealed class ChatResponseEvent {
    data class Delta(val runId: String, val fullText: String) : ChatResponseEvent()
    data class Final(val runId: String, val fullText: String) : ChatResponseEvent()
    data class Error(val runId: String, val errorMessage: String) : ChatResponseEvent()
}
