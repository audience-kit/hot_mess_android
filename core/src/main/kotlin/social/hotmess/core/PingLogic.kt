package social.hotmess.core

import java.time.Instant
import java.time.ZoneId

// Ping's logic that isn't the API: what the send sheet offers and what's picked, what a venue or event
// page says about friends' Pings, and reading Ping pushes. The models are in Models.kt.

/** A place the send sheet can pick: one of tonight's events, or a venue. */
sealed interface PingPick {
    val id: String

    data class EventPick(val event: Event) : PingPick {
        override val id: String get() = event.id
    }

    data class VenuePick(val venue: Venue) : PingPick {
        override val id: String get() = venue.id
    }

    companion object {
        /** What a Ping's pick was, to edit it. */
        fun of(target: PingTarget): PingPick? = target.event?.let { EventPick(it) } ?: target.venue?.let { VenuePick(it) }
    }
}

/** The picks on the send sheet, in the order they were made. */
data class PingSelection(val eventIds: List<String> = emptyList(), val venueIds: List<String> = emptyList()) {
    val isEmpty: Boolean get() = eventIds.isEmpty() && venueIds.isEmpty()
    val count: Int get() = eventIds.size + venueIds.size

    fun contains(pick: PingPick): Boolean = when (pick) {
        is PingPick.EventPick -> eventIds.any { RecordId.same(it, pick.id) }
        is PingPick.VenuePick -> venueIds.any { RecordId.same(it, pick.id) }
    }

    fun toggle(pick: PingPick): PingSelection {
        val selected = contains(pick)
        return when (pick) {
            is PingPick.EventPick ->
                copy(eventIds = if (selected) eventIds.filterNot { RecordId.same(it, pick.id) } else eventIds + pick.id)
            is PingPick.VenuePick ->
                copy(venueIds = if (selected) venueIds.filterNot { RecordId.same(it, pick.id) } else venueIds + pick.id)
        }
    }

    companion object {
        /** What the sheet starts with: the picks of the Ping it edits, plus [extra] (e.g. "Ping here"). */
        fun of(editing: Ping?, extra: PingPick? = null): PingSelection {
            val start = (editing?.targets.orEmpty().mapNotNull { PingPick.of(it) }).fold(PingSelection()) { selection, pick ->
                if (selection.contains(pick)) selection else selection.toggle(pick)
            }
            return if (extra == null || start.contains(extra)) start else start.toggle(extra)
        }
    }
}

/** What the send sheet offers: tonight's events in the locale first, then the locale's venues, and the Ping it edits. */
data class PingChoices(
    val events: List<Event> = emptyList(),
    val venues: List<Venue> = emptyList(),
    /** The user's active Ping; sending again edits it. */
    val myPing: Ping? = null,
) {
    /**
     * With each of [picks] listed, at the top of its group, when it isn't already: a venue from another
     * city opened with "Ping here", or a pick of the Ping being edited.
     */
    fun including(picks: List<PingPick>): PingChoices {
        var events = events
        var venues = venues
        picks.forEach { pick ->
            when (pick) {
                is PingPick.EventPick -> if (events.none { RecordId.same(it.id, pick.id) }) events = listOf(pick.event) + events
                is PingPick.VenuePick -> if (venues.none { RecordId.same(it.id, pick.id) }) venues = listOf(pick.venue) + venues
            }
        }
        return copy(events = events, venues = venues)
    }

    companion object {
        /** Tonight's events (the night runs to 5am, as on the calendar), soonest first, and the venues in [localeId]. */
        fun of(
            events: List<Event>,
            venues: List<Venue>,
            localeId: String?,
            myPing: Ping?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): PingChoices {
            val tonight = NightCalendar.night(now, zone)
            return PingChoices(
                events = events.filter { NightCalendar.night(it.startAt, zone) == tonight }.distinctBy { it.id }.sortedBy { it.startAt },
                venues = venues.sortedForList(localeId).filter { it.isIn(localeId) },
                myPing = myPing,
            )
        }

        /** Whether [event] is on tonight, so a Ping can pick it. */
        fun isTonight(event: Event, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
            NightCalendar.night(event.startAt, zone) == NightCalendar.night(now, zone)
    }
}

/** What a venue or event page says about friends' Pings that picked it. */
object PingStrip {
    /** "Jordan wants to come here tonight", or "3 friends want to come here tonight". */
    fun headline(pings: List<Ping>): String {
        val senders = senders(pings)
        return if (senders.size == 1) "${senders[0].firstName} wants to come here tonight" else "${senders.size} friends want to come here tonight"
    }

    /** "Jordan, Sam and Kai", for under the headline. */
    fun names(pings: List<Ping>): String = Formatting.list(senders(pings).map { it.firstName }, "and", limit = 4)

    fun senders(pings: List<Ping>): List<Friend> = pings.filterNot { it.isMine }.map { it.user }.distinctBy { RecordId.normalize(it.id) ?: it.id }
}

/** A Ping push, read from the FCM message's data. */
data class PingPush(val kind: Kind, val pingId: String?) {
    enum class Kind {
        /** To friends when a Ping is sent. */
        PING,

        /** To the sender when a friend joins. */
        PING_JOIN,
    }

    companion object {
        const val KIND = "kind"
        const val PING_ID = "ping_id"

        /** The push in [data], or null when it isn't one of Ping's. */
        fun parse(data: Map<String, String?>): PingPush? {
            val kind = when (data[KIND]) {
                "ping" -> Kind.PING
                "ping_join" -> Kind.PING_JOIN
                else -> return null
            }
            return PingPush(kind, data[PING_ID]?.takeIf { it.isNotBlank() })
        }
    }
}
