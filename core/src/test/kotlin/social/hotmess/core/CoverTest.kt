package social.hotmess.core

import com.audiencekit.Admission
import com.audiencekit.AdmissionStatus
import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.CoverCharge
import com.audiencekit.CoverPass
import com.audiencekit.CoverPurchase
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import com.audiencekit.Money
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val tonight = CoverCharge(amount = Money(1000), total = Money(1112), fee = Money(50), night = "2026-10-09", from = "21:00", payable = true)
private val paid = Admission(
    id = "7b4e0c6a-1d2f-4e8a-9c3b-5f6a7b8c9d0e",
    status = AdmissionStatus.PAID,
    night = "2026-10-09",
    total = Money(1112),
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
    private val venue = Venue(id = "v-1", name = "The Wildrose", tickets = VenueTicketsMixin(VenueCoverMixin(charge = tonight)))

    @Test
    fun aPayableCoverCanBePaid() {
        assertEquals(CoverAction.PAY, CoverOffer.of(venue)?.action)
        assertNull(CoverOffer.of(venue.copy(tickets = null)))
    }

    @Test
    fun anotherCoverIsPaidAtTheDoor() {
        assertEquals(CoverAction.PAY_AT_DOOR, CoverOffer.of(venue.copy(tickets = VenueTicketsMixin(VenueCoverMixin(charge = tonight.copy(payable = false)))))?.action)
    }

    @Test
    fun aPaidPassIsShownInstead() {
        val offer = CoverOffer.of(venue.copy(tickets = VenueTicketsMixin(VenueCoverMixin(charge = tonight, viewerAdmission = paid))))!!
        assertEquals(CoverAction.SHOW_PASS, offer.action)
        assertEquals(paid, offer.pass)
        val pending = paid.copy(status = AdmissionStatus.PENDING)
        assertEquals(CoverAction.PAY, CoverOffer.of(venue.copy(tickets = VenueTicketsMixin(VenueCoverMixin(charge = tonight, viewerAdmission = pending))))?.action)
    }

    @Test
    fun anEventsCoverCanBePaidOnlyTonight() {
        val event = Event(
            id = "e-1",
            startAt = Instant.parse("2026-10-10T04:00:00Z"),
            venueMixin = EventVenueMixin(venue),
            tickets = EventTicketsMixin(EventCoverMixin(tonight)),
        )
        assertEquals(CoverAction.PAY, CoverOffer.of(event)?.action)
        assertTrue(CoverOffer.of(event)!!.tonight)

        val nextWeek = event.copy(tickets = EventTicketsMixin(EventCoverMixin(tonight.copy(night = "2026-10-16"))))
        assertEquals(CoverAction.PRICE_ONLY, CoverOffer.of(nextWeek)?.action)
        assertEquals("Cover $11.12 · from 9pm", Formatting.coverLine(nextWeek.coverCharge!!, CoverOffer.of(nextWeek)!!.tonight, Locale.US))

        val withPass = event.copy(venueMixin = EventVenueMixin(venue.copy(tickets = VenueTicketsMixin(VenueCoverMixin(charge = tonight, viewerAdmission = paid)))))
        assertEquals(CoverAction.SHOW_PASS, CoverOffer.of(withPass)?.action)
        assertEquals(CoverAction.PRICE_ONLY, CoverOffer.of(event.copy(venueMixin = null))?.action)
        assertNull(CoverOffer.of(event.copy(tickets = null)))
    }
}

class CoverPaymentTest {
    private val pending = paid.copy(status = AdmissionStatus.PENDING)
    private val stripe = CoverPurchase(
        admission = pending,
        paymentIntentClientSecret = "pi_1_secret_2",
        publishableKey = "pk_test_1",
        stripeAccountId = "acct_1",
    )
    private val square = CoverPurchase(
        admission = pending,
        provider = "SQUARE",
        squareApplicationId = "sq0idp-1",
        squareLocationId = "L1",
    )

    @Test
    fun stripeVenuesPayOnThePaymentSheet() {
        assertEquals(CoverPayment.Stripe(paid.id, "pi_1_secret_2", "pk_test_1", "acct_1"), CoverPayment.of(stripe))
        assertEquals(CoverPayment.Confirm(paid.id), CoverPayment.of(stripe.copy(paymentIntentClientSecret = null)))
        assertTrue(CoverPayment.of(stripe.copy(publishableKey = null)) is CoverPayment.Unavailable)
    }

