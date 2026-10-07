package social.hotmess.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.time.Instant
import java.util.UUID

/**
 * One line in a venue's or locale's chat room, as its frame describes it. Older servers send only
 * [body], [userId] and [avatarUrl]; everything else is optional and defaults to a plain text line.
 *
 * [body] is the line in words, which for a rich message is its summary ("Shared an event: Sunset
 * Social"); [caption] is what the sender wrote: an announcement's or special's body, or an event's or
 * photo's caption.
 */
data class VenueMessage(
    val id: String = UUID.randomUUID().toString(),
    val body: String,
    val userId: String,
    /** The sender's photo, or the venue's for a post as the venue. */
    val avatarUrl: String? = null,
    /** The name the room shows: "First L." for anyone but the viewer's friends, or the venue's name. */
    val name: String? = null,
    val sentAt: Instant? = null,
    /** Who the sender is in the room, worked out by the server; null for everyone else. */
    val role: ChatRole? = null,
    val kind: ChatKind = ChatKind.TEXT,
    /** An announcement's or special's headline. */
    val title: String? = null,
    val caption: String? = null,
    /** A photo message's photo, or an announcement's. */
    val photoUrl: String? = null,
    /** The event an event message shares. */
    val event: SharedEvent? = null,
    /** When a special stops showing. */
    val endsAt: Instant? = null,
    /** The announcement pinned under the room's title. */
    val pinned: Boolean = false,
    /**
     * The sender isn't in the room but a notification reaches them (a yellow dot). The server only
     * says so about the viewer's friends, and to admins.
     */
    val reachable: Boolean = false,
) {
    fun isOutgoing(currentUserId: String?): Boolean = currentUserId != null && userId.equals(currentUserId, ignoreCase = true)

    /** Posted as the venue: [name] and [avatarUrl] are the venue's, never the sender's. */
    val isPostedAsVenue: Boolean get() = role == ChatRole.VENUE

    /** Whether the room still shows it: a special stops at its end time. */
    fun isShowing(at: Instant = Instant.now()): Boolean = !(kind == ChatKind.SPECIAL && endsAt != null && !endsAt.isAfter(at))
}

/** Who someone is in a chat room, set by the server. Shown as a tag after their name. */
enum class ChatRole(val label: String) {
    /** Posting as the venue, with its name and photo. */
    VENUE("Venue"),

    /** A host or performer on tonight's event at the venue. */
    HOST("Host"),

    /** One of the audience's admins. */
    STAFF("Staff"),
    ;

    companion object {
        /** Reads the socket's `venue` and GraphQL's `VENUE` alike; anything else is no role. */
        fun parse(raw: String?): ChatRole? = raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
    }
}

/** What a chat message is. Only people with a role send anything but [TEXT]. */
enum class ChatKind {
    TEXT,
    ANNOUNCEMENT,
    EVENT,
    PHOTO,
    SPECIAL,
    ;

    val isRich: Boolean get() = this != TEXT

    companion object {
        /** Any case; an unknown kind reads as [TEXT], so it shows as its summary. */
        fun parse(raw: String?): ChatKind = raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } } ?: TEXT
    }
}

