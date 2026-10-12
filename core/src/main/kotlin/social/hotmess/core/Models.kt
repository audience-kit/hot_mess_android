package social.hotmess.core

import com.audiencekit.Admission
import com.audiencekit.Coordinate
import com.audiencekit.CoverCharge
import com.audiencekit.Presence
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

// The app's models, decoded straight from the audience's GraphQL. Every field the screens can do
// without is optional, so a null from the API leaves a gap on screen instead of failing the payload.
// Image fields (`bannerUrl`, `avatarUrl`, `photoUrl`) are the API's PhotoURL scalar: absolute, and null
// when there's no image, never "".

/** A venue: a bar, club or space the audience lists. */
@Serializable
data class Venue(
    val id: String,
    val name: String = "",
    /** A line about the place; list rows fall back to the address. */
    val description: String? = null,
    /** Metres from the position the venues were looked up near. */
    val distance: Double? = null,
    /** Its own photo of the place; null when it has none (the API never sends an empty URL). */
    val photoUrl: String? = null,
    /** Its wide image: its first banner, else [photoUrl], else its Facebook Page's cover; null when it has none. */
    val bannerUrl: String? = null,
    /** Where it is, as WKT: `POINT (<longitude> <latitude>)`. */
    val point: String? = null,
    val locale: LocaleSummary? = null,
    val hidden: Boolean = false,
    val order: Int = 0,
    /** Its chat mixin; read through [recentMessages]. */
    private val chat: ChatMixin? = null,
    /** Its profile mixin: address, phone, links and Facebook Page. Read through [address] and friends. */
    private val profile: ListingProfile? = null,
    /**
     * Its tickets mixin, holding the cover mixin (`tickets { cover }`); null when not asked for or the audience doesn't
     * take cover. Read through [coverCharge] and friends.
     */
    private val tickets: VenueTicketsMixin? = null,
) {
    private val cover: VenueCoverMixin? get() = tickets?.cover

    /** The last few lines of its chat room, oldest first; Now and the venue screen ask, and only people there (or admins) get them. */
    val recentMessages: List<ChatLine> get() = chat?.lines.orEmpty()

    val address: String? get() = profile?.address

    val phone: String? get() = profile?.phone

    val facebookId: String? get() = profile?.facebook?.facebookId

    val isLiked: Boolean get() = profile?.facebook?.isLiked == true

    /** Where else to find it, like its Instagram; only the venue screen asks. */
    val socialLinks: List<SocialLink> get() = profile?.links.orEmpty()

    /** Tonight's cover, or null when there's none; only the venue screen, the event screen and Now ask. */
    val coverCharge: CoverCharge? get() = cover?.charge

    /** The user's cover for tonight, paid or being paid; asked for alongside [coverCharge]. */
    val viewerAdmission: Admission? get() = cover?.viewerAdmission

    /** Whether the user can scan passes at its door; only the venue screen asks. */
    val canWorkDoor: Boolean get() = cover?.canWorkDoor == true

    val coordinate: Coordinate? get() = point?.let(Coordinate::fromWkt)

    /** What goes under the name in a list row. */
    val summary: String? get() = description?.takeIf { it.isNotBlank() } ?: address?.takeIf { it.isNotBlank() }

    val facebookUrl: String? get() = facebookId?.let { "https://facebook.com/$it" }

    val shareUrl: String get() = "https://hotmess.social/venues/${id.lowercase()}"
}

/** A venue's tickets mixin (`Venue.tickets`), read for the cover mixin the cover module nests in it. */
@Serializable
data class VenueTicketsMixin(val cover: VenueCoverMixin? = null)

/** A venue's cover mixin (`Venue.tickets.cover`), as much of it as the app asks for. */
@Serializable
data class VenueCoverMixin(
    val charge: CoverCharge? = null,
    val viewerAdmission: Admission? = null,
    val canWorkDoor: Boolean = false,
)

/** An event's tickets mixin (`Event.tickets`), read for the cover mixin the cover module nests in it. */
@Serializable
data class EventTicketsMixin(val cover: EventCoverMixin? = null)

