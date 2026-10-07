package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.Coordinates
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PingModelTest {
    private val json = HotMessApi.json

    @Test
    fun decodesAPingWithPicksAndJoins() {
        val ping = json.decodeFromString(Ping.serializer(), PingFixtures.FRIEND_PING)

        assertEquals("Jordan Lee", ping.user.name)
        assertEquals("who's coming 🙃", ping.noteText)
        assertEquals(Instant.parse("2026-10-10T12:00:00Z"), ping.expiresAt)
        assertFalse(ping.isMine)
        assertEquals(listOf("Drag Bingo", "The Wildrose"), ping.targets.map { it.name })
        assertEquals("Drag Bingo or The Wildrose", ping.placesSummary)
        assertEquals("Sam and Alex are in", ping.whoIsIn)
        assertEquals(listOf("t-1"), ping.targets[0].joins.map { it.targetId })
        assertEquals(PingReach.FRIENDS_OF_CIRCLE, ping.reach)
        assertEquals("via Sam", ping.viaText)
        assertEquals(listOf("Jordan", "Sam", "Alex"), ping.circle.map { it.firstName })
    }

    @Test
    fun reachDefaultsToFriendsOnly() {
        val mine = json.decodeFromString(Ping.serializer(), PingFixtures.MY_PING)
        assertEquals(PingReach.FRIENDS, mine.reach)
        assertNull(mine.viaText)
        assertEquals(listOf("Rick"), mine.circle.map { it.firstName })
        val unknown = json.decodeFromString(Ping.serializer(), PingFixtures.MY_PING.replace("\"reach\":\"FRIENDS\"", "\"reach\":\"EVERYONE\""))
        assertEquals(PingReach.FRIENDS, unknown.reach)
        assertEquals("Friends of the circle", PingReach.FRIENDS_OF_CIRCLE.title)
    }

    @Test
    fun aPingWithNoPicksIsForAnywhere() {
        val ping = Ping(id = "p", user = Friend("u", "Riley"))
        assertTrue(ping.isAnywhere)
        assertEquals("Anywhere tonight?", ping.placesSummary)
        assertNull(ping.whoIsIn)
        assertNull(ping.copy(note = "  ").noteText)
    }

    @Test
    fun knowsWhichPickYouAreIn() {
        val ping = json.decodeFromString(Ping.serializer(), PingFixtures.FRIEND_PING).let { ping ->
            val me = Friend("ME", "Me Myself")
            ping.copy(
                joined = true,
                joins = ping.joins + PingJoin("j-9", me, targetId = "t-2"),
                targets = ping.targets.map { if (it.id == "t-2") it.copy(joins = it.joins + PingJoin("j-9", me, "t-2")) else it },
            )
        }

        assertTrue(ping.isIn(ping.targets[1], "me"))
        assertFalse(ping.isIn(ping.targets[0], "me"))
        assertFalse(ping.isIn(null, "me"))
        assertFalse(ping.copy(joined = false).isIn(ping.targets[1], "me"))
    }

    @Test
    fun withoutYourIdOnlyJoinedIsKnown() {
        val anywhere = Ping(id = "p", user = Friend("u", "Riley"), joined = true)
        assertTrue(anywhere.isIn(null, null))
        assertTrue(anywhere.copy(joins = listOf(PingJoin("j", Friend("me"), null))).isIn(null, "me"))
        assertFalse(anywhere.copy(joined = false).isIn(null, null))
    }

    @Test
    fun findsThePickForAVenueOrItsEvent() {
        val ping = json.decodeFromString(Ping.serializer(), PingFixtures.FRIEND_PING)
        assertEquals("t-2", ping.pickForVenue("v-wildrose")?.id)
        assertEquals("t-1", ping.pickForVenue("v-neighbours")?.id, "a venue's pick includes its events")
        assertEquals("t-1", ping.pickForEvent("e-bingo")?.id)
        assertNull(ping.pickForEvent("e-other"))
    }

    @Test
    fun idsMatchAcrossForms() {
        val uuid = "b0f8b66a-e636-495d-9475-0f5317ea08e0"
        val global = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("gid://audience-kit/Venue/$uuid".toByteArray())
        assertTrue(RecordId.same(uuid.uppercase(), global))
        assertTrue(RecordId.same("t-1", "T-1"))
        assertFalse(RecordId.same("t-1", null))
        assertFalse(RecordId.same("t-1", "t-2"))
    }

    @Test
    fun nowSwapsInAnUpdatedPing() {
        val friend = json.decodeFromString(Ping.serializer(), PingFixtures.FRIEND_PING)
        val now = Now(friendPings = listOf(friend, Ping(id = "other", user = Friend("x"))))

        val joined = now.replacingPing(friend.copy(joined = true))
        assertTrue(joined.friendPings.first().joined)
        assertEquals("other", joined.friendPings[1].id)

        val mine = Ping(id = "mine", user = Friend("me"), isMine = true)
        assertEquals(mine, now.replacingPing(mine).myPing)
    }
}

class PingLogicTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val locale = "11111111-1111-1111-1111-111111111111"

    private fun event(id: String, start: String) = Event(id = id, name = id, startAt = Instant.parse(start))

    @Test
    fun choicesAreTonightsEventsThenTheLocalesVenues() {
        // Friday 10pm in Seattle; the night runs until 5am Saturday.
        val now = Instant.parse("2026-10-10T05:00:00Z")
        val choices = PingChoices.of(
            events = listOf(
                event("late", "2026-10-10T09:30:00Z"), // 2:30am Saturday, still Friday night
                event("early", "2026-10-10T03:00:00Z"), // 8pm Friday
                event("tomorrow", "2026-10-11T03:00:00Z"),
                event("yesterday", "2026-10-09T03:00:00Z"),
            ),
            venues = listOf(
                Venue(id = "b", name = "Bar", locale = LocaleSummary(locale)),
                Venue(id = "x", name = "Elsewhere", locale = LocaleSummary("22222222-2222-2222-2222-222222222222")),
                Venue(id = "a", name = "Arc", locale = LocaleSummary(locale)),
                Venue(id = "h", name = "Hidden", hidden = true, locale = LocaleSummary(locale)),
            ),
            localeId = locale,
            myPing = null,
            now = now,
            zone = zone,
        )

        assertEquals(listOf("early", "late"), choices.events.map { it.id })
        assertEquals(listOf("a", "b"), choices.venues.map { it.id })
        assertTrue(PingChoices.isTonight(event("late", "2026-10-10T09:30:00Z"), now, zone))
        assertFalse(PingChoices.isTonight(event("tomorrow", "2026-10-11T03:00:00Z"), now, zone))
    }

    @Test
    fun choicesIncludeAPickFromElsewhere() {
        val far = Venue(id = "far", name = "Far")
        val choices = PingChoices(venues = listOf(Venue(id = "a"))).including(listOf(PingPick.VenuePick(far), PingPick.VenuePick(Venue(id = "A"))))
        assertEquals(listOf("far", "a"), choices.venues.map { it.id })
    }

    @Test
    fun selectionTogglesInPickOrder() {
        val bingo = PingPick.EventPick(event("bingo", "2026-10-10T04:00:00Z"))
        val wildrose = PingPick.VenuePick(Venue(id = "wildrose"))
        val nyne = PingPick.VenuePick(Venue(id = "nyne"))

        val selection = PingSelection().toggle(nyne).toggle(bingo).toggle(wildrose)
        assertEquals(listOf("nyne", "wildrose"), selection.venueIds)
        assertEquals(listOf("bingo"), selection.eventIds)
        assertEquals(3, selection.count)

        val without = selection.toggle(PingPick.VenuePick(Venue(id = "NYNE")))
        assertEquals(listOf("wildrose"), without.venueIds)
        assertTrue(PingSelection().isEmpty)
    }

    @Test
    fun editingStartsFromThePingsPicksPlusTheOneOpenedFrom() {
        val ping = HotMessApi.json.decodeFromString(Ping.serializer(), PingFixtures.FRIEND_PING)
        val here = PingPick.VenuePick(Venue(id = "nyne"))

        val selection = PingSelection.of(ping, here)
        assertEquals(listOf("e-bingo"), selection.eventIds)
        assertEquals(listOf("v-wildrose", "nyne"), selection.venueIds)
        assertEquals(listOf("v-wildrose"), PingSelection.of(ping, PingPick.VenuePick(Venue(id = "V-WILDROSE"))).venueIds)
        assertTrue(PingSelection.of(null).isEmpty)
    }

    @Test
    fun stripNamesTheSenders() {
        val jordan = Ping(id = "1", user = Friend("u1", "Jordan Lee"))
        val sam = Ping(id = "2", user = Friend("u2", "Sam Chatter"))
        val kai = Ping(id = "3", user = Friend("u3", "Kai"))

        assertEquals("Jordan wants to come here tonight", PingStrip.headline(listOf(jordan)))
        assertEquals("3 friends want to come here tonight", PingStrip.headline(listOf(jordan, sam, kai)))
        assertEquals("Jordan, Sam and Kai", PingStrip.names(listOf(jordan, sam, kai)))
        assertEquals("Jordan", PingStrip.names(listOf(jordan, jordan.copy(id = "4"))))
    }

    @Test
    fun readsPingPushes() {
        assertEquals(PingPush(PingPush.Kind.PING, "p-1"), PingPush.parse(mapOf("kind" to "ping", "ping_id" to "p-1")))
        assertEquals(PingPush(PingPush.Kind.PING_JOIN, null), PingPush.parse(mapOf("kind" to "ping_join", "ping_id" to "")))
        assertNull(PingPush.parse(mapOf("kind" to "chat")))
        assertNull(PingPush.parse(emptyMap()))
        assertEquals(AppTab.NOW, AppRoute.NowPing("p-1").tab)
    }

    @Test
    fun formatsClockTimesAndLists() {
        assertEquals("5am", Formatting.clock(Instant.parse("2026-10-10T12:00:00Z"), zone))
        assertEquals("9:46pm", Formatting.clock(Instant.parse("2026-10-10T04:46:00Z"), zone))
        assertEquals("A or B", Formatting.list(listOf("A", "B"), "or"))
        assertEquals("A, B and C", Formatting.list(listOf("A", "B", "C"), "and"))
        assertEquals("A, B, C and 2 others", Formatting.list(listOf("A", "B", "C", "D", "E"), "and", limit = 3))
        assertEquals("", Formatting.list(emptyList(), "and"))
    }
}

