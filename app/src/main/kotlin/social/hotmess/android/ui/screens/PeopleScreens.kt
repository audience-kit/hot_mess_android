package social.hotmess.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.R
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.DetailInset
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventCard
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.HeroFeed
import social.hotmess.android.ui.components.HeroHeader
import social.hotmess.android.ui.components.HeroScaffold
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.PersonCard
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SocialLinkRow
import social.hotmess.android.ui.components.TrackRow
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.core.AppRoute
import social.hotmess.core.Person
import social.hotmess.core.PersonDetail
import social.hotmess.core.PhotoTone
import social.hotmess.core.SocialLink

/** The DJs, hosts and performers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<Person>>("people")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { loader.load(Unit) { graph.api.people() } }

    ScreenScaffold(title = "People") {
        LoadStateView(state, onRetry = { loader.load(Unit) { graph.api.people() } }) { people ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(Unit, refresh = true) { graph.api.people() } }) {
                if (people.isEmpty()) {
                    Message(Icons.Rounded.People, "No one here yet", "DJs, hosts and performers will show up here.")
                } else {
                    Feed {
                        cardSection("people", null, people, key = { it.id }) { person ->
                            PersonCard(person, onClick = { navigator.open(AppRoute.PersonDetail(person.id)) })
                        }
                    }
                }
            }
        }
    }
}

/** One person: their cover and photo, where else to find them, their members and groups, their events and tracks. */
@Composable
fun PersonScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val loader = rememberLoader<PersonDetail>("person-$id")
    val state by loader.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) { loader.load(id) { graph.api.person(id) } }

    val current = state.valueOrNull
    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    HeroScaffold(
        title = current?.name ?: "Person",
        tone = heroTone,
        collapsed = current == null || scrolled,
        onBack = navigator::back,
    ) { topInset ->
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.person(id) } }) { detail ->
            val open = { person: Person -> navigator.open(AppRoute.PersonDetail(person.id)) }
            HeroFeed(listState, hero = { Hero(detail, topInset) { heroTone = it } }) {
                if (detail.tipLinks.isNotEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                            detail.tipLinks.forEach { link ->
                                PrimaryButton(
                                    "Tip on ${link.tipApp}",
                                    onClick = { link.url?.let(context::openUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.Paid,
                                )
                            }
                        }
                    }
                }
                val links = detail.profileLinks.filter { it.url != null }
                val facebook = detail.person.facebookUrl.takeIf { links.none { it.network == SocialLink.Network.FACEBOOK } }
                if (links.isNotEmpty() || facebook != null) {
                    item {
                        DetailSection("Elsewhere") {
                            CardRows(links) { link -> SocialLinkRow(link) { link.url?.let(context::openUrl) } }
                            if (facebook != null) {
                                if (links.isNotEmpty()) RowDivider()
                                InfoRow("Open in Facebook", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(facebook) })
                            }
                        }
                    }
                }
                cardSection("members", "Members", detail.members, key = { it.id }) { PersonCard(it, onClick = { open(it) }) }
                cardSection("groups", "Part of", detail.groups, key = { it.id }) { PersonCard(it, onClick = { open(it) }) }
                if (detail.events.isEmpty()) {
                    item { DetailSection("Events") { EmptyRow("Nothing coming up yet.") } }
                } else {
                    cardSection("events", "Events", detail.events, key = { it.id }) { event ->
                        EventCard(event, onClick = { navigator.open(AppRoute.EventDetail(event.id)) })
                    }
                }
                if (detail.tracks.isNotEmpty()) {
                    item {
                        DetailSection(
                            "Tracks",
                            footer = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Image(
                                        painterResource(R.drawable.powered_by_soundcloud),
                                        contentDescription = "Powered by SoundCloud",
                                        modifier = Modifier.height(24.dp),
                                    )
                                }
                            },
                        ) {
                            // The artwork (64) plus the 12 gap, past the row's 20 inset.
                            CardRows(detail.tracks, divider = DetailInset + 76.dp) { track ->
                                TrackRow(track, track.providerUrl?.let { url -> { context.openUrl(url) } })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The cover photo, with their picture and name over it. */
@Composable
private fun Hero(detail: PersonDetail, topInset: Dp, onTone: (PhotoTone) -> Unit) {
    HeroHeader(detail.coverUrl, topInset, onTone = onTone) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Avatar(
                detail.pictureUrl,
                detail.name,
                size = 76.dp,
                modifier = Modifier.border(3.dp, LocalContentColor.current, CircleShape),
            )
            Text(
                detail.name,
                style = HotMessType.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
    }
}
