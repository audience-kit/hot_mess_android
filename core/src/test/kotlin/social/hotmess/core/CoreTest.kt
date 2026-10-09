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
import kotlin.test.assertFalse
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
    fun decodesATroupesMembersAndAPerformersGroups() {
        val troupe = json.decodeFromString(PersonDetail.serializer(), Fixtures.TROUPE)

        assertEquals(listOf("Anita Cocktail", "Ella Vator"), troupe.members.map { it.name })
        assertEquals("https://img/anita", troupe.members.first().pictureUrl)
        assertNull(troupe.members.last().pictureUrl, "an empty URL is no URL")
        assertEquals("p-2", troupe.groups.single().id)
        assertEquals("House of Glitter", troupe.groups.single().name)
    }

    @Test
    fun aPersonWithoutMembersOrGroupsHasNone() {
        val person = json.decodeFromString(PersonDetail.serializer(), Fixtures.PERSON)

        assertTrue(person.members.isEmpty())
        assertTrue(person.groups.isEmpty())
    }

    @Test
    fun musicLinksNameTheirService() {
        val spotify = SocialLink(id = "1", handle = "artist/4Z8W", provider = "spotify")
        val appleMusic = SocialLink(id = "2", handle = "us/artist/dugan/123", provider = "apple_music")
        val soundcloud = SocialLink(id = "3", handle = "dugan", provider = "soundcloud")

        assertEquals(SocialLink.Network.SPOTIFY, spotify.network)
        assertEquals("Spotify", spotify.label)
        assertEquals("Apple Music", appleMusic.label)
        assertEquals("/dugan", soundcloud.label)
    }

    @Test
    fun splitsTipLinksFromProfiles() {
        val person = PersonDetail(
            id = "p-1",
            socialLinks = listOf(
                SocialLink("s-1", "glitter", "instagram", "https://instagram.com/glitter"),
                SocialLink("s-2", "GlitterTips", "cashapp", "https://cash.app/\$GlitterTips"),
                SocialLink("s-3", "glitter-tips", "venmo", "https://venmo.com/u/glitter-tips"),
            ),
        )

        assertEquals(listOf("Cash App", "Venmo"), person.tipLinks.map { it.tipApp })
        assertEquals(listOf("instagram"), person.profileLinks.map { it.provider })
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
        assertEquals(listOf("2", "3"), venues.sortedForList(here).filter { it.isIn(here) }.map { it.id }.sorted())
        assertFalse(venues.first().isIn(null))
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
    fun subscribesToALocaleRoom() {
        val locale = "4e5f6071-8293-4A41-8d5e-6f7a8b9c0d1e"
        val identifier = HotMessApi.json.parseToJsonElement(ChatProtocol.identifier(ChatRoom.locale(locale))).jsonObject
        assertEquals("LocaleChannel", identifier["channel"]!!.jsonPrimitive.content)
        assertEquals(locale.lowercase(), identifier["locale_id"]!!.jsonPrimitive.content)
        assertNull(identifier["venue_id"])
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
    fun readsTheSenderAndTimeOfALine() {
        val line = ChatProtocol.parse(
            """{"identifier":"x","message":{"type":"incoming","id":"m-1","message":"hi","user_id":"U-1",
              "name":"Aurora B.","avatar_url":"https://cdn/a.jpg","sent_at":"2026-10-10T04:02:00Z","role":null,
              "kind":"text","title":null,"body":"hi","photo_url":null,"event":null,"ends_at":null,"pinned":false}}""",
        )
        val message = assertIs<ChatProtocol.Frame.Message>(line).message
        assertEquals("m-1", message.id)
        assertEquals("hi", message.body)
        assertEquals("Aurora B.", message.name)
        assertEquals("https://cdn/a.jpg", message.avatarUrl)
        assertEquals(Instant.parse("2026-10-10T04:02:00Z"), message.sentAt)
        assertNull(message.role)
        assertEquals(ChatKind.TEXT, message.kind)
        assertFalse(message.pinned)
        assertFalse(message.isPostedAsVenue)
    }

    @Test
    fun anOlderLineStillReadsAsPlainText() {
        val message = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse("""{"identifier":"x","message":{"message":"hi","user_id":"u-1","avatar_url":""}}"""),
        ).message
        assertNull(message.name)
        assertNull(message.sentAt)
        assertNull(message.avatarUrl, "an empty URL is no URL")
        assertEquals(ChatKind.TEXT, message.kind)
        assertTrue(message.id.isNotEmpty())
    }

    @Test
    fun readsRolesInAnyCase() {
        fun role(raw: String) = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse("""{"identifier":"x","message":{"message":"hi","user_id":"u-1","role":"$raw"}}"""),
        ).message.role

        assertEquals(ChatRole.VENUE, role("venue"))
        assertEquals(ChatRole.HOST, role("host"))
        assertEquals(ChatRole.STAFF, role("STAFF"))
        assertNull(role("bouncer"))
        assertEquals("Host", ChatRole.HOST.label)
    }

    @Test
    fun aPostAsTheVenueCarriesTheVenuesNameAndPhoto() {
        val message = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(
                """{"identifier":"x","message":{"message":"Doors at 9","user_id":"u-1","role":"venue",
                  "name":"Neighbours","avatar_url":"https://cdn/venue.jpg"}}""",
            ),
        ).message
        assertTrue(message.isPostedAsVenue)
        assertEquals("Neighbours", message.name)
        assertEquals("https://cdn/venue.jpg", message.avatarUrl)
    }

    @Test
    fun readsAnAnnouncement() {
        val message = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(
                """{"identifier":"x","message":{"type":"incoming","id":"m-2","message":"Announcement: Coat check closes at midnight\nGrab your things",
                  "user_id":"u-1","role":"venue","kind":"announcement","title":"Coat check closes at midnight",
                  "body":"Grab your things","photo_url":"https://cdn/p.jpg","pinned":true}}""",
            ),
        ).message
        assertEquals(ChatKind.ANNOUNCEMENT, message.kind)
        assertTrue(message.kind.isRich)
        assertEquals("Coat check closes at midnight", message.title)
        assertEquals("Grab your things", message.caption)
        assertEquals("https://cdn/p.jpg", message.photoUrl)
        assertTrue(message.pinned)
        assertTrue(message.body.startsWith("Announcement: "), "the summary stays the line's text")
    }

    @Test
    fun readsASharedEventAndAPhoto() {
        val event = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(
                """{"identifier":"x","message":{"message":"Shared an event: Sunset Social","user_id":"u-1","role":"host",
                  "kind":"event","body":"","event":{"id":"E-1","name":"Sunset Social","start_at":"2026-10-18T02:00:00Z"}}}""",
            ),
        ).message
        assertEquals(ChatKind.EVENT, event.kind)
        assertEquals(SharedEvent("E-1", "Sunset Social", Instant.parse("2026-10-18T02:00:00Z")), event.event)
        assertNull(event.caption, "an empty caption is none")

        val photo = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(
                """{"identifier":"x","message":{"message":"Shared a photo: Full house","user_id":"u-1","role":"venue",
                  "kind":"photo","body":"Full house","photo_url":"https://cdn/crowd.jpg"}}""",
            ),
        ).message
        assertEquals(ChatKind.PHOTO, photo.kind)
        assertEquals("Full house", photo.caption)
        assertEquals("https://cdn/crowd.jpg", photo.photoUrl)
    }

    @Test
    fun aSpecialStopsShowingAtItsEnd() {
        val special = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(
                """{"identifier":"x","message":{"message":"Special: Two for one","user_id":"u-1","role":"venue",
                  "kind":"special","title":"Two for one","body":"Well drinks","ends_at":"2026-10-10T06:00:00Z"}}""",
            ),
        ).message
        assertEquals(ChatKind.SPECIAL, special.kind)
        assertEquals(Instant.parse("2026-10-10T06:00:00Z"), special.endsAt)
        assertTrue(special.isShowing(Instant.parse("2026-10-10T05:59:00Z")))
        assertFalse(special.isShowing(Instant.parse("2026-10-10T06:00:00Z")))
    }

    @Test
    fun anUnknownKindReadsAsText() {
        val message = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse("""{"identifier":"x","message":{"message":"Shared a poll","user_id":"u-1","kind":"poll"}}"""),
        ).message
        assertEquals(ChatKind.TEXT, message.kind)
        assertEquals("Shared a poll", message.body)
    }

    @Test
    fun readsTheRosterAndPresence() {
        assertEquals(
            ChatProtocol.Frame.Roster(setOf("u-1", "u-2")),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"roster","online":["U-1","u-2"]}}"""),
        )
        assertEquals(
            ChatProtocol.Frame.PresenceChanged("u-3", online = true),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"presence","user_id":"U-3","presence":"online"}}"""),
        )
        assertEquals(
            ChatProtocol.Frame.PresenceChanged("u-3", online = false),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"presence","user_id":"u-3","presence":"offline"}}"""),
        )
        assertNull(ChatProtocol.parse("""{"identifier":"x","message":{"type":"presence","presence":"online"}}"""))
    }

    @Test
    fun readsWhoIsInTheRoomAndTheViewersFriends() {
        val frame = ChatProtocol.parse(
            """{"identifier":"x","message":{"type":"roster","online":["U-1","u-2"],
              "people":[{"user_id":"U-1","name":"Aurora Bell","avatar_url":"https://a/1.jpg","friend":true},
                        {"user_id":"u-2","name":"Jo P.","avatar_url":null},
                        {"name":"No Id"}, "junk"],
              "friends":[{"user_id":"U-1","name":"Aurora Bell"},{"user_id":"f-9","name":"Sam Chatter"},{"name":"x"}]}}""",
        )
        assertEquals(
            ChatProtocol.Frame.Roster(
                setOf("u-1", "u-2"),
                people = listOf(
                    RoomPerson("u-1", "Aurora Bell", "https://a/1.jpg", friend = true),
                    RoomPerson("u-2", "Jo P."),
                ),
                friends = listOf(Friend("u-1", "Aurora Bell"), Friend("f-9", "Sam Chatter")),
            ),
            frame,
        )
        // A newcomer comes with their name and photo; leaving carries neither.
        assertEquals(
            ChatProtocol.Frame.PresenceChanged("u-3", online = true, name = "Kiko M.", avatarUrl = "https://a/3.jpg"),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"presence","user_id":"u-3","presence":"online","name":"Kiko M.","avatar_url":"https://a/3.jpg"}}"""),
        )
        // Malformed extras don't cost the roster.
        assertEquals(
            ChatProtocol.Frame.Roster(setOf("u-1")),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"roster","online":["u-1"],"people":"nope","friends":7}}"""),
        )
    }

    @Test
    fun readsHistoryPinsAndPushPresence() {
        val history = ChatProtocol.parse(
            """{"identifier":"x","message":{"type":"history","messages":[
                {"id":"m-1","message":"first","user_id":"u-1","presence":"push"},
                {"message":"no sender"},
                {"id":"m-2","message":"second","user_id":"u-2","role":"staff"}],
              "pinned":{"id":"m-0","message":"Announcement: Doors","user_id":"u-3","kind":"announcement","title":"Doors","pinned":true}}}""",
        ) as ChatProtocol.Frame.History
        assertEquals(listOf("first", "second"), history.messages.map { it.body })
        assertEquals(true, history.messages.first().reachable)
        assertEquals(ChatRole.STAFF, history.messages.last().role)
        assertEquals("Doors", history.pinned?.title)

        val pin = ChatProtocol.parse(
            """{"identifier":"x","message":{"type":"pin","id":"m-0","pinned":true,
              "announcement":{"id":"m-0","message":"Announcement: Doors","user_id":"u-3","kind":"announcement","title":"Doors","pinned":true}}}""",
        ) as ChatProtocol.Frame.Pin
        assertEquals("m-0", pin.id)
        assertEquals("Doors", pin.announcement?.title)
        assertEquals(
            ChatProtocol.Frame.Pin("m-0", null),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"pin","id":"m-0","pinned":false}}"""),
        )

        assertEquals(
            ChatProtocol.Frame.Removed("m-1"),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"removed","id":"m-1"}}"""),
        )
        assertEquals(
            ChatProtocol.Frame.Cleared,
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"cleared","cleared_at":"2026-10-09T04:00:00.123Z"}}"""),
        )
        assertEquals(null, ChatProtocol.parse("""{"identifier":"x","message":{"type":"removed"}}"""))

        assertEquals(
            ChatProtocol.Frame.PresenceChanged("u-3", online = false, reachable = true),
            ChatProtocol.parse("""{"identifier":"x","message":{"type":"presence","user_id":"u-3","presence":"push"}}"""),
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

class FriendDirectoryTest {
    private val friendId = "b0f8b66a-e636-495d-9475-0f5317ea08e0"

    @Test
    fun swapsInAFriendsFullName() {
        val friends = FriendDirectory()
        friends.record(
            Now(
                friends = listOf(Friend(friendId.uppercase(), "Aurora Borealis")),
                friendVenues = listOf(FriendVenue(Venue("v1"), 1, listOf(Friend("f2", "Sam Chatter")))),
            ),
        )

        assertEquals("Aurora Borealis", friends.displayName(friendId, "Aurora B."))
        assertEquals("Sam Chatter", friends.fullName("F2"))
        assertEquals("Jo P.", friends.displayName("someone-else", "Jo P."), "strangers keep the room's short name")
        assertNull(friends.fullName(null))

        friends.clear()
        assertEquals("Aurora B.", friends.displayName(friendId, "Aurora B."))
    }
}

class RoomPeopleTest {
    @Test
    fun followsTheRosterAndPresence() {
        var room = RoomPeople().roster(setOf("U-1", "u-2"), listOf(RoomPerson("u-1", "Aurora Bell", friend = true)))
        assertEquals(setOf("u-1", "u-2"), room.ids)
        assertEquals("Aurora Bell", room["U-1"]?.name)

        room = room.joined("u-3", "Kiko M.", "https://a/3.jpg").left("u-2")
        assertEquals(setOf("u-1", "u-3"), room.ids)

        // An older server's bare roster keeps what was known; a chat line fills gaps only.
        room = room.roster(setOf("u-1", "u-3"))
        assertEquals(RoomPerson("u-1", "Aurora Bell", friend = true), room["u-1"])
        room = room.described("u-3", "Someone Else", null).described("u-4", "Not Here", null)
        assertEquals("Kiko M.", room["u-3"]?.name)
        assertNull(room["u-4"])
    }

    @Test
    fun hereNowPutsFriendsFirstAndLeavesOutTheViewer() {
        val viewer = "b0f8b66a-e636-495d-9475-0f5317ea08e0"
        val people = listOf(
            RoomPerson(viewer, "Me Myself"),
            RoomPerson("u-zed", "zed Q."),
            RoomPerson("u-amy", "Amy R."),
            RoomPerson("u-nameless"),
            RoomPerson("u-wren", "Wren Fox", friend = true),
            RoomPerson("u-bo", "Bo T."),
        )
        val directory = mapOf("u-bo" to "Bo Turner")
        val here = RoomPeople.hereNow(people, viewer.uppercase()) { directory[it] }
        assertEquals(listOf("Bo Turner", "Wren Fox", "Amy R.", "zed Q.", null), here.map { it.name })
        assertEquals(listOf(true, true, false, false, false), here.map { it.friend })
        assertTrue(RoomPeople.hereNow(listOf(RoomPerson(viewer)), viewer).isEmpty())
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
        assertEquals("Fri 9pm · To be announced", Formatting.eventSubtitle(event.copy(venue = null), zone, Locale.US))
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
    fun venueOverviewAsksForAndKeepsItsRecentChat() = runTest {
        val (api, bodies) = api(
            "query Venue(" to """{"data":{"venue":{"id":"v1","name":"Nyne","chatOpen":true,"events":[],
                "recentMessages":[{"id":"m1","message":"who's here?","name":"Sam","userId":"u1","avatarUrl":"",
                  "sentAt":"2026-10-07T08:00:00Z"}]}}}""",
        )
        val overview = api.venueOverview("v1")

        assertTrue(bodies.single().contains("recentMessages(limit: 3)"))
        assertTrue(overview.chatOpen)
        val line = overview.venue.recentMessages.single()
        assertEquals("who's here?", line.message)
        assertEquals("Sam", line.name)
        assertNull(line.avatarUrl)
    }

    @Test
    fun nowAwayFromVenuesCountsFriendsPerVenue() = runTest {
        val (api, _) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"Spokane","venues":[],"events":[],
                "friendVenues":[{"venue":{"id":"v1","name":"Nyne"},"friendCount":2,
                  "friends":[{"id":"f1","name":"Alex Friend"},{"id":"f2","name":"Sam Chatter"}]}]}}}}""",
        )
        val entry = api.now(Coordinates(47.66, -117.43)).friendVenues.single()

        assertEquals("Nyne", entry.venue.name)
        assertEquals(2, entry.friendCount)
        assertEquals(listOf("Alex", "Sam"), entry.friends.map { it.firstName })
    }

    @Test
    fun nowAwayFromVenuesHasTheLocaleChat() = runTest {
        val (api, bodies) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"Seattle","venues":[],"events":[],
                "locale":{"id":"l1","name":"Seattle","chatOpen":true,"recentMessages":[{"id":"m1",
                  "message":"anyone out?","name":"Ada","userId":"u1","sentAt":"2026-10-07T08:00:00Z"}]}}}}}""",
        )
        val locale = api.now(Coordinates(47.62, -122.32)).locale!!

        assertTrue(locale.chatOpen)
        assertEquals("Seattle", locale.name)
        assertEquals("anyone out?", locale.recentMessages.single().message)
        assertTrue(bodies.single().contains("chatOpen"))
    }

    @Test
    fun recentChatAndFriendsCarryPresenceAndRoles() = runTest {
        val (api, bodies) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"now":{"title":"Nyne","venues":null,"events":[],
                "venue":{"id":"v1","name":"Nyne","recentMessages":[{"id":"m1","message":"Special: Two for one",
                  "name":"Nyne","userId":"u1","avatarUrl":"","sentAt":"2026-10-07T08:00:00Z","presence":"OFFLINE",
                  "role":"VENUE","kind":"SPECIAL","endsAt":"2026-10-07T09:00:00Z","postedAsVenue":true},
                  {"id":"m2","message":"hi","name":"Alex Friend","userId":"f1","presence":"ONLINE","kind":"TEXT"}]},
                "friends":[{"id":"f1","name":"Alex Friend","facebookId":"4242","presence":"PUSH"}]}}}}""",
        )
        val now = api.now(Coordinates(47.66, -117.41))

        assertTrue(bodies.single().contains("presence role kind endsAt postedAsVenue"))
        val (special, hello) = now.venue!!.recentMessages
        assertEquals(ChatRole.VENUE, special.role)
        assertEquals(ChatKind.SPECIAL, special.kind)
        assertTrue(special.postedAsVenue)
        assertFalse(special.isShowing(Instant.parse("2026-10-07T09:30:00Z")))
        assertEquals(com.audiencekit.Presence.ONLINE, hello.presence)
        assertNull(hello.role)
        assertEquals(ChatKind.TEXT, hello.kind)
        assertEquals(com.audiencekit.Presence.PUSH, now.friends.single().presence)
        assertEquals("Alex Friend", api.friends.fullName("f1"), "Now's friends are remembered for chat")
    }

    @Test
    fun venueOverviewHasItsSocialLinks() = runTest {
        val (api, bodies) = api(
            "query Venue(" to """{"data":{"venue":{"id":"v-1","name":"The Wildrose","chatOpen":true,"events":[${Fixtures.EVENT}],
                "socialLinks":[{"id":"s-1","handle":"thewildrosebar","provider":"instagram","url":"https://instagram.com/thewildrosebar"},
                  {"id":"s-2","handle":"wildrose","provider":"facebook","url":""}]}}}""",
        )
        val overview = api.venueOverview("v-1")

        assertTrue(bodies.single().contains("socialLinks"))
        assertTrue(overview.chatOpen)
        assertEquals(1, overview.events.size)
        val links = overview.venue.socialLinks
        assertEquals(listOf(SocialLink.Network.INSTAGRAM, SocialLink.Network.FACEBOOK), links.map { it.network })
        assertEquals("https://instagram.com/thewildrosebar", links.first().url)
        assertNull(links.last().url, "an empty URL is no URL")
    }

    @Test
    fun venueOverviewHasTheFriendsHere() = runTest {
        val (api, bodies) = api(
            "query Venue(" to """{"data":{"venue":{"id":"v1","name":"Nyne","chatOpen":true,"events":[],
                "friends":[{"id":"f1","name":"Alex Friend","facebookId":"4242","presence":"ONLINE"}]}}}""",
        )
        val overview = api.venueOverview("v1")

        assertTrue(bodies.single().contains("friends { id name facebookId presence }"))
        val friend = overview.friends.single()
        assertEquals("Alex Friend", friend.name)
        assertEquals("https://m.me/4242", friend.messengerUrl)
        assertEquals(com.audiencekit.Presence.ONLINE, friend.presence)
        assertEquals("Alex Friend", api.friends.fullName("f1"), "the venue's friends are remembered for chat")
    }

    @Test
    fun aVenueWithoutFriendsHasNone() = runTest {
        val (api, _) = api("query Venue(" to """{"data":{"venue":{"id":"v1","name":"Nyne","events":[]}}}""")
        assertTrue(api.venueOverview("v1").friends.isEmpty())
    }

    @Test
    fun aVenueWithoutLinksHasNone() {
        assertTrue(HotMessApi.json.decodeFromString(Venue.serializer(), Fixtures.VENUE).socialLinks.isEmpty())
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

    const val TROUPE = """{"id":"p-3","name":"The Glitterettes","pictureUrl":null,"coverUrl":null,"events":[],"socialLinks":[],"tracks":[],
        "members":[{"id":"p-4","name":"Anita Cocktail","pictureUrl":"https://img/anita"},{"id":"p-5","name":"Ella Vator","pictureUrl":""}],
        "groups":[{"id":"p-2","name":"House of Glitter","pictureUrl":null}]}"""
}
