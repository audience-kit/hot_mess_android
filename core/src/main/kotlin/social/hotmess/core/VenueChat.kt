package social.hotmess.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * A chat room: a venue's, for people at the venue, or a locale's, for people out in the locale who
 * aren't at a venue. Both speak the same frames, so one connection and one screen serve either.
 */
data class ChatRoom(val kind: Kind, val id: String) {
    enum class Kind(val channel: String, val key: String) {
        VENUE("RealtimeChannel", "venue_id"),
        LOCALE("LocaleChannel", "locale_id"),
    }

    companion object {
        fun venue(id: String) = ChatRoom(Kind.VENUE, id)
        fun locale(id: String) = ChatRoom(Kind.LOCALE, id)
    }
}

/**
 * The Action Cable protocol behind a chat room (see [ChatRoom]): the `RealtimeChannel` subscription for a
 * venue, chat lines in and out, and the server's own frames. Kept apart from the socket so it can be
 * tested.
 *
 * A venue's room is only open to people at the venue, and a locale's to people out in it away from its
 * venues: the server rejects the subscription otherwise, and sends `{"type":"left"}` when someone's
 * presence lapses. It adds the sender to each line itself.
 * Admins can join from anywhere: everyone is told `{"type":"range","out_of_range":…}` on joining,
 * and admins again whenever that changes.
 */
object ChatProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Frame {
        /** The server accepted the connection or the subscription. */
        data object Connected : Frame
        data class Message(val message: VenueMessage) : Frame
        data object Disconnected : Frame

        /** The server won't let this person in: they haven't reported a position in the room's place lately. */
        data object Rejected : Frame

        /** They were in the room, but they've left its place. */
        data object Left : Frame

        /** Whether they're outside the room's place. Only admins get in from outside. */
        data class Range(val outOfRange: Boolean) : Frame
    }

    /** Action Cable names a subscription by a JSON *string*, not an object. */
    fun identifier(room: ChatRoom): String =
        JsonObject(mapOf("channel" to JsonPrimitive(room.kind.channel), room.kind.key to JsonPrimitive(room.id.lowercase()))).toString()

    fun subscribe(room: ChatRoom): String =
        json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("subscribe", identifier(room), null))

    fun message(room: ChatRoom, body: String): String {
        val line = json.encodeToString(OutgoingMessage.serializer(), OutgoingMessage(message = body))
        return json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("message", identifier(room), line))
    }

    /** A venue's room, by the venue's id. */
    fun subscribe(venueId: String): String = subscribe(ChatRoom.venue(venueId))

    fun message(venueId: String, body: String): String = message(ChatRoom.venue(venueId), body)

    /** Reads one frame from the server, or null for pings and anything unrecognized. */
    fun parse(text: String): Frame? {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
            "welcome", "confirm_subscription" -> Frame.Connected
            "disconnect" -> Frame.Disconnected
            "reject_subscription" -> Frame.Rejected
            null -> {
                // A frame with no type and a message object is a chat line. Action Cable reuses
                // `message` for its ping counter, so anything else there is ignored.
                val message = frame["message"] as? JsonObject ?: return null
                when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
                    "left" -> return Frame.Left
                    "range" -> return Frame.Range((message["out_of_range"] as? JsonPrimitive)?.booleanOrNull ?: false)
                }
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
    private data class OutgoingMessage(val message: String)

    @Serializable
    private data class IncomingMessage(
        val message: String,
        @SerialName("user_id") val userId: String,
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )
}

/** A live connection to a chat room, a venue's or a locale's. */
class VenueChatConnection(
    private val room: ChatRoom,
    private val url: String,
    private val token: String?,
    private val client: OkHttpClient = OkHttpClient(),
) {
    sealed interface Event {
        data object Connected : Event
        data class Received(val message: VenueMessage) : Event
        data class Disconnected(val reason: String?) : Event
        data object Rejected : Event
        data object Left : Event
        data class Range(val outOfRange: Boolean) : Event
    }

    @Volatile private var socket: WebSocket? = null

    /** Connects when collected and closes the socket when the collector goes away. */
    fun events(): Flow<Event> = callbackFlow {
        val request = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "JWT $it") }
        }.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ChatProtocol.subscribe(room))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val frame = ChatProtocol.parse(text)) {
                    ChatProtocol.Frame.Connected -> trySend(Event.Connected)
                    ChatProtocol.Frame.Disconnected -> trySend(Event.Disconnected(null))
                    ChatProtocol.Frame.Rejected -> trySend(Event.Rejected)
                    ChatProtocol.Frame.Left -> trySend(Event.Left)
                    is ChatProtocol.Frame.Message -> trySend(Event.Received(frame.message))
                    is ChatProtocol.Frame.Range -> trySend(Event.Range(frame.outOfRange))
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
    fun send(body: String): Boolean = socket?.send(ChatProtocol.message(room, body)) ?: false
}
