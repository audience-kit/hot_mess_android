package social.hotmess.core

import com.audiencekit.APIRequest
import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitException
import com.audiencekit.Coordinates
import com.audiencekit.DeviceDescription
import com.audiencekit.RSVPState
import com.audiencekit.graphQLVariables
import kotlinx.coroutines.CancellationException
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
        return VenueOverview(venue = venue.venue(), events = venue.events)
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

    // region Session

    /** The oldest Android build the API still serves, or null when it doesn't say. */
    suspend fun minimumVersion(device: DeviceDescription): VersionInfo? {
        val body = json.encodeToString(ManifestRequest.serializer(), ManifestRequest(device)).toByteArray()
        val bytes = call { client.data(APIRequest(method = "POST", path = "/", body = body, authenticated = false)) }
        return parseManifest(bytes.toString(Charsets.UTF_8))
    }

    /** Stores this device's Firebase Cloud Messaging token for the session. */
    suspend fun registerForPush(token: String) {
        call { client.registerDevice(token) }
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

        /** The manifest's `client.mobile.android`; the API only lists `apple` so far. */
        fun parseManifest(text: String): VersionInfo? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val android = ((root["client"] as? JsonObject)?.get("mobile") as? JsonObject)?.get("android") as? JsonObject
                ?: return null
            return runCatching { json.decodeFromJsonElement(VersionInfo.serializer(), android) }.getOrNull()
        }
    }
}

/** A venue and its upcoming events, as the venue screen shows them. */
data class VenueOverview(val venue: Venue, val events: List<Event> = emptyList())

/** Visible venues, those in [localeId] first, then by the audience's order and name. */
fun List<Venue>.sortedForList(localeId: String?): List<Venue> {
    val local = localeId?.let(RecordId::normalize)
    return filterNot { it.hidden }
        .sortedWith(
            compareBy<Venue> { venue ->
                val inLocale = local != null && venue.locale?.id?.let(RecordId::normalize) == local
                if (inLocale) 0 else 1
            }.thenBy { it.order }.thenBy { it.name.lowercase() },
        )
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
    val events: List<Event> = emptyList(),
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
