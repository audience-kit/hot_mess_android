package social.hotmess.core

import com.audiencekit.APIRequest
import com.audiencekit.Admission
import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitException
import com.audiencekit.Coordinates
import com.audiencekit.CoverCharge
import com.audiencekit.CoverPurchase
import com.audiencekit.DeviceDescription
import com.audiencekit.DoorNight
import com.audiencekit.DoorVenue
import com.audiencekit.RSVPState
import com.audiencekit.ScanResult
import com.audiencekit.graphQLVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The typed surface of the Hot Mess API, all of it on the AudienceKit SDK.
 *
 * Everything goes through the audience's GraphQL endpoint except the version manifest, which is
 * read before sign-in and isn't part of any audience. Failures surface as [ApiError].
 */
class HotMessApi(val client: AudienceKitClient) {
    // region Home

    /**
     * Records where the device is and returns what's happening there. With no position there's
     * nothing to look up, so the screen gets an empty "Now".
     */
    suspend fun now(near: Coordinates?): Now {
        near ?: return Now()
        return query(
            Documents.REPORT_LOCATION,
            ReportLocationResponse.serializer(),
            mapOf("position" to near.toGraphQLValue()),
        ).reportLocation.now
    }

    /**
     * [now], or without a position just your Ping and your friends', which don't need one, so Now can
     * show them before location is on.
     */
    suspend fun nowOrPings(near: Coordinates?): Now {
        if (near != null) return now(near)
        return coroutineScope {
            val mine = async { myPing() }
            val friends = async { friendPings() }
            Now(myPing = mine.await(), friendPings = friends.await())
        }
    }

    /** Records where the device is, so the API knows which venue the user is in. */
    suspend fun reportLocation(position: Coordinates) {
        now(position)
    }

    // endregion

    // region Locales and venues

    suspend fun closestLocale(near: Coordinates?): AppLocale? {
        near ?: return null
        val locale = query(
            Documents.CLOSEST_LOCALE,
            ClosestLocaleResponse.serializer(),
            mapOf("near" to near.toGraphQLValue()),
        ).closestLocale ?: return null
        val id = RecordId.normalize(locale.id) ?: return null
        return AppLocale(id = id, name = locale.displayName ?: "")
    }

    /**
     * The audience's venues for the Venues tab: visible ones, those in [localeId] first, then in the
     * audience's order.
     */
    suspend fun venues(localeId: String?): List<Venue> =
        query(Documents.VENUES, VenuesResponse.serializer()).venues.sortedForList(localeId)

    /** A venue with its upcoming events, in one request. */
    suspend fun venueOverview(id: String): VenueOverview {
        val venue = query(Documents.VENUE, VenueResponse.serializer(), graphQLVariables("id" to id)).venue
            ?: throw ApiError.NotFound
        return VenueOverview(venue = venue.venue(), events = venue.events, chatOpen = venue.chatOpen, friendPings = venue.friendPings)
    }

    // endregion

    // region People

    /** The people the audience follows, in the audience's order. */
    suspend fun people(): List<Person> =
        query(Documents.PEOPLE, PeopleResponse.serializer()).people
            .sortedWith(compareBy<Person> { it.order }.thenBy { it.name.lowercase() })

    suspend fun person(id: String): PersonDetail =
        query(Documents.PERSON, PersonResponse.serializer(), graphQLVariables("id" to id)).person
            ?: throw ApiError.NotFound

    // endregion

    // region Events

    suspend fun events(localeId: String): EventListing {
        val locale = query(Documents.LOCALE_EVENTS, LocaleEventsResponse.serializer(), graphQLVariables("id" to localeId)).locale
            ?: throw ApiError.NotFound
        return EventListing.upcoming(locale.events)
    }

    suspend fun event(id: String): Event =
        query(Documents.EVENT, EventResponse.serializer(), graphQLVariables("id" to id)).event
            ?: throw ApiError.NotFound

