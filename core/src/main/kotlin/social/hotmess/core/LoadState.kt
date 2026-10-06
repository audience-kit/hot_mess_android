package social.hotmess.core

/** What a screen is showing: a spinner, its content, or an error with an optional retry. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data class Failed(val message: String, val isRetryable: Boolean) : LoadState<Nothing>

    val valueOrNull: T? get() = (this as? Loaded<T>)?.value

    companion object {
        fun failed(error: Throwable): Failed {
            val apiError = ApiError.of(error)
            return Failed(apiError.message ?: "Something went wrong.", apiError.isRetryable)
        }
    }
}
