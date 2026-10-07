package social.hotmess.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.ChatPeek
import social.hotmess.android.ui.components.ChatThreadMessage
import social.hotmess.android.ui.components.DetailInset
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventCard
import social.hotmess.android.ui.components.FriendFaces
import social.hotmess.android.ui.components.HeroFeed
import social.hotmess.android.ui.components.HeroHeader
import social.hotmess.android.ui.components.HeroScaffold
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.VenueCard
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.friendNames
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.ChatLine
import social.hotmess.core.Friend
import social.hotmess.core.FriendVenue
import social.hotmess.core.Now
import social.hotmess.core.PhotoTone
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

    LaunchedEffect(coordinates) { loader.load(coordinates) { graph.api.now(coordinates) } }

    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    val title = state.valueOrNull?.title?.takeIf { it.isNotBlank() } ?: "Now"
    HeroScaffold(title = title, tone = heroTone, collapsed = state.valueOrNull == null || scrolled) { topInset ->
        LoadStateView(state, onRetry = { loader.load(coordinates, refresh = false) { graph.api.now(coordinates) } }) { now ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(coordinates, refresh = true) { graph.api.now(coordinates) } }) {
                HeroFeed(
                    listState,
                    hero = {
                        HeroHeader(now.imageUrl, topInset, onTone = { heroTone = it }) {
                            Text(
                                title,
                                style = HotMessType.display,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                    },
                ) {
                    simulated?.let { name ->
                        item {
                            DetailSection("Pretending to be at $name") {
                                InfoRow("Stop pretending", icon = Icons.Rounded.LocationOff, onClick = { graph.location.stopSimulating() })
                            }
                        }
                    }
                    nearby(now, coordinates != null, navigator)
                    now.venue?.let { venue ->
                        item { SmallTalk(venue, navigator) }
                        item { FriendsHere(now.friends) }
                    }
                    if (now.venue == null) whereFriendsAre(now.friendVenues, navigator)
                    if (now.events.isEmpty()) {
                        item { DetailSection("Events") { EmptyRow("There's nothing coming up yet.") } }
                    } else {
                        cardSection("events", "Events", now.events, key = { it.id }) { event ->
                            EventCard(event, onClick = { navigator.open(AppRoute.EventDetail(event.id)) })
                        }
                    }
                }
            }
        }
    }
}

/** The venue you're at, the venues near you, or a nudge to turn on location. */
private fun LazyListScope.nearby(now: Now, located: Boolean, navigator: Navigator) {
    val open = { venue: Venue -> navigator.open(AppRoute.VenueDetail(venue.id)) }
    val venue = now.venue
    val venues = now.venues
    when {
        venue != null -> cardSection("here", "You're at", listOf(venue), key = { it.id }) { VenueCard(it, onClick = { open(it) }) }
        venues != null && venues.isEmpty() -> item { DetailSection("Venues near you") { EmptyRow("You aren't near any venues right now.") } }
        venues != null -> cardSection("nearby", "Venues near you", venues.take(3), key = { it.id }) { VenueCard(it, onClick = { open(it) }) }
        !located -> item { DetailSection("Venues near you") { EmptyRow("Turn on location to see what's happening near you.") } }
    }
}

/** The last few lines of the venue's chat, and the way into the room. */
@Composable
private fun SmallTalk(venue: Venue, navigator: Navigator) {
    ChatPeek(
        rememberPeekMessages(venue.recentMessages),
        title = "Small talk",
        room = venue.name,
        onOpen = { navigator.openChat(venue.id, venue.name) },
    )
}

/**
 * A venue's recent chat lines as ChatPeek draws them: the viewer's own on the right, and the
 * sender's picture when the line has none.
 */
@Composable
internal fun rememberPeekMessages(lines: List<ChatLine>): List<ChatThreadMessage> {
    val graph = LocalAppGraph.current
    val user by graph.session.user.collectAsStateWithLifecycle()
    val viewerId = user?.id
    return remember(lines, viewerId) {
        lines.map { line ->
            ChatThreadMessage(
                id = line.id,
                senderId = line.userId.ifEmpty { line.id },
                senderName = line.name,
                avatarUrl = line.avatarUrl ?: line.userId.takeIf { it.isNotEmpty() }?.let(graph.configuration::avatarUrl),
                text = line.message,
                sentAt = line.sentAt,
                own = viewerId != null && line.userId.equals(viewerId, ignoreCase = true),
            )
        }
    }
}

/** Friends who are at the venue too, by their full names; tapping one opens Messenger. */
@Composable
private fun FriendsHere(friends: List<Friend>) {
    val configuration = LocalAppGraph.current.configuration
    val context = LocalContext.current
    DetailSection("Friends here") {
        if (friends.isEmpty()) {
            EmptyRow("None of your friends are here yet.")
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = DetailInset, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalAlignment = Alignment.Top,
            ) {
                friends.forEach { friend ->
                    val messenger = friend.messengerUrl
                    Column(
                        Modifier
                            .width(72.dp)
                            .then(if (messenger != null) Modifier.clickable(role = Role.Button) { context.openUrl(messenger) } else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Avatar(configuration.avatarUrl(friend.id), friend.name, size = Sizes.avatarLg)
                        // Friends see each other's full names; two lines fit most, and longer ones
                        // truncate at the end.
                        Text(
                            friend.name,
                            style = HotMessType.bodySmall,
                            color = tokens.ink,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Away from venues: the venues where friends have been lately, as venue cards with their faces top
 * left, how many top right, and their full names (truncated to fit) under the venue's name.
 */
private fun LazyListScope.whereFriendsAre(entries: List<FriendVenue>, navigator: Navigator) {
    cardSection("friend-venues", "Where your friends are", entries, key = { it.venue.id }) { entry ->
        VenueCard(
            entry.venue,
            onClick = { navigator.open(AppRoute.VenueDetail(entry.venue.id)) },
            detail = friendNames(entry.friends),
            pill = if (entry.friendCount == 1) "1 friend" else "${entry.friendCount} friends",
            corner = { FriendFaces(entry.friends) },
        )
    }
}
