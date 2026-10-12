package social.hotmess.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationProtocolTest {
    private val json = Json
    private val audience = "6A1B2C3D-0000-4000-8000-000000000001"

    @Test
    fun executesMyNotificationsOnTheChannel() {
        val identifier = GraphQLCable.identifier(audience, "n-1")
        val ids = json.parseToJsonElement(identifier).jsonObject
        assertEquals("GraphQLChannel", ids["channel"]!!.jsonPrimitive.content)
        assertEquals(audience.lowercase(), ids["audience_id"]!!.jsonPrimitive.content)

        val frame = json.parseToJsonElement(NotificationProtocol.execute(identifier)).jsonObject
        assertEquals("message", frame["command"]!!.jsonPrimitive.content)
        assertEquals(identifier, frame["identifier"]!!.jsonPrimitive.content)
        val data = json.parseToJsonElement(frame["data"]!!.jsonPrimitive.content).jsonObject
        assertEquals("execute", data["action"]!!.jsonPrimitive.content)
        assertEquals(emptyMap(), data["variables"]!!.jsonObject)
        val query = data["query"]!!.jsonPrimitive.content
        assertTrue(query.startsWith("subscription MyNotifications"), query)
        assertTrue(query.contains("myNotifications {"), query)
        listOf("notificationId", "title", "body", "category", "url", "data", "sentAt").forEach { assertTrue(query.contains(it), it) }
    }

    @Test
    fun decodesANotification() {
        val text = """
            {"identifier":"x","message":{"result":{"data":{"myNotifications":{
              "notificationId":"n-42","title":"Sam pinged","body":"Out at The Lounge","category":"PING",
              "url":"https://hotmess.social/pings/0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f",
              "data":{"kind":"ping","ping_id":"0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f","notification_id":"n-42","count":3,
                "url":"https://hotmess.social/pings/0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f"},
              "sentAt":"2026-10-11T22:15:00.123Z"}}},"more":true}}
        """.trimIndent()
        val frames = NotificationProtocol.parse(text)
        assertEquals(1, frames.size)
        val notification = (frames.single() as NotificationProtocol.Frame.Notification).notification
        assertEquals("n-42", notification.notificationId)
        assertEquals("Sam pinged", notification.title)
        assertEquals("Out at The Lounge", notification.body)
        assertEquals("PING", notification.category)
        assertEquals(Instant.parse("2026-10-11T22:15:00.123Z"), notification.sentAt)
        assertEquals("3", notification.data["count"], "non-string data reads as FCM's strings")
        assertEquals(AppRoute.NowPing("0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f"), notification.route, "routed on url")
        assertEquals(PingPush("0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f", "ping"), notification.ping)
    }

    @Test
    fun theUrlIsPutInTheDataAsFcmCarriesIt() {
        val url = "https://hotmess.social/pings/0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f"
        val fromField = NotificationProtocol.notification(json.parseToJsonElement("""{"notificationId":"n-9","url":"$url","data":{}}""").jsonObject)!!
        assertEquals(url, fromField.data[PingPush.URL])
        assertEquals("0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f", fromField.ping?.pingId)

        val fromData = NotificationProtocol.notification(json.parseToJsonElement("""{"notificationId":"n-10","data":{"url":"$url"}}""").jsonObject)!!
        assertEquals(url, fromData.url)
        assertEquals(AppRoute.NowPing("0f4e8c2a-7b1d-4c3e-9a5f-2d6b8e1c4a7f"), fromData.route)
    }

    @Test
    fun takesTheIdFromItsDataAndPutsItThere() {
        val fromData = NotificationProtocol.notification(
            json.parseToJsonElement("""{"data":"{\"kind\":\"ping_join\",\"ping_id\":\"p-7\",\"notification_id\":\"n-7\"}"}""").jsonObject,
        )!!
        assertEquals("n-7", fromData.notificationId)
        assertEquals(PingPush("p-7", "ping_join"), fromData.ping)

        val fromField = NotificationProtocol.notification(json.parseToJsonElement("""{"notificationId":"n-8","data":{}}""").jsonObject)!!
        assertEquals("n-8", fromField.data[PushNotification.NOTIFICATION_ID])
        assertNull(fromField.ping)

        assertNull(NotificationProtocol.notification(json.parseToJsonElement("""{"title":"no id","data":{}}""").jsonObject))
    }

    @Test
    fun readsTheChannelAndItsEnd() {
        assertEquals(listOf(NotificationProtocol.Frame.Subscribed), NotificationProtocol.parse("""{"identifier":"x","type":"confirm_subscription"}"""))
        assertEquals(listOf(NotificationProtocol.Frame.Rejected), NotificationProtocol.parse("""{"identifier":"x","type":"reject_subscription"}"""))
        // Nothing arrives on subscribing; Action Cable's pings aren't results.
        assertEquals(emptyList(), NotificationProtocol.parse("""{"identifier":"x","message":{"result":{"data":{}},"more":true}}"""))
        assertEquals(emptyList(), NotificationProtocol.parse("""{"type":"ping","message":1760000000}"""))
        assertEquals(
            listOf(NotificationProtocol.Frame.Rejected),
            NotificationProtocol.parse("""{"identifier":"x","message":{"result":{"errors":[{"message":"Sign in required"}]},"more":false}}"""),
        )
        assertEquals(listOf(NotificationProtocol.Frame.Ended), NotificationProtocol.parse("""{"identifier":"x","message":{"result":{"data":null},"more":false}}"""))
    }
}