/** An event's cover mixin (`Event.tickets.cover`). */
@Serializable
data class EventCoverMixin(val charge: CoverCharge? = null)

/** An event's rsvp mixin (`Event.rsvp`): the signed-in user's own answer (`mine`). */
@Serializable
data class EventRsvpMixin(val mine: Rsvp = Rsvp.UNSURE)

/** An event's venue mixin (`Event.venue`): its venue, if it has one yet. */
@Serializable
data class EventVenueMixin(val venue: Venue? = null)

/** A venue's or event's ping mixin (`Venue.ping`, `Event.ping`): friends' active Pings that pick it. */
@Serializable
data class PingFeedMixin(val friendPings: List<Ping> = emptyList())

/**
 * A venue's or locale's chat mixin (`chat { open recentMessages }`): whether the user can join its room, and the
 * room's last few lines, oldest first (a connection, asked for as `nodes`).
 */
@Serializable
data class ChatMixin(val open: Boolean = false, private val recentMessages: Connection<ChatLine>? = null) {
    val lines: List<ChatLine> get() = recentMessages?.nodes.orEmpty().filterNotNull()
}

/** An event's people mixin (`people { lineup }`): who's hosting or playing, a connection asked for as `nodes`. */
@Serializable
data class EventPeopleMixin(private val lineup: Connection<Person>? = null) {
    val people: List<Person> get() = lineup?.nodes.orEmpty().filterNotNull()
}

/**
 * An events mixin's upcoming events (`Venue.events`, `Locale.events`, `Person.events`): `upcoming` is a
 * connection, asked for as `nodes` (every event, since no `first` is given).
 */
@Serializable
data class UpcomingEvents(private val upcoming: Connection<Event>? = null) {
    val events: List<Event> get() = upcoming?.nodes.orEmpty().filterNotNull()
}

/**
 * A person's music mixin (`Person.music`): their newest tracks, a connection asked for as `nodes`.
 */
@Serializable
data class PersonMusic(private val tracks: Connection<Track>? = null) {
    val trackList: List<Track> get() = tracks?.nodes.orEmpty().filterNotNull()
}

/**
 * A venue's or person's profile mixin (`Venue.profile`, `Person.profile`), as much of it as the app asks
 * for. `socialLinks` is a connection asked for as `nodes`; [facebook] is the facebook module's mixin.
 */
@Serializable
data class ListingProfile(
    val address: String? = null,
    val phone: String? = null,
    private val socialLinks: Connection<SocialLink>? = null,
    val facebook: FacebookProfile? = null,
) {
    /** Its social links, dropping null nodes; empty when not asked for. */
    val links: List<SocialLink> get() = socialLinks?.nodes.orEmpty().filterNotNull()
}

/** A listing's Facebook Page ID and whether the user likes it (`profile.facebook`). */
@Serializable
data class FacebookProfile(val facebookId: String? = null, val isLiked: Boolean = false)

/** A Relay connection's `nodes`, which GraphQL types as nullable items. */
@Serializable
data class Connection<T>(val nodes: List<T?> = emptyList())

/** The locale a venue belongs to, as much of it as the lists need. */
@Serializable
data class LocaleSummary(val id: String, val name: String? = null, val label: String? = null) {
    val displayName: String? get() = name ?: label
}

/** A city or region of the audience, as the app remembers it. */
@Serializable
data class AppLocale(val id: String, val name: String)

/** A performer, DJ or promoter the audience follows. */
@Serializable
data class Person(
    val id: String,
    val name: String = "",
    val role: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val order: Int = 0,
    /** Their profile mixin, for the Facebook Page. Read through [facebookId] and [isLiked]. */
    private val profile: ListingProfile? = null,
) {
    val facebookId: String? get() = profile?.facebook?.facebookId

    val isLiked: Boolean get() = profile?.facebook?.isLiked == true

    val facebookUrl: String? get() = facebookId?.let { "https://facebook.com/$it" }
}

