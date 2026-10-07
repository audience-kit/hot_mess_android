package social.hotmess.core

import com.audiencekit.Coordinate
import com.audiencekit.GlobalID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
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

/** A venue: a bar, club or space the audience lists. */
@Serializable
data class Venue(
    val id: String,
    val name: String = "",
    val address: String? = null,
    /** A line about the place; list rows fall back to the address. */
    val description: String? = null,
    val phone: String? = null,
    val facebookId: String? = null,
    /** Metres from the position the venues were looked up near. */
    val distance: Double? = null,
    @Serializable(with = BlankAsNullSerializer::class) val photoUrl: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val heroUrl: String? = null,
    /** Where it is, as WKT: `POINT (<longitude> <latitude>)`. */
    val point: String? = null,
    val isLiked: Boolean = false,
    val locale: LocaleSummary? = null,
    val hidden: Boolean = false,
    val order: Int = 0,
    /** The last few lines of its chat room, oldest first; only Now asks, and only people there get them. */
    val recentMessages: List<ChatLine> = emptyList(),
) {
    val coordinate: Coordinate? get() = point?.let(Coordinate::fromWkt)

    /** What goes under the name in a list row. */
    val summary: String? get() = description?.takeIf { it.isNotBlank() } ?: address?.takeIf { it.isNotBlank() }

    val facebookUrl: String? get() = facebookId?.let { "https://facebook.com/$it" }

    val shareUrl: String get() = "https://hotmess.social/venues/${id.lowercase()}"
}

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
    val facebookId: String? = null,
    val role: String? = null,
    val isLiked: Boolean = false,
    @Serializable(with = BlankAsNullSerializer::class) val pictureUrl: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val coverUrl: String? = null,
    val order: Int = 0,
) {
    val facebookUrl: String? get() = facebookId?.let { "https://facebook.com/$it" }
}

/** A person with what their page shows: upcoming events, where else to find them and their tracks. */
@Serializable
data class PersonDetail(
    val id: String,
    val name: String = "",
    val facebookId: String? = null,
    val isLiked: Boolean = false,
    @Serializable(with = BlankAsNullSerializer::class) val pictureUrl: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val coverUrl: String? = null,
    val events: List<Event> = emptyList(),
    val socialLinks: List<SocialLink> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val person: Person
        get() = Person(id = id, name = name, facebookId = facebookId, isLiked = isLiked, pictureUrl = pictureUrl, coverUrl = coverUrl)
}

@Serializable
data class SocialLink(
    val id: String,
    val handle: String = "",
    val provider: String = "",
    @Serializable(with = BlankAsNullSerializer::class) val url: String? = null,
) {
    /** The network, for picking a glyph: facebook, instagram, soundcloud, x, or null for anything else. */
    val network: Network?
        get() = when (provider.lowercase(Locale.ROOT)) {
            "facebook" -> Network.FACEBOOK
            "instagram" -> Network.INSTAGRAM
            "soundcloud" -> Network.SOUNDCLOUD
            "twitter", "x" -> Network.X
            else -> null
        }

    enum class Network { FACEBOOK, INSTAGRAM, SOUNDCLOUD, X }
}

@Serializable
data class Track(
    val id: String,
    val title: String = "",
    val provider: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val providerUrl: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val waveformUrl: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val artworkUrl: String? = null,
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
    @Serializable(with = BlankAsNullSerializer::class) val coverPhotoUrl: String? = null,
    val isFeatured: Boolean = false,
    @SerialName("viewerRsvp") val rsvp: Rsvp = Rsvp.UNSURE,
    val venue: Venue? = null,
    /** Who's hosting or playing; only the event screen asks for it. */
    val people: List<Person> = emptyList(),
) {
    val facebookUrl: String? get() = facebookId?.let { "https://facebook.com/events/$it" }

    val shareUrl: String get() = "https://hotmess.social/events/${id.lowercase()}"
}

/** A Facebook friend who also uses the app (from the user_friends permission). */
@Serializable
data class Friend(val id: String, val name: String = "", val facebookId: String? = null) {
    val firstName: String get() = name.firstNameForDisplay()

    /** Opens a Messenger thread with them, when they have a Facebook ID. */
    val messengerUrl: String? get() = facebookId?.let { "https://m.me/$it" }
}

/** A line someone sent in a venue's chat room, as Now previews it. */
@Serializable
data class ChatLine(
    val id: String,
    val message: String = "",
    /** The name the room showed for the sender. */
    val name: String? = null,
    val userId: String = "",
    @Serializable(with = BlankAsNullSerializer::class) val avatarUrl: String? = null,
    @Serializable(with = InstantSerializer::class) val sentAt: Instant? = null,
)

/** What's happening where the device is, from `reportLocation`. */
@Serializable
data class Now(
    val title: String = "",
    @Serializable(with = BlankAsNullSerializer::class) val imageUrl: String? = null,
    /** The venue the user is in, when they're in one. */
    val venue: Venue? = null,
    /** Venues nearby, nearest first; null when the user is in a venue. */
    val venues: List<Venue>? = null,
    val events: List<Event> = emptyList(),
    /** Friends out at the venue the user is in, or at venues in their locale. */
    val friends: List<Friend> = emptyList(),
) {
    val isNearVenues: Boolean get() = venues != null
}

/** The oldest build the API still serves, from `POST /`. */
@Serializable
data class VersionInfo(
    @SerialName("minimum_build") val minimumBuild: Int = 0,
    @SerialName("current_version") val currentVersion: String? = null,
    @SerialName("minimum_version") val minimumVersion: String? = null,
)

/** The signed-in user, as the app shows them. */
data class User(val id: String, val name: String) {
    val firstName: String get() = name.firstNameForDisplay()
    val initials: String get() = name.initialsForDisplay()
}

/** Record IDs come as UUIDs, or as Relay global IDs wrapping one. Normalizes both to a lowercase UUID. */
object RecordId {
    fun normalize(id: String): String? {
        runCatching { return UUID.fromString(id).toString() }
        val global = GlobalID.parse(id) ?: return null
        return runCatching { UUID.fromString(global.modelId).toString() }.getOrNull()
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

/** A URL field where the API sometimes sends an empty string for "none". */
object BlankAsNullSerializer : KSerializer<String?> {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor = PrimitiveSerialDescriptor("social.hotmess.BlankAsNull", PrimitiveKind.STRING).nullable

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): String? =
        if (decoder.decodeNotNullMark()) decoder.decodeString().takeIf { it.isNotBlank() } else decoder.decodeNull()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Up to two initials, for an avatar placeholder. */
fun String.initialsForDisplay(): String =
    split(' ').filter { it.isNotEmpty() }.take(2).map { it.first().uppercaseChar() }.joinToString("")

/** The first word of a name. */
fun String.firstNameForDisplay(): String = split(' ').firstOrNull { it.isNotEmpty() } ?: this