    @Test
    fun squareVenuesPayWithCardEntry() {
        assertTrue(square.isSquare)
        assertFalse(square.isPaid, "a Square purchase has no client secret but still needs paying")
        assertEquals(CoverPayment.Square(paid.id, "sq0idp-1", "L1"), CoverPayment.of(square))
        assertTrue(CoverPayment.of(square.copy(squareApplicationId = null)) is CoverPayment.Unavailable)
        assertTrue(CoverPayment.of(square.copy(squareApplicationId = " ")) is CoverPayment.Unavailable)
    }

    @Test
    fun aPaidPassIsShownWhicheverTheProvider() {
        assertEquals(CoverPayment.Paid(paid), CoverPayment.of(stripe.copy(admission = paid)))
        assertEquals(CoverPayment.Paid(paid), CoverPayment.of(square.copy(admission = paid)))
    }
}

class PassStatusTest {
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
            """{"data":{"venues":{"venue":{"id":"v-1","name":"The Wildrose","chat":{"open":false},"events":{"upcoming":{"nodes":[]}},
               "tickets":{"cover":{"charge":{"amount":{"cents":1000,"currency":"usd"},"total":{"cents":1112,"currency":"usd"},
                 "fee":{"cents":50,"currency":"usd"},"night":"2026-10-09","from":"21:00","payable":true,"event":null},
               "viewerAdmission":{"id":"a-1","status":"PAID","night":"2026-10-09","total":{"cents":1112,"currency":"usd"},"isRefundable":true,
                 "passSecret":"AAEC","venue":{"id":"v-1","name":"The Wildrose"}}}}}}}}"""
        }
        val venue = api.venueOverview("v-1").venue

        assertTrue(bodies.single().contains("tickets { cover { charge {") && bodies.single().contains("viewerAdmission { id status"))
        assertEquals(Money(1112, "usd"), venue.coverCharge?.total)
        assertEquals(CoverAction.SHOW_PASS, CoverOffer.of(venue)?.action)
    }

    @Test
    fun theEventAsksForItsCoverAndTheVenuesTonight() = runTest {
        val (api, bodies) = api {
            """{"data":{"events":{"event":{"id":"e-1","name":"Drag Bingo","startAt":"2026-10-10T04:00:00Z",
               "tickets":{"cover":{"charge":{"amount":{"cents":500,"currency":"usd"},"total":{"cents":612,"currency":"usd"},"night":"2026-10-09","payable":true}}},
               "venue":{"name":"The Wildrose","venue":{"id":"v-1","name":"The Wildrose","tickets":{"cover":{"charge":{"amount":{"cents":500,"currency":"usd"},
                 "total":{"cents":612,"currency":"usd"},"night":"2026-10-09","payable":true},"viewerAdmission":null}}}}}}}}"""
        }
        val event = api.event("e-1")

        assertTrue(bodies.single().contains("venue { venue { id tickets { cover { charge {"))
        assertEquals(CoverAction.PAY, CoverOffer.of(event)?.action)
    }

    @Test
    fun aSquareVenueSaysHowToPay() = runTest {
        val (api, _) = api {
            """{"data":{"buyCover":{"admission":{"id":"a-1","status":"PENDING","night":"2026-10-09","total":{"cents":1112,"currency":"usd"}},
               "provider":"SQUARE","paymentIntentClientSecret":null,"publishableKey":null,"stripeAccountId":null,
               "squareApplicationId":"sq0idp-1","squareLocationId":"L1"}}}"""
        }
        assertEquals(CoverPayment.Square("a-1", "sq0idp-1", "L1"), CoverPayment.of(api.buyCover("v-1")))
    }

    @Test
    fun aSquareNonceIsSentWithPayCover() = runTest {
        val (api, bodies) = api {
            """{"data":{"payCover":{"admission":{"id":"a-1","status":"PAID","night":"2026-10-09","total":{"cents":1112,"currency":"usd"},
               "passSecret":"AAEC"}}}}"""
        }
        val admission = api.payCover("a-1", "cnon:card-nonce-ok")

        assertTrue(bodies.single().contains("mutation PayCover"))
        assertTrue(bodies.single().contains("cnon:card-nonce-ok"))
        assertEquals(AdmissionStatus.PAID, admission.status)
        assertTrue(admission.isPaid)
    }

    @Test
    fun coverErrorsAreShownAsTheApiWroteThem() = runTest {
        val (api, _) = api { """{"data":null,"errors":[{"message":"The Wildrose doesn't take cover in the app yet","extensions":{"code":"INVALID"}}]}""" }

        val error = assertFailsWith<ApiError.Rejected> { api.buyCover("v-1") }
        assertEquals("The Wildrose doesn't take cover in the app yet", error.message)
    }
}
