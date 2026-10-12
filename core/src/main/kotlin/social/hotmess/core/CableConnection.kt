package social.hotmess.core

import com.audiencekit.AudienceKitSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What one subscription on the [CableConnection] hears. */
sealed interface CableEvent {
    /** Action Cable confirmed the channel and the operation was sent to run (again, after a reconnect). */
    data object Subscribed : CableEvent

    /** One `{"result":{"data":…,"errors":…},"more":…}` message; [GraphQLCable.result] reads it. */
    data class Result(val json: JsonObject) : CableEvent {
        val result: GraphQLCable.Envelope.Result get() = GraphQLCable.result(json)
    }

    /** The channel was refused (another audience, a ban, signed out). The flow ends after it. */
    data object Rejected : CableEvent

    /**
     * The socket went away. With [reconnects] the connection is already reconnecting and will subscribe
     * and run the operation again (a [Subscribed] follows); without, the flow ends after it.
     */
    data class Disconnected(val reason: String?, val reconnects: Boolean) : CableEvent
}

/** One websocket, as [CableConnection] needs it: a seam so the protocol can be tested without a server. */
interface CableSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?)

    /** What the socket tells the connection, from any thread. */
    interface Listener {
        fun onMessage(text: String)
        fun onClosed(reason: String?)
        fun onFailure(reason: String?)
    }
}

/** Opens a [CableSocket] to the realtime URL, with the session's token if there is one. */
fun interface CableSocketFactory {
    fun open(url: String, token: String?, listener: CableSocket.Listener): CableSocket
}

/**
 * [CableSocketFactory] on OkHttp. The handshake is dressed like any API request: `Authorization: Bearer <token>`
 * (the only scheme the API accepts) and the contract version in `X-AudienceKit-API-Version`.
 */
class OkHttpCableSocketFactory(private val client: OkHttpClient) : CableSocketFactory {
    override fun open(url: String, token: String?, listener: CableSocket.Listener): CableSocket {
        val request = Request.Builder().url(url).apply {
            header("X-AudienceKit-API-Version", AudienceKitSDK.API_VERSION)
            token?.let { header("Authorization", "Bearer $it") }
        }.build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(reason.ifBlank { null })

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    listener.onFailure(t.message ?: t.javaClass.simpleName)
            },
        )
        return object : CableSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String?) {
                socket.close(code, reason)
            }
        }
    }
}

/**
 * The app's one Action Cable connection, which every GraphQL subscription shares: each [subscribe] is
 * one `GraphQLChannel` subscription on it, told apart by its identifier.
 *
 * The socket opens with the first subscription and closes after the last ends, so collectors scoped to
 * the screen (or the app being in front and signed in) decide when it's open. The token is read on
 * every (re)open. Action Cable pings every 3 seconds: [SILENCE_MS] without a frame, or a failure, and
 * the socket is dropped, every subscriber told [CableEvent.Disconnected] (reconnecting), and it
 * reconnects with backoff, subscribing and running every live operation again once welcomed.
 *
 * A connection refused as unauthorized tells [onUnauthorized] the token and ends every subscription.
 */
