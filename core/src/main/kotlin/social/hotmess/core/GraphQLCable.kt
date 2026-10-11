package social.hotmess.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
import java.util.UUID

/**
 * GraphQL subscriptions over Action Cable, whatever the operation: the `GraphQLChannel` subscription,
 * `execute` once Action Cable confirms it, and the `{"result":{"data":…,"errors":…},"more":<bool>}`
 * results that follow. Chat rooms ([ChatProtocol]) and your notifications ([NotificationProtocol]) are
 * both operations on it.
 */
object GraphQLCable {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
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

    /** Runs [query] with [variables] on the channel [identifier] names. */
    fun execute(identifier: String, query: String, variables: JsonObject = JsonObject(emptyMap())): String {
        val data = JsonObject(
            mapOf(
                "action" to JsonPrimitive("execute"),
                "query" to JsonPrimitive(query),
                "variables" to variables,
            ),
        ).toString()
        return json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("message", identifier, data))
    }

    /** One frame from the server, as Action Cable frames it. */
    sealed interface Envelope {
        /** Action Cable confirmed the channel subscription: time to run the operation. */
        data object Subscribed : Envelope

        /** The channel was refused (another audience, not signed in). */
        data object Rejected : Envelope

        /** The server is closing the connection. */
        data object Disconnected : Envelope

        /** One result of the operation; [more] false ends it. */
        data class Result(val data: JsonObject?, val errors: JsonArray, val more: Boolean) : Envelope {
            /** The subscription field's value: the one entry of `data`, when it's an object. */
            val event: JsonObject? get() = data?.values?.firstOrNull() as? JsonObject
        }
    }

    /** Reads one frame, or null for pings, welcomes and anything unrecognized. */
    fun parse(text: String): Envelope? {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
            "confirm_subscription" -> Envelope.Subscribed
            "disconnect" -> Envelope.Disconnected
            "reject_subscription" -> Envelope.Rejected
            // Action Cable reuses `message` for its ping counter, so only an object there is a result.
            null -> (frame["message"] as? JsonObject)?.let { message ->
                val result = message["result"] as? JsonObject
                Envelope.Result(
                    data = result?.get("data") as? JsonObject,
                    errors = (result?.get("errors") as? JsonArray) ?: JsonArray(emptyList()),
                    more = (message["more"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )
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

    /**
     * One operation on its own socket, while collected: opens [url] with [token], subscribes to the
     * channel, runs [execute] (given the identifier) once it's confirmed, and hands every frame to
     * [onFrame]. [onFrame] returns false to close the socket and end the flow. The flow also ends when
     * the socket closes or fails, after [onClosed] says why.
     */
    fun <T> connect(
        client: OkHttpClient,
        url: String,
        token: String?,
        audienceId: String,
        execute: (identifier: String) -> String,
        onFrame: (Envelope, emit: (T) -> Unit) -> Boolean,
        onClosed: (reason: String?, emit: (T) -> Unit) -> Unit,
    ): Flow<T> = callbackFlow {
        val identifier = identifier(audienceId, newChannelId())
        val request = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "JWT $it") }
        }.build()
        val emit: (T) -> Unit = { trySend(it) }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(subscribe(identifier))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = parse(text) ?: return
                if (envelope == Envelope.Subscribed) webSocket.send(execute(identifier))
                if (!onFrame(envelope, emit)) {
                    webSocket.close(1000, null)
                    channel.close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed(reason.ifBlank { null }, emit)
                channel.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message, emit)
                channel.close()
            }
        }

        val socket = client.newWebSocket(request, listener)
        awaitClose { socket.close(1001, null) }
    }

    @Serializable
    private data class OutgoingFrame(val command: String, val identifier: String, val data: String?)
}
