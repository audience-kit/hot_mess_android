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

    /** A chat room by its venue's or locale's id ([room]), from a link or a recent-chat push. */
    data class Chat(val room: ChatRoom) : AppRoute

    /** Which tab the route opens in. */
    val tab: AppTab
        get() = when (this) {
            is VenueDetail -> AppTab.VENUES
            is EventDetail -> AppTab.EVENTS
            is PersonDetail -> AppTab.PEOPLE
            is NowPing -> AppTab.NOW
            // A venue's room over its page; a locale's is reached from Now.
            is Chat -> if (room.kind == ChatRoom.Kind.VENUE) AppTab.VENUES else AppTab.NOW
        }
}

object DeepLink {
    /**
     * Reads `hotmess://venues/<uuid>` (the type is the host) and
     * `https://hotmess.social/venues/<uuid>`; likewise `events` and `people`. A room's link,
     * `venues/<uuid>/chat` or `locales/<uuid>/chat`, opens its chat.
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

        if (segments.getOrNull(2) == "chat") {
            return when (segments[0]) {
                "venues" -> AppRoute.Chat(ChatRoom.venue(id))
                "locales" -> AppRoute.Chat(ChatRoom.locale(id))
                else -> null
            }
        }

        return when (segments[0]) {
            "venues" -> AppRoute.VenueDetail(id)
            "events" -> AppRoute.EventDetail(id)
            "people" -> AppRoute.PersonDetail(id)
            else -> null
        }
    }
}

/**
 * A push about new messages in a chat room the user left lately (they asked for these: `recentChatPush`),
 * read from the FCM message's data. Its [url] is the room's web link, `…/venues/<id>/chat` or
 * `…/locales/<id>/chat`, which [route] opens.
 */
data class RecentChatPush(val url: String, val roomId: String?, val messageId: String?) {
    val route: AppRoute? get() = DeepLink.route(url)

    companion object {
        const val KIND = "kind"
        const val URL = "url"
        const val ROOM_ID = "room_id"
        const val MESSAGE_ID = "message_id"
        private const val RECENT_CHAT = "recent_chat"

        /** The push in [data], or null when it isn't one, or has no link the app can open. */
        fun parse(data: Map<String, String?>): RecentChatPush? {
            if (data[KIND] != RECENT_CHAT) return null
            val url = data[URL]?.takeIf { it.isNotBlank() } ?: return null
            return RecentChatPush(url, data[ROOM_ID]?.takeIf { it.isNotBlank() }, data[MESSAGE_ID]?.takeIf { it.isNotBlank() })
                .takeIf { it.route != null }
        }
    }
}
