package social.hotmess.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * One line in a venue's or locale's chat room, as its GraphQL `ChatMessage` describes it. Only [body]
 * and [userId] are needed; everything else is optional and defaults to a plain text line.
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
    /** Posted by talent as their listing (role HOST): [name] and [avatarUrl] are this person's. */
    val personId: String? = null,
) {
    fun isOutgoing(currentUserId: String?): Boolean = currentUserId != null && userId.equals(currentUserId, ignoreCase = true)

    /** Posted as the venue: [name] and [avatarUrl] are the venue's, never the sender's. */
    val isPostedAsVenue: Boolean get() = role == ChatRole.VENUE

    /**
     * A listing, not the human who typed it: the venue, or talent posting as themselves from Backstage. It
     * has no presence, never takes a friend's name, and groups apart from the sender's own lines.
     */
    val isFromListing: Boolean get() = isPostedAsVenue || personId != null

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
 * aren't at a venue. Both deliver the same events, so one connection and one screen serve either.
 *
 * [field] is the room's GraphQL subscription field and [argument] the id it takes, which is also how
 * `sendMyChatMessage` names the room.
 */
data class ChatRoom(val kind: Kind, val id: String) {
    enum class Kind(val field: String, val argument: String) {
        VENUE("venueChat", "venueId"),
        LOCALE("localeChat", "localeId"),
    }

    companion object {
        fun venue(id: String) = ChatRoom(Kind.VENUE, id)
        fun locale(id: String) = ChatRoom(Kind.LOCALE, id)
    }
}

/**
 * A chat room (see [ChatRoom]) over GraphQL subscriptions on Action Cable: the `GraphQLChannel`
 * subscription, the `venueChat`/`localeChat` operation run on it, and the `ChatRoomEvent`s it delivers.
 * Kept apart from the socket so it can be tested.
 *
 * Each operation is its own channel subscription, named by the audience and an id of the client's
 * choosing. Once Action Cable confirms it, the client performs `execute` with the GraphQL document;
 * every result then arrives as `{"result":{"data":…,"errors":…},"more":<bool>}`, the first being
 * `ChatJoined` (who is here, the recent lines, the pinned announcement and whether an admin is out of
 * range). `more: false` ends the operation. An operation the server refuses (not at the venue, banned)
 * comes back with errors and no data.
 *
 * Saying something is the `sendMyChatMessage` mutation (see [SEND_MESSAGE]); the room delivers the line
 * to everyone following it, the sender included.
 */
object ChatProtocol {
    // Lenient, so an id sent as a number still reads as a string rather than dropping the line.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** A chat line's fields, as [message] reads them. */
    const val MESSAGE_FIELDS: String =
        "id message body name avatarUrl sentAt userId role kind title photoUrl event { id name startAt } endsAt pinned " +
            "postedAsVenue postedAsPerson person { id }"

    /**
     * The room's subscription document, for `$id`. ChatJoined's `pinned` is aliased: it's a line there and
     * a flag on ChatPinChanged, and fields of one name must have one shape.
     */
    fun document(room: ChatRoom): String = """
        subscription ChatRoom(${'$'}id: ID!) {
          ${room.kind.field}(${room.kind.argument}: ${'$'}id) {
            __typename
            ... on ChatJoined {
              outOfRange
              roster { people { userId name avatarUrl friend } friends { userId name } }
              history { senderPresence message { $MESSAGE_FIELDS } }
              pinnedMessage { senderPresence message { $MESSAGE_FIELDS } }
            }
            ... on ChatMessagePosted { senderPresence message { $MESSAGE_FIELDS } }
            ... on ChatMessageUpdated { message { $MESSAGE_FIELDS } }
            ... on ChatMessageRemoved { messageId }
            ... on ChatPinChanged { messageId pinned announcement { $MESSAGE_FIELDS } }
            ... on ChatRoomCleared { clearedAt }
            ... on ChatPresenceChanged { userId presence name avatarUrl }
            ... on ChatRangeChanged { outOfRange }
            ... on ChatLeft { reason }
          }
        }
    """.trimIndent()

    /** Says something in a room as yourself; `$input` takes `venueId` or `localeId`, and `message`. */
    const val SEND_MESSAGE: String =
        "mutation SendMyChatMessage(\$input: ChatMessageSendOwnInput!) { sendMyChatMessage(input: \$input) { message { $MESSAGE_FIELDS } } }"

    /** The mutation's input for saying [body] in [room]. */
    fun sendInput(room: ChatRoom, body: String): JsonObject = JsonObject(
        mapOf(room.kind.argument to JsonPrimitive(room.id.lowercase()), "message" to JsonPrimitive(body)),
    )

    sealed interface Frame {
        /** Action Cable confirmed the channel subscription: time to run the operation. */
        data object Subscribed : Frame

        /** In the room: the first event (ChatJoined) arrived. */
        data object Connected : Frame
        data class Message(val message: VenueMessage) : Frame
        data object Disconnected : Frame

        /**
         * The server won't let this person in: the channel was rejected (another audience, banned) or the
         * operation came back with errors and no data (not at the room's place lately).
         */
        data object Rejected : Frame

        /** They were in the room, but they've left its place, or were banned or removed. */
        data object Left : Frame

        /** The server ended the operation (`more: false`); reconnecting starts it over. */
        data object Ended : Frame

        /** Whether they're outside the room's place. Only admins get in from outside. */
        data class Range(val outOfRange: Boolean) : Frame

        /** Everyone in the room now, by lowercase user id, with who they are, and all the joiner's friends. */
        data class Roster(
            val online: Set<String>,
            val people: List<RoomPerson> = emptyList(),
            val friends: List<Friend> = emptyList(),
        ) : Frame

        /**
         * Someone (by lowercase user id) came into the room or left it; a newcomer with their [name] and
         * [avatarUrl]. Someone who left but whom a notification reaches is [reachable].
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

        /** An admin removed a line (by [id]); nobody sees it again. */
        data class Removed(val id: String) : Frame

        /** An admin cleared the room: every line so far is gone. */
        data object Cleared : Frame
    }

    /**
     * Action Cable names a subscription by a JSON *string*, not an object: the GraphQL channel, the
     * audience, and [channelId], which keeps one operation's identifier apart from another's.
     */
    fun identifier(audienceId: String, channelId: String): String = JsonObject(
        mapOf(
            "channel" to JsonPrimitive("GraphQLChannel"),
            "audience_id" to JsonPrimitive(audienceId.lowercase()),
            "channelId" to JsonPrimitive(channelId),
        ),
    ).toString()

    /** A fresh id for [identifier]. */
    fun newChannelId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun subscribe(identifier: String): String =
        json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("subscribe", identifier, null))

    /** Runs the room's subscription on the channel [identifier] names. */
    fun execute(identifier: String, room: ChatRoom): String {
        val data = JsonObject(
            mapOf(
                "action" to JsonPrimitive("execute"),
                "query" to JsonPrimitive(document(room)),
                "variables" to JsonObject(mapOf("id" to JsonPrimitive(room.id.lowercase()))),
            ),
        ).toString()
        return json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("message", identifier, data))
    }

    /**
     * Reads one frame from the server: what it says, in order, or nothing for pings and anything
     * unrecognized. ChatJoined is several things at once, and a result with `more: false` also ends.
     */
    fun parse(text: String): List<Frame> {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        return when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
            "confirm_subscription" -> listOf(Frame.Subscribed)
            "disconnect" -> listOf(Frame.Disconnected)
            "reject_subscription" -> listOf(Frame.Rejected)
            // Action Cable reuses `message` for its ping counter, so only an object there is a result.
            null -> (frame["message"] as? JsonObject)?.let(::result).orEmpty()
            else -> emptyList()
        }
    }

    /** `{"result":{"data":…,"errors":…},"more":…}`. */
    private fun result(message: JsonObject): List<Frame> {
        val result = message["result"] as? JsonObject
        val more = (message["more"] as? JsonPrimitive)?.booleanOrNull ?: true
        val data = result?.get("data") as? JsonObject
        val errors = (result?.get("errors") as? JsonArray).orEmpty()
        val event = data?.values?.firstOrNull() as? JsonObject
        val frames = when {
            event != null -> event(event)
            // Refused: not in the room, banned, or the room isn't there.
            errors.isNotEmpty() -> return listOf(Frame.Rejected)
            else -> emptyList()
        }
        return if (more) frames else frames + Frame.Ended
    }

    /** One ChatRoomEvent, by its `__typename`. An unknown event is skipped. */
    private fun event(event: JsonObject): List<Frame> = when (event.string("__typename")) {
        "ChatJoined" -> joined(event)
        "ChatMessagePosted" -> listOfNotNull(posted(event)?.let(Frame::Message))
        "ChatMessageUpdated" -> listOfNotNull(message(event["message"])?.let(Frame::Message))
        "ChatMessageRemoved" -> listOfNotNull(event.string("messageId")?.let(Frame::Removed))
        "ChatPinChanged" -> listOfNotNull(pin(event))
        "ChatRoomCleared" -> listOf(Frame.Cleared)
        "ChatPresenceChanged" -> listOfNotNull(presence(event))
        "ChatRangeChanged" -> listOf(Frame.Range(event.boolean("outOfRange")))
        "ChatLeft" -> listOf(Frame.Left)
        else -> emptyList()
    }

    private fun joined(event: JsonObject): List<Frame> {
        val roster = event["roster"] as? JsonObject
        // Anything malformed in `people` or `friends` is skipped, never the whole roster.
        val people = (roster?.get("people") as? JsonArray).orEmpty().mapNotNull { entry ->
            val person = entry as? JsonObject ?: return@mapNotNull null
            val id = person.string("userId") ?: return@mapNotNull null
            RoomPerson(
                userId = id.lowercase(),
                name = person.string("name"),
                avatarUrl = person.string("avatarUrl"),
                friend = person.boolean("friend"),
            )
        }
        val friends = (roster?.get("friends") as? JsonArray).orEmpty().mapNotNull { entry ->
            val friend = entry as? JsonObject ?: return@mapNotNull null
            val id = friend.string("userId") ?: return@mapNotNull null
            Friend(id.lowercase(), friend.string("name").orEmpty())
        }
        // A malformed line drops only itself.
        val history = (event["history"] as? JsonArray).orEmpty().mapNotNull { posted(it as? JsonObject) }
        return listOf(
            Frame.Connected,
            Frame.Range(event.boolean("outOfRange")),
            Frame.Roster(people.map { it.userId }.toSet(), people, friends),
            Frame.History(history, posted((event["pinnedMessage"] ?: event["pinned"]) as? JsonObject)),
        )
    }

    /** A ChatMessagePosted: its line, reachable when the sender isn't here but a notification reaches them. */
    private fun posted(event: JsonObject?): VenueMessage? {
        val line = message(event?.get("message")) ?: return null
        return line.copy(reachable = event?.string("senderPresence").equals("PUSH", ignoreCase = true))
    }

    private fun pin(event: JsonObject): Frame? {
        val id = event.string("messageId") ?: return null
        return Frame.Pin(id, if (event.boolean("pinned")) message(event["announcement"]) else null)
    }

    private fun presence(event: JsonObject): Frame? {
        val userId = event.string("userId") ?: return null
        val presence = event.string("presence") ?: return null
        val online = presence.equals("ONLINE", ignoreCase = true)
        return Frame.PresenceChanged(
            userId.lowercase(),
            online,
            name = if (online) event.string("name") else null,
            avatarUrl = if (online) event.string("avatarUrl") else null,
            reachable = presence.equals("PUSH", ignoreCase = true),
        )
    }

    /** A ChatMessage, as [MESSAGE_FIELDS] asks for it, or null when it's malformed. */
    fun message(element: JsonElement?): VenueMessage? {
        val message = element as? JsonObject ?: return null
        return runCatching { json.decodeFromJsonElement(IncomingMessage.serializer(), message) }.getOrNull()?.toMessage()
    }

    /** A string field, or null when it's missing, not a string-like value, or blank. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull.blankToNull()

    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    /** The websocket endpoint, derived from the HTTP base URL: `wss://<host>/connection`. */
    fun realtimeUrl(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val scheme = if (uri.scheme == "http") "ws" else "wss"
        val path = uri.path.orEmpty().trimEnd('/') + "/connection"
        return runCatching { URI(scheme, uri.userInfo, uri.host, uri.port, path, null, null).toString() }.getOrNull()
    }

    @Serializable
    private data class OutgoingFrame(val command: String, val identifier: String, val data: String?)

    /** A ChatMessage. `message` (the line in words) and `userId` are required; the rest default to a plain line. */
    @Serializable
    private data class IncomingMessage(
        val message: String,
        val userId: String,
        val avatarUrl: String? = null,
        val id: String? = null,
        val name: String? = null,
        val sentAt: String? = null,
        val role: String? = null,
        val kind: String? = null,
        val title: String? = null,
        val body: String? = null,
        val photoUrl: String? = null,
        val event: IncomingEvent? = null,
        val endsAt: String? = null,
        val pinned: Boolean? = null,
        val postedAsPerson: Boolean? = null,
        val person: IncomingPerson? = null,
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
                personId = if (postedAsPerson == true) person?.id.blankToNull()?.lowercase() else null,
            )
        }
    }

    @Serializable
    private data class IncomingEvent(val id: String? = null, val name: String? = null, val startAt: String? = null)

    @Serializable
    private data class IncomingPerson(val id: String? = null)

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
}

