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

    val REPORT_LOCATION: String = """
        mutation ReportLocation(${'$'}position: CoordinatesInput!) {
          reportLocation(input: { position: ${'$'}position }) {
            now {
              title imageUrl
              venue { $VENUE_FIELDS $RECENT_MESSAGES }
              venues { $VENUE_FIELDS }
              events { $EVENT_FIELDS }
              friends { id name facebookId }
              friendVenues { venue { $VENUE_FIELDS } friendCount friends { id name facebookId } }
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
          event(id: ${'$'}id) { $EVENT_FIELDS people { $PERSON_FIELDS } }
        }
    """.trimIndent()
}
