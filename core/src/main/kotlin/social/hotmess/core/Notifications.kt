package social.hotmess.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A notification sent to you, as `Subscription.myNotifications` delivers it while the app is open: the
 * same one FCM delivers, with the same [notificationId] in both copies' [data] (alongside `kind` and
 * `ping_id`), so the app shows one and drops the other.
 */
data class PushNotification(
    val notificationId: String,
    val title: String? = null,
    val body: String? = null,
    val category: String? = null,
    /** What the app acts on (`kind`, `ping_id`, `notification_id`), as strings, like FCM's data map. */
    val data: Map<String, String> = emptyMap(),
    val sentAt: Instant? = null,
) {
    /** The Ping it's about, routed as a tapped FCM notification is, or null for anything else. */
    val ping: PingPush? get() = PingPush.parse(data)

    companion object {
        /** The key both copies carry the id under. */
        const val NOTIFICATION_ID = "notification_id"
    }
}

/**
 * `myNotifications` on the [GraphQLCable]: one operation, no variables. Nothing arrives on subscribing
 * (the first result, if any, has no data); every notification after is one result.
 */
object NotificationProtocol {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    const val DOCUMENT: String =
        "subscription MyNotifications { myNotifications { notificationId title body category data sentAt } }"

    fun execute(identifier: String): String = GraphQLCable.execute(identifier, DOCUMENT)

    sealed interface Frame {
        /** The channel is confirmed and the operation runs. */
        data object Subscribed : Frame
        data class Notification(val notification: PushNotification) : Frame

        /** Refused: the channel, or the operation (signed out, or a server without it). */
        data object Rejected : Frame
        data object Disconnected : Frame

        /** The server ended the operation (`more: false`); reconnecting starts it over. */
        data object Ended : Frame
    }

    fun parse(text: String): List<Frame> = frames(GraphQLCable.parse(text))

    fun frames(envelope: GraphQLCable.Envelope?): List<Frame> = when (envelope) {
        null -> emptyList()
        GraphQLCable.Envelope.Subscribed -> listOf(Frame.Subscribed)
        GraphQLCable.Envelope.Rejected -> listOf(Frame.Rejected)
        GraphQLCable.Envelope.Disconnected -> listOf(Frame.Disconnected)
        is GraphQLCable.Envelope.Result -> {
            val notification = envelope.event?.let(::notification)
            when {
                notification != null -> listOf(Frame.Notification(notification))
                envelope.data == null && envelope.errors.isNotEmpty() -> return listOf(Frame.Rejected)
                else -> emptyList()
            } + if (envelope.more) emptyList() else listOf(Frame.Ended)
        }
    }

    /** A `PushNotification`, or null without an id (in the field or its data). */
    fun notification(event: JsonObject): PushNotification? {
        val data = dataMap(event["data"])
        val id = event.string("notificationId") ?: data[PushNotification.NOTIFICATION_ID] ?: return null
        return PushNotification(
            notificationId = id,
            title = event.string("title"),
            body = event.string("body"),
            category = event.string("category"),
            // The id is in the data as FCM's copy has it, whichever way it came.
            data = data + (PushNotification.NOTIFICATION_ID to id),
            sentAt = event.string("sentAt")?.let(InstantSerializer::parse),
        )
    }

    /** The JSON scalar as FCM's string map: strings as they are, other values as JSON, nulls dropped. */
    private fun dataMap(element: JsonElement?): Map<String, String> {
        val obj = when (element) {
            is JsonObject -> element
            // A JSON scalar sent as an encoded string.
            is JsonPrimitive -> element.contentOrNull?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            else -> null
        } ?: return emptyMap()
        return obj.mapNotNull { (key, value) ->
            when (value) {
                JsonNull -> null
                is JsonPrimitive -> value.contentOrNull?.let { key to it }
                else -> key to value.toString()
            }
        }.toMap()
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** A live `myNotifications` subscription, on its own socket, while collected. */
class NotificationStreamConnection(
    private val url: String,
    private val token: String?,
    private val audienceId: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    sealed interface Event {
        data object Connected : Event
        data class Received(val notification: PushNotification) : Event
        data object Rejected : Event
        data class Disconnected(val reason: String?) : Event
    }

    fun events(): Flow<Event> = GraphQLCable.connect(
        client,
        url,
        token,
        audienceId,
        execute = NotificationProtocol::execute,
        onFrame = { envelope, emit ->
            var open = true
            for (frame in NotificationProtocol.frames(envelope)) {
                when (frame) {
                    NotificationProtocol.Frame.Subscribed -> emit(Event.Connected)
                    is NotificationProtocol.Frame.Notification -> emit(Event.Received(frame.notification))
                    NotificationProtocol.Frame.Rejected -> {
                        emit(Event.Rejected)
                        open = false
                    }
                    NotificationProtocol.Frame.Disconnected -> emit(Event.Disconnected(null))
                    NotificationProtocol.Frame.Ended -> {
                        emit(Event.Disconnected(null))
                        open = false
                    }
                }
            }
            open
        },
        onClosed = { reason, emit -> emit(Event.Disconnected(reason)) },
    )
}

/**
 * The notification ids shown lately, in the app or as a system notification, so each notification is
 * shown once though it arrives twice (over the cable and from FCM). Bounded to the last [capacity] ids
 * and safe to use from any thread: the cable's collector and FCM's service thread share it.
 */
class RecentNotifications(private val capacity: Int = 64) {
    private val lock = ReentrantLock()
    private val claimed = lock.newCondition()
    private val ids = LinkedHashSet<String>()

    /** Records [id]; true when it's new, so this copy is the one to show. */
    fun claim(id: String): Boolean = lock.withLock {
        if (!ids.add(id)) return false
        while (ids.size > capacity) ids.remove(ids.first())
        claimed.signalAll()
        true
    }

    operator fun contains(id: String): Boolean = lock.withLock { id in ids }

    /** Waits up to [timeoutMs] for [id] to be claimed; whether it was. Blocks, so never on the main thread. */
    fun awaitClaimed(id: String, timeoutMs: Long): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (id !in ids) {
            if (remaining <= 0) return false
            remaining = claimed.awaitNanos(remaining)
        }
        true
    }

    /**
     * Whether FCM's copy with [id] should still be posted as a system notification. In the background,
     * or without an id, it always is, as before. In front, it isn't once the in-app copy was shown; while
     * the cable is [streamLive], the in-app copy gets up to [windowMs] to arrive first. Posting claims the
     * id, so a late in-app copy is dropped instead.
     */
    fun shouldPostSystemCopy(id: String?, foreground: Boolean, streamLive: Boolean, windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        if (id.isNullOrBlank()) return true
        if (!foreground) {
            claim(id)
            return true
        }
        if (streamLive) awaitClaimed(id, windowMs)
        return claim(id)
    }

    companion object {
        /** How long FCM's copy waits, in front, for the cable's. */
        const val DEFAULT_WINDOW_MS: Long = 2_000L
    }
}
