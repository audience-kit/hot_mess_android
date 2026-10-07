package social.hotmess.android.payments

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import sqip.Callback
import sqip.CardEntry
import sqip.CardEntryActivityResult
import sqip.InAppPaymentsSdk

/**
 * Square's card entry, for paying a Square venue's cover. It's an activity Square starts for a result, so
 * [MainActivity][social.hotmess.android.MainActivity] hands its result back through [onActivityResult], and
 * [CoverCheckout] collects [results]. Kept on the app graph so a result still arrives after the screen
 * behind card entry was recreated.
 */
class SquareCardEntry {
    private val _results = MutableSharedFlow<CardEntryActivityResult>(extraBufferCapacity = 1)

    /** Card entry's results: the card's nonce, or canceled. */
    val results: SharedFlow<CardEntryActivityResult> = _results.asSharedFlow()

    /** Opens card entry for the audience's Square application. */
    fun start(activity: Activity, applicationId: String) {
        InAppPaymentsSdk.squareApplicationId = applicationId
        CardEntry.startCardEntryActivity(activity, true, CardEntry.DEFAULT_CARD_ENTRY_REQUEST_CODE)
    }

    /** Takes card entry's result from the activity; false when [requestCode] is someone else's. */
    fun onActivityResult(requestCode: Int, data: Intent?): Boolean {
        if (requestCode != CardEntry.DEFAULT_CARD_ENTRY_REQUEST_CODE) return false
        CardEntry.handleActivityResult(data, object : Callback<CardEntryActivityResult> {
            override fun onResult(result: CardEntryActivityResult) {
                _results.tryEmit(result)
            }
        })
        return true
    }
}
