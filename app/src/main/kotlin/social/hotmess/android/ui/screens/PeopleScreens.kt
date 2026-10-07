package social.hotmess.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.R
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
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.PersonRow
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.SocialLinkRow
import social.hotmess.android.ui.components.TrackRow
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.Person
import social.hotmess.core.PersonDetail
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
                        item {
                            Section(null) {
                                CardRows(people, divider = 72.dp) { person ->
                                    PersonRow(person) { navigator.open(AppRoute.PersonDetail(person.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One person: their cover and photo, where else to find them, their events and tracks. */
@Composable
fun PersonScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val loader = rememberLoader<PersonDetail>("person-$id")
    val state by loader.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) { loader.load(id) { graph.api.person(id) } }

    ScreenScaffold(title = state.valueOrNull?.name ?: "", onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.person(id) } }) { detail ->
            Feed {
                item { Header(detail) }
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
                if (links.isNotEmpty() || detail.facebookId != null) {
                    item {
                        Section("Elsewhere") {
                            CardRows(links) { link -> SocialLinkRow(link) { link.url?.let(context::openUrl) } }
                            if (links.none { it.network == SocialLink.Network.FACEBOOK }) {
                                detail.person.facebookUrl?.let { url ->
                                    InfoRow("Open in Facebook", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(url) })
                                }
                            }
                        }
                    }
                }
                item {
                    Section("Events") {
                        if (detail.events.isEmpty()) EmptyRow("Nothing coming up yet.")
                        CardRows(detail.events, divider = 76.dp) { event ->
                            EventRow(event) { navigator.open(AppRoute.EventDetail(event.id)) }
                        }
                    }
                }
                if (detail.tracks.isNotEmpty()) {
                    item {
                        Section("Tracks") {
                            CardRows(detail.tracks, divider = 92.dp) { track ->
                                TrackRow(track, track.providerUrl?.let { url -> { context.openUrl(url) } })
                            }
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(
                                painterResource(R.drawable.powered_by_soundcloud),
                                contentDescription = "Powered by SoundCloud",
                                modifier = Modifier.height(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(detail: PersonDetail) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Box(Modifier.fillMaxWidth().height(208.dp)) {
            RemoteImage(detail.coverUrl, Modifier.fillMaxWidth().height(160.dp).clip(Radius.lg))
            Avatar(
                detail.pictureUrl,
                detail.name,
                size = 96.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .border(4.dp, tokens.surface, CircleShape),
            )
        }
        Text(detail.name, style = HotMessType.title, color = tokens.ink)
    }
}
