package social.hotmess.core

import com.audiencekit.Admission
import com.audiencekit.AdmissionStatus
import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.CoverCharge
import com.audiencekit.CoverPass
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import com.audiencekit.ScanOutcome
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val tonight = CoverCharge(amountCents = 1000, totalCents = 1112, feeCents = 50, night = "2026-10-09", from = "21:00", payable = true)
private val paid = Admission(
    id = "7b4e0c6a-1d2f-4e8a-9c3b-5f6a7b8c9d0e",
    status = AdmissionStatus.PAID,
    night = "2026-10-09",
    totalCents = 1112,
    passSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
)

class CoverFormattingTest {
    @Test
    fun money() {
        assertEquals("$11.12", Formatting.money(1112, "usd", Locale.US))
        assertEquals("$10", Formatting.money(1000, "usd", Locale.US))
        assertEquals("€7.50", Formatting.money(750, "eur", Locale.US))
    }

    @Test
    fun coverStartTimes() {
        assertEquals("9pm", Formatting.clockTime("21:00"))
        assertEquals("9:30pm", Formatting.clockTime("21:30"))
        assertEquals("12am", Formatting.clockTime("00:00"))
        assertEquals("12pm", Formatting.clockTime("12:00"))
        assertNull(Formatting.clockTime("late"))
    }

    @Test
    fun coverLines() {
        assertEquals("Cover $11.12 tonight · from 9pm", Formatting.coverLine(tonight, tonight = true, locale = Locale.US))
        assertEquals("Cover $11.12 · from 9pm", Formatting.coverLine(tonight, tonight = false, locale = Locale.US))
        assertEquals("Cover $11.12 tonight", Formatting.coverLine(tonight.copy(from = null), tonight = true, locale = Locale.US))
    }

    @Test
    fun nights() {
        assertEquals("Fri, Oct 9", Formatting.night("2026-10-09", Locale.US))
        assertEquals("someday", Formatting.night("someday", Locale.US))
    }
}

class CoverOfferTest {
    private val venue = Venue(id = "v-1", name = "The Wildrose", coverCharge = tonight)

    @Test
    fun aPayableCoverCanBePaid() {
        assertEquals(CoverAction.PAY, CoverOffer.of(venue)?.action)
        assertNull(CoverOffer.of(venue.copy(coverCharge = null)))
    }

    @Test
    fun anotherCoverIsPaidAtTheDoor() {
        assertEquals(CoverAction.PAY_AT_DOOR, CoverOffer.of(venue.copy(coverCharge = tonight.copy(payable = false)))?.action)
    }

    @Test
    fun aPaidPassIsShownInstead() {
        val offer = CoverOffer.of(venue.copy(viewerAdmission = paid))!!
        assertEquals(CoverAction.SHOW_PASS, offer.action)
        assertEquals(paid, offer.pass)
        val pending = paid.copy(status = AdmissionStatus.PENDING)
        assertEquals(CoverAction.PAY, CoverOffer.of(venue.copy(viewerAdmission = pending))?.action)
    }

    @Test
    fun anEventsCoverCanBePaidOnlyTonight() {
        val event = Event(id = "e-1", startAt = Instant.parse("2026-10-10T04:00:00Z"), venue = venue, coverCharge = tonight)
        assertEquals(CoverAction.PAY, CoverOffer.of(event)?.action)
        assertTrue(CoverOffer.of(event)!!.tonight)

        val nextWeek = event.copy(coverCharge = tonight.copy(night = "2026-10-16"))
        assertEquals(CoverAction.PRICE_ONLY, CoverOffer.of(nextWeek)?.action)
        assertEquals("Cover $11.12 · from 9pm", Formatting.coverLine(nextWeek.coverCharge!!, CoverOffer.of(nextWeek)!!.tonight, Locale.US))

        val withPass = event.copy(venue = venue.copy(viewerAdmission = paid))
        assertEquals(CoverAction.SHOW_PASS, CoverOffer.of(withPass)?.action)
        assertEquals(CoverAction.PRICE_ONLY, CoverOffer.of(event.copy(venue = null))?.action)
        assertNull(CoverOffer.of(event.copy(coverCharge = null)))
    }
}

class DoorTest {
    @Test
    fun outcomesHaveTones() {
        assertEquals(ScanTone.ADMIT, ScanOutcome.ADMIT.tone)
        assertEquals(ScanTone.RE_ENTRY, ScanOutcome.RE_ENTRY.tone)
        listOf(ScanOutcome.EXPIRED, ScanOutcome.NOT_PAID, ScanOutcome.WRONG_VENUE, ScanOutcome.UNREADABLE, ScanOutcome.UNKNOWN)
            .forEach { assertEquals(ScanTone.REFUSE, it.tone) }
    }

