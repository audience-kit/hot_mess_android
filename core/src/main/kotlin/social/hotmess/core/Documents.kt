package social.hotmess.core

import com.audiencekit.Queries

/**
 * The GraphQL documents the app runs against the audience's endpoint. Fields decode straight into
 * the models in Models.kt; ask for a field there before using it here.
 */
object Documents {
    /** How many of the locale's upcoming events Now lists away from a venue. */
    const val LOCALE_EVENT_LIMIT: Int = 5

    const val VENUE_FIELDS: String =
        "id name address description phone distance point facebookId photoUrl heroUrl isLiked"

    /** A chat line as a preview shows it: rich messages in words (`message`), with the sender's role and presence. */
    const val CHAT_LINE_FIELDS: String = "id message name userId avatarUrl sentAt presence role kind endsAt postedAsVenue postedAsPerson"

    /** A venue's last few chat lines, oldest first; empty unless the viewer is at the venue or an admin. */
    const val RECENT_MESSAGES: String = "recentMessages(limit: 3) { $CHAT_LINE_FIELDS }"

    /** Tonight's cover at a venue and the user's pass for it, in the venue's cover facet (null when the audience has no cover). */
    const val VENUE_COVER_FIELDS: String =
        "cover { charge { ${Queries.COVER_CHARGE_FIELDS} } viewerAdmission { ${Queries.ADMISSION_FIELDS} } }"

    const val PERSON_FIELDS: String = "id name facebookId isLiked pictureUrl bannerUrl"

    /** An event, with its venue under the event's venue facet (`venue { venue }`). */
    const val EVENT_FIELDS: String =
        "id name startAt endAt facebookId bannerUrl isFeatured viewerRsvp venue { venue { $VENUE_FIELDS } }"

    const val FRIEND_FIELDS: String = "id name facebookId"

    /** Friends out now, with whether they can be reached (in chat, or by push). */
    const val FRIEND_OUT_FIELDS: String = "$FRIEND_FIELDS presence"

    /** A Ping with its picks and who's in. Picks carry enough of the venue or event for a row. */
    const val PING_FIELDS: String =
        "id note createdAt expiresAt isMine joined reach user { $FRIEND_FIELDS } via { $FRIEND_FIELDS } " +
            "targets { id venue { id name photoUrl } event { id name startAt venue { venue { id name } } } " +
            "joins { id targetId user { $FRIEND_FIELDS } } } " +
            "joins { id targetId user { $FRIEND_FIELDS } }"

    val REPORT_LOCATION: String = """
        mutation ReportLocation(${'$'}position: CoordinatesInput!) {
          reportLocation(input: { position: ${'$'}position }) {
            venue {
              $VENUE_FIELDS $RECENT_MESSAGES $VENUE_COVER_FIELDS
              events { upcoming { nodes { $EVENT_FIELDS } } }
              friends { list { nodes { $FRIEND_OUT_FIELDS } } }
            }
            nearbyVenues { $VENUE_FIELDS }
            locale {
              id name heroUrl chatOpen $RECENT_MESSAGES
              events { upcoming(first: $LOCALE_EVENT_LIMIT) { nodes { $EVENT_FIELDS } } }
              friends {
                list { nodes { $FRIEND_OUT_FIELDS } }
                venues { nodes { venue { $VENUE_FIELDS } friendCount friends { $FRIEND_OUT_FIELDS } } }
              }
            }
            audience { ping { myPing { $PING_FIELDS } friendPings { $PING_FIELDS } } }
          }
        }
    """.trimIndent()

    /** The signed-in user, with their profile photo (the SDK's Me doesn't ask for it). */
    val ME: String = """
        query Me {
          me { id name firstName lastName photo { cdnUrl } }
        }
    """.trimIndent()

    val CLOSEST_LOCALE: String = """
        query ClosestLocale(${'$'}near: CoordinatesInput!) {
          closestLocale(near: ${'$'}near) { id name label }
        }
    """.trimIndent()

