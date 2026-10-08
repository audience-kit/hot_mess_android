package social.hotmess.core

import com.audiencekit.Queries

/**
 * The GraphQL documents the app runs against the audience's endpoint. Fields decode straight into
 * the models in Models.kt; ask for a field there before using it here.
 */
object Documents {
    const val VENUE_FIELDS: String =
        "id name address description phone distance point facebookId photoUrl heroUrl isLiked"

    /** A chat line as a preview shows it: rich messages in words (`message`), with the sender's role and presence. */
    const val CHAT_LINE_FIELDS: String = "id message name userId avatarUrl sentAt presence role kind endsAt postedAsVenue"

    /** A venue's last few chat lines, oldest first; empty unless the viewer is at the venue or an admin. */
    const val RECENT_MESSAGES: String = "recentMessages(limit: 3) { $CHAT_LINE_FIELDS }"

    /** Tonight's cover at a venue and the user's pass for it. */
    const val VENUE_COVER_FIELDS: String =
        "coverCharge { ${Queries.COVER_CHARGE_FIELDS} } viewerAdmission { ${Queries.ADMISSION_FIELDS} }"

    const val PERSON_FIELDS: String = "id name facebookId isLiked pictureUrl coverUrl"

    const val EVENT_FIELDS: String =
        "id name startAt endAt facebookId coverPhotoUrl isFeatured viewerRsvp venue { $VENUE_FIELDS }"

    const val FRIEND_FIELDS: String = "id name facebookId"

    /** Friends out now, with whether they can be reached (in chat, or by push). */
    const val FRIEND_OUT_FIELDS: String = "$FRIEND_FIELDS presence"

    /** A Ping with its picks and who's in. Picks carry enough of the venue or event for a row. */
    const val PING_FIELDS: String =
        "id note createdAt expiresAt isMine joined reach user { $FRIEND_FIELDS } via { $FRIEND_FIELDS } " +
            "targets { id venue { id name photoUrl } event { id name startAt venue { id name } } " +
            "joins { id targetId user { $FRIEND_FIELDS } } } " +
            "joins { id targetId user { $FRIEND_FIELDS } }"

    val REPORT_LOCATION: String = """
        mutation ReportLocation(${'$'}position: CoordinatesInput!) {
          reportLocation(input: { position: ${'$'}position }) {
            now {
              title imageUrl
              venue { $VENUE_FIELDS $RECENT_MESSAGES $VENUE_COVER_FIELDS }
              venues { $VENUE_FIELDS }
              locale { id name chatOpen $RECENT_MESSAGES }
              events { $EVENT_FIELDS }
              friends { $FRIEND_OUT_FIELDS }
              friendVenues { venue { $VENUE_FIELDS } friendCount friends { $FRIEND_OUT_FIELDS } }
              myPing { $PING_FIELDS }
              friendPings { $PING_FIELDS }
            }
          }
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
            $VENUE_FIELDS chatOpen canWorkDoor
            $RECENT_MESSAGES
            $VENUE_COVER_FIELDS
            events { $EVENT_FIELDS }
            socialLinks { id handle provider url }
            friends { $FRIEND_OUT_FIELDS }
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
            events { $EVENT_FIELDS }
            socialLinks { id handle provider url }
            members { id name pictureUrl }
            groups { id name pictureUrl }
            tracks { id title provider providerUrl waveformUrl artworkUrl }
          }
        }
    """.trimIndent()

    val LOCALE_EVENTS: String = """
        query LocaleEvents(${'$'}id: ID!) {
          locale(id: ${'$'}id) { events { $EVENT_FIELDS } }
        }
    """.trimIndent()

    val EVENT: String = """
        query Event(${'$'}id: ID!) {
          event(id: ${'$'}id) {
            $EVENT_FIELDS
            coverCharge { ${Queries.COVER_CHARGE_FIELDS} }
            venue { id $VENUE_COVER_FIELDS }
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
