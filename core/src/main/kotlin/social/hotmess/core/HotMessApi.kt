package social.hotmess.core

import com.audiencekit.APIRequest
import com.audiencekit.Admission
import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitException
import com.audiencekit.Coordinates
import com.audiencekit.CoverPurchase
import com.audiencekit.GraphQLError
import com.audiencekit.RSVPState
import com.audiencekit.graphQLVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The typed surface of the Hot Mess API, all of it on the AudienceKit SDK.
 *
 * Everything goes through the audience's GraphQL endpoint except `GET /v1/status`, which is read before
 * sign-in and isn't part of any audience. Failures surface as [ApiError].
 */
class HotMessApi(val client: AudienceKitClient) {
    /** Friends seen in Now's reports, so chat can show them by their full names. */
    val friends = FriendDirectory()

    /** Told about every failed call with what was called, for error reports. */
    var onFailure: ((error: ApiError, operation: String) -> Unit)? = null

    // region Home

    /** Location reports in flight, by position, so the same report asked for twice at once is sent once. */
    private val reports = mutableMapOf<Coordinates, Deferred<Now>>()

    /** Runs shared requests, so one caller going away (a screen recomposing) doesn't cancel the others'. */
    private val shared = CoroutineScope(SupervisorJob())

    /**
     * Records where the device is and returns what's happening there. With no position there's
     * nothing to look up, so the screen gets an empty "Now". Location tracking and the Now screen both
     * ask when the position changes; asking for a position that's already in flight joins that request.
     */
    suspend fun now(near: Coordinates?): Now {
        near ?: return Now()
        // On the first caller's dispatcher, but not its job.
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
        val report = synchronized(reports) {
            reports[near] ?: shared.async(dispatcher) {
                query(
                    Documents.REPORT_LOCATION,
                    ReportLocationResponse.serializer(),
                    mapOf("position" to near.toGraphQLValue()),
                ).reportLocation.now(json)
            }.also { request ->
                reports[near] = request
                request.invokeOnCompletion { synchronized(reports) { if (reports[near] === request) reports.remove(near) } }
            }
        }
        return report.await().also(friends::record)
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
        val venueFriends = venue.friends?.list.orEmpty()
        friends.record(venueFriends)
        return VenueOverview(
            venue = venue.venue(),
            events = venue.events,
            chatOpen = venue.chat?.open == true,
            friends = venueFriends,
            friendPings = venue.friendPings,
        )
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

    // endregion

    // region Chat

    /**
     * Says [body] in [room] as the signed-in user (`sendMyChatMessage`), returning the line as the room
     * sees it, or null if the response didn't carry one. The room also delivers it to everyone following,
     * the sender included. Errors written for people ("You're not at the venue") come through as they are.
     */
    suspend fun sendChatMessage(room: ChatRoom, body: String): VenueMessage? {
        val data = mutate(ChatProtocol.SEND_MESSAGE, JsonObject.serializer(), mapOf("input" to ChatProtocol.sendInput(room, body)))
        val payload = data["sendMyChatMessage"] as? JsonObject
        return ChatProtocol.message(payload?.get("message"))
    }

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

    /**
     * What `GET /v1/status` says about the Android app: the oldest version still served, the latest, and the
     * store link; null when it says nothing about Android. Read-only and open, so it works before sign-in.
     */
    suspend fun clientVersions(): VersionInfo? {
        val bytes = call("GET /v1/status") { client.data(APIRequest(path = "/v1/status", authenticated = false)) }
        return parseStatus(bytes.toString(Charsets.UTF_8))
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

    /**
     * Sends an error report. Never throws: reporting must not cause an error of its own. Signed out it goes without
     * the session token.
     */
    suspend fun reportError(report: ClientErrorReport, signedIn: Boolean) {
        val body = json.encodeToString(ClientErrorReport.serializer(), report).toByteArray()
        try {
            client.data(APIRequest(method = "POST", path = "/v1/client_errors", body = body, authenticated = signedIn))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** "Report a problem". Works signed out too, so sign-in trouble can be reported. */
    suspend fun reportProblem(report: BugReportRequest, signedIn: Boolean): BugReportResponse {
        val body = json.encodeToString(BugReportRequest.serializer(), report).toByteArray()
        val bytes = call("POST /v1/bug_reports") {
            client.data(APIRequest(method = "POST", path = "/v1/bug_reports", body = body, authenticated = signedIn))
        }
        return json.decodeFromString(BugReportResponse.serializer(), bytes.toString(Charsets.UTF_8))
    }

    /** The signed-in user, with the picture the API has for them (`avatarUrl`, null when they have none). */
    suspend fun me(): User? {
        val me = query(Documents.ME, MeResponse.serializer()).me?.self ?: return null
        val id = RecordId.normalize(me.id) ?: return null
        val name = me.name ?: listOfNotNull(me.firstName, me.lastName).joinToString(" ")
        return User(id = id, name = name, avatarUrl = me.avatarUrl)
    }

    // endregion

    private suspend fun <T> query(
        document: String,
        serializer: KSerializer<T>,
        variables: Map<String, JsonElement>? = null,
    ): T = call(operationName(document)) { client.graphQL(document, serializer, variables, json = json) }

    /**
     * [call] for the cover mutations. An error the API wrote for people ("The Wildrose doesn't take cover in
     * the app yet"; one with an `extensions.code`) is shown as it is; see [ApiError.fromGraphQL].
     */
    private suspend fun <T> mutate(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudienceKitException.GraphQL) {
            throw rejection(e.errors) ?: failed(ApiError.from(e), "cover")
        } catch (e: AudienceKitException) {
            throw failed(ApiError.from(e), "cover")
        }

    /** A mutation whose refusals ("That ping has ended", an INVALID or CONFLICT error) are shown as written. */
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
            throw rejection(e.errors) ?: failed(ApiError.from(e), operationName(document))
        } catch (e: AudienceKitException) {
            throw failed(ApiError.from(e), operationName(document))
        }

    /** The refusal in [errors] to show as written, or null when they aren't one (and are reported as a failure). */
    private fun rejection(errors: List<GraphQLError>): ApiError.Rejected? = ApiError.fromGraphQL(errors) as? ApiError.Rejected

    private suspend fun <T> call(operation: String = "api", block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudienceKitException) {
            throw failed(ApiError.from(e), operation)
        }

    private fun failed(error: ApiError, operation: String): ApiError {
        runCatching { onFailure?.invoke(error, operation) }
        return error
    }

    companion object {
        /** Ignores fields the app doesn't know, so new API fields never break a shipped build. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * The first error's message when the API wrote it for people: a refusal with an `extensions.code` of
         * INVALID, CONFLICT, FORBIDDEN or RATE_LIMITED ("That ping has ended"). Null for anything else: an error
         * graphql-ruby raised itself has no code ("Field 'x' doesn't exist on type 'Mutation'"), and an INTERNAL
         * or UNAVAILABLE one isn't about the request. The code decides, never the message's wording.
         */
        fun userFacingMessage(errors: List<GraphQLError>): String? {
            val error = errors.firstOrNull() ?: return null
            val code = error.code ?: return null
            if (code !in ApiError.REJECTION_CODES) return null
            return error.message.trim().takeIf { it.isNotEmpty() }
        }

        /** `clients.android` of `GET /v1/status`; null when the status says nothing about Android. */
        fun parseStatus(text: String): VersionInfo? =
            runCatching { json.decodeFromString(StatusResponse.serializer(), text) }.getOrNull()?.clients?.android
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
    /** The user's friends who are here now. */
    val friends: List<Friend> = emptyList(),
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

/** `GET /v1/status`: `clients.<platform>` is each app's versions; `api` (the contract version and SDK minimums) isn't read. */
@Serializable
private data class StatusResponse(val clients: Clients? = null) {
    @Serializable data class Clients(val android: VersionInfo? = null)
}

@Serializable
private data class ReportLocationResponse(val reportLocation: LocationReport)

/**
 * What reportLocation says about where the user is: the [event] they're at, the [venue] they're in, else
 * their [locale] and its [nearbyVenues]. Friends, events and Pings come from those objects' mixins; [now]
 * shapes it all for the Now screen, with the title and hero taken from the event, venue or locale.
 */
@Serializable
private data class LocationReport(
    val event: Event? = null,
    /** Read twice: as the [Venue] and for its [VenueMixins]. */
    val venue: JsonObject? = null,
    val nearbyVenues: List<Venue>? = null,
    val locale: LocaleNode? = null,
    val audience: AudienceNode? = null,
) {
    @Serializable
    data class VenueMixins(val events: UpcomingEvents? = null, val friends: FriendsMixin? = null)

    @Serializable
    data class LocaleNode(
        val id: String,
        val name: String? = null,
        /** Its location's photo; null when there's none. */
        val bannerUrl: String? = null,
        val chat: ChatMixin? = null,
        val events: UpcomingEvents? = null,
        val friends: FriendsMixin? = null,
    )

    /** The audience the report is in, read for the viewer's pings: `audience { me { ping } }`. */
    @Serializable
    data class AudienceNode(val me: MeNode? = null)

    @Serializable
    data class MeNode(val ping: PingFeed? = null)

    @Serializable
    data class PingFeed(val myPing: Ping? = null, val friendPings: List<Ping> = emptyList())

    fun now(json: Json): Now {
        val node = venue
        val nowLocale = locale?.let { NowLocale(it.id, it.name, it.chat?.open == true, it.chat?.lines.orEmpty()) }
        val ping = audience?.me?.ping
        if (node != null) {
            val place = json.decodeFromJsonElement(Venue.serializer(), node)
            val mixins = json.decodeFromJsonElement(VenueMixins.serializer(), node)
            return Now(
                title = event?.name ?: place.name,
                imageUrl = event?.bannerUrl ?: place.bannerUrl ?: place.photoUrl,
                event = event,
                venue = place,
                venues = nearbyVenues,
                // The event the user is at leads the screen, so it isn't listed again.
                events = mixins.events?.events.orEmpty().filterNot { it.id == event?.id },
                friends = mixins.friends?.list.orEmpty(),
                locale = nowLocale,
                myPing = ping?.myPing,
                friendPings = ping?.friendPings.orEmpty(),
            )
        }
        return Now(
            title = event?.name ?: locale?.name?.let { "Happening Now in $it" } ?: "Happening Now",
            imageUrl = event?.bannerUrl ?: locale?.bannerUrl,
            event = event,
            venues = nearbyVenues,
            events = locale?.events?.events.orEmpty().filterNot { it.id == event?.id }.take(Documents.LOCALE_EVENT_LIMIT),
            friends = locale?.friends?.list.orEmpty(),
            friendVenues = locale?.friends?.venues.orEmpty(),
            locale = nowLocale,
            myPing = ping?.myPing,
            friendPings = ping?.friendPings.orEmpty(),
        )
    }
}

/** `me { self }`: the viewer's state, and their own profile in it. */
@Serializable
private data class MeResponse(val me: UserState? = null) {
    @Serializable
    data class UserState(val self: Me? = null)

    @Serializable
    data class Me(
        val id: String,
        val name: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val avatarUrl: String? = null,
    )
}

@Serializable
private data class ClosestLocaleResponse(val closestLocale: LocaleSummary? = null)

@Serializable
private data class VenuesResponse(@SerialName("venues") val venuesMixin: VenuesMixin? = null) {
    @Serializable data class VenuesMixin(val list: Nodes<Venue> = Nodes())

    /** `venues { list { nodes } }` on the root, the audience; a missing venues mixin reads as none. */
    val venues: List<Venue> get() = venuesMixin?.list?.nodes.orEmpty()
}

/** A connection's `nodes`. */
@Serializable
private data class Nodes<T>(val nodes: List<T> = emptyList())

/** A venue plus its events, which GraphQL nests in one object. */
@Serializable
private data class VenueNode(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val distance: Double? = null,
    val photoUrl: String? = null,
    val bannerUrl: String? = null,
    val point: String? = null,
    val profile: ListingProfile? = null,
    val chat: ChatMixin? = null,
    val tickets: VenueTicketsMixin? = null,
    @SerialName("events") val eventsMixin: UpcomingEvents? = null,
    val friends: FriendsMixin? = null,
    val ping: PingFeedMixin? = null,
) {
    val friendPings: List<Ping> get() = ping?.friendPings.orEmpty()

    fun venue() = Venue(
        id = id,
        name = name,
        description = description,
        distance = distance,
        photoUrl = photoUrl,
        bannerUrl = bannerUrl,
        point = point,
        profile = profile,
        chat = chat,
        tickets = tickets,
    )

    val events: List<Event> get() = eventsMixin?.events.orEmpty()
}

@Serializable
private data class VenueResponse(val venues: Venues? = null) {
    @Serializable data class Venues(val venue: VenueNode? = null)

    /** `venues { venue(id:) }`. */
    val venue: VenueNode? get() = venues?.venue
}

@Serializable
private data class PeopleResponse(@SerialName("people") val peopleMixin: PeopleMixin? = null) {
    @Serializable data class PeopleMixin(val list: Nodes<Person> = Nodes())

    /** `people { list { nodes } }` on the root, the audience; a missing people mixin reads as none. */
    val people: List<Person> get() = peopleMixin?.list?.nodes.orEmpty()
}

@Serializable
private data class PersonResponse(val people: People? = null) {
    @Serializable data class People(val person: PersonDetail? = null)

    /** `people { person(id:) }`. */
    val person: PersonDetail? get() = people?.person
}

@Serializable
private data class LocaleEventsResponse(val locale: Locale? = null) {
    @Serializable data class Locale(@SerialName("events") val eventsMixin: UpcomingEvents? = null) {
        val events: List<Event> get() = eventsMixin?.events.orEmpty()
    }
}

@Serializable
private data class EventResponse(val events: Events? = null) {
    @Serializable data class Events(val event: Event? = null)

    /** `events { event(id:) }`. */
    val event: Event? get() = events?.event
}

@Serializable
private data class MyPingResponse(val me: ViewerPings? = null) {
    /** `me { ping { myPing } }`: the viewer's own Ping is theirs, under `me`. */
    val myPing: Ping? get() = me?.ping?.myPing
}

@Serializable
private data class FriendPingsResponse(val me: ViewerPings? = null) {
    /** `me { ping { friendPings } }`; signed out or a missing ping mixin reads as none. */
    val friendPings: List<Ping> get() = me?.ping?.friendPings.orEmpty()
}

/** `me { ping }`: the viewer's ping mixin. */
@Serializable
private data class ViewerPings(val ping: AudiencePings? = null)

/** The viewer's ping mixin: the user's own Ping and friends' active ones. */
@Serializable
private data class AudiencePings(val myPing: Ping? = null, val friendPings: List<Ping> = emptyList())

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