    val VENUES: String = """
        query Venues {
          venues { $VENUE_FIELDS hidden order locale { id name label } }
        }
    """.trimIndent()

    val VENUE: String = """
        query Venue(${'$'}id: ID!) {
          venue(id: ${'$'}id) {
            $VENUE_FIELDS chatOpen
            $RECENT_MESSAGES
            cover { charge { ${Queries.COVER_CHARGE_FIELDS} } viewerAdmission { ${Queries.ADMISSION_FIELDS} } canWorkDoor }
            events { upcoming { nodes { $EVENT_FIELDS } } }
            socialLinks { id handle provider url }
            friends { list { nodes { $FRIEND_OUT_FIELDS } } }
            friendPings { $PING_FIELDS }
          }
        }
    """.trimIndent()

    val PEOPLE: String = """
        query People {
          people { $PERSON_FIELDS order }
        }
    """.trimIndent()

    val PERSON: String = """
        query Person(${'$'}id: ID!) {
          person(id: ${'$'}id) {
            $PERSON_FIELDS
            events { upcoming { nodes { $EVENT_FIELDS } } }
            socialLinks { id handle provider url }
            members { id name pictureUrl }
            groups { id name pictureUrl }
            music { tracks { nodes { id title provider providerUrl waveformUrl artworkUrl } } }
          }
        }
    """.trimIndent()

    val LOCALE_EVENTS: String = """
        query LocaleEvents(${'$'}id: ID!) {
          locale(id: ${'$'}id) { events { upcoming { nodes { $EVENT_FIELDS } } } }
        }
    """.trimIndent()

    val EVENT: String = """
        query Event(${'$'}id: ID!) {
          event(id: ${'$'}id) {
            $EVENT_FIELDS
            cover { charge { ${Queries.COVER_CHARGE_FIELDS} } }
            venue { venue { id $VENUE_COVER_FIELDS } }
            people { $PERSON_FIELDS }
            friendPings { $PING_FIELDS }
          }
        }
    """.trimIndent()

    val MY_PING: String = """
        query MyPing {
          myPing { $PING_FIELDS }
        }
    """.trimIndent()

    val FRIEND_PINGS: String = """
        query FriendPings {
          friendPings { $PING_FIELDS }
        }
    """.trimIndent()

    val SEND_PING: String = """
        mutation SendPing(${'$'}venueIds: [ID!], ${'$'}eventIds: [ID!], ${'$'}note: String, ${'$'}localeId: ID, ${'$'}reach: PingReach) {
          sendPing(input: { venueIds: ${'$'}venueIds, eventIds: ${'$'}eventIds, note: ${'$'}note, localeId: ${'$'}localeId, reach: ${'$'}reach }) {
            ping { $PING_FIELDS }
          }
        }
    """.trimIndent()

    val JOIN_PING: String = """
        mutation JoinPing(${'$'}pingId: ID!, ${'$'}targetId: ID) {
          joinPing(input: { pingId: ${'$'}pingId, targetId: ${'$'}targetId }) { ping { $PING_FIELDS } }
        }
    """.trimIndent()

    val LEAVE_PING: String = """
        mutation LeavePing(${'$'}pingId: ID!) {
          leavePing(input: { pingId: ${'$'}pingId }) { ping { $PING_FIELDS } }
        }
    """.trimIndent()

    val END_PING: String = """
        mutation EndPing {
          endPing(input: {}) { ended }
        }
    """.trimIndent()

    /** The SDK's registerDevice, plus which app the token is for; Android builds are never sandboxed. */
    val REGISTER_DEVICE: String = """
        mutation RegisterDevice(${'$'}notificationToken: String!, ${'$'}appId: String, ${'$'}sandbox: Boolean) {
          registerDevice(input: { notificationToken: ${'$'}notificationToken, appId: ${'$'}appId, sandbox: ${'$'}sandbox }) { registered }
        }
    """.trimIndent()
}
