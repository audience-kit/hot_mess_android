package social.hotmess.android.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.core.ApiError
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.VenueRow
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.ChatLine
import social.hotmess.core.Friend
import social.hotmess.core.FriendVenue
import social.hotmess.core.Now
import social.hotmess.core.Venue

/**
 * What's happening where you are: the venue you're in, with the last few lines of its chat and the
 * friends who are there, or the venues nearby; then what's on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val coordinates by graph.location.coordinates.collectAsStateWithLifecycle()
    val loader = rememberLoader<Now>("now")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val simulated by graph.location.simulatedVenue.collectAsStateWithLifecycle()

    LaunchedEffect(coordinates) { loader.load(coordinates) { graph.api.nowOrPings(coordinates) } }

    // A Ping push arrived or was tapped: reload, keeping what's on screen meanwhile.
    val latestCoordinates by rememberUpdatedState(coordinates)
    LaunchedEffect(Unit) {
        graph.pingUpdates.collect {
            val near = latestCoordinates
            loader.load(near, refresh = true) { graph.api.nowOrPings(near) }
        }
    }

    val userId = rememberUserId()
    val scope = rememberCoroutineScope()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var ending by rememberSaveable { mutableStateOf(false) }
    var endError by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = rememberPingActions { ping -> loader.update { it.replacingPing(ping) } }

    fun endPing() {
        ending = true
        scope.launch {
            try {
                graph.api.endPing()
                loader.update { it.copy(myPing = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                endError = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                ending = false
            }
        }
    }

    ScreenScaffold(
        title = state.valueOrNull?.title?.takeIf { it.isNotBlank() } ?: "Now",
        actions = {
            TextButton(onClick = { sheetOpen = true }) {
                Icon(Icons.Rounded.Campaign, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(20.dp))
                Text("Ping", style = HotMessType.label, color = tokens.accentInk, modifier = Modifier.padding(start = Space.s1))
            }
        },
    ) {
        LoadStateView(state, onRetry = { loader.load(coordinates, refresh = false) { graph.api.nowOrPings(coordinates) } }) { now ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(coordinates, refresh = true) { graph.api.nowOrPings(coordinates) } }) {
                Feed {
                    simulated?.let { name ->
                        item {
                            Section("Pretending to be at $name") {
                                InfoRow("Stop pretending", icon = Icons.Rounded.LocationOff, onClick = { graph.location.stopSimulating() })
                            }
                        }
                    }
                    now.myPing?.let { ping ->
                        item(key = "my-ping") { MyPingCard(ping, onEdit = { sheetOpen = true }, onEnd = ::endPing, ending = ending) }
                    }
                    if (now.friendPings.isNotEmpty()) {
                        item(key = "friend-pings-title") {
                            Text(
                                "Friends going out tonight",
                                style = HotMessType.heading,
                                color = tokens.ink,
                                modifier = Modifier.padding(horizontal = Space.s1),
                            )
                        }
                        now.friendPings.forEach { ping ->
                            item(key = "ping-${ping.id}") {
                                FriendPingCard(ping, userId, actions) { target ->
                                    target.event?.let { navigator.open(AppRoute.EventDetail(it.id)) }
                                        ?: target.venue?.let { navigator.open(AppRoute.VenueDetail(it.id)) }
                                }
                            }
                        }
                    }
                    now.imageUrl?.let { url ->
                        item { RemoteImage(url, Modifier.fillMaxWidth().height(160.dp).clip(Radius.lg)) }
                    }
                    item { Nearby(now, coordinates != null, navigator) }
                    now.venue?.let { venue ->
                        item { SmallTalk(venue, navigator) }
                        item { FriendsHere(now.friends) }
                    }
                    if (now.venue == null && now.friendVenues.isNotEmpty()) {
                        item { WhereFriendsAre(now.friendVenues, navigator) }
                    }
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

    if (sheetOpen) {
        PingSheet(
            preselect = null,
            onDismiss = { sheetOpen = false },
            onSent = { ping -> loader.update { it.replacingPing(ping) } },
        )
    }
    endError?.let { message ->
        AlertDialog(
            onDismissRequest = { endError = null },
            confirmButton = { TextButton(onClick = { endError = null }) { Text("OK") } },
            title = { Text("Couldn't end your ping") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun Nearby(now: Now, located: Boolean, navigator: Navigator) {
    val venue = now.venue
    when {
        venue != null -> Section("You're at") {
            VenueRow(venue) { navigator.open(AppRoute.VenueDetail(venue.id)) }
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

/** The last few lines of the venue's chat, and the way into the room. */
@Composable
private fun SmallTalk(venue: Venue, navigator: Navigator) {
    val configuration = LocalAppGraph.current.configuration
    Section("Small talk") {
        if (venue.recentMessages.isEmpty()) EmptyRow("No one's said anything yet.")
        CardRows(venue.recentMessages, divider = 56.dp) { line ->
            ChatPreviewRow(line, line.avatarUrl ?: line.userId.takeIf { it.isNotEmpty() }?.let(configuration::avatarUrl))
        }
        RowDivider()
        InfoRow(
            "Join the chat",
            icon = Icons.AutoMirrored.Rounded.Chat,
            onClick = { navigator.openChat(venue.id, venue.name) },
            trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        )
    }
}

@Composable
private fun ChatPreviewRow(line: ChatLine, avatarUrl: String?) {
    RowButton(null) {
        Avatar(avatarUrl, line.name.orEmpty(), size = 28.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            line.name?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.caption, color = tokens.inkMuted) }
            Text(line.message, style = HotMessType.body, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        line.sentAt?.let {
            Text(
                DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = HotMessType.caption,
                color = tokens.inkMuted,
            )
        }
    }
}

/** Friends who are at the venue too; tapping one opens Messenger. */
@Composable
private fun FriendsHere(friends: List<Friend>) {
    val configuration = LocalAppGraph.current.configuration
    val context = LocalContext.current
    Section("Friends here") {
        if (friends.isEmpty()) {
            EmptyRow("None of your friends are here yet.")
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.s4, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                friends.forEach { friend ->
                    val messenger = friend.messengerUrl
                    Column(
                        Modifier
                            .width(68.dp)
                            .then(if (messenger != null) Modifier.clickable(role = Role.Button) { context.openUrl(messenger) } else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Avatar(configuration.avatarUrl(friend.id), friend.name, size = 56.dp)
                        Text(friend.firstName, style = HotMessType.caption, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Away from venues: the venues where friends have been lately, with how many. */
@Composable
private fun WhereFriendsAre(entries: List<FriendVenue>, navigator: Navigator) {
    Section("Where your friends are") {
        CardRows(entries, divider = 72.dp) { entry ->
            RowButton({ navigator.open(AppRoute.VenueDetail(entry.venue.id)) }) {
                RemoteImage(entry.venue.photoUrl, Modifier.size(44.dp).clip(Radius.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.venue.name, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.friends.joinToString(", ") { it.firstName },
                        style = HotMessType.bodySmall,
                        color = tokens.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (entry.friendCount == 1) "1 friend" else "${entry.friendCount} friends",
                    style = HotMessType.caption,
                    color = tokens.accentInk,
                )
            }
        }
    }
}
