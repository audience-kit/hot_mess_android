package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Card
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.HeroImage
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.VenueMap
import social.hotmess.android.ui.components.VenueRow
import social.hotmess.android.ui.dial
import social.hotmess.android.ui.openMap
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.share
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.Formatting
import social.hotmess.core.Venue
import social.hotmess.core.VenueOverview
import social.hotmess.core.isIn

/** Every venue on a map, then as a list: the ones in your city, then everywhere else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenuesScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val branding by graph.brand.branding.collectAsStateWithLifecycle()
    val loader = rememberLoader<List<Venue>>("venues")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val localeId = locale?.id
    var mapExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(localeId) { loader.load(localeId) { graph.api.venues(localeId) } }

    ScreenScaffold(title = branding?.audience?.name?.let { "$it venues" } ?: "Venues") {
        LoadStateView(state, onRetry = { loader.load(localeId) { graph.api.venues(localeId) } }) { venues ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(localeId, refresh = true) { graph.api.venues(localeId) } }) {
                Feed {
                    if (venues.any { it.coordinate != null }) {
                        item {
                            Card {
                                Box {
                                    VenueMap(
                                        venues = venues,
                                        onVenue = { navigator.open(AppRoute.VenueDetail(it)) },
                                        onMapTap = { mapExpanded = true },
                                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(Radius.lg),
                                    )
                                    MapButton(
                                        icon = Icons.Rounded.OpenInFull,
                                        label = "Expand map",
                                        onClick = { mapExpanded = true },
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )
                                }
                            }
                        }
                    }
                    val (here, elsewhere) = venues.partition { it.isIn(localeId) }
                    val name = locale?.name
                    if (venues.isEmpty()) {
                        item { Section("All venues") { EmptyRow("There are no venues here yet.") } }
                    }
                    if (here.isNotEmpty() && name != null) {
                        item {
                            Section(name) {
                                CardRows(here, divider = 84.dp) { venue ->
                                    VenueRow(venue) { navigator.open(AppRoute.VenueDetail(venue.id)) }
                                }
                            }
                        }
                    }
                    if (elsewhere.isNotEmpty()) {
                        item {
                            Section(if (here.isEmpty() || name == null) "All venues" else "Elsewhere") {
                                CardRows(elsewhere, divider = 84.dp) { venue ->
                                    VenueRow(venue) { navigator.open(AppRoute.VenueDetail(venue.id)) }
                                }
                            }
                        }
                    }
                }
            }
            if (mapExpanded) {
                FullScreenVenueMap(
                    venues = venues,
                    onVenue = {
                        mapExpanded = false
                        navigator.open(AppRoute.VenueDetail(it))
                    },
                    onClose = { mapExpanded = false },
                )
            }
        }
    }
}

/** The venues map filling the screen. Back or the close button dismisses it. */
@Composable
private fun FullScreenVenueMap(venues: List<Venue>, onVenue: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            VenueMap(venues = venues, onVenue = onVenue, modifier = Modifier.fillMaxSize())
            MapButton(
                icon = Icons.Rounded.Close,
                label = "Close map",
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(8.dp).background(tokens.surfaceRaised, CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = tokens.ink)
    }
}

/** One venue: its photo, how to get there, its chat room and what's on. */
@Composable
fun VenueScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val loader = rememberLoader<VenueOverview>("venue-$id")
    val state by loader.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) { loader.load(id) { graph.api.venueOverview(id) } }

    val venue = state.valueOrNull?.venue
    ScreenScaffold(
        title = venue?.name ?: "Venue",
        onBack = navigator::back,
        actions = {
            if (venue != null) {
                IconButton(onClick = { context.share(venue.shareUrl, venue.name) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share")
                }
            }
        },
    ) {
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.venueOverview(id) } }) { overview ->
            val loaded = overview.venue
            Feed {
                (loaded.heroUrl ?: loaded.photoUrl)?.let { url -> item { HeroImage(url) } }
                item {
                    Section("About") {
                        loaded.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = HotMessType.body, color = tokens.ink, modifier = Modifier.padding(Space.s4))
                            RowDivider()
                        }
                        loaded.address?.takeIf { it.isNotBlank() }?.let { address ->
                            val coordinate = loaded.coordinate
                            InfoRow(
                                address,
                                icon = Icons.Rounded.Place,
                                onClick = coordinate?.let { { context.openMap(it.latitude, it.longitude, loaded.name) } },
                                trailing = coordinate?.let { Icons.AutoMirrored.Rounded.KeyboardArrowRight },
                            )
                            RowDivider()
                        }
                        loaded.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                            InfoRow(phone, icon = Icons.Rounded.Phone, onClick = { context.dial(phone) })
                            RowDivider()
                        }
                        loaded.distance?.let {
                            InfoRow("Distance", Formatting.distance(it), icon = Icons.Rounded.NearMe)
                            RowDivider()
                        }
                        val facebook = loaded.facebookUrl
                        if (facebook != null) {
                            InfoRow("Open in Facebook", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(facebook) })
                        } else {
                            InfoRow("Share", icon = Icons.Rounded.Share, onClick = { context.share(loaded.shareUrl, loaded.name) })
                        }
                    }
                }
                if (overview.chatOpen) {
                    item {
                        Section("Chat") {
                            InfoRow(
                                "Join the room",
                                icon = Icons.AutoMirrored.Rounded.Chat,
                                onClick = { navigator.openChat(loaded.id, loaded.name) },
                                trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            )
                        }
                    }
                }
                loaded.coordinate?.takeIf { graph.configuration.isTestBuild }?.let { coordinate ->
                    item {
                        PretendHere(loaded.name, coordinate.latitude, coordinate.longitude) {
                            loader.load(id, refresh = true) { graph.api.venueOverview(id) }
                        }
                    }
                }
                item {
                    Section("Events") {
                        if (overview.events.isEmpty()) EmptyRow("There's nothing coming up here yet.")
                        CardRows(overview.events, divider = 76.dp) { event ->
                            EventRow(event) { navigator.open(AppRoute.EventDetail(event.id)) }
                        }
                    }
                }
            }
        }
    }
}

/** Test builds only: report the venue's own position, so the app and the API treat you as inside it. */
@Composable
private fun PretendHere(name: String, latitude: Double, longitude: Double, onReported: () -> Unit) {
    val location = LocalAppGraph.current.location
    val simulated by location.simulatedVenue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Reload once the visit is reported, so the chat row (chatOpen) catches up.
    fun after(job: Job?) {
        job ?: return
        scope.launch {
            job.join()
            onReported()
        }
    }
    Section("Testing") {
        if (simulated == name) {
            InfoRow("Stop pretending", icon = Icons.Rounded.LocationOff, onClick = { after(location.stopSimulating()) })
        } else {
            InfoRow("Pretend I'm here", icon = Icons.Rounded.MyLocation, onClick = { after(location.simulate(latitude, longitude, name)) })
        }
        EmptyRow("Test builds only. Reports this venue's location instead of yours until you stop.")
    }
}
