package social.hotmess.core

/** A titled run of events on the Events tab. */
data class EventSection(val id: String, val title: String, val events: List<Event>)

data class EventListing(val sections: List<EventSection> = emptyList()) {
    val isEmpty: Boolean get() = sections.all { it.events.isEmpty() }

    /** Every event across the sections, once each, soonest first. */
    val allEvents: List<Event> get() = sections.flatMap { it.events }.distinctBy { it.id }.sortedBy { it.startAt }

    companion object {
        /**
         * Splits upcoming events the way the iOS app does: up to two events with a cover photo as
         * "Featured", then every event, soonest first, as "Upcoming".
         */
        fun upcoming(events: List<Event>): EventListing {
            val upcoming = events.sortedBy { it.startAt }
            val featured = upcoming.filter { it.coverPhotoUrl != null }.take(2)
            return EventListing(
                listOf(
                    EventSection(id = "featured", title = "Featured", events = featured),
                    EventSection(id = "upcoming", title = "Upcoming", events = upcoming),
                ).filter { it.events.isNotEmpty() },
            )
        }
    }
}
