package social.hotmess.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CableConnectionTest {
    private val json = Json

    private class FakeSocket(val token: String?, val listener: CableSocket.Listener) : CableSocket {
        val sent = mutableListOf<String>()
        var closed = false

        override fun send(text: String): Boolean {
            sent += text
            return !closed
        }

        override fun close(code: Int, reason: String?) {
            closed = true
        }
    }

    private class FakeFactory : CableSocketFactory {
        val sockets = mutableListOf<FakeSocket>()
        override fun open(url: String, token: String?, listener: CableSocket.Listener): CableSocket =
            FakeSocket(token, listener).also { sockets += it }
    }

    /** The frames [socket] sent with [command], as (identifier, data). */
    private fun FakeSocket.commands(command: String): List<Pair<String, String?>> = sent
        .map { json.parseToJsonElement(it).jsonObject }
        .filter { it["command"]!!.jsonPrimitive.content == command }
        .map { it["identifier"]!!.jsonPrimitive.content to (it["data"] as? JsonPrimitive)?.content }

    private fun FakeSocket.receive(vararg fields: Pair<String, Any>) = listener.onMessage(
        JsonObject(
            fields.associate { (key, value) ->
                key to when (value) {
                    is JsonObject -> value
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            },
        ).toString(),
    )

    private fun FakeSocket.welcome() = receive("type" to "welcome")
    private fun FakeSocket.confirm(identifier: String) = receive("type" to "confirm_subscription", "identifier" to identifier)
    private fun FakeSocket.result(identifier: String, value: String, more: Boolean = true) = receive(
        "identifier" to identifier,
        "message" to json.parseToJsonElement("""{"result":{"data":{"field":{"value":"$value"}}},"more":$more}""").jsonObject,
    )

    private class Collected(val events: MutableList<CableEvent>, val job: Job)

    private fun TestScope.collect(flow: Flow<CableEvent>): Collected {
        val events = mutableListOf<CableEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(events) }
        return Collected(events, job)
    }

    private fun values(events: List<CableEvent>): List<String> =
        events.filterIsInstance<CableEvent.Result>().map { it.result.event!!["value"]!!.jsonPrimitive.content }

    private var token = "t-1"

    private fun TestScope.cable(factory: FakeFactory) = CableConnection("wss://example.test/connection", { token }, factory, backgroundScope)

    /** Two confirmed subscriptions on one welcomed socket, and their identifiers. */
    private fun TestScope.twoLive(factory: FakeFactory, cable: CableConnection): Triple<Collected, Collected, Pair<String, String>> {
        val a = collect(cable.subscribe("AUDIENCE", "subscription A { field { value } }"))
        val b = collect(cable.subscribe("AUDIENCE", "subscription B { field { value } }"))
        runCurrent()
        val socket = factory.sockets.single()
        socket.welcome()
        val (idA, idB) = socket.commands("subscribe").map { it.first }
        socket.confirm(idA)
        socket.confirm(idB)
        runCurrent()
        return Triple(a, b, idA to idB)
    }

    @Test
    fun sharesOneSocketSubscribesOnWelcomeAndExecutesOnConfirm() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val a = collect(cable.subscribe("AUDIENCE", "subscription A { field { value } }", operationName = "A"))
        val b = collect(cable.subscribe("AUDIENCE", "subscription B { field { value } }"))
        runCurrent()

        val socket = factory.sockets.single()
        assertEquals("t-1", socket.token)
        assertTrue(socket.sent.isEmpty(), "nothing goes before the welcome")

        socket.welcome()
        val subscribes = socket.commands("subscribe")
        assertEquals(2, subscribes.size)
        val (idA, idB) = subscribes.map { it.first }
        assertTrue(idA != idB)
        assertTrue(
            Regex("""\{"audience_id":"audience","channel":"GraphQLChannel","channelId":"[0-9a-f]{12}"\}""").matches(idA),
            idA,
        )

        socket.confirm(idA)
        val execute = socket.commands("message").single()
        assertEquals(idA, execute.first, "the identifier is the same string on every frame")
        val data = json.parseToJsonElement(execute.second!!).jsonObject
        assertEquals("execute", data["action"]!!.jsonPrimitive.content)
        assertEquals("subscription A { field { value } }", data["query"]!!.jsonPrimitive.content)
        assertEquals("A", data["operationName"]!!.jsonPrimitive.content)
        assertTrue(data["variables"] is JsonObject)
        socket.confirm(idB)
        assertEquals(listOf(idA, idB), socket.commands("message").map { it.first })

        socket.result(idB, "for b")
        socket.result(idA, "for a")
        socket.receive("type" to "ping", "message" to 1_760_000_000)
        runCurrent()
        assertEquals(listOf("for a"), values(a.events))
        assertEquals(listOf("for b"), values(b.events))
        assertEquals(CableEvent.Subscribed, a.events.first())
        assertEquals(1, factory.sockets.size)
    }

    @Test
    fun subscribesAtOnceOnAWelcomedSocket() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        twoLive(factory, cable)
        val socket = factory.sockets.single()
        collect(cable.subscribe("AUDIENCE", "subscription C { field { value } }"))
        runCurrent()
        assertEquals(3, socket.commands("subscribe").size)
        assertEquals(1, factory.sockets.size)
    }

    @Test
    fun pingsKeepTheSocket() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        twoLive(factory, cable)
        val socket = factory.sockets.single()
        repeat(10) {
            advanceTimeBy(3_000)
            socket.receive("type" to "ping", "message" to it)
        }
        runCurrent()
        assertFalse(socket.closed)
        assertEquals(1, factory.sockets.size)
    }

    @Test
    fun silenceReconnectsOnceAndRunsEveryOperationAgain() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val (a, b, ids) = twoLive(factory, cable)
        val first = factory.sockets.single()

        advanceTimeBy(CableConnection.SILENCE_MS + 1)
        runCurrent()
        assertTrue(first.closed)
        listOf(a, b).forEach { assertTrue(it.events.last() is CableEvent.Disconnected && (it.events.last() as CableEvent.Disconnected).reconnects) }
        assertEquals(1, factory.sockets.size, "waits out the backoff")

        token = "t-2"
        advanceTimeBy(CableConnection.INITIAL_BACKOFF_MS + 1)
        runCurrent()
        assertEquals(2, factory.sockets.size, "one reconnect")
        val second = factory.sockets.last()
        assertEquals("t-2", second.token, "the token is read on every open")

        // The old socket is gone: anything it still says is ignored.
        first.result(ids.first, "stale")
        second.welcome()
        assertEquals(listOf(ids.first, ids.second), second.commands("subscribe").map { it.first })
        second.confirm(ids.first)
        second.confirm(ids.second)
        assertEquals(listOf(ids.first, ids.second), second.commands("message").map { it.first })
        second.result(ids.first, "again")
        runCurrent()
        assertEquals(listOf("again"), values(a.events))
        assertEquals(2, a.events.count { it == CableEvent.Subscribed })
        assertEquals(2, b.events.count { it == CableEvent.Subscribed })
        assertFalse(a.job.isCompleted)
        assertEquals(2, factory.sockets.size)
    }

    @Test
    fun failureReconnectsWithBackoff() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val (a, b, _) = twoLive(factory, cable)
        factory.sockets.single().listener.onFailure("reset")
        runCurrent()
        assertEquals(CableEvent.Disconnected("reset", reconnects = true), a.events.last())
        assertEquals(CableEvent.Disconnected("reset", reconnects = true), b.events.last())
        advanceTimeBy(CableConnection.INITIAL_BACKOFF_MS + 1)
        runCurrent()
        assertEquals(2, factory.sockets.size)

        // It fails again before the welcome: the wait doubles.
        factory.sockets.last().listener.onFailure("again")
        advanceTimeBy(CableConnection.INITIAL_BACKOFF_MS + 1)
        runCurrent()
        assertEquals(2, factory.sockets.size)
        advanceTimeBy(CableConnection.INITIAL_BACKOFF_MS)
        runCurrent()
        assertEquals(3, factory.sockets.size)
    }

    @Test
    fun anUnauthorizedConnectionEndsTheSessionAndEveryFlow() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val refused = mutableListOf<String>()
        cable.onUnauthorized = { refused += it }
        val (a, b, _) = twoLive(factory, cable)
        val socket = factory.sockets.single()

        socket.receive("type" to "disconnect", "reason" to "unauthorized", "reconnect" to false)
        runCurrent()
        assertEquals(listOf("t-1"), refused)
        assertTrue(a.job.isCompleted && b.job.isCompleted, "every flow ends")
        assertEquals(CableEvent.Disconnected("unauthorized", reconnects = false), a.events.last())
        assertTrue(socket.closed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, factory.sockets.size, "no reconnect")
        assertFalse(cable.isActive)
    }

    @Test
    fun theLastCollectorClosesTheSocket() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val (a, b, ids) = twoLive(factory, cable)
        val socket = factory.sockets.single()

        a.job.cancel()
        runCurrent()
        assertEquals(listOf(ids.first), socket.commands("unsubscribe").map { it.first })
        assertFalse(socket.closed)

        b.job.cancel()
        runCurrent()
        assertTrue(socket.closed)
        assertFalse(cable.isActive)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, factory.sockets.size, "no watchdog or reconnect left behind")

        // The next subscription opens a fresh one.
        collect(cable.subscribe("AUDIENCE", "subscription C { field { value } }"))
        runCurrent()
        assertEquals(2, factory.sockets.size)
    }

    @Test
    fun aRejectionOrTheEndOfTheOperationEndsOnlyItsFlow() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val (a, b, ids) = twoLive(factory, cable)
        val socket = factory.sockets.single()

        socket.receive("type" to "reject_subscription", "identifier" to ids.first)
        runCurrent()
        assertEquals(CableEvent.Rejected, a.events.last())
        assertTrue(a.job.isCompleted)
        assertFalse(b.job.isCompleted)

        socket.result(ids.second, "last", more = false)
        runCurrent()
        assertEquals(listOf("last"), values(b.events))
        assertIs<CableEvent.Result>(b.events.last())
        assertTrue(b.job.isCompleted)
        assertEquals(listOf(ids.second), socket.commands("unsubscribe").map { it.first })
        assertTrue(socket.closed, "nothing left on it")
    }

    @Test
    fun disconnectEndsEverything() = runTest {
        val factory = FakeFactory()
        val cable = cable(factory)
        val (a, b, _) = twoLive(factory, cable)
        cable.disconnect()
        runCurrent()
        assertTrue(a.job.isCompleted && b.job.isCompleted)
        assertTrue(factory.sockets.single().closed)
        assertFalse(cable.isActive)
    }
}