/** The event an event message shares, as much as its frame carries. */
data class SharedEvent(val id: String, val name: String? = null, val startAt: Instant? = null)

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
 *
 * Presence: on joining, `{"type":"roster","online":[<user id>, …]}` lists everyone in the room now, the
 * joiner included; then `{"type":"presence","user_id":…,"presence":"online"|"offline"}` as people come
 * and go. Newer servers add to the roster `people` (everyone in the room, with `name`, `avatar_url` and
 * whether they're the joiner's `friend`) and `friends` (all the joiner's friends, by full name), and to
 * an online presence frame the newcomer's `name` and `avatar_url`. Every one of those is optional.
 *
 * Newer servers also send, on joining, `{"type":"history","messages":[<line>, …],"pinned":<line>|null}`
 * (the room's recent lines, oldest first, and the announcement pinned under its title however old);
 * `{"type":"pin","id":…,"pinned":true,"announcement":<line>}` or `{"type":"pin","id":…,"pinned":false}`
 * as announcements are pinned and unpinned; and `"presence":"push"`, on a presence frame or a line, for a
 * friend outside the room whom a notification reaches.
 */
object ChatProtocol {
    // Lenient, so an id sent as a number still reads as a string rather than dropping the line.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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

        /**
         * Everyone in the room now, by lowercase user id, sent on joining; with [people] (who they are)
         * and [friends] (all the joiner's friends, full names) from newer servers.
         */
        data class Roster(
            val online: Set<String>,
            val people: List<RoomPerson> = emptyList(),
            val friends: List<Friend> = emptyList(),
        ) : Frame

        /**
         * Someone (by lowercase user id) came into the room or left it; a newcomer with their [name] and
         * [avatarUrl] when sent. Someone who left but whom a notification reaches is [reachable].
         */
        data class PresenceChanged(
            val userId: String,
            val online: Boolean,
            val name: String? = null,
            val avatarUrl: String? = null,
            val reachable: Boolean = false,
        ) : Frame

        /** The room's recent lines, oldest first, and its pinned announcement, sent on joining. */
        data class History(val messages: List<VenueMessage>, val pinned: VenueMessage? = null) : Frame

        /** An announcement (by [id]) was pinned, with the [announcement], or unpinned (null). */
        data class Pin(val id: String, val announcement: VenueMessage?) : Frame
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
                    "roster" -> return roster(message)
                    "presence" -> return presence(message)
                    "history" -> return history(message)
                    "pin" -> return pin(message)
                }
                val payload = runCatching { json.decodeFromJsonElement(IncomingMessage.serializer(), message) }.getOrNull()
                    ?: return null
                Frame.Message(payload.toMessage())
            }
            else -> null
        }
    }

    private fun roster(message: JsonObject): Frame? {
        val online = message["online"] as? JsonArray ?: return null
        // Anything malformed in `people` or `friends` is skipped, never the whole roster.
        val people = (message["people"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val person = entry as? JsonObject ?: return@mapNotNull null
            val id = person.string("user_id") ?: return@mapNotNull null
            RoomPerson(
                userId = id.lowercase(),
                name = person.string("name"),
                avatarUrl = person.string("avatar_url"),
                friend = (person["friend"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        val friends = (message["friends"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val friend = entry as? JsonObject ?: return@mapNotNull null
            val id = friend.string("user_id") ?: return@mapNotNull null
            Friend(id.lowercase(), friend.string("name").orEmpty())
        }
        return Frame.Roster(online.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.toSet(), people, friends)
    }

    private fun line(element: kotlinx.serialization.json.JsonElement?): VenueMessage? {
        val message = element as? JsonObject ?: return null
        return runCatching { json.decodeFromJsonElement(IncomingMessage.serializer(), message) }.getOrNull()?.toMessage()
    }

    /** A malformed line drops only itself. */
    private fun history(message: JsonObject): Frame = Frame.History(
        (message["messages"] as? JsonArray).orEmpty().mapNotNull(::line),
        line(message["pinned"]),
    )

    private fun pin(message: JsonObject): Frame? {
        val id = message.string("id") ?: return null
        val pinned = (message["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false
        return Frame.Pin(id, if (pinned) line(message["announcement"]) else null)
    }

    private fun presence(message: JsonObject): Frame? {
        val userId = message.string("user_id") ?: return null
        val presence = message.string("presence") ?: return null
        val online = presence.equals("online", ignoreCase = true)
        return Frame.PresenceChanged(
            userId.lowercase(),
            online,
            name = if (online) message.string("name") else null,
            avatarUrl = if (online) message.string("avatar_url") else null,
            reachable = presence.equals("push", ignoreCase = true),
        )
    }

    /** A string field, or null when it's missing, not a string-like value, or blank. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull.blankToNull()

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

    /** A chat line's frame. Only `message` and `user_id` are always there; the rest came later. */
    @Serializable
    private data class IncomingMessage(
        val message: String,
        @SerialName("user_id") val userId: String,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        val id: String? = null,
        val name: String? = null,
        @SerialName("sent_at") val sentAt: String? = null,
        val role: String? = null,
        val kind: String? = null,
        val title: String? = null,
        val body: String? = null,
        @SerialName("photo_url") val photoUrl: String? = null,
        val event: IncomingEvent? = null,
        @SerialName("ends_at") val endsAt: String? = null,
        val pinned: Boolean? = null,
        val presence: String? = null,
    ) {
        fun toMessage(): VenueMessage {
            val base = VenueMessage(body = message, userId = userId, avatarUrl = avatarUrl.blankToNull())
            return base.copy(
                id = id.blankToNull() ?: base.id,
                name = name.blankToNull(),
                sentAt = sentAt?.let(InstantSerializer::parse),
                role = ChatRole.parse(role),
                kind = ChatKind.parse(kind),
                title = title.blankToNull(),
                caption = body.blankToNull(),
                photoUrl = photoUrl.blankToNull(),
                event = event?.id.blankToNull()?.let { SharedEvent(it, event?.name.blankToNull(), event?.startAt?.let(InstantSerializer::parse)) },
                endsAt = endsAt?.let(InstantSerializer::parse),
                pinned = pinned ?: false,
                reachable = presence.equals("push", ignoreCase = true),
            )
        }
    }

    @Serializable
    private data class IncomingEvent(
        val id: String? = null,
        val name: String? = null,
        @SerialName("start_at") val startAt: String? = null,
    )

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
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

        /** Everyone in the room now, by lowercase user id, and who they are and the viewer's friends when sent. */
        data class Roster(
            val online: Set<String>,
            val people: List<RoomPerson> = emptyList(),
            val friends: List<Friend> = emptyList(),
        ) : Event

        data class PresenceChanged(
            val userId: String,
            val online: Boolean,
            val name: String? = null,
            val avatarUrl: String? = null,
            val reachable: Boolean = false,
        ) : Event

        data class History(val messages: List<VenueMessage>, val pinned: VenueMessage?) : Event
        data class Pin(val id: String, val announcement: VenueMessage?) : Event
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
                    is ChatProtocol.Frame.Roster -> trySend(Event.Roster(frame.online, frame.people, frame.friends))
                    is ChatProtocol.Frame.PresenceChanged ->
                        trySend(Event.PresenceChanged(frame.userId, frame.online, frame.name, frame.avatarUrl, frame.reachable))
                    is ChatProtocol.Frame.History -> trySend(Event.History(frame.messages, frame.pinned))
                    is ChatProtocol.Frame.Pin -> trySend(Event.Pin(frame.id, frame.announcement))
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
