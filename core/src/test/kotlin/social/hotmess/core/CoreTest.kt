package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.Coordinates
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDecodingTest {
    private val json = HotMessApi.json

    @Test
    fun decodesAnEventWithItsVenueAndRsvp() {
        val event = json.decodeFromString(Event.serializer(), Fixtures.EVENT)

        assertEquals("Drag Brunch", event.name)
        assertEquals(Instant.parse("2026-10-10T04:00:00Z"), event.startAt)
        assertEquals(Rsvp.MAYBE, event.rsvp)
        assertEquals("The Wildrose", event.venue?.name)
        assertEquals(47.6145, event.venue?.coordinate?.latitude)
        assertNull(event.coverPhotoUrl, "an empty URL is no URL")
        assertEquals("https://facebook.com/events/1234", event.facebookUrl)
    }

    @Test
    fun acceptsTheOldCompactDateFormat() {
        assertEquals(Instant.parse("2017-04-27T04:00:00Z"), InstantSerializer.parse("2017-04-26T21:00:00.000-0700"))
        assertEquals(Instant.parse("2026-10-10T04:00:00Z"), InstantSerializer.parse("2026-10-10T04:00:00.000Z"))
    }

    @Test
    fun unknownRsvpIsUndecided() {
        val event = json.decodeFromString(Event.serializer(), Fixtures.EVENT.replace("\"MAYBE\"", "\"SOMETHING_NEW\""))
        assertEquals(Rsvp.UNSURE, event.rsvp)
    }

    @Test
    fun decodesAPersonWithLinksAndTracks() {
        val person = json.decodeFromString(PersonDetail.serializer(), Fixtures.PERSON)

        assertEquals("DJ Glitter", person.name)
        assertEquals(SocialLink.Network.INSTAGRAM, person.socialLinks.single().network)
        assertEquals("Late Night", person.tracks.single().title)
        assertEquals(1, person.events.size)
    }

    @Test
    fun recordIdsNormalizeGlobalIds() {
        val uuid = "B0F8B66A-E636-495D-9475-0F5317EA08E0"
        val global = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("gid://audience-kit/Venue/${uuid.lowercase()}".toByteArray())

        assertEquals(uuid.lowercase(), RecordId.normalize(uuid))
        assertEquals(uuid.lowercase(), RecordId.normalize(global))
        assertNull(RecordId.normalize("not-an-id"))
    }

    @Test
    fun initials() {
        assertEquals("RM", "Rick Mark Jr".initialsForDisplay())
        assertEquals("Rick", "Rick Mark".firstNameForDisplay())
    }
}

class ListingTest {
    private fun event(id: String, start: String, cover: String? = null) =
        Event(id = id, name = id, startAt = Instant.parse(start), coverPhotoUrl = cover)

    @Test
    fun featuresUpToTwoEventsWithCovers() {
        val listing = EventListing.upcoming(
            listOf(
                event("c", "2026-10-12T04:00:00Z", "https://img/c"),
                event("a", "2026-10-10T04:00:00Z", "https://img/a"),
                event("b", "2026-10-11T04:00:00Z"),
                event("d", "2026-10-13T04:00:00Z", "https://img/d"),
            ),
        )

        assertEquals(listOf("featured", "upcoming"), listing.sections.map { it.id })
        assertEquals(listOf("a", "c"), listing.sections[0].events.map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), listing.sections[1].events.map { it.id })
    }

    @Test
    fun noEventsMeansNoSections() {
        assertTrue(EventListing.upcoming(emptyList()).isEmpty)
    }

    @Test
    fun venuesInTheLocaleComeFirstAndHiddenOnesAreLeftOut() {
        val here = "11111111-1111-1111-1111-111111111111"
        val there = "22222222-2222-2222-2222-222222222222"
        val venues = listOf(
            Venue(id = "1", name = "Zed", order = 0, locale = LocaleSummary(there)),
            Venue(id = "2", name = "Bar", order = 1, locale = LocaleSummary(here)),
            Venue(id = "3", name = "Arc", order = 1, locale = LocaleSummary(here)),
            Venue(id = "4", name = "Gone", hidden = true, locale = LocaleSummary(here)),
        )

        assertEquals(listOf("3", "2", "1"), venues.sortedForList(here.uppercase()).map { it.id })
        assertEquals(listOf("1", "3", "2"), venues.sortedForList(null).map { it.id })
    }
}

class DeepLinkTest {
    private val id = "b0f8b66a-e636-495d-9475-0f5317ea08e0"

