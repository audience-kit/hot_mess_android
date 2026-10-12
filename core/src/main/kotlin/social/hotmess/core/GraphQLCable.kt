package social.hotmess.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.util.UUID

/**
 * GraphQL subscriptions over Action Cable, whatever the operation: the `GraphQLChannel` subscription,
 * `execute` once Action Cable confirms it, and the `{"result":{"data":…,"errors":…},"more":<bool>}`
 * results that follow. Chat rooms ([ChatProtocol]) and your notifications ([NotificationProtocol]) are
 * both operations on it. These are the frames; [CableConnection] is the one socket that carries them.
 */
object GraphQLCable {
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Action Cable names a subscription by a JSON *string*, not an object: the GraphQL channel, the
     * audience, and [channelId], which keeps one operation's identifier apart from another's. Built once
     * per subscription, keys sorted, and sent byte for byte the same on every frame, since Action Cable
     * matches identifiers as strings.
     */
    fun identifier(audienceId: String, channelId: String): String = JsonObject(
        sortedMapOf(
            "audience_id" to JsonPrimitive(audienceId.lowercase()),
            "channel" to JsonPrimitive("GraphQLChannel"),
            "channelId" to JsonPrimitive(channelId),
        ),
    ).toString()

    /** A fresh id for [identifier]. */
    fun newChannelId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun subscribe(identifier: String): String =
        json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("subscribe", identifier, null))

    fun unsubscribe(identifier: String): String =
        json.encodeToString(OutgoingFrame.serializer(), OutgoingFrame("unsubscribe", identifier, null))

    /** Runs [query] with [variables] on the channel [identifier] names. */
    fun execute(
        identifier: String,
        query: String,
        variables: JsonObject = JsonObject(emptyMap()),
        operationName: String? = null,
    ): String {
        val data = JsonObject(
            buildMap {
                put("action", JsonPrimitive("execute"))
                put("query", JsonPrimitive(query))
                put("variables", variables)
                operationName?.let { put("operationName", JsonPrimitive(it)) }
            },
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
            null -> (frame["message"] as? JsonObject)?.let(::result)
            else -> null
        }
    }

    /** One result message, `{"result":{"data":…,"errors":…},"more":…}`. */
    fun result(message: JsonObject): Envelope.Result {
        val result = message["result"] as? JsonObject
        return Envelope.Result(
            data = result?.get("data") as? JsonObject,
            errors = (result?.get("errors") as? JsonArray) ?: JsonArray(emptyList()),
            more = (message["more"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
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
}