/**
 * A person with what their page shows: upcoming events, where else to find them, their tracks, the
 * people they're made of ([members], like a troupe's performers) and the ones they're part of ([groups]).
 */
@Serializable
data class PersonDetail(
    val id: String,
    val name: String = "",
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    @SerialName("events") private val eventsMixin: UpcomingEvents? = null,
    /** Their profile mixin: links and Facebook Page. Read through [socialLinks], [facebookId] and [isLiked]. */
    private val profile: ListingProfile? = null,
    @SerialName("music") private val musicMixin: PersonMusic? = null,
    val members: List<Person> = emptyList(),
    val groups: List<Person> = emptyList(),
) {
    /** Their upcoming events, from the events mixin; empty when the audience has no events. */
    val events: List<Event> get() = eventsMixin?.events.orEmpty()

    /** Their newest tracks, from the music mixin; empty when the audience has no music. */
    val tracks: List<Track> get() = musicMixin?.trackList.orEmpty()

    /** Where else to find them, from the profile mixin. */
    val socialLinks: List<SocialLink> get() = profile?.links.orEmpty()

    val facebookId: String? get() = profile?.facebook?.facebookId

    val isLiked: Boolean get() = profile?.facebook?.isLiked == true

    val person: Person
        get() = Person(id = id, name = name, avatarUrl = avatarUrl, bannerUrl = bannerUrl, profile = profile)

    /** Where fans can tip them (Cash App, Venmo), each opening that app. */
    val tipLinks: List<SocialLink>
        get() = socialLinks.filter { it.tipApp != null && it.url != null }

    /** Their social profiles, without the tip links. */
    val profileLinks: List<SocialLink>
        get() = socialLinks.filter { it.tipApp == null }
}

@Serializable
data class SocialLink(
    val id: String,
    val handle: String = "",
    val provider: String = "",
    val url: String? = null,
) {
    /** The network, for picking a glyph: facebook, instagram, soundcloud, spotify, apple music, x, or null for anything else. */
    val network: Network?
        get() = when (provider.lowercase(Locale.ROOT)) {
            "facebook" -> Network.FACEBOOK
            "instagram" -> Network.INSTAGRAM
            "soundcloud" -> Network.SOUNDCLOUD
            "spotify" -> Network.SPOTIFY
            "apple_music" -> Network.APPLE_MUSIC
            "twitter", "x" -> Network.X
            else -> null
        }

    /** What the row says: the service for Spotify and Apple Music, whose handles are URL paths, else "/handle". */
    val label: String
        get() = when (network) {
            Network.SPOTIFY -> "Spotify"
            Network.APPLE_MUSIC -> "Apple Music"
            else -> "/$handle"
        }

    enum class Network { FACEBOOK, INSTAGRAM, SOUNDCLOUD, SPOTIFY, APPLE_MUSIC, X }

    /** The app fans tip them with, for a Cash App or Venmo link; null for a social profile. */
    val tipApp: String?
        get() = when (provider.lowercase(Locale.ROOT)) {
            "cashapp" -> "Cash App"
            "venmo" -> "Venmo"
            else -> null
        }
}

@Serializable
data class Track(
    val id: String,
    val title: String = "",
    val provider: String? = null,
    val providerUrl: String? = null,
    val waveformUrl: String? = null,
    val artworkUrl: String? = null,
)

/** Whether the signed-in user is going. GraphQL sends the enum's names. */
@Serializable(with = RsvpSerializer::class)
enum class Rsvp {
    ATTENDING, MAYBE, DECLINED, UNSURE;

    companion object {
        /** The choices the RSVP picker offers, in order. */
        val selectable: List<Rsvp> = listOf(ATTENDING, MAYBE, DECLINED)
    }
}

