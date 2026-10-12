package social.hotmess.core

import com.audiencekit.Admission
import com.audiencekit.AdmissionStatus
import com.audiencekit.CoverCharge
import com.audiencekit.CoverPurchase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Digital cover charge: paying a venue's cover in the app, and the pass the door scans. Working the door is staff
// work, in the staff apps.

/** What the cover row offers. */
enum class CoverAction {
    /** It can be paid in the app: "Pay cover". */
    PAY,

    /** It's paid at the door. */
    PAY_AT_DOOR,

    /** The user has paid: "Show pass". */
    SHOW_PASS,

    /** Another night's cover: just the price. */
    PRICE_ONLY,
}

/** A cover as a venue, event or Now shows it: the price, whether it's tonight, and what the user can do. */
data class CoverOffer(
    val charge: CoverCharge,
    val tonight: Boolean,
    val action: CoverAction,
    /** The user's paid pass for it, when [action] is [CoverAction.SHOW_PASS]. */
    val pass: Admission? = null,
) {
    /** "Cover $11.12 tonight · from 9pm". */
    val line: String get() = Formatting.coverLine(charge, tonight)

    companion object {
        /** A venue's cover tonight, or null when it has none. */
        fun of(venue: Venue): CoverOffer? {
            val charge = venue.coverCharge ?: return null
            return tonight(charge, venue.viewerAdmission)
        }

        /**
         * An event's cover. It can be paid (or its pass shown) only when it's tonight's cover at its venue,
         * the one the venue's own [Venue.coverCharge] is for; any other night just shows the price.
         */
        fun of(event: Event): CoverOffer? {
            val charge = event.coverCharge ?: return null
            val venue = event.venue
            val tonight = venue?.coverCharge?.night?.let { it == charge.night } == true
            if (!tonight) return CoverOffer(charge, tonight = false, action = CoverAction.PRICE_ONLY)
            return tonight(charge, venue?.viewerAdmission)
        }

        private fun tonight(charge: CoverCharge, admission: Admission?): CoverOffer {
            val pass = admission?.takeIf { it.isPaid && it.night == charge.night }
            val action = when {
                pass != null -> CoverAction.SHOW_PASS
                charge.payable -> CoverAction.PAY
                else -> CoverAction.PAY_AT_DOOR
            }
            return CoverOffer(charge, tonight = true, action = action, pass = pass)
        }
    }
}

/**
 * How to pay for a [CoverPurchase]: Stripe venues pay on Stripe's payment sheet, Square venues with
 * Square's card entry and [HotMessApi.payCover].
 */
sealed interface CoverPayment {
    /** There's nothing to pay: show the pass. */
    data class Paid(val admission: Admission) : CoverPayment

    /** Stripe has nothing left to take, but the pass isn't paid yet: check it with `confirmCover`. */
    data class Confirm(val admissionId: String) : CoverPayment

    /** Stripe's payment sheet, as a direct charge on the venue's own Stripe account. */
    data class Stripe(
        val admissionId: String,
        val clientSecret: String,
        val publishableKey: String,
        val stripeAccountId: String?,
    ) : CoverPayment

    /** Square's card entry, whose nonce goes to [HotMessApi.payCover]. */
    data class Square(
        val admissionId: String,
        val applicationId: String,
        val locationId: String?,
    ) : CoverPayment

    /** The API didn't send what paying needs. */
    data class Unavailable(val message: String) : CoverPayment

    companion object {
        private const val UNAVAILABLE = "This venue can't take cover in the app right now. Pay at the door."

        fun of(purchase: CoverPurchase): CoverPayment {
            val admission = purchase.admission
            if (admission.isPaid) return Paid(admission)
            if (purchase.isSquare) {
                val applicationId = purchase.squareApplicationId?.takeIf { it.isNotBlank() } ?: return Unavailable(UNAVAILABLE)
                return Square(admission.id, applicationId, purchase.squareLocationId)
            }
            val clientSecret = purchase.paymentIntentClientSecret ?: return Confirm(admission.id)
            val publishableKey = purchase.publishableKey?.takeIf { it.isNotBlank() } ?: return Unavailable(UNAVAILABLE)
            return Stripe(admission.id, clientSecret, publishableKey, purchase.stripeAccountId)
        }
    }
}

/** A pass's status in words. */
val Admission.statusText: String
    get() = when (status) {
        AdmissionStatus.PAID -> if (checkedInAt != null) "Checked in" else "Paid"
        AdmissionStatus.PENDING -> "Payment pending"
        AdmissionStatus.FAILED -> "Payment declined"
        AdmissionStatus.CANCELED -> "Canceled"
        AdmissionStatus.REFUNDED -> "Refunded"
        AdmissionStatus.DISPUTED -> "Disputed"
        AdmissionStatus.UNKNOWN -> "Unavailable"
    }

/**
 * The user's passes, kept for the session, so a pass opened once still shows (and its code still
 * changes) with no signal at the door. Newest first.
 */
class PassBook {
    private val _passes = MutableStateFlow<List<Admission>>(emptyList())
    val passes: StateFlow<List<Admission>> = _passes.asStateFlow()

    operator fun get(id: String): Admission? = _passes.value.firstOrNull { it.id == id }

    /** Replaces the list with what `admissions` returned. */
    fun replaceAll(admissions: List<Admission>) {
        _passes.update { current -> admissions.map { it.keepingSecretOf(current) } }
    }

    /** Adds or updates one pass, e.g. after paying or a refund. */
    fun put(admission: Admission) {
        _passes.update { current ->
            val updated = admission.keepingSecretOf(current)
            if (current.any { it.id == admission.id }) {
                current.map { if (it.id == admission.id) updated else it }
            } else {
                listOf(updated) + current
            }
        }
    }

    fun clear() {
        _passes.value = emptyList()
    }

    private fun Admission.keepingSecretOf(current: List<Admission>): Admission =
        if (passSecret != null) this else copy(passSecret = current.firstOrNull { it.id == id }?.passSecret)
}
