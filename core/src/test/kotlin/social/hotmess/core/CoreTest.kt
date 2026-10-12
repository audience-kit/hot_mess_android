package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.Coordinates
import com.audiencekit.GraphQLError
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.async
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
        assertNull(event.bannerUrl, "no banner is null")
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
        assertEquals("https://facebook.com/99", person.person.facebookUrl, "the Page comes through the profile mixin")
        assertFalse(person.isLiked)
        assertEquals("Late Night", person.tracks.single().title)
        assertEquals(1, person.events.size)
    }

    @Test
    fun decodesATroupesMembersAndAPerformersGroups() {
        val troupe = json.decodeFromString(PersonDetail.serializer(), Fixtures.TROUPE)

        assertEquals(listOf("Anita Cocktail", "Ella Vator"), troupe.members.map { it.name })
        assertEquals("https://img/anita", troupe.members.first().avatarUrl)
        assertNull(troupe.members.last().avatarUrl, "no picture is null")
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
            profile = ListingProfile(
                socialLinks = Connection(
                    listOf(
                        SocialLink("s-1", "glitter", "instagram", "https://instagram.com/glitter"),
                        SocialLink("s-2", "GlitterTips", "cashapp", "https://cash.app/\$GlitterTips"),
                        SocialLink("s-3", "glitter-tips", "venmo", "https://venmo.com/u/glitter-tips"),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Cash App", "Venmo"), person.tipLinks.map { it.tipApp })
        assertEquals(listOf("instagram"), person.profileLinks.map { it.provider })
    }

    @Test
    fun recordIdsNormalizeToLowercaseUuids() {
        val uuid = "B0F8B66A-E636-495D-9475-0F5317EA08E0"

        assertEquals(uuid.lowercase(), RecordId.normalize(uuid))
        assertEquals(uuid.lowercase(), RecordId.normalize(uuid.lowercase()))
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
        Event(id = id, name = id, startAt = Instant.parse(start), bannerUrl = cover)

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
        assertEquals(AppRoute.NowPing(id), DeepLink.route("https://hotmess.social/pings/$id"), "a push's url")
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
    @Test
    fun buildsTheIdentifierWithSortedKeys() {
        assertEquals(
            """{"audience_id":"a0f8b66a-e636-495d-9475-0f5317ea08e0","channel":"GraphQLChannel","channelId":"c-1"}""",
            GraphQLCable.identifier("A0F8B66A-E636-495D-9475-0F5317EA08E0", "c-1"),
        )
    }

    private val venue = "b0f8b66a-e636-495d-9475-0f5317ea08e0"
    private val audience = "A0F8B66A-E636-495D-9475-0F5317EA08E0"

    /** A subscription result carrying one ChatRoomEvent, as GraphQLChannel transmits it. */
    private fun result(event: String, more: Boolean = true) =
        """{"identifier":"x","message":{"result":{"data":{"venueChat":$event}},"more":$more}}"""

    /** A ChatMessagePosted with [fields] as its message. */
    private fun posted(fields: String, presence: String? = null) =
        result("""{"__typename":"ChatMessagePosted","senderPresence":${presence?.let { "\"$it\"" } ?: "null"},"message":{$fields}}""")

    private fun line(fields: String): VenueMessage =
        assertIs<ChatProtocol.Frame.Message>(ChatProtocol.parse(posted(fields)).single()).message

    @Test
    fun subscribesToTheGraphQLChannelWithAStringIdentifier() {
        val identifier = ChatProtocol.identifier(audience, "c-1")
        val fields = HotMessApi.json.parseToJsonElement(identifier).jsonObject
        assertEquals("GraphQLChannel", fields["channel"]!!.jsonPrimitive.content)
        assertEquals(audience.lowercase(), fields["audience_id"]!!.jsonPrimitive.content)
        assertEquals("c-1", fields["channelId"]!!.jsonPrimitive.content)

        val frame = HotMessApi.json.parseToJsonElement(ChatProtocol.subscribe(identifier)).jsonObject
        assertEquals("subscribe", frame["command"]!!.jsonPrimitive.content)
        assertEquals(identifier, frame["identifier"]!!.jsonPrimitive.content, "the identifier is a JSON string")
        assertTrue(ChatProtocol.newChannelId() != ChatProtocol.newChannelId())
    }

    @Test
    fun executesTheRoomsSubscriptionOnTheChannel() {
        val identifier = ChatProtocol.identifier(audience, "c-1")
        val frame = HotMessApi.json.parseToJsonElement(ChatProtocol.execute(identifier, ChatRoom.venue(venue.uppercase()))).jsonObject
        assertEquals("message", frame["command"]!!.jsonPrimitive.content)
        assertEquals(identifier, frame["identifier"]!!.jsonPrimitive.content)
        val data = HotMessApi.json.parseToJsonElement(frame["data"]!!.jsonPrimitive.content).jsonObject
        assertEquals("execute", data["action"]!!.jsonPrimitive.content)
        val query = data["query"]!!.jsonPrimitive.content
        assertTrue(query.startsWith("subscription"), query)
        assertTrue(query.contains("venueChat(venueId: \$id)"), query)
        assertTrue(query.contains("pinnedMessage {"), query)
        assertEquals(venue, data["variables"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun followsALocaleRoom() {
        val locale = "4e5f6071-8293-4A41-8d5e-6f7a8b9c0d1e"
        val query = ChatProtocol.document(ChatRoom.locale(locale))
        assertTrue(query.contains("localeChat(localeId: \$id)"), query)
        assertFalse(query.contains("venueChat"))
        assertEquals(
            """{"localeId":"${locale.lowercase()}","message":"hi"}""",
            ChatProtocol.sendInput(ChatRoom.locale(locale), "hi").toString(),
        )
    }

    @Test
    fun readsServerFrames() {
        assertEquals(emptyList(), ChatProtocol.parse("""{"type":"welcome"}"""))
        assertEquals(listOf(ChatProtocol.Frame.Subscribed), ChatProtocol.parse("""{"type":"confirm_subscription","identifier":"x"}"""))
        assertEquals(listOf(ChatProtocol.Frame.Disconnected), ChatProtocol.parse("""{"type":"disconnect"}"""))
        assertEquals(emptyList(), ChatProtocol.parse("""{"type":"ping","message":1700000000}"""))
        assertEquals(emptyList(), ChatProtocol.parse("""{"message":1700000000}"""))
        assertEquals(emptyList(), ChatProtocol.parse("not json"))
        assertEquals(emptyList(), ChatProtocol.parse(result("""{"__typename":"ChatSomethingNew"}""")), "an unknown event is skipped")

        val message = line(""""message":"hi","userId":"U-1","avatarUrl":null""")
        assertEquals("hi", message.body)
        assertTrue(message.isOutgoing("u-1"))
    }

    @Test
    fun readsBeingTurnedAwayAndLeaving() {
        assertEquals(listOf(ChatProtocol.Frame.Rejected), ChatProtocol.parse("""{"type":"reject_subscription","identifier":"x"}"""))
        // Refused: errors and no data.
        assertEquals(
            listOf(ChatProtocol.Frame.Rejected),
            ChatProtocol.parse("""{"identifier":"x","message":{"result":{"data":null,"errors":[{"message":"You're not at the venue"}]},"more":false}}"""),
        )
        assertEquals(listOf(ChatProtocol.Frame.Left), ChatProtocol.parse(result("""{"__typename":"ChatLeft","reason":"AWAY"}""")))
        assertEquals(
            listOf(ChatProtocol.Frame.Left, ChatProtocol.Frame.Ended),
            ChatProtocol.parse(result("""{"__typename":"ChatLeft","reason":"BANNED"}""", more = false)),
        )
        // The server ending the operation with nothing more to say.
        assertEquals(listOf(ChatProtocol.Frame.Ended), ChatProtocol.parse("""{"identifier":"x","message":{"more":false}}"""))
    }

    @Test
    fun readsWhetherAnAdminIsOutOfRange() {
        assertEquals(
            listOf(ChatProtocol.Frame.Range(outOfRange = true)),
            ChatProtocol.parse(result("""{"__typename":"ChatRangeChanged","outOfRange":true}""")),
        )
        assertEquals(
            listOf(ChatProtocol.Frame.Range(outOfRange = false)),
            ChatProtocol.parse(result("""{"__typename":"ChatRangeChanged","outOfRange":false}""")),
        )
    }

    @Test
    fun readsTheSenderAndTimeOfALine() {
        val message = line(
            """"id":"m-1","message":"hi","userId":"U-1","name":"Aurora B.","avatarUrl":"https://cdn/a.jpg",
              "sentAt":"2026-10-10T04:02:00Z","role":null,"kind":"TEXT","title":null,"body":"hi","photoUrl":null,
              "event":null,"endsAt":null,"pinned":false,"postedAsVenue":false,"postedAsPerson":false,"person":null""",
        )
        assertEquals("m-1", message.id)
        assertEquals("hi", message.body)
        assertEquals("Aurora B.", message.name)
        assertEquals("https://cdn/a.jpg", message.avatarUrl)
        assertEquals(Instant.parse("2026-10-10T04:02:00Z"), message.sentAt)
        assertNull(message.role)
        assertEquals(ChatKind.TEXT, message.kind)
        assertFalse(message.pinned)
        assertFalse(message.isPostedAsVenue)
        assertFalse(message.reachable)
        assertNull(message.personId)
    }

    @Test
    fun aBareLineStillReadsAsPlainText() {
        val message = line(""""message":"hi","userId":"u-1","avatarUrl":"" """)
        assertNull(message.name)
        assertNull(message.sentAt)
        assertNull(message.avatarUrl, "an empty URL is no URL")
        assertEquals(ChatKind.TEXT, message.kind)
        assertTrue(message.id.isNotEmpty())
        // A line with no sender is dropped.
        assertEquals(emptyList(), ChatProtocol.parse(posted(""""message":"no sender"""")))
    }

    @Test
    fun readsRolesInAnyCase() {
        fun role(raw: String) = line(""""message":"hi","userId":"u-1","role":"$raw"""").role

        assertEquals(ChatRole.VENUE, role("VENUE"))
        assertEquals(ChatRole.HOST, role("HOST"))
        assertEquals(ChatRole.STAFF, role("staff"))
        assertNull(role("BOUNCER"))
        assertEquals("Host", ChatRole.HOST.label)
    }

    @Test
    fun aPostAsTheVenueCarriesTheVenuesNameAndPhoto() {
        val message = line(
            """"message":"Doors at 9","userId":"u-1","role":"VENUE","postedAsVenue":true,
              "name":"Neighbours","avatarUrl":"https://cdn/venue.jpg"""",
        )
        assertTrue(message.isPostedAsVenue)
        assertEquals("Neighbours", message.name)
        assertEquals("https://cdn/venue.jpg", message.avatarUrl)
    }

    @Test
    fun aPostAsAPersonIsTheirListingNotTheSender() {
        val message = line(
            """"message":"Doors at 9","userId":"u-1","role":"HOST","name":"Miss Texas",
              "postedAsPerson":true,"person":{"id":"P-1"}""",
        )
        assertEquals("p-1", message.personId)
        assertTrue(message.isFromListing)
        assertFalse(message.isPostedAsVenue)
        assertEquals(ChatRole.HOST, message.role)

        // A person on a line someone posted as themself doesn't make it a listing's.
        assertNull(line(""""message":"hi","userId":"u-1","postedAsPerson":false,"person":{"id":"P-1"}""").personId)
    }

    @Test
    fun readsAnAnnouncement() {
        val message = line(
            """"id":"m-2","message":"Announcement: Coat check closes at midnight\nGrab your things",
              "userId":"u-1","role":"VENUE","kind":"ANNOUNCEMENT","title":"Coat check closes at midnight",
              "body":"Grab your things","photoUrl":"https://cdn/p.jpg","pinned":true""",
        )
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
        val event = line(
            """"message":"Shared an event: Sunset Social","userId":"u-1","role":"HOST",
              "kind":"EVENT","body":"","event":{"id":"E-1","name":"Sunset Social","startAt":"2026-10-18T02:00:00Z"}""",
        )
        assertEquals(ChatKind.EVENT, event.kind)
        assertEquals(SharedEvent("E-1", "Sunset Social", Instant.parse("2026-10-18T02:00:00Z")), event.event)
        assertNull(event.caption, "an empty caption is none")

        val photo = line(
            """"message":"Shared a photo: Full house","userId":"u-1","role":"VENUE",
              "kind":"PHOTO","body":"Full house","photoUrl":"https://cdn/crowd.jpg"""",
        )
        assertEquals(ChatKind.PHOTO, photo.kind)
        assertEquals("Full house", photo.caption)
        assertEquals("https://cdn/crowd.jpg", photo.photoUrl)
    }

    @Test
    fun aSpecialStopsShowingAtItsEnd() {
        val special = line(
            """"message":"Special: Two for one","userId":"u-1","role":"VENUE",
              "kind":"SPECIAL","title":"Two for one","body":"Well drinks","endsAt":"2026-10-10T06:00:00Z"""",
        )
        assertEquals(ChatKind.SPECIAL, special.kind)
        assertEquals(Instant.parse("2026-10-10T06:00:00Z"), special.endsAt)
        assertTrue(special.isShowing(Instant.parse("2026-10-10T05:59:00Z")))
        assertFalse(special.isShowing(Instant.parse("2026-10-10T06:00:00Z")))
    }

    @Test
    fun anUnknownKindReadsAsText() {
        val message = line(""""message":"Shared a poll","userId":"u-1","kind":"POLL"""")
        assertEquals(ChatKind.TEXT, message.kind)
        assertEquals("Shared a poll", message.body)
    }

    @Test
    fun aLineFromSomeoneANotificationReachesIsReachable() {
        fun reachable(presence: String?) = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(posted(""""message":"hi","userId":"u-1"""", presence)).single(),
        ).message.reachable

        assertTrue(reachable("PUSH"))
        assertFalse(reachable("ONLINE"))
        assertFalse(reachable(null))
    }

    @Test
    fun joiningSaysWhoIsHereWhatWasSaidAndWhatsPinned() {
        val frames = ChatProtocol.parse(
            result(
                """{"__typename":"ChatJoined","outOfRange":true,
                  "roster":{
                    "people":[{"userId":"U-1","name":"Aurora Bell","avatarUrl":"https://a/1.jpg","friend":true},
                              {"userId":"u-2","name":"Jo P.","avatarUrl":null,"friend":false},
                              {"name":"No Id"}, "junk"],
                    "friends":[{"userId":"U-1","name":"Aurora Bell"},{"userId":"f-9","name":"Sam Chatter"},{"name":"x"}]},
                  "history":[
                    {"senderPresence":"PUSH","message":{"id":"m-1","message":"first","userId":"u-1"}},
                    {"senderPresence":null,"message":{"message":"no sender"}},
                    {"senderPresence":"ONLINE","message":{"id":"m-2","message":"second","userId":"u-2","role":"STAFF"}}],
                  "pinnedMessage":{"senderPresence":null,"message":{"id":"m-0","message":"Announcement: Doors","userId":"u-3",
                    "kind":"ANNOUNCEMENT","title":"Doors","pinned":true}}}""",
            ),
        )
        assertEquals(ChatProtocol.Frame.Connected, frames[0])
        assertEquals(ChatProtocol.Frame.Range(outOfRange = true), frames[1])
        assertEquals(
            ChatProtocol.Frame.Roster(
                setOf("u-1", "u-2"),
                people = listOf(
                    RoomPerson("u-1", "Aurora Bell", "https://a/1.jpg", friend = true),
                    RoomPerson("u-2", "Jo P."),
                ),
                friends = listOf(Friend("u-1", "Aurora Bell"), Friend("f-9", "Sam Chatter")),
            ),
            frames[2],
        )
        val history = assertIs<ChatProtocol.Frame.History>(frames[3])
        assertEquals(listOf("first", "second"), history.messages.map { it.body })
        assertTrue(history.messages.first().reachable)
        assertFalse(history.messages.last().reachable)
        assertEquals(ChatRole.STAFF, history.messages.last().role)
        assertEquals("Doors", history.pinned?.title)
        assertEquals(4, frames.size)
    }

    @Test
    fun joiningAnEmptyRoomOrAMalformedRosterStillJoins() {
        val frames = ChatProtocol.parse(
            result("""{"__typename":"ChatJoined","outOfRange":false,"roster":{"people":"nope","friends":7},"history":[],"pinnedMessage":null}"""),
        )
        assertEquals(
            listOf(
                ChatProtocol.Frame.Connected,
                ChatProtocol.Frame.Range(false),
                ChatProtocol.Frame.Roster(emptySet()),
                ChatProtocol.Frame.History(emptyList(), null),
            ),
            frames,
        )
    }

    @Test
    fun readsPresence() {
        // A newcomer comes with their name and photo; leaving carries neither.
        assertEquals(
            listOf(ChatProtocol.Frame.PresenceChanged("u-3", online = true, name = "Kiko M.", avatarUrl = "https://a/3.jpg")),
            ChatProtocol.parse(
                result("""{"__typename":"ChatPresenceChanged","userId":"U-3","presence":"ONLINE","name":"Kiko M.","avatarUrl":"https://a/3.jpg"}"""),
            ),
        )
        assertEquals(
            listOf(ChatProtocol.Frame.PresenceChanged("u-3", online = false)),
            ChatProtocol.parse(
                result("""{"__typename":"ChatPresenceChanged","userId":"u-3","presence":"OFFLINE","name":"Kiko M.","avatarUrl":null}"""),
            ),
        )
        assertEquals(
            listOf(ChatProtocol.Frame.PresenceChanged("u-3", online = false, reachable = true)),
            ChatProtocol.parse(result("""{"__typename":"ChatPresenceChanged","userId":"u-3","presence":"PUSH"}""")),
        )
        assertEquals(emptyList(), ChatProtocol.parse(result("""{"__typename":"ChatPresenceChanged","presence":"ONLINE"}""")))
    }

    @Test
    fun readsEditsPinsRemovalsAndClearing() {
        val updated = assertIs<ChatProtocol.Frame.Message>(
            ChatProtocol.parse(
                result("""{"__typename":"ChatMessageUpdated","message":{"id":"m-0","message":"Special: Two for one","userId":"u-3","kind":"SPECIAL","title":"Two for one"}}"""),
            ).single(),
        ).message
        assertEquals("m-0", updated.id)
        assertEquals("Two for one", updated.title)

        val pin = assertIs<ChatProtocol.Frame.Pin>(
            ChatProtocol.parse(
                result(
                    """{"__typename":"ChatPinChanged","messageId":"m-0","pinned":true,
                      "announcement":{"id":"m-0","message":"Announcement: Doors","userId":"u-3","kind":"ANNOUNCEMENT","title":"Doors","pinned":true}}""",
                ),
            ).single(),
        )
        assertEquals("m-0", pin.id)
        assertEquals("Doors", pin.announcement?.title)
        assertEquals(
            listOf(ChatProtocol.Frame.Pin("m-0", null)),
            ChatProtocol.parse(result("""{"__typename":"ChatPinChanged","messageId":"m-0","pinned":false,"announcement":null}""")),
        )

        assertEquals(
            listOf(ChatProtocol.Frame.Removed("m-1")),
            ChatProtocol.parse(result("""{"__typename":"ChatMessageRemoved","messageId":"m-1"}""")),
        )
        assertEquals(emptyList(), ChatProtocol.parse(result("""{"__typename":"ChatMessageRemoved"}""")))
        assertEquals(
            listOf(ChatProtocol.Frame.Cleared),
            ChatProtocol.parse(result("""{"__typename":"ChatRoomCleared","clearedAt":"2026-10-09T04:00:00.123Z"}""")),
        )
    }

    @Test
    fun sendsOnlyTheTextWithTheRoom() {
        assertEquals("""{"venueId":"$venue","message":"hi"}""", ChatProtocol.sendInput(ChatRoom.venue(venue.uppercase()), "hi").toString())
        assertTrue(ChatProtocol.SEND_MESSAGE.contains("sendMyChatMessage(input: \$input)"))
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
        val event = Event(id = "e", startAt = Instant.parse("2026-10-10T04:00:00Z"), venueMixin = EventVenueMixin(Venue(id = "v", name = "The Wildrose")))
        assertEquals("Fri 9pm · The Wildrose", Formatting.eventSubtitle(event, zone, Locale.US))
        assertEquals("Fri 9pm · To be announced", Formatting.eventSubtitle(event.copy(venueMixin = null), zone, Locale.US))
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
    fun sendsAChatLineWithTheMutation() = runTest {
        val (api, bodies) = api(
            "SendMyChatMessage" to """{"data":{"sendMyChatMessage":{"message":{"id":"m-9","message":"hi","userId":"u-1","name":"Aurora B.","kind":"TEXT","sentAt":"2026-10-10T04:02:00Z"}}}}""",
        )
        val line = api.sendChatMessage(ChatRoom.venue("V-1"), "hi")

        assertEquals("m-9", line?.id)
        assertEquals("hi", line?.body)
        val body = HotMessApi.json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("""{"input":{"venueId":"v-1","message":"hi"}}""", body["variables"].toString())
    }

    @Test
    fun aRefusedChatLineSaysWhy() = runTest {
        val (api, _) = api(
            "SendMyChatMessage" to """{"data":{"sendMyChatMessage":null},"errors":[{"message":"You're not in that room","extensions":{"code":"FORBIDDEN","reason":"not_allowed"}}]}""",
        )
        val error = assertFailsWith<ApiError.Rejected> { api.sendChatMessage(ChatRoom.locale("l-1"), "hi") }
        assertEquals("You're not in that room", error.message)
        assertEquals("not_allowed", error.reason)
    }

    @Test
    fun nowWithoutAPositionMakesNoRequest() = runTest {
        val (api, bodies) = api()
        assertEquals(Now(), api.now(null))
        assertTrue(bodies.isEmpty())
    }

    @Test
    fun nowReportsThePosition() = runTest {
        val (api, bodies) = api("ReportLocation" to """{"data":{"reportLocation":{"nearbyVenues":[],"locale":{"id":"l1","name":"Capitol Hill","events":{"upcoming":{"nodes":[${Fixtures.EVENT}]}}}}}}""")
        val now = api.now(Coordinates(47.61, -122.32))

        assertEquals("Happening Now in Capitol Hill", now.title)
        assertTrue(now.isNearVenues)
        assertEquals(1, now.events.size)
        assertTrue(bodies.single().contains("\"latitude\":47.61"))
    }

    @Test
    fun reportsOfTheSamePositionAtOnceShareOneRequest() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var requests = 0
        val transport = HttpTransport {
            requests++
            gate.await()
            com.audiencekit.HttpResponse(200, """{"data":{"reportLocation":{"venue":{"id":"v1","name":"Nyne"}}}}""".toByteArray())
        }
        val api = HotMessApi(
            AudienceKitClient(
                AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
                InMemoryTokenStore("token"),
                transport,
            ),
        )
        val position = Coordinates(47.66, -117.41)
        val tracking = async { api.now(position) }
        val screen = async { api.now(position) }
        val leaving = async { api.now(position) }
        testScheduler.runCurrent()
        leaving.cancel()
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals("Nyne", tracking.await().title)
        assertEquals("Nyne", screen.await().title)
        assertEquals(1, requests, "one request, which a caller leaving doesn't cancel")

        api.now(position)
        assertEquals(2, requests, "a later report is sent again")
    }

    @Test
    fun meHasTheirPhoto() = runTest {
        val (api, bodies) = api(
            "query Me" to """{"data":{"me":{"self":{"id":"5b3c8a72-4e19-4d06-b2f5-8c7a1e0d9b22","name":"Billie Jean",
                "avatarUrl":"https://cdn.example/billie.jpg"}}}}""",
        )
        val me = api.me()!!

        assertEquals("Billie Jean", me.name)
        assertEquals("https://cdn.example/billie.jpg", me.avatarUrl)
        assertTrue(bodies.single().contains("avatarUrl"))
        assertFalse(bodies.single().contains("/picture"))
    }

    @Test
    fun nowAtAVenueHasItsRecentChatAndFriends() = runTest {
        val (api, _) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"nearbyVenues":null,
                "venue":{"id":"v1","name":"Nyne","bannerUrl":"https://cdn.example/nyne.jpg","events":{"upcoming":{"nodes":[]}},
                  "chat":{"recentMessages":{"nodes":[{"id":"m1","message":"who's here?","name":"Sam",
                  "userId":"u1","avatarUrl":null,"sentAt":"2026-10-07T08:00:00Z"}]}},
                  "friends":{"list":{"nodes":[{"id":"f1","name":"Alex Friend","facebookId":"4242"}]}}}}}}""",
        )
        val now = api.now(Coordinates(47.66, -117.41))

        assertEquals("Nyne", now.title)
        assertEquals("https://cdn.example/nyne.jpg", now.imageUrl)
        assertFalse(now.isNearVenues)
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
            "query Venue(" to """{"data":{"venues":{"venue":{"id":"v1","name":"Nyne","events":{"upcoming":{"nodes":[]}},
                "chat":{"open":true,"recentMessages":{"nodes":[{"id":"m1","message":"who's here?","name":"Sam","userId":"u1",
                  "avatarUrl":null,"sentAt":"2026-10-07T08:00:00Z"}]}}}}}}""",
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
            "ReportLocation" to """{"data":{"reportLocation":{"nearbyVenues":[],
                "locale":{"id":"l1","name":"Spokane","friends":{"list":{"nodes":[]},
                  "venues":{"nodes":[{"venue":{"id":"v1","name":"Nyne"},"friendCount":2,
                    "friends":[{"id":"f1","name":"Alex Friend"},{"id":"f2","name":"Sam Chatter"}]}]}}}}}}""",
        )
        val entry = api.now(Coordinates(47.66, -117.43)).friendVenues.single()

        assertEquals("Nyne", entry.venue.name)
        assertEquals(2, entry.friendCount)
        assertEquals(listOf("Alex", "Sam"), entry.friends.map { it.firstName })
    }

    @Test
    fun nowAwayFromVenuesHasTheLocaleChat() = runTest {
        val (api, bodies) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"nearbyVenues":[],
                "locale":{"id":"l1","name":"Seattle","bannerUrl":"https://cdn.example/seattle.jpg","chat":{"open":true,
                  "recentMessages":{"nodes":[{"id":"m1",
                  "message":"anyone out?","name":"Ada","userId":"u1","sentAt":"2026-10-07T08:00:00Z"}]}}}}}}""",
        )
        val now = api.now(Coordinates(47.62, -122.32))
        val locale = now.locale!!

        assertEquals("https://cdn.example/seattle.jpg", now.imageUrl)

        assertTrue(locale.chatOpen)
        assertEquals("Seattle", locale.name)
        assertEquals("anyone out?", locale.recentMessages.single().message)
        assertTrue(bodies.single().contains("chat { open recentMessages"))
    }

    @Test
    fun recentChatAndFriendsCarryPresenceAndRoles() = runTest {
        val (api, bodies) = api(
            "ReportLocation" to """{"data":{"reportLocation":{"nearbyVenues":null,
                "venue":{"id":"v1","name":"Nyne","chat":{"recentMessages":{"nodes":[{"id":"m1","message":"Special: Two for one",
                  "name":"Nyne","userId":"u1","avatarUrl":null,"sentAt":"2026-10-07T08:00:00Z","presence":"OFFLINE",
                  "role":"VENUE","kind":"SPECIAL","endsAt":"2026-10-07T09:00:00Z","postedAsVenue":true},
                  {"id":"m2","message":"hi","name":"Alex Friend","userId":"f1","presence":"ONLINE","kind":"TEXT"}]}},
                  "friends":{"list":{"nodes":[{"id":"f1","name":"Alex Friend","facebookId":"4242","presence":"PUSH"}]}}}}}}""",
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
            "query Venue(" to """{"data":{"venues":{"venue":{"id":"v-1","name":"The Wildrose","chat":{"open":true},"events":{"upcoming":{"nodes":[${Fixtures.EVENT}]}},
                "profile":{"socialLinks":{"nodes":[{"id":"s-1","handle":"thewildrosebar","provider":"instagram","url":"https://instagram.com/thewildrosebar"},
                  null,{"id":"s-2","handle":"wildrose","provider":"facebook","url":null}]}}}}}}""",
        )
        val overview = api.venueOverview("v-1")

        assertTrue(bodies.single().contains("profile { socialLinks { nodes {"))
        assertTrue(overview.chatOpen)
        assertEquals(1, overview.events.size)
        val links = overview.venue.socialLinks
        assertEquals(listOf(SocialLink.Network.INSTAGRAM, SocialLink.Network.FACEBOOK), links.map { it.network })
        assertEquals("https://instagram.com/thewildrosebar", links.first().url)
        assertNull(links.last().url, "no URL is null")
    }

    @Test
    fun venueOverviewHasTheFriendsHere() = runTest {
        val (api, bodies) = api(
            "query Venue(" to """{"data":{"venues":{"venue":{"id":"v1","name":"Nyne","chat":{"open":true},"events":{"upcoming":{"nodes":[]}},
                "friends":{"list":{"nodes":[{"id":"f1","name":"Alex Friend","facebookId":"4242","presence":"ONLINE"}]}}}}}}""",
        )
        val overview = api.venueOverview("v1")

        assertTrue(bodies.single().contains("friends { list { nodes { id name facebookId avatarUrl presence } } }"))
        val friend = overview.friends.single()
        assertEquals("Alex Friend", friend.name)
        assertEquals("https://m.me/4242", friend.messengerUrl)
        assertEquals(com.audiencekit.Presence.ONLINE, friend.presence)
        assertEquals("Alex Friend", api.friends.fullName("f1"), "the venue's friends are remembered for chat")
    }

    @Test
    fun aVenueWithoutFriendsHasNone() = runTest {
        val (api, _) = api("query Venue(" to """{"data":{"venues":{"venue":{"id":"v1","name":"Nyne","events":{"upcoming":{"nodes":[]}}}}}}""")
        assertTrue(api.venueOverview("v1").friends.isEmpty())
    }

    @Test
    fun aVenueWithoutLinksHasNone() {
        assertTrue(HotMessApi.json.decodeFromString(Venue.serializer(), Fixtures.VENUE).socialLinks.isEmpty())
    }

    @Test
    fun aVenuesAddressPhoneAndPageComeFromItsProfile() {
        val venue = HotMessApi.json.decodeFromString(Venue.serializer(), Fixtures.VENUE)

        assertEquals("1021 E Pike St", venue.address)
        assertNull(venue.phone)
        assertEquals("https://facebook.com/55", venue.facebookUrl)
        assertTrue(venue.isLiked)
    }

    @Test
    fun aVenueWithoutAProfileHasNoAddressOrPage() {
        val venue = HotMessApi.json.decodeFromString(Venue.serializer(), """{"id":"v-2","name":"Pop-up"}""")

        assertNull(venue.address)
        assertNull(venue.facebookId)
        assertFalse(venue.isLiked)
    }

    @Test
    fun missingRecordsAreNotFound() = runTest {
        val (api, _) = api("query Event" to """{"data":{"events":{"event":null}}}""")
        assertFailsWith<ApiError.NotFound> { api.event("e") }
    }

    @Test
    fun graphQLErrorsBecomeAFriendlyError() = runTest {
        val (api, _) = api("query People" to """{"data":null,"errors":[{"message":"Field 'x' doesn't exist"}]}""")
        val error = assertFailsWith<ApiError.Other> { api.people() }
        assertTrue(!error.message!!.contains("Field"))
    }

    @Test
    fun statusWithoutAnAndroidEntryMeansNoMinimum() {
        assertNull(HotMessApi.parseStatus("""{"api":{"version":"2026-10-11"},"clients":{"ios":{"minimumVersion":"1.0.15"}}}"""))
        assertNull(HotMessApi.parseStatus("not json"))
        val android = HotMessApi.parseStatus(
            """{"api":{"version":"2026-10-11","sdk":{"android":{"minimumVersion":"0.1.0"}}},
               "clients":{"android":{"minimumVersion":"1.2","latestVersion":"1.3.1","storeUrl":"https://play.google.com/store/apps/details?id=social.hotmess.android"}}}""",
        )
        assertEquals(VersionInfo("1.2", "1.3.1", "https://play.google.com/store/apps/details?id=social.hotmess.android"), android)
    }

    @Test
    fun theStatusIsReadWithoutASession() = runTest {
        val (api, _) = api("/v1/status" to """{"api":{"version":"2026-10-11"},"clients":{"android":{"minimumVersion":"2.0","latestVersion":"2.0","storeUrl":null}}}""")
        assertEquals("2.0", api.clientVersions()?.minimumVersion)
    }

    @Test
    fun errorsAreClassifiedByCodeNotWording() = runTest {
        val (api, _) = api("query People" to """{"data":null,"errors":[{"message":"Pick places in your city","extensions":{"code":"NOT_FOUND"}}]}""")
        assertFailsWith<ApiError.NotFound> { api.people() }
    }
}

class VersionInfoTest {
    @Test
    fun comparesVersionsPartByPart() {
        val minimum = VersionInfo(minimumVersion = "1.0.15")
        assertTrue(minimum.supports("1.0.15"))
        assertTrue(minimum.supports("1.1"))
        assertTrue(minimum.supports("2.0-staging"), "a build type's suffix doesn't count")
        assertFalse(minimum.supports("1.0.9"))
        assertFalse(minimum.supports("0.9.99"))
        assertTrue(VersionInfo(minimumVersion = "1.2").supports("1.2.0"))
        assertTrue(VersionInfo().supports("0.1"), "no minimum supports everything")
        assertTrue(minimum.supports("dev"), "an unreadable version isn't refused")
        assertTrue(VersionInfo(minimumVersion = "soon").supports("1.0"))
    }
}

class ApiErrorTest {
    private fun error(message: String, extensions: String? = null): GraphQLError {
        val extra = if (extensions == null) "" else ""","extensions":$extensions"""
        return HotMessApi.json.decodeFromString(GraphQLError.serializer(), """{"message":"$message"$extra}""")
    }

    @Test
    fun classifiesGraphQLErrorsByCode() {
        assertEquals(ApiError.Unauthorized, ApiError.fromGraphQL(listOf(error("Sign in", """{"code":"UNAUTHENTICATED"}"""))))
        assertEquals(ApiError.NotFound, ApiError.fromGraphQL(listOf(error("Nope", """{"code":"NOT_FOUND"}"""))))
        assertEquals(ApiError.Server(500), ApiError.fromGraphQL(listOf(error("Stripe failed", """{"code":"INTERNAL"}"""))))
        assertEquals(ApiError.Unavailable, ApiError.fromGraphQL(listOf(error("Stripe is down", """{"code":"UNAVAILABLE"}"""))))
        assertTrue(ApiError.Unavailable.isRetryable)

        val invalid = ApiError.fromGraphQL(listOf(error("Name can't be blank", """{"code":"INVALID","fields":{"name":["can't be blank"]}}""")))
        assertEquals(ApiError.Rejected("INVALID", "Name can't be blank", mapOf("name" to listOf("can't be blank"))), invalid)
        assertFalse(invalid.isRetryable)

        val forbidden = ApiError.fromGraphQL(listOf(error("Banned", """{"code":"FORBIDDEN","reason":"banned"}""")))
        assertEquals("banned", (forbidden as ApiError.Rejected).reason)
        assertTrue(ApiError.fromGraphQL(listOf(error("Slow down", """{"code":"RATE_LIMITED"}"""))).isRetryable)
    }

    @Test
    fun anErrorWithoutACodeIsNotForPeople() {
        // graphql-ruby's own errors carry its codes or none; their wording never reaches the screen.
        assertEquals(ApiError.Other(ApiError.GENERIC_MESSAGE), ApiError.fromGraphQL(listOf(error("Field 'x' doesn't exist on type 'Query'"))))
        assertEquals(ApiError.Other(ApiError.GENERIC_MESSAGE), ApiError.fromGraphQL(listOf(error("That ping has ended"))))
        assertEquals(ApiError.Other(ApiError.GENERIC_MESSAGE), ApiError.fromGraphQL(listOf(error("Bad variable", """{"code":"undefinedField"}"""))))
        assertNull(HotMessApi.userFacingMessage(listOf(error("Field 'sendPing' doesn't exist on type 'Mutation'"))))
        assertNull(HotMessApi.userFacingMessage(emptyList()))
        assertEquals("Pick places in your city", HotMessApi.userFacingMessage(listOf(error("Pick places in your city", """{"code":"INVALID"}"""))))
    }
}

private object Fixtures {
    const val VENUE = """{"id":"v-1","name":"The Wildrose","distance":120.5,"point":"POINT (-122.3194 47.6145)",
        "photoUrl":"https://img/v","bannerUrl":null,"profile":{"address":"1021 E Pike St","phone":null,"facebook":{"facebookId":"55","isLiked":true}}}"""

    const val EVENT = """{"id":"e-1","name":"Drag Brunch","startAt":"2026-10-10T04:00:00Z","endAt":null,
        "facebookId":"1234","bannerUrl":null,"isFeatured":false,"rsvp":{"mine":"MAYBE"},"venue":{"name":"The Wildrose","venue":$VENUE}}"""

    const val PERSON = """{"id":"p-1","name":"DJ Glitter","avatarUrl":"https://img/p","bannerUrl":null,"events":{"upcoming":{"nodes":[$EVENT]}},
        "profile":{"facebook":{"facebookId":"99","isLiked":false},"socialLinks":{"nodes":[{"id":"s-1","handle":"glitter","provider":"Instagram","url":"https://instagram.com/glitter"}]}},
        "music":{"tracks":{"nodes":[{"id":"t-1","title":"Late Night","provider":"soundcloud","providerUrl":null,"waveformUrl":null,"artworkUrl":null},null]}}}"""

    const val TROUPE = """{"id":"p-3","name":"The Glitterettes","avatarUrl":null,"bannerUrl":null,"events":{"upcoming":{"nodes":[]}},"profile":{"socialLinks":{"nodes":[]}},"music":{"tracks":{"nodes":[]}},
        "members":[{"id":"p-4","name":"Anita Cocktail","avatarUrl":"https://img/anita"},{"id":"p-5","name":"Ella Vator","avatarUrl":null}],
        "groups":[{"id":"p-2","name":"House of Glitter","avatarUrl":null}]}"""
}
