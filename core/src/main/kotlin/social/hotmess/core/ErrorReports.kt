package social.hotmess.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One error report for `POST /v1/client_errors`, which the AudienceKit API logs as a `[client_error]` line in its
 * Heroku log.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientErrorReport(
    // HotMessApi.json leaves defaults out, but the API needs to know which app sent it.
    @EncodeDefault val app: String = "android",
    /** `crash`, `error` or what else went wrong. */
    val kind: String,
    val message: String,
    val stack: String? = null,
    val screen: String? = null,
    val version: String? = null,
    val build: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    val device: String? = null,
) {
    companion object {
        const val MAX_STACK = 8_000

        /** An uncaught exception, as the crash handler saves it for the next launch to send. */
        fun crash(error: Throwable, thread: String, device: DeviceFacts): ClientErrorReport = ClientErrorReport(
            kind = "crash",
            message = "Crash on $thread: ${describe(error)}",
            stack = error.stackTraceToString().take(MAX_STACK),
            version = device.version,
            build = device.build,
            osVersion = device.osVersion,
            device = device.model,
        )

        fun describe(error: Throwable): String =
            listOfNotNull(error::class.qualifiedName ?: error::class.simpleName, error.message).joinToString(": ")
    }
}

/** What reports say about the app and the phone. */
data class DeviceFacts(val version: String, val build: String, val osVersion: String, val model: String)

/** One thing that went wrong, as "Report a problem" attaches it. */
@Serializable
data class RecentError(val kind: String, val message: String, val at: String)

/** "Report a problem", for `POST /v1/bug_reports`. Works signed out, when [host] names the audience. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BugReportRequest(
    // The API refuses a report without it, and HotMessApi.json leaves defaults out.
    @EncodeDefault val app: String = "android",
    val description: String,
    val email: String? = null,
    val screen: String? = null,
    val version: String,
    val build: String,
    @SerialName("os_version") val osVersion: String,
    val device: String,
    val host: String? = null,
    val diagnostics: Diagnostics? = null,
) {
    @Serializable
    data class Diagnostics(
        @SerialName("signed_in") val signedIn: Boolean,
        val environment: String,
        val locale: String? = null,
        @SerialName("recent_errors") val recentErrors: List<RecentError>,
    )
}

@Serializable
data class BugReportResponse(val id: String)

/**
 * The last [capacity] things that went wrong, and which of them have been sent, so a failure that repeats is sent
 * once and a launch sends at most [maxSent].
 */
class RecentErrors(private val capacity: Int = 20, private val maxSent: Int = 30) {
    private val entries = ArrayDeque<RecentError>()
    private val sent = mutableSetOf<String>()

    @Synchronized
    fun remember(kind: String, message: String, at: String) {
        entries.addLast(RecentError(kind, message.take(500), at))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Whether [report] should be sent: the first time it's seen, and while under the per-launch limit. */
    @Synchronized
    fun shouldSend(report: ClientErrorReport): Boolean {
        if (sent.size >= maxSent) return false
        return sent.add("${report.kind}:${report.message}")
    }

    @Synchronized
    fun snapshot(): List<RecentError> = entries.toList()
}

/** Whether this failure points at a bug worth sending, rather than at the network or the session. */
val ApiError.isReportable: Boolean
    get() = when (this) {
        is ApiError.Decoding -> true
        is ApiError.Server -> status >= 500
        ApiError.Offline, ApiError.Unauthorized, ApiError.NotFound, is ApiError.Other -> false
    }

val ApiError.reportDescription: String
    get() = when (this) {
        is ApiError.Decoding -> "decoding failed: $detail"
        is ApiError.Server -> "HTTP $status"
        is ApiError.Other -> detail
        else -> this::class.simpleName ?: "error"
    }

/** "query Venue" or "mutation SendPing", for error reports: a GraphQL document's first line up to its variables. */
fun operationName(document: String): String {
    val name = document.trim().takeWhile { it != '(' && it != '{' && it != '\n' }.trim()
    return if (name.isEmpty()) "graphql" else "graphql $name"
}
