package social.hotmess.android.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
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
import social.hotmess.android.ui.components.NightCalendarView
import social.hotmess.android.ui.components.PersonCard
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.RsvpPicker
import social.hotmess.android.ui.components.VenueCard
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.share
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.Event
import social.hotmess.core.EventListing
import social.hotmess.core.Formatting
import social.hotmess.core.NightCalendar
import social.hotmess.core.PhotoTone
import social.hotmess.core.PingChoices
import social.hotmess.core.PingPick
import social.hotmess.core.Rsvp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What's on in the city you're in: a four-week calendar of how busy each night is, then a couple of
 * featured nights and everything upcoming, or just the night picked on the calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val loader = rememberLoader<EventListing>("events")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { graph.location.refreshLocale() }

    val current = locale
    ScreenScaffold(title = current?.name?.let { "Events in $it" } ?: "Events") {
        if (current == null) {
            Message(
                icon = Icons.Rounded.LocationOff,
                title = "Where are you?",
                text = "We need your location to find events near you.",
            )
            return@ScreenScaffold
        }
        var selectedNight by remember(current.id) { mutableStateOf<LocalDate?>(null) }
        LaunchedEffect(current.id) { loader.load(current.id) { graph.api.events(current.id) } }

        LoadStateView(state, onRetry = { loader.load(current.id) { graph.api.events(current.id) } }) { listing ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(current.id, refresh = true) { graph.api.events(current.id) } }) {
                if (listing.isEmpty) {
                    Message(Icons.Rounded.CalendarMonth, "Nothing on yet", "There are no upcoming events in ${current.name}. Check back soon.")
                } else {
                    val nights = remember(listing) {
                        NightCalendar.of(listing.allEvents, firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek)
                    }
                    val night = nights.nights.firstOrNull { it.date == selectedNight }
                    val open = { event: Event -> navigator.open(AppRoute.EventDetail(event.id)) }
                    Feed {
                        item(key = "calendar") {
                            DetailSection { NightCalendarView(nights, selectedNight) { selectedNight = it } }
                        }
                        if (night != null) {
                            val title = night.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
                            if (night.events.isEmpty()) {
                                item(key = "night-${night.date}") {
                                    DetailSection(title) { EmptyRow("Nothing on this night yet.") }
                                }
                            } else {
                                cardSection("night-${night.date}", title, night.events, key = { it.id }) { event ->
                                    EventCard(event, onClick = { open(event) })
                                }
                            }
                        } else {
                            listing.sections.filter { it.events.isNotEmpty() }.forEach { section ->
                                cardSection(section.id, section.title, section.events, key = { it.id }) { event ->
                                    EventCard(event, onClick = { open(event) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One event: its cover, your RSVP, when and where, and who's playing. */
@Composable
fun EventScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoader<Event>("event-$id")
    val state by loader.state.collectAsStateWithLifecycle()
    var rsvpError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) { loader.load(id) { graph.api.event(id) } }

    val userId = rememberUserId()
    var pingHere by rememberSaveable { mutableStateOf(false) }
    val actions = rememberPingActions { ping ->
        loader.update { event -> event.copy(friendPings = event.friendPings.map { if (it.id == ping.id) ping else it }) }
        graph.pingUpdated(ping.id)
    }

    fun choose(rsvp: Rsvp) {
        val previous = state.valueOrNull?.rsvp ?: return
        if (rsvp == previous) return
        loader.update { it.copy(rsvp = rsvp) }
        scope.launch {
            try {
                graph.api.setRsvp(rsvp, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loader.update { it.copy(rsvp = previous) }
                rsvpError = ApiError.of(e).message ?: "Try again in a moment."
            }
        }
    }

    val event = state.valueOrNull
    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    HeroScaffold(
        title = event?.name ?: "Event",
        tone = heroTone,
        collapsed = event == null || scrolled,
        onBack = navigator::back,
        actions = {
            if (event != null) {
                IconButton(onClick = { context.share(event.shareUrl, event.name) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share")
                }
            }
        },
    ) { topInset ->
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.event(id) } }) { loaded ->
            HeroFeed(
                listState,
                hero = {
                    HeroHeader(loaded.coverPhotoUrl, topInset, onTone = { heroTone = it }) {
                        Text(
                            "${Formatting.monthAbbreviation(loaded.startAt)} ${Formatting.dayOfMonth(loaded.startAt)} · ${Formatting.shortTime(loaded.startAt)}".uppercase(),
                            style = HotMessType.caption,
                        )
                        Text(
                            loaded.name,
                            style = HotMessType.title,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        loaded.venue?.name?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = HotMessType.subheading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
            ) {
                item {
                    // Pings are tonight only, so only tonight's events can be picked.
                    PingStripSection(
                        pings = loaded.friendPings,
                        pickOf = { it.pickForEvent(loaded.id) },
                        userId = userId,
                        actions = actions,
                        onPingHere = if (PingChoices.isTonight(loaded)) ({ pingHere = true }) else null,
                    )
                }
                item { DetailSection("Your RSVP") { RsvpPicker(loaded.rsvp, ::choose) } }
                item {
                    DetailSection("When") {
                        InfoRow("Starts", Formatting.dateTime(loaded.startAt))
                        loaded.endAt?.let {
                            RowDivider()
                            InfoRow("Ends", Formatting.dateTime(it))
                        }
                        loaded.facebookUrl?.let { url ->
                            RowDivider()
                            InfoRow("Open in Facebook", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(url) })
                        }
                    }
                }
                val venue = loaded.venue
                if (venue != null) {
                    cardSection("venue", "Venue", listOf(venue), key = { it.id }) {
                        VenueCard(it, onClick = { navigator.open(AppRoute.VenueDetail(it.id)) })
                    }
                } else {
                    item { DetailSection("Venue") { EmptyRow(Formatting.TO_BE_ANNOUNCED) } }
                }
                cardSection("lineup", "Lineup", loaded.people, key = { it.id }) { person ->
                    PersonCard(person, onClick = { navigator.open(AppRoute.PersonDetail(person.id)) })
                }
            }
        }
    }

    if (pingHere && event != null) {
        PingSheet(
            preselect = PingPick.EventPick(event),
            onDismiss = { pingHere = false },
            onSent = { graph.pingUpdated(it.id) },
        )
    }

    rsvpError?.let { message ->
        AlertDialog(
            onDismissRequest = { rsvpError = null },
            confirmButton = { TextButton(onClick = { rsvpError = null }) { Text("OK") } },
            title = { Text("Couldn't save RSVP") },
            text = { Text(message) },
        )
    }
}