@Serializable
data class Event(
    val id: String,
    val name: String = "",
    @Serializable(with = InstantSerializer::class) val startAt: Instant,
    @Serializable(with = InstantSerializer::class) val endAt: Instant? = null,
    val facebookId: String? = null,
    val bannerUrl: String? = null,
    val isFeatured: Boolean = false,
    /** Its rsvp mixin (`rsvp { mine }`); read the signed-in user's answer through [rsvp]. */
    @SerialName("rsvp") private val rsvpMixin: EventRsvpMixin? = null,
    /** Its venue mixin; read the venue through [venue]. */
    @SerialName("venue") private val venueMixin: EventVenueMixin? = null,
    /** Its people mixin; read through [people]. Only the event screen asks for it. */
    @SerialName("people") private val peopleMixin: EventPeopleMixin? = null,
    /** Its tickets mixin, holding the cover mixin; read through [coverCharge]. Only the event screen asks. */
    private val tickets: EventTicketsMixin? = null,
    /** Its ping mixin; read through [friendPings]. Only the event screen asks for it. */
    @SerialName("ping") private val pingFeed: PingFeedMixin? = null,
) {
    /** Whether the signed-in user is going, from the rsvp mixin; undecided when the event wasn't asked for it. */
    val rsvp: Rsvp get() = rsvpMixin?.mine ?: Rsvp.UNSURE

    /** With the signed-in user's answer set to [rsvp], as the picker shows it before the API answers. */
    fun withRsvp(rsvp: Rsvp): Event = copy(rsvpMixin = EventRsvpMixin(mine = rsvp))

    /** Where it is; null when it has no venue yet ("To be announced"). */
    val venue: Venue? get() = venueMixin?.venue

    /** Who's hosting or playing; only the event screen asks for it. */
    val people: List<Person> get() = peopleMixin?.people.orEmpty()

    /** Friends' active Pings that pick this event; only the event screen asks for it. */
    val friendPings: List<Ping> get() = pingFeed?.friendPings.orEmpty()

    /** With [ping] in place of the copy among its friends' Pings. */
    fun replacingFriendPing(ping: Ping): Event =
        copy(pingFeed = PingFeedMixin(friendPings.map { if (it.id == ping.id) ping else it }))

    /** The cover at its venue that night; null when there's none or it sells tickets. Only the event screen asks. */
    val coverCharge: CoverCharge? get() = tickets?.cover?.charge

    val facebookUrl: String? get() = facebookId?.let { "https://facebook.com/events/$it" }

    val shareUrl: String get() = "https://hotmess.social/events/${id.lowercase()}"
}

/**
 * A Facebook friend who also uses the app (from the user_friends permission). [presence] is whether they
 * can be reached now, where the query asks for it (Now's friends); null where it doesn't.
 */
@Serializable
data class Friend(
    val id: String,
    val name: String = "",
    val facebookId: String? = null,
    val presence: Presence? = null,
    /** Their picture; null when they have none, and friends then show their initials. */
    val avatarUrl: String? = null,
) {
    val firstName: String get() = name.firstNameForDisplay()

    /** Opens a Messenger thread with them, when they have a Facebook ID. */
    val messengerUrl: String? get() = facebookId?.let { "https://m.me/$it" }
}

/**
 * The friends module's mixin on a venue or locale (`friends { list { nodes } venues { nodes } }`): your
 * friends out there lately and, on a locale, how many at each of its venues. Null where the module is off.
 */
@Serializable
data class FriendsMixin(
    @SerialName("list") private val listNodes: Connection<Friend>? = null,
    @SerialName("venues") private val venueNodes: Connection<FriendVenue>? = null,
) {
    val list: List<Friend> get() = listNodes?.nodes.orEmpty().filterNotNull()
    val venues: List<FriendVenue> get() = venueNodes?.nodes.orEmpty().filterNotNull()
}

/** A venue where some of your friends have been lately, with how many. */
@Serializable
data class FriendVenue(val venue: Venue, val friendCount: Int = 0, val friends: List<Friend> = emptyList())

/**
 * A line someone sent in a venue's or locale's chat room, as Now previews it. [message] is the line in
 * words, a rich message's summary included ("Shared an event: Sunset Social"), which is all a preview shows.
 */
