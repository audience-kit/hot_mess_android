package social.hotmess.android.ui.screens

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
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.payments.CoverCheckout
import social.hotmess.android.payments.CoverCheckoutFailure
import social.hotmess.android.payments.rememberCoverCheckout
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.ChatPeek
import social.hotmess.android.ui.components.ChatThreadMessage
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.PassCard
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.SkipTheLineCard
import social.hotmess.android.ui.components.VenueRow
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.ChatLine
import social.hotmess.core.CoverAction
import social.hotmess.core.CoverOffer
import social.hotmess.core.Friend
import social.hotmess.core.FriendVenue
import social.hotmess.core.Now
import social.hotmess.core.NowLocale
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

    val checkout = rememberCoverCheckout { pass ->
        loader.load(coordinates, refresh = true) { graph.api.now(coordinates) }
        navigator.openPass(pass.id)
    }
    val checkoutState by checkout.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = state.valueOrNull?.title?.takeIf { it.isNotBlank() } ?: "Now") {
        LoadStateView(state, onRetry = { loader.load(coordinates, refresh = false) { graph.api.now(coordinates) } }) { now ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(coordinates, refresh = true) { graph.api.now(coordinates) } }) {
                Feed {
                    simulated?.let { name ->
                        item {
                            Section("Pretending to be at $name") {
                                InfoRow("Stop pretending", icon = Icons.Rounded.LocationOff, onClick = { graph.location.stopSimulating() })
                            }
                        }
                    }
                    // At a venue with a cover tonight: your pass front and center, or the way to skip the line.
                    now.venue?.let { venue ->
                        val offer = CoverOffer.of(venue)
                        val pass = offer?.pass
                        when {
                            pass != null -> item(key = "pass") {
                                PassCard(pass, venue.name) {
                                    graph.passes.put(pass)
                                    navigator.openPass(pass.id)
                                }
                            }
                            offer != null && offer.action == CoverAction.PAY -> item(key = "cover") {
                                SkipTheLineCard(
                                    offer,
                                    paying = checkoutState == CoverCheckout.State.Working,
                                    onPay = { checkout.pay(venue.id, venue.name) },
                                )
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
                    now.locale?.takeIf { now.venue == null && it.chatOpen }?.let { locale ->
                        item { LocaleSmallTalk(locale, navigator) }
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

    CoverCheckoutFailure(checkout)
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
    ChatPeek(
        rememberPeekMessages(venue.recentMessages),
        title = "Small talk",
        room = venue.name,
        onOpen = { navigator.openChat(venue.id, venue.name) },
    )
}

/** Away from venues: the last few lines of the locale's chat, for everyone out in it, and the way in. */
@Composable
private fun LocaleSmallTalk(locale: NowLocale, navigator: Navigator) {
    val name = locale.name ?: "your city"
    ChatPeek(
        rememberPeekMessages(locale.recentMessages),
        title = "Small talk in $name",
        room = name,
        onOpen = { navigator.openLocaleChat(locale.id, name) },
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