    @Test
    fun readsAppAndWebLinks() {
        assertEquals(AppRoute.VenueDetail(id), DeepLink.route("hotmess://venues/$id"))
        assertEquals(AppRoute.EventDetail(id), DeepLink.route("https://hotmess.social/events/${id.uppercase()}"))
        assertEquals(AppRoute.PersonDetail(id), DeepLink.route("https://hotmess.social/people/$id"))
        assertEquals(AppTab.EVENTS, AppRoute.EventDetail(id).tab)
    }

    @Test
    fun ignoresAnythingElse() {
        assertNull(DeepLink.route("https://hotmess.social/venues/not-a-uuid"))
        assertNull(DeepLink.route("https://hotmess.social/about"))
        assertNull(DeepLink.route("hotmess://friends/$id"))
    }
}

class ChatProtocolTest {
    private val venue = "b0f8b66a-e636-495d-9475-0f5317ea08e0"

    @Test
    fun subscribesWithAStringIdentifier() {
        val frame = HotMessApi.json.parseToJsonElement(ChatProtocol.subscribe(venue)).toString()
        assertTrue(frame.contains("\"command\":\"subscribe\""))
        assertTrue(frame.contains("venue_id\\\":\\\"$venue"), frame)
        assertTrue(frame.contains("\"identifier\":\"{"), "the identifier is a JSON string")
    }

    @Test
    fun readsServerFrames() {
        assertEquals(ChatProtocol.Frame.Connected, ChatProtocol.parse("""{"type":"welcome"}"""))
        assertEquals(ChatProtocol.Frame.Connected, ChatProtocol.parse("""{"type":"confirm_subscription","identifier":"x"}"""))
        assertNull(ChatProtocol.parse("""{"type":"ping","message":1700000000}"""))
        assertNull(ChatProtocol.parse("""{"message":1700000000}"""))

        val line = ChatProtocol.parse("""{"identifier":"x","message":{"message":"hi","user_id":"U-1","avatar_url":null}}""")
        val message = assertIs<ChatProtocol.Frame.Message>(line).message
        assertEquals("hi", message.body)
        assertTrue(message.isOutgoing("u-1"))
    }

    @Test
    fun readsBeingTurnedAwayAndLeaving() {
        assertEquals(ChatProtocol.Frame.Rejected, ChatProtocol.parse("""{"type":"reject_subscription","identifier":"x"}"""))
        assertEquals(ChatProtocol.Frame.Left, ChatProtocol.parse("""{"identifier":"x","message":{"type":"left"}}"""))
    }

    @Test
    fun readsWhetherAnAdminIsOutOfRange() {
        assertEquals(
            ChatProtocol.Frame.Range(outOfRange = true),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"range","out_of_range":true}}"""),
        )
        assertEquals(
            ChatProtocol.Frame.Range(outOfRange = false),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"range","out_of_range":false}}"""),
        )
    }

    @Test
    fun sendsOnlyTheText() {
        val frame = HotMessApi.json.parseToJsonElement(ChatProtocol.message(venue, "hi")).jsonObject
        assertEquals("""{"message":"hi"}""", frame["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun derivesTheSocketUrl() {
        assertEquals("wss://api.audiencekit.com/connection", ChatProtocol.realtimeUrl("https://api.audiencekit.com"))
        assertEquals("ws://10.0.2.2:3000/connection", ChatProtocol.realtimeUrl("http://10.0.2.2:3000/"))
    }
}

class FormattingTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun shortTimes() {
        assertEquals("Fri 9pm", Formatting.shortTime(Instant.parse("2026-10-10T04:00:00Z"), zone, Locale.US))
        assertEquals("Fri 9:30pm", Formatting.shortTime(Instant.parse("2026-10-10T04:30:00Z"), zone, Locale.US))
    }

    @Test
    fun eventSubtitleJoinsWithAMiddleDot() {
        val event = Event(id = "e", startAt = Instant.parse("2026-10-10T04:00:00Z"), venue = Venue(id = "v", name = "The Wildrose"))
        assertEquals("Fri 9pm · The Wildrose", Formatting.eventSubtitle(event, zone, Locale.US))
    }

    @Test
    fun distances() {
        assertEquals("350 m", Formatting.distance(350.0, Locale.GERMANY))
        assertEquals("1,2 km", Formatting.distance(1200.0, Locale.GERMANY))
        assertEquals("2 km", Formatting.distance(2000.0, Locale.FRANCE))
        assertEquals("0.7 mi", Formatting.distance(1100.0, Locale.US))
        assertEquals("164 ft", Formatting.distance(50.0, Locale.US))
    }
}

