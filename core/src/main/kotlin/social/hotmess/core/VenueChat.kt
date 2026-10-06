package social.hotmess.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.UUID

/** One line in a venue's chat room. */
data class VenueMessage(
    val id: String = UUID.randomUUID().toString(),
    val body: String,
    val userId: String,
    val avatarUrl: String? = null,
) {
    fun isOutgoing(currentUserId: String?): Boolean = currentUserId != null && userId.equals(currentUserId, ignoreCase = true)
}

/**
 * The Action Cable protocol behind a venue's chat room: the `RealtimeChannel` subscription for
 * `chat_<venue id>`, chat lines in and out, and the server's own frames. Kept apart from the socket
 * so it can be tested.
 */
object ChatProtocol {
    private const val CHANNEL = "RealtimeChannel"
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Frame {
        /** The server accepted the connection or the subscription. */
        data object Connected : Frame
        data class Message(val message: VenueMessage) : Frame
        data object Disconnected : Frame
    }

    /** Action Cable names a subscription by a JSON *string*, not an object. */
    fun identifier(venueId: String): String =
        JsonObject(mapOf("channel" to JsonPrimitive(CHANNEL), "venue_id" to JsonPrimitive("chat_${venueId.uppercase()}"))).toString()

    fun subscribe(venueId: String): String =
        json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("subscribe", identifier(venueId), null))

    fun message(venueId: String, body: String, userId: String, avatarUrl: String?): String {
        val line = json.encodeToString(OutgoingMessage.serializer(), OutgoingMessage(message = body, userId = userId.uppercase(), avatarUrl = avatarUrl))
        return json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("message", identifier(venueId), line))
    }

    /** Reads one frame from the server, or null for pings and anything unrecognized. */
    fun parse(text: String): Frame? {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
            "welcome", "confirm_subscription" -> Frame.Connected
            "disconnect" -> Frame.Disconnected
            null -> {
                // A frame with no type and a message object is a chat line. Action Cable reuses
                // `message` for its ping counter, so anything else there is ignored.
                val message = frame["message"] as? JsonObject ?: return null
                val payload = runCatching { json.decodeFromJsonElement(IncomingMessage.serializer(), message) }.getOrNull()
                    ?: return null
                Frame.Message(VenueMessage(body = payload.message, userId = payload.userId, avatarUrl = payload.avatarUrl))
            }
            else -> null
        }
    }

    /** The websocket endpoint, derived from the HTTP base URL: `wss://<host>/connection`. */
    fun realtimeUrl(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val scheme = if (uri.scheme == "http") "ws" else "wss"
        val path = uri.path.orEmpty().trimEnd('/') + "/connection"
        return runCatching { URI(scheme, uri.userInfo, uri.host, uri.port, path, null, null).toString() }.getOrNull()
    }

    @Serializable
    private data class OutgoingFrame(val command: String, val identifier: String, val data: String?)

    @Serializable
    private data class OutgoingMessage(
        val type: String = "outgoing",
        val message: String,
        @SerialName("user_id") val userId: String,
        @SerialName("avatar_url") val avatarUrl: String?,
    )

    @Serializable
    private data class IncomingMessage(
        val message: String,
        @SerialName("user_id") val userId: String,
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )
}

/** A live connection to a venue's chat room. */
class VenueChatConnection(
    private val venueId: String,
    private val url: String,
    private val token: String?,
    private val client: OkHttpClient = OkHttpClient(),
) {
    sealed interface Event {
        data object Connected : Event
        data class Received(val message: VenueMessage) : Event
        data class Disconnected(val reason: String?) : Event
    }

    @Volatile private var socket: WebSocket? = null

    /** Connects when collected and closes the socket when the collector goes away. */
    fun events(): Flow<Event> = callbackFlow {
        val request = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "JWT $it") }
        }.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ChatProtocol.subscribe(venueId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val frame = ChatProtocol.parse(text)) {
                    ChatProtocol.Frame.Connected -> trySend(Event.Connected)
                    ChatProtocol.Frame.Disconnected -> trySend(Event.Disconnected(null))
                    is ChatProtocol.Frame.Message -> trySend(Event.Received(frame.message))
                    null -> Unit
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(Event.Disconnected(reason.ifBlank { null }))
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(Event.Disconnected(t.message))
                channel.close()
            }
        }

        socket = client.newWebSocket(request, listener)
        awaitClose {
            socket?.close(1001, null)
            socket = null
        }
    }

    /** Sends a line. The server echoes it back, so the caller doesn't add it locally. */
    fun send(body: String, userId: String, avatarUrl: String?): Boolean =
        socket?.send(ChatProtocol.message(venueId, body, userId, avatarUrl)) ?: false
}
