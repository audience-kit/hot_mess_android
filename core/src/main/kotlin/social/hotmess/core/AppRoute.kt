package social.hotmess.core

import java.net.URI

/** The tabs, in the order the bottom bar shows them. */
enum class AppTab { NOW, EVENTS, VENUES, PEOPLE, ME }

/** A screen a link or a row can open. IDs are lowercase UUIDs. */
sealed interface AppRoute {
    data class VenueDetail(val id: String) : AppRoute
    data class EventDetail(val id: String) : AppRoute
    data class PersonDetail(val id: String) : AppRoute

    /** The Now tab, opened from a Ping push; [pingId] names the Ping it was about. */
    data class NowPing(val pingId: String? = null) : AppRoute

    /** Which tab the route opens in. */
    val tab: AppTab
        get() = when (this) {
            is VenueDetail -> AppTab.VENUES
            is EventDetail -> AppTab.EVENTS
            is PersonDetail -> AppTab.PEOPLE
            is NowPing -> AppTab.NOW
        }
}

object DeepLink {
    /**
     * Reads `hotmess://venues/<uuid>` (the type is the host) and
     * `https://hotmess.social/venues/<uuid>`; likewise `events` and `people`.
     */
    fun route(url: String): AppRoute? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = if (uri.scheme == "hotmess") {
            listOfNotNull(uri.host, uri.path).flatMap { it.split('/') }
        } else {
            uri.path.orEmpty().split('/')
        }.filter { it.isNotEmpty() }

        if (segments.size < 2) return null
        val id = runCatching { java.util.UUID.fromString(segments[1]).toString() }.getOrNull() ?: return null

        return when (segments[0]) {
            "venues" -> AppRoute.VenueDetail(id)
            "events" -> AppRoute.EventDetail(id)
            "people" -> AppRoute.PersonDetail(id)
            else -> null
        }
    }
}