class HotMessApiTest {
    private fun api(vararg responses: Pair<String, String>): Pair<HotMessApi, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { request ->
            val body = request.body?.toString(Charsets.UTF_8).orEmpty()
            bodies += body
            val match = responses.firstOrNull { body.contains(it.first) || request.url.endsWith(it.first) }
                ?: return@HttpTransport com.audiencekit.HttpResponse(404, ByteArray(0))
            com.audiencekit.HttpResponse(200, match.second.toByteArray())
        }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client) to bodies
    }

    @Test
    fun nowWithoutAPositionMakesNoRequest() = runTest {
        val (api, bodies) = api()
        assertEquals(Now(), api.now(null))
        assertTrue(bodies.isEmpty())
    }

    @Test
    fun nowReportsThePosition() = runTest {
        val (api, bodies) = api("ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"Capitol Hill","venues":[],"events":[${Fixtures.EVENT}]}}}}""")
        val now = api.now(Coordinates(47.61, -122.32))

        assertEquals("Capitol Hill", now.title)
        assertTrue(now.isNearVenues)
        assertEquals(1, now.events.size)
        assertTrue(bodies.single().contains("\"latitude\":47.61"))
    }

    @Test
    fun nowAtAVenueHasItsRecentChatAndFriends() = runTest {
        val (api, _) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"Nyne","venues":null,"events":[],
                "venue":{"id":"v1","name":"Nyne","recentMessages":[{"id":"m1","message":"who's here?","name":"Sam",
                  "userId":"u1","avatarUrl":"","sentAt":"2026-10-07T08:00:00Z"}]},
                "friends":[{"id":"f1","name":"Alex Friend","facebookId":"4242"}]}}}}""",
        )
        val now = api.now(Coordinates(47.66, -117.41))

        val line = now.venue!!.recentMessages.single()
        assertEquals("who's here?", line.message)
        assertEquals("Sam", line.name)
        assertNull(line.avatarUrl)
        assertEquals(Instant.parse("2026-10-07T08:00:00Z"), line.sentAt)
        assertEquals("Alex", now.friends.single().firstName)
        assertEquals("https://m.me/4242", now.friends.single().messengerUrl)
    }

    @Test
    fun missingRecordsAreNotFound() = runTest {
        val (api, _) = api("query Event" to """{"data":{"event":null}}""")
        assertFailsWith<ApiError.NotFound> { api.event("e") }
    }

    @Test
    fun graphQLErrorsBecomeAFriendlyError() = runTest {
        val (api, _) = api("query People" to """{"data":null,"errors":[{"message":"Field 'x' doesn't exist"}]}""")
        val error = assertFailsWith<ApiError.Other> { api.people() }
        assertTrue(!error.message!!.contains("Field"))
    }

    @Test
    fun manifestWithoutAnAndroidEntryMeansNoMinimum() {
        assertNull(HotMessApi.parseManifest("""{"client":{"mobile":{"apple":{"minimum_build":1000}}}}"""))
        assertEquals(
            12,
            HotMessApi.parseManifest("""{"client":{"mobile":{"android":{"minimum_build":12,"minimum_version":"1.2"}}}}""")?.minimumBuild,
        )
    }
}

private object Fixtures {
    const val VENUE = """{"id":"v-1","name":"The Wildrose","address":"1021 E Pike St","distance":120.5,
        "point":"POINT (-122.3194 47.6145)","facebookId":"55","photoUrl":"https://img/v","heroUrl":null,"isLiked":true}"""

    const val EVENT = """{"id":"e-1","name":"Drag Brunch","startAt":"2026-10-10T04:00:00Z","endAt":null,
        "facebookId":"1234","coverPhotoUrl":"","isFeatured":false,"viewerRsvp":"MAYBE","venue":$VENUE}"""

    const val PERSON = """{"id":"p-1","name":"DJ Glitter","facebookId":null,"isLiked":false,"pictureUrl":"https://img/p",
        "coverUrl":null,"events":[$EVENT],"socialLinks":[{"id":"s-1","handle":"glitter","provider":"Instagram","url":"https://instagram.com/glitter"}],
        "tracks":[{"id":"t-1","title":"Late Night","provider":"soundcloud","providerUrl":null,"waveformUrl":null,"artworkUrl":null}]}"""
}
