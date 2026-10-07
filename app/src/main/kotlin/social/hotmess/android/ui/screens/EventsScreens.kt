package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.payments.CoverCheckout
import social.hotmess.android.payments.CoverCheckoutFailure
import social.hotmess.android.payments.rememberCoverCheckout
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Card
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.CoverRow
import social.hotmess.android.ui.components.EventRow
import social.hotmess.android.ui.components.FeaturedEventRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.HeroImage
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.NightCalendarView
import social.hotmess.android.ui.components.PersonRow
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.RsvpPicker
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.VenueRow
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.share
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.CoverOffer
import social.hotmess.core.Event
import social.hotmess.core.EventListing
import social.hotmess.core.Formatting
import social.hotmess.core.NightCalendar
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
                    Feed {
                        item(key = "calendar") {
                            Card { NightCalendarView(nights, selectedNight) { selectedNight = it } }
                        }
                        if (night != null) {
                            item(key = "night-${night.date}") {
                                Section(night.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))) {
                                    if (night.events.isEmpty()) {
                                        Text(
                                            "Nothing on this night yet.",
                                            style = HotMessType.body,
                                            color = tokens.inkMuted,
                                            modifier = Modifier.padding(Space.s4),
                                        )
                                    } else {
                                        CardRows(night.events, divider = 76.dp) { event ->
                                            EventRow(event) { navigator.open(AppRoute.EventDetail(event.id)) }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(listing.sections.filter { it.events.isNotEmpty() }, key = { it.id }) { section ->
                                Section(section.title) {
                                    if (section.id == "featured") {
                                        CardRows(section.events, divider = 0.dp) { event ->
                                            FeaturedEventRow(event) { navigator.open(AppRoute.EventDetail(event.id)) }
                                        }
                                    } else {
                                        CardRows(section.events, divider = 76.dp) { event ->
                                            EventRow(event) { navigator.open(AppRoute.EventDetail(event.id)) }
                                        }
                                    }
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

    val checkout = rememberCoverCheckout { pass ->
        loader.load(id, refresh = true) { graph.api.event(id) }
        navigator.openPass(pass.id)
    }
    val checkoutState by checkout.state.collectAsStateWithLifecycle()

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
    ScreenScaffold(
        title = event?.name ?: "Event",
        onBack = navigator::back,
        actions = {
            if (event != null) {
                IconButton(onClick = { context.share(event.shareUrl, event.name) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share")
                }
            }
        },
    ) {
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.event(id) } }) { loaded ->
            Feed {
                loaded.coverPhotoUrl?.let { url -> item { HeroImage(url) } }
                item {
                    Section(null) {
                        Text(
                            loaded.name,
                            style = HotMessType.title,
                            color = tokens.ink,
                            modifier = Modifier.padding(Space.s4),
                        )
                    }
                }
                item { Section("Your RSVP") { RsvpPicker(loaded.rsvp, ::choose) } }
                val venue = loaded.venue
                CoverOffer.of(loaded)?.let { offer ->
                    item {
                        Section("Cover") {
                            CoverRow(
                                offer,
                                paying = checkoutState == CoverCheckout.State.Working,
                                onPay = { venue?.let { checkout.pay(it.id, it.name) } },
                                onShowPass = { pass ->
                                    graph.passes.put(pass)
                                    navigator.openPass(pass.id)
                                },
                            )
                        }
                    }
                }
                item {
                    Section("When") {
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
                item {
                    Section("Venue") {
                        val venue = loaded.venue
                        if (venue != null) {
                            VenueRow(venue) { navigator.open(AppRoute.VenueDetail(venue.id)) }
                        } else {
                            InfoRow(Formatting.TO_BE_ANNOUNCED)
                        }
                    }
                }
                if (loaded.people.isNotEmpty()) {
                    item {
                        Section("Lineup") {
                            CardRows(loaded.people, divider = 72.dp) { person ->
                                PersonRow(person) { navigator.open(AppRoute.PersonDetail(person.id)) }
                            }
                        }
                    }
                }
            }
        }
    }

    CoverCheckoutFailure(checkout)

    rsvpError?.let { message ->
        AlertDialog(
            onDismissRequest = { rsvpError = null },
            confirmButton = { TextButton(onClick = { rsvpError = null }) { Text("OK") } },
            title = { Text("Couldn't save RSVP") },
            text = { Text(message) },
        )
    }
}