    suspend fun setRsvp(rsvp: Rsvp, eventId: String) {
        call { client.setRSVP(RSVPState.valueOf(rsvp.name), eventId) }
    }

    // endregion

    // region Cover charge

    /**
     * Starts paying tonight's cover at a venue: the pass, pending until paid, and what Stripe's payment
     * sheet or Square's card entry needs ([CoverPayment] says which). Asking again the same night returns
     * the same pass and payment.
     */
    suspend fun buyCover(venueId: String): CoverPurchase = mutate { client.buyCover(venueId) }

    /**
     * Pays a Square venue's cover with the nonce Square's card entry made ([sourceId]). The pass comes back
     * paid when Square took the payment.
     */
    suspend fun payCover(admissionId: String, sourceId: String, verificationToken: String? = null): Admission =
        mutate { client.payCover(admissionId, sourceId, verificationToken) }

    /** Checks the payment with Stripe once the payment sheet finishes, so the pass works straight away. */
    suspend fun confirmCover(admissionId: String): Admission = mutate { client.confirmCover(admissionId) }

    suspend fun refundAdmission(admissionId: String): Admission = mutate { client.refundAdmission(admissionId) }

    /** The user's passes, newest first. */
    suspend fun admissions(): List<Admission> = call { client.admissions() }

    /** Venues whose door the user can work; empty for almost everyone. */
    suspend fun doorVenues(): List<DoorVenue> = call { client.doorVenues() }

    /** Tonight's counts at a venue's door. */
    suspend fun door(venueId: String): DoorNight? = call { client.venueDoor(venueId) }?.door

    /** Checks a scanned pass at a venue's door. */
    suspend fun scan(venueId: String, code: String): ScanResult = mutate { client.scanAdmission(venueId, code) }

    // endregion

    // region Ping

    /** The signed-in user's active Ping, without reporting the position. */
    suspend fun myPing(): Ping? = query(Documents.MY_PING, MyPingResponse.serializer()).myPing

    /** Friends' active Pings, newest first, without reporting the position. */
    suspend fun friendPings(): List<Ping> = query(Documents.FRIEND_PINGS, FriendPingsResponse.serializer()).friendPings

    /**
     * Sends a Ping for tonight, or edits the active one (which pushes nothing new). No picks means
     * "anywhere tonight?"; a blank note is no note. [localeId] is the app's current locale, which the
     * API needs when there are no picks. A null [reach] keeps the active Ping's (friends only for a new one).
     */
    suspend fun sendPing(
        venueIds: List<String>,
        eventIds: List<String>,
        note: String?,
        localeId: String?,
        reach: PingReach? = null,
    ): Ping =
        mutate(
            Documents.SEND_PING,
            SendPingResponse.serializer(),
            graphQLVariables(
                "venueIds" to venueIds,
                "eventIds" to eventIds,
                "note" to note?.trim()?.takeIf { it.isNotEmpty() },
                "localeId" to localeId,
                "reach" to reach,
            ),
        ).sendPing.ping

    /** "I'm in" on one pick ([targetId]), or on the Ping as a whole when that's null. */
    suspend fun joinPing(pingId: String, targetId: String?): Ping =
        mutate(Documents.JOIN_PING, JoinPingResponse.serializer(), graphQLVariables("pingId" to pingId, "targetId" to targetId))
            .joinPing.ping

    /** Takes back "I'm in" on a friend's Ping. */
    suspend fun leavePing(pingId: String): Ping =
        mutate(Documents.LEAVE_PING, LeavePingResponse.serializer(), graphQLVariables("pingId" to pingId)).leavePing.ping

    /** Ends the signed-in user's active Ping early. */
    suspend fun endPing(): Boolean = mutate(Documents.END_PING, EndPingResponse.serializer()).endPing.ended