@Serializable
data class ChatLine(
    val id: String,
    val message: String = "",
    /** The name the room showed for the sender: in full for friends, "First L." for anyone else, or the venue's. */
    val name: String? = null,
    val userId: String = "",
    val avatarUrl: String? = null,
    @Serializable(with = InstantSerializer::class) val sentAt: Instant? = null,
    /** Whether the sender can be reached now; OFFLINE unless they're a friend (or the viewer is an admin). */
    val presence: Presence? = null,
    /** GraphQL's ChatRole (`VENUE`, `HOST`, `STAFF`), or null for everyone else; read [role]. */
    @SerialName("role") val roleName: String? = null,
    /** GraphQL's ChatKind; read [kind]. */
    @SerialName("kind") val kindName: String? = null,
    /** When a special stops showing. */
    @Serializable(with = InstantSerializer::class) val endsAt: Instant? = null,
    /** Posted as the venue: [name] and [avatarUrl] are the venue's. */
    val postedAsVenue: Boolean = false,
    /** Posted by talent as their listing (role HOST): [name] and [avatarUrl] are the person's. */
    val postedAsPerson: Boolean = false,
) {
    val role: ChatRole? get() = ChatRole.parse(roleName)

    /** Posted as the venue or as a person: a listing, so no presence and no friend's name for the sender. */
    val isFromListing: Boolean get() = postedAsVenue || postedAsPerson
    val kind: ChatKind get() = ChatKind.parse(kindName)

    /** Whether the room still shows it: a special stops at its end time. */
    fun isShowing(at: Instant = Instant.now()): Boolean = !(kind == ChatKind.SPECIAL && endsAt != null && !endsAt.isAfter(at))
}

/**
 * A Ping: someone saying "I want to go out tonight", with the venues and events they picked, in their
 * order (none means anywhere). Only their Facebook friends on the app see it, and it clears at 5am.
 */
@Serializable
data class Ping(
    val id: String,
    /** Who sent it. */
    val user: Friend,
    /** The sender's own words, which may hold emoji. */
    val note: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null,
    /** The next 5am in the Ping's locale. */
    @Serializable(with = InstantSerializer::class) val expiresAt: Instant? = null,
    /** Sent by the signed-in user. */
    val isMine: Boolean = false,
    /** The signed-in user is in, on any pick or for the Ping as a whole. */
    val joined: Boolean = false,
    val targets: List<PingTarget> = emptyList(),
    /** Everyone in, oldest first. */
    val joins: List<PingJoin> = emptyList(),
    /** Who can see it: the sender's friends, or friends of anyone in its circle. */
    val reach: PingReach = PingReach.FRIENDS,
    /** The circle member the viewer knows, when the sender isn't their friend; null otherwise. */
    val via: Friend? = null,
) {
    /** The sender and everyone in, once each: whose faces the card shows. */
    val circle: List<Friend> get() = (listOf(user) + joins.map { it.user }).distinctBy { RecordId.normalize(it.id) ?: it.id }

    /** "via Sam", when the viewer sees it through a circle member. */
    val viaText: String? get() = via?.let { "via ${it.firstName}" }

    /** No picks: "anywhere tonight?". */
    val isAnywhere: Boolean get() = targets.isEmpty()

    /** The note without surrounding space, or null when there's nothing to show. */
    val noteText: String? get() = note?.trim()?.takeIf { it.isNotEmpty() }

    /** "Drag Bingo or The Wildrose", or "Anywhere tonight?" with no picks. */
    val placesSummary: String
        get() = if (isAnywhere) ANYWHERE else Formatting.list(targets.map { it.name }.filter { it.isNotBlank() }, "or")

    /** Everyone in, once each, oldest first. */
    val people: List<Friend> get() = joins.map { it.user }.distinctBy { RecordId.normalize(it.id) ?: it.id }

    /** "Sam and Alex are in", or null when no one is yet. */
    val whoIsIn: String?
        get() {
            val names = people.map { it.firstName }
            if (names.isEmpty()) return null
            return Formatting.list(names, "and", limit = 3) + if (names.size == 1) " is in" else " are in"
        }

    /** The pick of the venue [venueId], directly or through one of its events. */
    fun pickForVenue(venueId: String): PingTarget? =
        targets.firstOrNull { RecordId.same(it.venue?.id, venueId) }
            ?: targets.firstOrNull { RecordId.same(it.event?.venue?.id, venueId) }

    /** The pick of the event [eventId]. */
    fun pickForEvent(eventId: String): PingTarget? = targets.firstOrNull { RecordId.same(it.event?.id, eventId) }

    /**
     * Whether the signed-in user ([userId]) is in for [target], or for the Ping as a whole when it's null.
     * Without [userId] only [joined] is known, which is enough for a Ping with one pick or none.
     */
    fun isIn(target: PingTarget?, userId: String?): Boolean {
        if (!joined) return false
        val mine = joins.filter { RecordId.same(it.user.id, userId) }
        if (target == null) {
            return if (userId == null) isAnywhere else mine.any { it.targetId == null } || (isAnywhere && mine.isNotEmpty())
        }
        if (userId == null) return targets.size == 1
        return mine.any { RecordId.same(it.targetId, target.id) } || target.joins.any { RecordId.same(it.user.id, userId) }
    }

    companion object {
        const val ANYWHERE = "Anywhere tonight?"
    }
}