class PingApiTest {
    private fun api(vararg responses: Pair<String, String>): Pair<HotMessApi, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { request ->
            val body = request.body?.toString(Charsets.UTF_8).orEmpty()
            bodies += body
            val match = responses.firstOrNull { body.contains(it.first) } ?: return@HttpTransport HttpResponse(404, ByteArray(0))
            HttpResponse(200, match.second.toByteArray())
        }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client) to bodies
    }

    /** A null variable, whether the SDK writes it out or leaves it out. */
    private fun assertNullValue(value: JsonElement?, message: String? = null) =
        assertTrue(value == null || value == JsonNull, message ?: "expected null, got $value")

    private fun variables(body: String) = HotMessApi.json.parseToJsonElement(body).jsonObject["variables"]!!.jsonObject

    @Test
    fun nowCarriesYourPingAndYourFriends() = runTest {
        val (api, bodies) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"Capitol Hill","venues":[],"events":[],
                "myPing":${PingFixtures.MY_PING},"friendPings":[${PingFixtures.FRIEND_PING}]}}}}""",
        )
        val now = api.now(Coordinates(47.61, -122.32))

        assertTrue(now.myPing!!.isMine)
        assertTrue(now.myPing!!.isAnywhere)
        assertEquals("Jordan", now.friendPings.single().user.firstName)
        assertTrue(bodies.single().contains("myPing {"))
        assertTrue(bodies.single().contains("friendPings {"))
    }

    @Test
    fun withoutAPositionNowStillHasPings() = runTest {
        val (api, bodies) = api(
            "query MyPing" to """{"data":{"myPing":${PingFixtures.MY_PING}}}""",
            "query FriendPings" to """{"data":{"friendPings":[${PingFixtures.FRIEND_PING}]}}""",
        )
        val now = api.nowOrPings(null)
        assertEquals("p-mine", now.myPing?.id)
        assertEquals(1, now.friendPings.size)
        assertTrue(bodies.none { it.contains("ReportLocation") })
    }

    @Test
    fun nowWithoutPingsDecodes() = runTest {
        val (api, _) = api("ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"x","myPing":null,"friendPings":[]}}}}""")
        val now = api.now(Coordinates(1.0, 2.0))
        assertNull(now.myPing)
        assertTrue(now.friendPings.isEmpty())
    }

    @Test
    fun sendsAPingWithItsPicksAndNote() = runTest {
        val (api, bodies) = api("SendPing" to """{"data":{"sendPing":{"ping":${PingFixtures.MY_PING}}}}""")
        val ping = api.sendPing(
            venueIds = listOf("v-1"),
            eventIds = listOf("e-1", "e-2"),
            note = "  ",
            localeId = "loc-1",
            reach = PingReach.FRIENDS_OF_CIRCLE,
        )

        assertTrue(ping.isMine)
        val sent = variables(bodies.single())
        assertEquals(listOf("v-1"), sent["venueIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("e-1", "e-2"), sent["eventIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNullValue(sent["note"], "a blank note is no note")
        assertEquals("loc-1", sent["localeId"]!!.jsonPrimitive.content)
        assertEquals("FRIENDS_OF_CIRCLE", sent["reach"]!!.jsonPrimitive.content)
    }

    @Test
    fun resendingWithoutAReachKeepsTheExistingOne() = runTest {
        val (api, bodies) = api("SendPing" to """{"data":{"sendPing":{"ping":${PingFixtures.MY_PING}}}}""")
        api.sendPing(venueIds = emptyList(), eventIds = emptyList(), note = "anyone?", localeId = null)

        val sent = variables(bodies.single())
        assertNullValue(sent["reach"])
        assertNullValue(sent["localeId"])
        assertEquals("anyone?", sent["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun joinsAPickOrTheWholePing() = runTest {
        val (api, bodies) = api("JoinPing" to """{"data":{"joinPing":{"ping":${PingFixtures.FRIEND_PING}}}}""")
        api.joinPing("p-1", "t-1")
        api.joinPing("p-1", null)

        assertEquals("t-1", variables(bodies[0])["targetId"]!!.jsonPrimitive.content)
        assertNullValue(variables(bodies[1])["targetId"])
    }

    @Test
    fun leavesAndEnds() = runTest {
        val (api, bodies) = api(
            "LeavePing" to """{"data":{"leavePing":{"ping":${PingFixtures.FRIEND_PING}}}}""",
            "EndPing" to """{"data":{"endPing":{"ended":true}}}""",
        )
        assertEquals("p-1", api.leavePing("p-1").id)
        assertTrue(api.endPing())
        assertTrue(bodies[1].contains("endPing(input: {})"))
    }

    @Test
    fun queriesPingsWithoutReportingLocation() = runTest {
        val (api, _) = api(
            "query MyPing" to """{"data":{"myPing":null}}""",
            "query FriendPings" to """{"data":{"friendPings":[${PingFixtures.FRIEND_PING}]}}""",
        )
        assertNull(api.myPing())
        assertEquals(1, api.friendPings().size)
    }

    @Test
    fun pingErrorsAreShownAsWritten() = runTest {
        val (api, _) = api("JoinPing" to """{"data":null,"errors":[{"message":"That ping has ended"}]}""")
        val error = assertFailsWith<ApiError.Other> { api.joinPing("p-1", null) }
        assertEquals("That ping has ended", error.message)
    }

    @Test
    fun schemaErrorsAreNotShown() {
        assertNull(HotMessApi.userFacingMessage(listOf("Field 'sendPing' doesn't exist on type 'Mutation'")))
        assertNull(HotMessApi.userFacingMessage(emptyList()))
        assertEquals("Pick places in your city", HotMessApi.userFacingMessage(listOf("Pick places in your city")))
    }

    @Test
    fun registersTheDeviceWithItsAppId() = runTest {
        val (api, bodies) = api("RegisterDevice" to """{"data":{"registerDevice":{"registered":true}}}""")
        api.registerForPush("fcm-token", "social.hotmess.android")

        val sent = variables(bodies.single())
        assertEquals("fcm-token", sent["notificationToken"]!!.jsonPrimitive.content)
        assertEquals("social.hotmess.android", sent["appId"]!!.jsonPrimitive.content)
        assertFalse(sent["sandbox"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun venueAndEventPagesCarryFriendsPings() = runTest {
        val (api, _) = api(
            "query Venue" to """{"data":{"venue":{"id":"v-wildrose","name":"The Wildrose","friendPings":[${PingFixtures.FRIEND_PING}]}}}""",
            "query Event" to """{"data":{"event":{"id":"e-bingo","name":"Drag Bingo","startAt":"2026-10-10T04:00:00Z",
                "friendPings":[${PingFixtures.FRIEND_PING}]}}}""",
        )
        assertEquals("p-1", api.venueOverview("v-wildrose").friendPings.single().id)
        assertEquals("p-1", api.event("e-bingo").friendPings.single().id)
    }
}

private object PingFixtures {
    const val FRIEND_PING = """{"id":"p-1","note":"who's coming 🙃","createdAt":"2026-10-10T04:46:00Z",
        "expiresAt":"2026-10-10T12:00:00Z","isMine":false,"joined":false,"reach":"FRIENDS_OF_CIRCLE",
        "user":{"id":"u-jordan","name":"Jordan Lee","facebookId":"77"},"via":{"id":"u-sam","name":"Sam Chatter"},
        "targets":[
          {"id":"t-1","venue":null,"event":{"id":"e-bingo","name":"Drag Bingo","startAt":"2026-10-10T04:00:00Z",
            "venue":{"id":"v-neighbours","name":"Neighbours"}},
            "joins":[{"id":"j-1","targetId":"t-1","user":{"id":"u-sam","name":"Sam Chatter"}}]},
          {"id":"t-2","venue":{"id":"v-wildrose","name":"The Wildrose","photoUrl":""},"event":null,"joins":[]}
        ],
        "joins":[{"id":"j-1","targetId":"t-1","user":{"id":"u-sam","name":"Sam Chatter"}},
                 {"id":"j-2","targetId":null,"user":{"id":"u-alex","name":"Alex Friend"}}]}"""

    const val MY_PING = """{"id":"p-mine","note":null,"createdAt":"2026-10-10T04:00:00Z","expiresAt":"2026-10-10T12:00:00Z",
        "isMine":true,"joined":false,"reach":"FRIENDS","via":null,"user":{"id":"u-me","name":"Rick Mark"},"targets":[],"joins":[]}"""
}