class RecentNotificationsTest {
    @Test
    fun claimsEachIdOnce() {
        val recent = RecentNotifications()
        assertTrue(recent.claim("a"))
        assertFalse(recent.claim("a"))
        assertTrue("a" in recent)
    }

    @Test
    fun keepsOnlyTheLatestIds() {
        val recent = RecentNotifications(capacity = 3)
        listOf("a", "b", "c", "d").forEach { recent.claim(it) }
        assertFalse("a" in recent)
        assertTrue(listOf("b", "c", "d").all { it in recent })
        assertTrue(recent.claim("a"), "a forgotten id is new again")
    }

    @Test
    fun dropsTheSystemCopyOfOneShownInTheApp() {
        val recent = RecentNotifications()
        recent.claim("n-1")
        assertFalse(recent.shouldPostSystemCopy("n-1", foreground = true, streamLive = true, windowMs = 0))
        assertFalse(recent.shouldPostSystemCopy("n-1", foreground = true, streamLive = false, windowMs = 0))
    }

    @Test
    fun postsTheSystemCopyAsBeforeInTheBackgroundOrWithoutAnId() {
        val recent = RecentNotifications()
        assertTrue(recent.shouldPostSystemCopy(null, foreground = true, streamLive = true, windowMs = 0))
        recent.claim("n-1")
        assertTrue(recent.shouldPostSystemCopy("n-1", foreground = false, streamLive = true, windowMs = 0))
    }

    @Test
    fun postsTheSystemCopyWhenTheAppsDoesntArriveAndKeepsTheLateOneAway() {
        val recent = RecentNotifications()
        val started = System.nanoTime()
        assertTrue(recent.shouldPostSystemCopy("n-2", foreground = true, streamLive = true, windowMs = 50))
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(45), "waits out the window")
        assertFalse(recent.claim("n-2"), "the in-app copy that comes late isn't shown")
        // Without a live stream there's nothing to wait for.
        val quick = System.nanoTime()
        assertTrue(recent.shouldPostSystemCopy("n-3", foreground = true, streamLive = false, windowMs = 5_000))
        assertTrue(System.nanoTime() - quick < TimeUnit.SECONDS.toNanos(1))
    }

    @Test
    fun waitsForTheInAppCopyWithinTheWindow() {
        val recent = RecentNotifications()
        val waiting = CountDownLatch(1)
        var post: Boolean? = null
        val fcm = thread {
            waiting.countDown()
            post = recent.shouldPostSystemCopy("n-4", foreground = true, streamLive = true, windowMs = 5_000)
        }
        waiting.await()
        Thread.sleep(20)
        assertTrue(recent.claim("n-4"), "the cable's copy is shown")
        fcm.join(5_000)
        assertEquals(false, post)
    }
}