    /** What the send sheet offers in [localeId], and the Ping it would edit. Without a locale, only venues you pick elsewhere. */
    suspend fun pingChoices(localeId: String?): PingChoices = coroutineScope {
        val active = async { myPing() }
        val tonight = async { localeId?.let { events(it).allEvents }.orEmpty() }
        val places = async { if (localeId == null) emptyList() else venues(localeId) }
        PingChoices.of(tonight.await(), places.await(), localeId, active.await())
    }

    // endregion

    // region Session

    /** The oldest Android build the API still serves, or null when it doesn't say. */
    suspend fun minimumVersion(device: DeviceDescription): VersionInfo? {
        val body = json.encodeToString(ManifestRequest.serializer(), ManifestRequest(device)).toByteArray()
        val bytes = call { client.data(APIRequest(method = "POST", path = "/", body = body, authenticated = false)) }
        return parseManifest(bytes.toString(Charsets.UTF_8))
    }

    /**
     * Stores this device's Firebase Cloud Messaging token for the session, with the app's package name
     * ([appId]) so the API knows which app to push to. FCM has no sandbox, so that's always false.
     */
    suspend fun registerForPush(token: String, appId: String) {
        query(
            Documents.REGISTER_DEVICE,
            RegisterDeviceResponse.serializer(),
            graphQLVariables("notificationToken" to token, "appId" to appId, "sandbox" to false),
        )
    }

    suspend fun me(): User? {
        val me = call { client.me() } ?: return null
        val id = RecordId.normalize(me.id) ?: return null
        val name = me.name ?: listOfNotNull(me.firstName, me.lastName).joinToString(" ")
        return User(id = id, name = name)
    }

    // endregion

    private suspend fun <T> query(
        document: String,
        serializer: KSerializer<T>,
        variables: Map<String, JsonElement>? = null,
    ): T = call { client.graphQL(document, serializer, variables, json = json) }

    /**
     * [call] for the cover mutations, whose errors are written for people ("The Wildrose doesn't take
     * cover in the app yet"), so they're shown as they are.
     */
    private suspend fun <T> mutate(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudienceKitException.GraphQL) {
            throw ApiError.Other(e.errors.firstOrNull()?.message ?: "Something went wrong.")
        } catch (e: AudienceKitException) {
            throw ApiError.from(e)
        }

    /** A mutation whose GraphQL errors are written for people ("That ping has ended"), so they're shown as they are. */
    private suspend fun <T> mutate(
        document: String,
        serializer: KSerializer<T>,
        variables: Map<String, JsonElement>? = null,
    ): T =
        try {
            client.graphQL(document, serializer, variables, json = json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudienceKitException.GraphQL) {
            throw userFacingMessage(e.errors.map { it.message })?.let { ApiError.Other(it) } ?: ApiError.from(e)
        } catch (e: AudienceKitException) {
            throw ApiError.from(e)
        }

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudienceKitException) {
            throw ApiError.from(e)
        }

    companion object {
        /** Ignores fields the app doesn't know, so new API fields never break a shipped build. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * The first error message when it's one written for people, or null for schema and validation
         * errors ("Field 'x' doesn't exist on type 'Mutation'"), which get the generic message instead.
         */
        fun userFacingMessage(messages: List<String>): String? {
            val message = messages.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val technical = listOf("Field '", "doesn't exist", "Variable ", "Argument '", "Expected type", "Parse error", "undefined method")
            return message.takeIf { text -> technical.none { text.contains(it) } }
        }

        /** The manifest's `client.mobile.android`; the API only lists `apple` so far. */
        fun parseManifest(text: String): VersionInfo? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val android = ((root["client"] as? JsonObject)?.get("mobile") as? JsonObject)?.get("android") as? JsonObject
                ?: return null
            return runCatching { json.decodeFromJsonElement(VersionInfo.serializer(), android) }.getOrNull()
        }
    }
}

/**
 * A venue and its upcoming events, as the venue screen shows them. [chatOpen] says whether the user can
 * join its chat room: they're at the venue, or they're an admin. The way in is hidden otherwise.
 */