/** Who can see a Ping. GraphQL sends the enum's names; anything unknown reads as [FRIENDS]. */
@Serializable(with = PingReachSerializer::class)
enum class PingReach {
    /** Only the sender's friends. */
    FRIENDS,

    /** Friends of anyone in the circle, so it spreads as people join. */
    FRIENDS_OF_CIRCLE;

    /** How the send sheet and your own card name it. */
    val title: String
        get() = when (this) {
            FRIENDS -> "My friends"
            FRIENDS_OF_CIRCLE -> "Friends of the circle"
        }
}

/** One place a Ping picks: a venue or an event, never both. */
@Serializable
data class PingTarget(
    val id: String,
    val venue: Venue? = null,
    val event: Event? = null,
    /** Who is in for this pick. */
    val joins: List<PingJoin> = emptyList(),
) {
    val name: String get() = venue?.name ?: event?.name ?: ""
}

/** Someone in on a Ping: for one pick ([targetId]), or for the Ping as a whole when that's null. */
@Serializable
data class PingJoin(val id: String, val user: Friend, val targetId: String? = null)

/** What's happening where the device is, from `reportLocation`. */
@Serializable
data class Now(
    val title: String = "",
    val imageUrl: String? = null,
    /** The venue the user is in, when they're in one. */
    val venue: Venue? = null,
    /** Venues nearby, nearest first; null when the user is in a venue. */
    val venues: List<Venue>? = null,
    val events: List<Event> = emptyList(),
    /** Friends out at the venue the user is in, or at venues in their locale. */
    val friends: List<Friend> = emptyList(),
    /** Where friends have been lately, most friends first, when the user isn't in a venue. */
    val friendVenues: List<FriendVenue> = emptyList(),
    /** The locale the user is in, or nearest, with its chat room. */
    val locale: NowLocale? = null,
    /** The signed-in user's active Ping, if they sent one tonight. */
    val myPing: Ping? = null,
    /** Friends' active Pings, newest first. */
    val friendPings: List<Ping> = emptyList(),
) {
    val isNearVenues: Boolean get() = venues != null

    /** With [ping] in place of the copy Now had of it: as your own Ping, or among your friends'. */
    fun replacingPing(ping: Ping): Now =
        if (ping.isMine) {
            copy(myPing = ping)
        } else {
            copy(friendPings = friendPings.map { if (it.id == ping.id) ping else it })
        }
}

/**
 * The user's locale as Now shows it. [chatOpen] says whether they can join its chat room: they're out in
 * the locale and not at a venue, or they're an admin. [recentMessages] are its last few lines, oldest
 * first, sent only to someone in the locale.
 */
