package social.hotmess.core

import com.audiencekit.AudienceKitException

/** The failures the screens know how to show, mapped from the SDK's. */
sealed class ApiError(message: String) : Exception(message) {
    /** No usable connection, or the request timed out. */
    data object Offline : ApiError("You appear to be offline. Check your connection and try again.")

    /** The session has ended; the app goes back to sign-in. */
    data object Unauthorized : ApiError("Your session ended. Sign in again to continue.")

    data object NotFound : ApiError("That isn't available any more.")

    data class Server(val status: Int) : ApiError("We had a problem on our end. Try again in a moment.")

    data class Decoding(val detail: String) : ApiError("We got something we didn't expect. Try again later.")

    data class Other(val detail: String) : ApiError(detail)

    /** Whether a "Try again" button makes sense. */
    val isRetryable: Boolean
        get() = when (this) {
            Offline, is Server, is Other -> true
            Unauthorized, NotFound, is Decoding -> false
        }

    companion object {
        fun from(error: AudienceKitException): ApiError = when (error) {
            is AudienceKitException.NotSignedIn,
            is AudienceKitException.Unauthorized,
            is AudienceKitException.SignInRejected,
            -> Unauthorized
            is AudienceKitException.NotFound -> NotFound
            is AudienceKitException.Http -> Server(error.status)
            is AudienceKitException.Network -> Offline
            is AudienceKitException.Decoding -> Decoding(error.detail)
            is AudienceKitException.GraphQL -> Other("We couldn't load that. Try again in a moment.")
            else -> Other(error.message ?: "Something went wrong.")
        }

        /** Any failure as an [ApiError], for the screens' error states. */
        fun of(error: Throwable): ApiError = when (error) {
            is ApiError -> error
            is AudienceKitException -> from(error)
            is java.io.IOException -> Offline
            else -> Other(error.message ?: "Something went wrong.")
        }
    }
}
