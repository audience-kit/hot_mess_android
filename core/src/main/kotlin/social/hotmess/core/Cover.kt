package social.hotmess.core

import com.audiencekit.Admission
import com.audiencekit.AdmissionStatus
import com.audiencekit.CoverCharge
import com.audiencekit.CoverPass
import com.audiencekit.ScanOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Digital cover charge: paying a venue's cover in the app, the pass the door scans, and Door mode.

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

/** How the door shows a scan: go, careful, or stop. */
enum class ScanTone { ADMIT, RE_ENTRY, REFUSE }

val ScanOutcome.tone: ScanTone
    get() = when (this) {
        ScanOutcome.ADMIT -> ScanTone.ADMIT
        ScanOutcome.RE_ENTRY -> ScanTone.RE_ENTRY
        else -> ScanTone.REFUSE
    }

/** The door's big word for a scan. */
val ScanOutcome.headline: String
    get() = when (this) {
        ScanOutcome.ADMIT -> "ADMIT"
        ScanOutcome.RE_ENTRY -> "RE-ENTRY"
        ScanOutcome.EXPIRED -> "OLD CODE"
        ScanOutcome.NOT_PAID -> "NOT PAID"
        ScanOutcome.WRONG_VENUE -> "WRONG PASS"
        ScanOutcome.UNREADABLE, ScanOutcome.UNKNOWN -> "NOT A PASS"
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
 * Keeps a camera that's still pointed at the same pass from scanning it again and again. A pass's code
 * changes every 30 seconds, so the same pass counts as the same code until [windowMillis] has passed
 * since it was last seen.
 */
class ScanDebouncer(private val windowMillis: Long = 5_000) {
    private val lastSeen = mutableMapOf<String, Long>()

    /** Whether to check [code], read at [nowMillis]. */
    fun accept(code: String, nowMillis: Long): Boolean {
        val key = key(code)
        val last = lastSeen[key]
        lastSeen[key] = nowMillis
        lastSeen.entries.removeAll { nowMillis - it.value > windowMillis * 4 }
        return last == null || nowMillis - last > windowMillis
    }

    private fun key(code: String): String {
        val trimmed = code.trim()
        return if (CoverPass.isPassCode(trimmed)) trimmed.split(".")[1] else trimmed
    }
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