class CableConnection(
    private val url: String,
    private val token: () -> String?,
    private val socketFactory: CableSocketFactory,
    /** Runs the silence watchdog and reconnect delays. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(url: String, token: () -> String?, client: OkHttpClient = OkHttpClient()) :
        this(url, token, OkHttpCableSocketFactory(client))

    /**
     * Told the token of a connection the server refused as unauthorized (`reject_unauthorized_connection`),
     * on the socket's thread. The app points it at its session, which ends the way an HTTP 401 ends it.
     */
    @Volatile var onUnauthorized: ((token: String) -> Unit)? = null

    private inner class Subscription(
        val identifier: String,
        val executeFrame: String,
        val channel: SendChannel<CableEvent>,
    ) {
        /** Confirmed (on this socket); messages before that aren't routed to it. */
        var confirmed = false
    }

    private val lock = Any()
    private val subscriptions = LinkedHashMap<String, Subscription>()
    private var socket: CableSocket? = null
    private var socketToken: String? = null
    private var welcomed = false

    /** Which socket is current; callbacks from an older one are ignored. */
    private var generation = 0
    private var backoffMs = INITIAL_BACKOFF_MS
    private var watchdog: Job? = null
    private var reconnect: Job? = null

    /** Whether a socket is open or opening (or about to reconnect). */
    val isActive: Boolean get() = synchronized(lock) { socket != null || reconnect != null }

    /**
     * Runs [query] with [variables] in [audienceId] while collected. Events end with the operation
     * (`more: false`), a rejection, or the connection refused; cancelling the collector unsubscribes.
     */
    fun subscribe(
        audienceId: String,
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        operationName: String? = null,
    ): Flow<CableEvent> = callbackFlow {
        val identifier = GraphQLCable.identifier(audienceId, GraphQLCable.newChannelId())
        val subscription = Subscription(identifier, GraphQLCable.execute(identifier, query, variables, operationName), channel)
        add(subscription)
        awaitClose { remove(subscription) }
    }.buffer(Channel.UNLIMITED)

    /** Closes the socket and ends every subscription (signing out). */
    fun disconnect() {
        val ended = synchronized(lock) {
            val all = subscriptions.values.toList()
            subscriptions.clear()
            teardown()
            backoffMs = INITIAL_BACKOFF_MS
            all
        }
        ended.forEach { it.channel.close() }
    }

    private fun add(subscription: Subscription) = synchronized(lock) {
        subscriptions[subscription.identifier] = subscription
        when {
            socket == null && reconnect == null -> open()
            welcomed -> socket?.send(GraphQLCable.subscribe(subscription.identifier))
        }
    }

    private fun remove(subscription: Subscription) = synchronized(lock) {
        if (subscriptions.remove(subscription.identifier) === subscription && welcomed) {
            socket?.send(GraphQLCable.unsubscribe(subscription.identifier))
        }
        if (subscriptions.isEmpty()) {
            teardown()
            backoffMs = INITIAL_BACKOFF_MS
        }
    }

    /** Opens a socket; under [lock]. */
    private fun open() {
        generation += 1
        welcomed = false
        val current = generation
        val token = token()
        socketToken = token
        socket = socketFactory.open(url, token, listener(current))
        watch(current)
    }

    /** Closes the socket and stops its timers, without telling anyone; under [lock]. */
    private fun teardown() {
        generation += 1
        welcomed = false
        watchdog?.cancel()
        watchdog = null
        reconnect?.cancel()
        reconnect = null
        socket?.close(1000, null)
        socket = null
        socketToken = null
    }

    /** (Re)starts the silence watchdog for socket [current]; under [lock]. */
    private fun watch(current: Int) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(SILENCE_MS)
            synchronized(lock) { if (current == generation) drop("No word from the server") }
        }
    }

    /** The socket failed, went quiet or was closed: tell everyone, and reconnect after backoff; under [lock]. */
    private fun drop(reason: String?) {
        teardown()
        if (subscriptions.isEmpty()) return
        subscriptions.values.forEach {
            it.confirmed = false
            it.channel.trySend(CableEvent.Disconnected(reason, reconnects = true))
        }
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        reconnect = scope.launch {
            delay(wait)
            synchronized(lock) {
                reconnect = null
                if (socket == null && subscriptions.isNotEmpty()) open()
            }
        }
    }

    /** The server refused or ended the connection for good: everyone's flow ends; under [lock]. */
    private fun end(reason: String?) {
        val all = subscriptions.values.toList()
        subscriptions.clear()
        teardown()
        backoffMs = INITIAL_BACKOFF_MS
        all.forEach {
            it.channel.trySend(CableEvent.Disconnected(reason, reconnects = false))
            it.channel.close()
        }
    }

    private fun listener(current: Int) = object : CableSocket.Listener {
        override fun onMessage(text: String) {
            var unauthorized: String? = null
            synchronized(lock) {
                if (current != generation) return
                watch(current)
                unauthorized = receive(text)
            }
            unauthorized?.let { token -> onUnauthorized?.invoke(token) }
        }

        override fun onClosed(reason: String?) = synchronized(lock) {
            if (current == generation) drop(reason)
        }

        override fun onFailure(reason: String?) = synchronized(lock) {
            if (current == generation) drop(reason)
        }
    }

    /** Handles one frame; the token to report as unauthorized, if the server refused it. Under [lock]. */
    private fun receive(text: String): String? {
        val frame = runCatching { GraphQLCable.json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val identifier = (frame["identifier"] as? JsonPrimitive)?.contentOrNull
        val subscription = identifier?.let { subscriptions[it] }
        when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
            "welcome" -> {
                welcomed = true
                backoffMs = INITIAL_BACKOFF_MS
                subscriptions.values.forEach { socket?.send(GraphQLCable.subscribe(it.identifier)) }
            }
            "confirm_subscription" -> subscription?.let {
                if (it.confirmed) return null
                it.confirmed = true
                socket?.send(it.executeFrame)
                it.channel.trySend(CableEvent.Subscribed)
            }
            "reject_subscription" -> subscription?.let {
                subscriptions.remove(it.identifier)
                it.channel.trySend(CableEvent.Rejected)
                it.channel.close()
                if (subscriptions.isEmpty()) teardown()
            }
            "disconnect" -> {
                val reason = (frame["reason"] as? JsonPrimitive)?.contentOrNull
                val reconnects = (frame["reconnect"] as? JsonPrimitive)?.booleanOrNull ?: true
                if (reason == "unauthorized") {
                    val refused = socketToken
                    end(reason)
                    return refused
                }
                if (reconnects) drop(reason) else end(reason)
            }
            // Pings, and anything else.
            "ping" -> Unit
            null -> {
                // Action Cable reuses `message` for its ping counter, so only an object there is a result.
                val message = frame["message"] as? JsonObject ?: return null
                val target = subscription?.takeIf { it.confirmed } ?: return null
                target.channel.trySend(CableEvent.Result(message))
                if ((message["more"] as? JsonPrimitive)?.booleanOrNull == false) {
                    // The operation is over; the channel goes too.
                    subscriptions.remove(target.identifier)
                    socket?.send(GraphQLCable.unsubscribe(target.identifier))
                    target.channel.close()
                    if (subscriptions.isEmpty()) teardown()
                }
            }
        }
        return null
    }

    companion object {
        /** Action Cable pings every 3 seconds; this long without a frame and the socket is gone. */
        const val SILENCE_MS: Long = 12_000L
        const val INITIAL_BACKOFF_MS: Long = 1_000L
        const val MAX_BACKOFF_MS: Long = 30_000L
    }
}
