package social.hotmess.core

import com.audiencekit.AudienceKitException
import com.audiencekit.GraphQLError

/**
 * The failures the screens know how to show, mapped from the SDK's. A GraphQL error is classified by its
 * `extensions.code` (UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, INVALID, RATE_LIMITED, CONFLICT, INTERNAL,
 * UNAVAILABLE), never by its message: only an error with a code was written by the API for people.
 */
sealed class ApiError(message: String) : Exception(message) {
    /** No usable connection, or the request timed out. */
    data object Offline : ApiError("You appear to be offline. Check your connection and try again.")

    /** The session has ended; the app goes back to sign-in. */
    data object Unauthorized : ApiError("Your session ended. Sign in again to continue.")

    data object NotFound : ApiError("That isn't available any more.")

    /** The API, or something it relies on, failed (an HTTP 5xx, or a GraphQL INTERNAL error). */
    data class Server(val status: Int) : ApiError("We had a problem on our end. Try again in a moment.")

    /** A service the API relies on is down or not set up (UNAVAILABLE, 503); nothing is wrong with the request. */
    data object Unavailable : ApiError("That isn't available right now. Try again in a little while.")

    data class Decoding(val detail: String) : ApiError("We got something we didn't expect. Try again later.")

    /**
     * The API refused the request and said why, in words for people: the input is wrong ([INVALID], with the
     * messages by field in [fields]), the record's state no longer allows it ([CONFLICT]), it isn't allowed
     * ([FORBIDDEN], with the API's [reason]) or too many were asked for ([RATE_LIMITED]).
     */
    data class Rejected(
        val code: String,
        val detail: String,
        val fields: Map<String, List<String>> = emptyMap(),
        val reason: String? = null,
    ) : ApiError(detail)

    data class Other(val detail: String) : ApiError(detail)

    /** Whether a "Try again" button makes sense. */
    val isRetryable: Boolean
        get() = when (this) {
            Offline, is Server, Unavailable, is Other -> true
            is Rejected -> code == RATE_LIMITED
            Unauthorized, NotFound, is Decoding -> false
        }

    companion object {
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val FORBIDDEN = "FORBIDDEN"
        const val NOT_FOUND = "NOT_FOUND"
        const val INVALID = "INVALID"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val CONFLICT = "CONFLICT"
        const val INTERNAL = "INTERNAL"
        const val UNAVAILABLE = "UNAVAILABLE"

        /** The codes whose messages are written for people and shown as they are. */
        val REJECTION_CODES: Set<String> = setOf(FORBIDDEN, INVALID, RATE_LIMITED, CONFLICT)

        fun from(error: AudienceKitException): ApiError = when (error) {
            is AudienceKitException.NotSignedIn,
            is AudienceKitException.Unauthorized,
            is AudienceKitException.SignInRejected,
            -> Unauthorized
            is AudienceKitException.NotFound -> NotFound
            is AudienceKitException.Http -> if (error.status == 503) Unavailable else Server(error.status)
            is AudienceKitException.Network -> Offline
            is AudienceKitException.Decoding -> Decoding(error.detail)
            is AudienceKitException.GraphQL -> fromGraphQL(error.errors)
            else -> Other(error.message ?: "Something went wrong.")
        }

        /**
         * A GraphQL response's errors as one [ApiError], by the first error's code. An error with no code is one
         * graphql-ruby raised itself (a field that doesn't exist, a bad variable), so its message isn't for people.
         */
        fun fromGraphQL(errors: List<GraphQLError>): ApiError {
            val error = errors.firstOrNull() ?: return Other(GENERIC_MESSAGE)
            val code = error.code ?: return Other(GENERIC_MESSAGE)
            return when (code) {
                UNAUTHENTICATED -> Unauthorized
                NOT_FOUND -> NotFound
                INTERNAL -> Server(500)
                UNAVAILABLE -> Unavailable
                in REJECTION_CODES -> Rejected(code, error.message.ifBlank { GENERIC_MESSAGE }, error.fields, error.reason)
                else -> Other(GENERIC_MESSAGE)
            }
        }

        /** Any failure as an [ApiError], for the screens' error states. */
        fun of(error: Throwable): ApiError = when (error) {
            is ApiError -> error
            is AudienceKitException -> from(error)
            is java.io.IOException -> Offline
            else -> Other(error.message ?: "Something went wrong.")
        }

        const val GENERIC_MESSAGE = "We couldn't load that. Try again in a moment."
    }
}