@Serializable
data class NowLocale(
    val id: String,
    val name: String? = null,
    val chatOpen: Boolean = false,
    val recentMessages: List<ChatLine> = emptyList(),
)

/**
 * What `GET /v1/status` says about this app (`clients.android`): the oldest version still served, the
 * newest one, and where to get it. Versions are dotted numbers (`1.0.15`), compared part by part.
 */
@Serializable
data class VersionInfo(
    val minimumVersion: String? = null,
    val latestVersion: String? = null,
    val storeUrl: String? = null,
) {
    /** Whether a build whose version name is [version] is still served. Unknown or unparseable versions are. */
    fun supports(version: String): Boolean {
        val minimum = parse(minimumVersion ?: return true) ?: return true
        val current = parse(version) ?: return true
        return compare(current, minimum) >= 0
    }

    companion object {
        /** The numeric parts of `1.2.3-staging`: `[1, 2, 3]`; null when it doesn't start with a number. */
        fun parse(version: String): List<Int>? {
            val numeric = version.trim().takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
            val parts = numeric.split('.').map { it.toIntOrNull() ?: return null }
            return parts.takeIf { it.isNotEmpty() }
        }

        /** Compares part by part, a missing part counting as 0 (`1.2` equals `1.2.0`). */
        fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (difference != 0) return difference
            }
            return 0
        }
    }
}

/** The signed-in user, as the app shows them, with their profile photo when they have one. */
data class User(val id: String, val name: String, val avatarUrl: String? = null) {
    val firstName: String get() = name.firstNameForDisplay()
    val initials: String get() = name.initialsForDisplay()
}

/** Record IDs are UUIDs, in whichever case they arrive. Normalizes one to a lowercase UUID. */
object RecordId {
    fun normalize(id: String): String? = runCatching { UUID.fromString(id).toString() }.getOrNull()

    /** Whether two IDs name the same record, whatever case each comes in. False when either is null. */
    fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.equals(b, ignoreCase = true)) return true
        val normalized = normalize(a) ?: return false
        return normalized == normalize(b)
    }
}

/** Decodes the API's timestamps: ISO 8601 with or without fractional seconds, or `…T21:00:00.000-0700`. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("social.hotmess.Instant", PrimitiveKind.STRING)

    private val compactOffset = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]xx", Locale.ROOT)

    fun parse(text: String): Instant? =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(text, compactOffset).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return parse(text) ?: throw kotlinx.serialization.SerializationException("Unrecognised date: $text")
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

/** Reads an RSVP in any case, treating anything unknown as undecided. */
object RsvpSerializer : KSerializer<Rsvp> {
    override val descriptor = PrimitiveSerialDescriptor("social.hotmess.Rsvp", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Rsvp {
        val raw = decoder.decodeString().uppercase(Locale.ROOT)
        return Rsvp.entries.firstOrNull { it.name == raw } ?: Rsvp.UNSURE
    }

    override fun serialize(encoder: Encoder, value: Rsvp) = encoder.encodeString(value.name)
}

/** Reads a Ping's reach in any case, treating anything unknown as friends only. */
object PingReachSerializer : KSerializer<PingReach> {
    override val descriptor = PrimitiveSerialDescriptor("social.hotmess.PingReach", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PingReach {
        val raw = decoder.decodeString().uppercase(Locale.ROOT)
        return PingReach.entries.firstOrNull { it.name == raw } ?: PingReach.FRIENDS
    }

    override fun serialize(encoder: Encoder, value: PingReach) = encoder.encodeString(value.name)
}

/** Up to two initials, for an avatar placeholder. */
fun String.initialsForDisplay(): String =
    split(' ').filter { it.isNotEmpty() }.take(2).map { it.first().uppercaseChar() }.joinToString("")

/** The first word of a name. */
fun String.firstNameForDisplay(): String = split(' ').firstOrNull { it.isNotEmpty() } ?: this
