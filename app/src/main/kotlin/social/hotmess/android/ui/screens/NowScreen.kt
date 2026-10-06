package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.VenueRow
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.Radius
import social.hotmess.core.AppRoute
import social.hotmess.core.Now

/** What's happening where you are: the venue you're in or the ones nearby, and what's on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val coordinates by graph.location.coordinates.collectAsStateWithLifecycle()
    val loader = rememberLoader<Now>("now")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(coordinates) { loader.load(coordinates) { graph.api.now(coordinates) } }

    ScreenScaffold(title = state.valueOrNull?.title?.takeIf { it.isNotBlank() } ?: "Now") {
        LoadStateView(state, onRetry = { loader.load(coordinates, refresh = false) { graph.api.now(coordinates) } }) { now ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(coordinates, refresh = true) { graph.api.now(coordinates) } }) {
                Feed {
                    now.imageUrl?.let { url ->
                        item { RemoteImage(url, Modifier.fillMaxWidth().height(160.dp).clip(Radius.lg)) }
                    }
                    item { Nearby(now, coordinates != null, navigator) }
                    item {
                        Section("Events") {
                            if (now.events.isEmpty()) EmptyRow("There's nothing coming up yet.")
                            CardRows(now.events, divider = 76.dp) { event ->
                                EventRow(event) { navigator.open(AppRoute.EventDetail(event.id)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Nearby(now: Now, located: Boolean, navigator: Navigator) {
    val venue = now.venue
    when {
        venue != null -> Section("You're at") {
            VenueRow(venue) { navigator.open(AppRoute.VenueDetail(venue.id)) }
            RowDivider()
            InfoRow(
                "Small talk",
                icon = Icons.AutoMirrored.Rounded.Chat,
                onClick = { navigator.openChat(venue.id, venue.name) },
                trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            )
        }
        now.venues != null -> Section("Venues near you") {
            val venues = now.venues.orEmpty().take(3)
            if (venues.isEmpty()) EmptyRow("You aren't near any venues right now.")
            CardRows(venues, divider = 84.dp) { nearby -> VenueRow(nearby) { navigator.open(AppRoute.VenueDetail(nearby.id)) } }
        }
        !located -> Section("Venues near you") {
            EmptyRow("Turn on location to see what's happening near you.")
        }
    }
}