/**
 * A live connection to a chat room, a venue's or a locale's, in [audienceId]: one `GraphQLChannel`
 * subscription running the room's subscription. Saying something is [HotMessApi.sendChatMessage].
 */
class VenueChatConnection(
    private val room: ChatRoom,
    private val url: String,
    private val token: String?,
    private val audienceId: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    sealed interface Event {
        data object Connected : Event
        data class Received(val message: VenueMessage) : Event
        data class Disconnected(val reason: String?) : Event
        data object Rejected : Event
        data object Left : Event
        data class Range(val outOfRange: Boolean) : Event

        /** Everyone in the room now, by lowercase user id, and who they are and the viewer's friends. */
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
        data class Removed(val id: String) : Event
        data object Cleared : Event
    }

    /** Connects when collected and closes the socket when the collector goes away. */
    fun events(): Flow<Event> = callbackFlow {
        val identifier = ChatProtocol.identifier(audienceId, ChatProtocol.newChannelId())
        val request = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "JWT $it") }
        }.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(ChatProtocol.subscribe(identifier))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (frame in ChatProtocol.parse(text)) {
                    when (frame) {
                        ChatProtocol.Frame.Subscribed -> webSocket.send(ChatProtocol.execute(identifier, room))
                        ChatProtocol.Frame.Connected -> trySend(Event.Connected)
                        ChatProtocol.Frame.Disconnected -> trySend(Event.Disconnected(null))
                        ChatProtocol.Frame.Rejected -> trySend(Event.Rejected)
                        ChatProtocol.Frame.Left -> trySend(Event.Left)
                        ChatProtocol.Frame.Ended -> {
                            // The server ended the room's operation; the caller reconnects to start it over.
                            trySend(Event.Disconnected(null))
                            webSocket.close(1000, null)
                            channel.close()
                        }
                        is ChatProtocol.Frame.Message -> trySend(Event.Received(frame.message))
                        is ChatProtocol.Frame.Range -> trySend(Event.Range(frame.outOfRange))
                        is ChatProtocol.Frame.Roster -> trySend(Event.Roster(frame.online, frame.people, frame.friends))
                        is ChatProtocol.Frame.PresenceChanged ->
                            trySend(Event.PresenceChanged(frame.userId, frame.online, frame.name, frame.avatarUrl, frame.reachable))
                        is ChatProtocol.Frame.History -> trySend(Event.History(frame.messages, frame.pinned))
                        is ChatProtocol.Frame.Pin -> trySend(Event.Pin(frame.id, frame.announcement))
                        is ChatProtocol.Frame.Removed -> trySend(Event.Removed(frame.id))
                        ChatProtocol.Frame.Cleared -> trySend(Event.Cleared)
                    }
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

        val socket = client.newWebSocket(request, listener)
        awaitClose { socket.close(1001, null) }
    }
}