data class VenueOverview(
    val venue: Venue,
    val events: List<Event> = emptyList(),
    val chatOpen: Boolean = false,
    /** Friends' active Pings that pick this venue or one of its events. */
    val friendPings: List<Ping> = emptyList(),
)

/** Visible venues, those in [localeId] first, then by the audience's order and name. */
fun List<Venue>.sortedForList(localeId: String?): List<Venue> =
    filterNot { it.hidden }
        .sortedWith(compareBy<Venue> { if (it.isIn(localeId)) 0 else 1 }.thenBy { it.order }.thenBy { it.name.lowercase() })

/** Whether the venue is in the locale [localeId]; false when there's no locale. */
fun Venue.isIn(localeId: String?): Boolean {
    val local = localeId?.let(RecordId::normalize) ?: return false
    return locale?.id?.let(RecordId::normalize) == local
}

@Serializable
private data class ManifestRequest(val device: DeviceDescription)

@Serializable
private data class ReportLocationResponse(val reportLocation: Payload) {
    @Serializable data class Payload(val now: Now)
}

@Serializable
private data class ClosestLocaleResponse(val closestLocale: LocaleSummary? = null)

@Serializable
private data class VenuesResponse(val venues: List<Venue> = emptyList())

/** A venue plus its events, which GraphQL nests in one object. */
@Serializable
private data class VenueNode(
    val id: String,
    val name: String = "",
    val address: String? = null,
    val description: String? = null,
    val phone: String? = null,
    val facebookId: String? = null,
    val distance: Double? = null,
    @Serializable(with = BlankAsNullSerializer::class) val photoUrl: String? = null,
    @Serializable(with = BlankAsNullSerializer::class) val heroUrl: String? = null,
    val point: String? = null,
    val isLiked: Boolean = false,
    val chatOpen: Boolean = false,
    val canWorkDoor: Boolean = false,
    val coverCharge: CoverCharge? = null,
    val viewerAdmission: Admission? = null,
    val recentMessages: List<ChatLine> = emptyList(),
    val events: List<Event> = emptyList(),
    val socialLinks: List<SocialLink> = emptyList(),
    val friendPings: List<Ping> = emptyList(),
) {
    fun venue() = Venue(
        id = id,
        name = name,
        address = address,
        description = description,
        phone = phone,
        facebookId = facebookId,
        distance = distance,
        photoUrl = photoUrl,
        heroUrl = heroUrl,
        point = point,
        isLiked = isLiked,
        socialLinks = socialLinks,
        recentMessages = recentMessages,
        coverCharge = coverCharge,
        viewerAdmission = viewerAdmission,
        canWorkDoor = canWorkDoor,
    )
}

@Serializable
private data class VenueResponse(val venue: VenueNode? = null)

@Serializable
private data class PeopleResponse(val people: List<Person> = emptyList())

@Serializable
private data class PersonResponse(val person: PersonDetail? = null)

@Serializable
private data class LocaleEventsResponse(val locale: Locale? = null) {
    @Serializable data class Locale(val events: List<Event> = emptyList())
}

@Serializable
private data class EventResponse(val event: Event? = null)

@Serializable
private data class MyPingResponse(val myPing: Ping? = null)

@Serializable
private data class FriendPingsResponse(val friendPings: List<Ping> = emptyList())

@Serializable
private data class PingPayload(val ping: Ping)

@Serializable
private data class SendPingResponse(val sendPing: PingPayload)

@Serializable
private data class JoinPingResponse(val joinPing: PingPayload)

@Serializable
private data class LeavePingResponse(val leavePing: PingPayload)

@Serializable
private data class EndPingResponse(val endPing: Payload) {
    @Serializable data class Payload(val ended: Boolean = false)
}

@Serializable
private data class RegisterDeviceResponse(val registerDevice: Payload? = null) {
    @Serializable data class Payload(val registered: Boolean = false)
}
