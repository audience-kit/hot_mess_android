package social.hotmess.core

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorReportsTest {
    private val json = HotMessApi.json
    private val device = DeviceFacts(version = "1.0", build = "7", osVersion = "Android 16", model = "Google Pixel 8 Pro")

    @Test
    fun `a crash names the exception and keeps its stack`() {
        val report = ClientErrorReport.crash(IllegalStateException("boom"), "main", device)

        assertEquals("crash", report.kind)
        assertEquals("Crash on main: java.lang.IllegalStateException: boom", report.message)
        assertTrue(report.stack!!.contains("ErrorReportsTest"))
        assertEquals("7", report.build)
    }

    @Test
    fun `reports use the API's snake_case keys`() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(ClientErrorReport.serializer(), ClientErrorReport(kind = "error", message = "x", osVersion = "Android 16")),
        ).jsonObject

        assertEquals("android", encoded["app"]!!.jsonPrimitive.content)
        assertEquals("Android 16", encoded["os_version"]!!.jsonPrimitive.content)

        val bug = BugReportRequest(
            description = "Map is blank", version = "1.0", build = "7", osVersion = "Android 16", device = "Pixel",
            diagnostics = BugReportRequest.Diagnostics(signedIn = false, environment = "production", recentErrors = emptyList()),
        )
        val bugJson = json.parseToJsonElement(json.encodeToString(BugReportRequest.serializer(), bug)).jsonObject
        assertEquals("android", bugJson["app"]!!.jsonPrimitive.content)
        val diagnostics = bugJson["diagnostics"]!!.jsonObject
        assertEquals("false", diagnostics["signed_in"]!!.jsonPrimitive.content)
        assertTrue("recent_errors" in diagnostics)
    }

    @Test
    fun `only failures that point at a bug are sent, once each`() {
        assertTrue(ApiError.Decoding("missing id").isReportable)
        assertTrue(ApiError.Server(502).isReportable)
        assertFalse(ApiError.Server(429).isReportable)
        assertFalse(ApiError.Offline.isReportable)
        assertFalse(ApiError.Unauthorized.isReportable)

        val recent = RecentErrors(capacity = 2, maxSent = 2)
        val report = ClientErrorReport(kind = "error", message = "boom")
        assertTrue(recent.shouldSend(report))
        assertFalse(recent.shouldSend(report))
        assertTrue(recent.shouldSend(report.copy(message = "other")))
        assertFalse(recent.shouldSend(report.copy(message = "third")))

        recent.remember("api", "one", "t1")
        recent.remember("api", "two", "t2")
        recent.remember("api", "three", "t3")
        assertEquals(listOf("two", "three"), recent.snapshot().map { it.message })
    }

    @Test
    fun `GraphQL failures are named by their operation`() {
        assertEquals("graphql query Venue", operationName("\n  query Venue(\$id: ID!) {\n venue { id } }"))
        assertEquals("graphql", operationName("{ me { id } }"))
    }
}
