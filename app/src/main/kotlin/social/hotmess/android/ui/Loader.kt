package social.hotmess.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.AppGraph
import social.hotmess.core.LoadState

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("No AppGraph provided") }

/**
 * Loads one screen's content and keeps it across configuration changes. Every screen exposes a
 * single [LoadState], so loading, empty and failure states look the same everywhere.
 */
class Loader<T> : ViewModel() {
    private val _state = MutableStateFlow<LoadState<T>>(LoadState.Loading)
    val state: StateFlow<LoadState<T>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var job: Job? = null
    private var loadedKey: Any? = NotLoaded

    /**
     * Loads with [fetch] unless content for [key] is already showing. A [refresh] keeps the content
     * on screen while it reloads, and keeps it if the reload fails.
     */
    fun load(key: Any?, refresh: Boolean = false, fetch: suspend () -> T) {
        if (!refresh && key == loadedKey && _state.value is LoadState.Loaded) return
        job?.cancel()
        job = viewModelScope.launch {
            if (refresh) _isRefreshing.value = true else if (_state.value !is LoadState.Loaded) _state.value = LoadState.Loading
            try {
                _state.value = LoadState.Loaded(fetch())
                loadedKey = key
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!refresh || _state.value !is LoadState.Loaded) _state.value = LoadState.failed(e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Changes the loaded content in place, e.g. an optimistic RSVP. */
    fun update(transform: (T) -> T) {
        val current = _state.value as? LoadState.Loaded<T> ?: return
        _state.value = LoadState.Loaded(transform(current.value))
    }

    private object NotLoaded
}

/** The [Loader] for one screen, e.g. `rememberLoader<Event>("event-$id")`. */
@Composable
fun <T> rememberLoader(key: String): Loader<T> = viewModel(key = key) { Loader<T>() }