    @Test
    fun theSamePassIsCheckedOnceAWhile() {
        val debouncer = ScanDebouncer(windowMillis = 5_000)
        val first = CoverPass.code(paid.id, paid.passSecret!!, 1_791_403_229)
        val next = CoverPass.code(paid.id, paid.passSecret!!, 1_791_403_230)

        assertTrue(debouncer.accept(first, 0))
        assertFalse(debouncer.accept(first, 1_000))
        assertFalse(debouncer.accept(next, 2_000), "the same pass after its code changed")
        assertTrue(debouncer.accept("HMC1.someone-else.1.sig", 2_500))
        assertTrue(debouncer.accept(next, 8_000))
        assertTrue(debouncer.accept("https://example.com", 8_000))
        assertFalse(debouncer.accept("https://example.com", 8_100))
    }

    @Test
    fun passStatusReads() {
        assertEquals("Paid", paid.statusText)
        assertEquals("Checked in", paid.copy(checkedInAt = "2026-10-10T04:42:00Z").statusText)
        assertEquals("Refunded", paid.copy(status = AdmissionStatus.REFUNDED).statusText)
    }
}

class PassQrTest {
    @Test
    fun drawsAPassThatReadsBack() {
        val code = CoverPass.code(paid.id, paid.passSecret!!, 1_791_403_200)
        assertEquals("HMC1.${paid.id}.59713440.NQh13_EnQAdAX6cE", code)
        val matrix = PassQr.encode(code)

        // Render it the way a camera sees it: 4 pixels a module, padded rows, black on white.
        val scale = 4
        val width = matrix.size * scale
        val stride = width + 16
        val pixels = ByteArray(stride * width) { 0xFF.toByte() }
        for (y in 0 until width) for (x in 0 until width) {
            if (matrix.isDark(x / scale, y / scale)) pixels[y * stride + x] = 0
        }

        assertEquals(code, PassQr.decode(pixels, width, width, rowStride = stride))
    }

    @Test
    fun aBlankFrameHasNoCode() {
        assertNull(PassQr.decode(ByteArray(100 * 100) { 0x80.toByte() }, 100, 100))
    }
}

class PassBookTest {
    @Test
    fun keepsPassesAndTheirSecrets() {
        val book = PassBook()
        book.put(paid)
        book.put(paid.copy(id = "other", night = "2026-10-10"))
        assertEquals(listOf("other", paid.id), book.passes.value.map { it.id })

        // The door's copy of a pass has no secret; the buyer's stays.
        book.put(paid.copy(status = AdmissionStatus.REFUNDED, passSecret = null))
        assertEquals(AdmissionStatus.REFUNDED, book[paid.id]?.status)
        assertEquals(paid.passSecret, book[paid.id]?.passSecret)

        book.replaceAll(listOf(paid.copy(passSecret = null)))
        assertEquals(paid.passSecret, book.passes.value.single().passSecret)
        book.clear()
        assertTrue(book.passes.value.isEmpty())
    }
}

class CoverApiTest {
    private fun api(respond: (String) -> String): Pair<HotMessApi, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { request ->
            val body = request.body?.toString(Charsets.UTF_8).orEmpty()
            bodies += body
            HttpResponse(200, respond(body).toByteArray())
        }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client) to bodies
    }

    @Test
    fun theVenueAsksForTonightsCoverAndPass() = runTest {
        val (api, bodies) = api {
            """{"data":{"venue":{"id":"v-1","name":"The Wildrose","chatOpen":false,"canWorkDoor":true,"events":[],
               "coverCharge":{"amountCents":1000,"totalCents":1112,"feeCents":50,"currency":"usd","night":"2026-10-09",
                 "from":"21:00","payable":true,"event":null},
               "viewerAdmission":{"id":"a-1","status":"PAID","night":"2026-10-09","totalCents":1112,"isRefundable":true,
                 "passSecret":"AAEC","venue":{"id":"v-1","name":"The Wildrose"}}}}}"""
        }
        val venue = api.venueOverview("v-1").venue

        assertTrue(bodies.single().contains("viewerAdmission { id status"))
        assertTrue(venue.canWorkDoor)
        assertEquals(1112, venue.coverCharge?.totalCents)
        assertEquals(CoverAction.SHOW_PASS, CoverOffer.of(venue)?.action)
    }

    @Test
    fun theEventAsksForItsCoverAndTheVenuesTonight() = runTest {
        val (api, bodies) = api {
            """{"data":{"event":{"id":"e-1","name":"Drag Bingo","startAt":"2026-10-10T04:00:00Z",
               "coverCharge":{"amountCents":500,"totalCents":612,"night":"2026-10-09","payable":true},
               "venue":{"id":"v-1","name":"The Wildrose","coverCharge":{"amountCents":500,"totalCents":612,
                 "night":"2026-10-09","payable":true},"viewerAdmission":null}}}}"""
        }
        val event = api.event("e-1")

        assertTrue(bodies.single().contains("venue { id coverCharge"))
        assertEquals(CoverAction.PAY, CoverOffer.of(event)?.action)
    }

    @Test
    fun coverErrorsAreShownAsTheApiWroteThem() = runTest {
        val (api, _) = api { """{"data":null,"errors":[{"message":"The Wildrose doesn't take cover in the app yet"}]}""" }

        val error = assertFailsWith<ApiError.Other> { api.buyCover("v-1") }
        assertEquals("The Wildrose doesn't take cover in the app yet", error.message)
    }
}
