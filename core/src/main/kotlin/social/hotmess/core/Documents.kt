package social.hotmess.core

/**
 * The GraphQL documents the app runs against the audience's endpoint. Fields decode straight into
 * the models in Models.kt; ask for a field there before using it here.
 */
object Documents {
    const val VENUE_FIELDS: String =
        "id name address description phone distance point facebookId photoUrl heroUrl isLiked"

    /** A venue's last few chat lines, oldest first; empty unless the viewer is at the venue or an admin. */
    const val RECENT_MESSAGES: String = "recentMessages(limit: 3) { id message name userId avatarUrl sentAt }"

    const val PERSON_FIELDS: String = "id name facebookId isLiked pictureUrl coverUrl"

    const val EVENT_FIELDS: String =
        "id name startAt endAt facebookId coverPhotoUrl isFeatured viewerRsvp venue { $VENUE_FIELDS }"

    const val FRIEND_FIELDS: String = "id name facebookId"

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
              venue { $VENUE_FIELDS $RECENT_MESSAGES }
              venues { $VENUE_FIELDS }
              locale { id name chatOpen recentMessages(limit: 3) { id message name userId avatarUrl sentAt } }
              events { $EVENT_FIELDS }
              friends { id name facebookId }
              friendVenues { venue { $VENUE_FIELDS } friendCount friends { id name facebookId } }
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
            $VENUE_FIELDS chatOpen
            $RECENT_MESSAGES
            events { $EVENT_FIELDS }
            socialLinks { id handle provider url }
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
          event(id: ${'$'}id) { $EVENT_FIELDS people { $PERSON_FIELDS } friendPings { $PING_FIELDS } }
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
