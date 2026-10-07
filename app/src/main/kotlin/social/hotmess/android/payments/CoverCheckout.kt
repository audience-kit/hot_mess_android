package social.hotmess.android.payments

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiencekit.Admission
import com.audiencekit.AdmissionStatus
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.PaymentSheetResultCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.core.ApiError
import social.hotmess.core.CoverPayment
import social.hotmess.core.HotMessApi
import social.hotmess.core.PassBook
import sqip.CardEntryActivityResult
import java.util.Locale

/**
 * Pays a venue's cover in the app. `buyCover` starts the payment, and [CoverPayment] says how it's taken:
 * - Stripe venues: Stripe's payment sheet takes it (as a direct charge on the venue's own Stripe account,
 *   with Google Pay), then `confirmCover` checks it so the pass works straight away, without waiting for
 *   Stripe's webhook.
 * - Square venues: Square's card entry makes a nonce for the card, and `payCover` pays with it.
 *
 * A ViewModel, so a payment in progress survives the screen rotating behind the payment sheet or card entry.
 */
class CoverCheckout(
    private val context: Context,
    private val api: HotMessApi,
    private val passes: PassBook,
    squareCardEntry: SquareCardEntry,
) : ViewModel() {
    sealed interface State {
        data object Idle : State

        /** Starting the payment, on the payment sheet, or confirming it. */
        data object Working : State

        data class Paid(val admission: Admission) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Shows the payment sheet; set while the screen that owns it is on screen. */
    internal var present: ((clientSecret: String, configuration: PaymentSheet.Configuration) -> Unit)? = null

    /** Opens Square's card entry; set while the screen that owns it is on screen. */
    internal var startCardEntry: ((applicationId: String) -> Unit)? = null

    /** The pass being paid for on the payment sheet. */
    private var pendingId: String? = null

    /** The pass being paid for in Square's card entry. */
    private var pendingSquareId: String? = null

    init {
        viewModelScope.launch { squareCardEntry.results.collect(::onCardEntryResult) }
    }

    /** Pays tonight's cover at [venueId], or goes straight to the pass when it's already paid. */
    fun pay(venueId: String, venueName: String) {
        if (_state.value == State.Working) return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val purchase = api.buyCover(venueId)
                passes.put(purchase.admission)
                when (val payment = CoverPayment.of(purchase)) {
                    is CoverPayment.Paid -> _state.value = State.Paid(payment.admission)
                    is CoverPayment.Confirm -> confirm(payment.admissionId)
                    is CoverPayment.Stripe -> {
                        val present = present ?: throw IllegalStateException("The payment sheet isn't ready.")
                        // Direct charges: the payment is made on the venue's own Stripe account.
                        PaymentConfiguration.init(context, payment.publishableKey, payment.stripeAccountId)
                        pendingId = payment.admissionId
                        present(payment.clientSecret, configuration(venueName, purchase.admission, payment.publishableKey))
                    }
                    is CoverPayment.Square -> {
                        val start = startCardEntry ?: throw IllegalStateException("Card entry isn't ready.")
                        pendingSquareId = payment.admissionId
                        start(payment.applicationId)
                    }
                    is CoverPayment.Unavailable -> _state.value = State.Failed(payment.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(ApiError.of(e).message ?: "We couldn't start the payment. Try again.")
            }
        }
    }

    internal fun onResult(result: PaymentSheetResult) {
        val id = pendingId
        pendingId = null
        when {
            id == null -> _state.value = State.Idle
            result is PaymentSheetResult.Completed -> viewModelScope.launch {
                try {
                    confirm(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = State.Failed(
                        "You've paid, but we couldn't load your pass. It'll be in Passes on the Me tab in a moment.",
                    )
                }
            }
            result is PaymentSheetResult.Canceled -> _state.value = State.Idle
            result is PaymentSheetResult.Failed ->
                _state.value = State.Failed(result.error.localizedMessage ?: "The payment didn't go through. Try again.")
            else -> _state.value = State.Idle
        }
    }

    /** Square's card entry finished: pay with the card's nonce, or back to idle when it was canceled. */
    private fun onCardEntryResult(result: CardEntryActivityResult) {
        val id = pendingSquareId ?: return
        pendingSquareId = null
        if (!result.isSuccess()) {
            _state.value = State.Idle
            return
        }
        val nonce = result.getSuccessValue().nonce
        viewModelScope.launch {
            try {
                val admission = api.payCover(id, nonce)
                passes.put(admission)
                when (admission.status) {
                    AdmissionStatus.PAID -> _state.value = State.Paid(admission)
                    AdmissionStatus.PENDING -> confirm(id)
                    else -> _state.value = State.Failed("The payment didn't go through, so you haven't been charged. Try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(ApiError.of(e).message ?: "The payment didn't go through. Try again.")
            }
        }
    }

    /** Back to idle once the screen has acted on [State.Paid] or shown [State.Failed]. */
    fun dismiss() {
        if (_state.value != State.Working) _state.value = State.Idle
    }

    /** Asks the API to check the payment with its provider, a few times while it's still processing. */
    private suspend fun confirm(admissionId: String) {
        repeat(CONFIRM_ATTEMPTS) { attempt ->
            val admission = api.confirmCover(admissionId)
            passes.put(admission)
            when (admission.status) {
                AdmissionStatus.PAID -> {
                    _state.value = State.Paid(admission)
                    return
                }
                AdmissionStatus.PENDING -> delay(1_500L * (attempt + 1))
                else -> {
                    _state.value = State.Failed("The payment didn't go through, so you haven't been charged. Try again.")
                    return
                }
            }
        }
        _state.value = State.Failed("Your payment is still going through. Your pass will be in Passes on the Me tab once it has.")
    }

    private fun configuration(venueName: String, admission: Admission, publishableKey: String): PaymentSheet.Configuration {
        val googlePay = PaymentSheet.GooglePayConfiguration(
            environment = if (publishableKey.startsWith("pk_live")) {
                PaymentSheet.GooglePayConfiguration.Environment.Production
            } else {
                PaymentSheet.GooglePayConfiguration.Environment.Test
            },
            countryCode = "US",
            currencyCode = admission.currency.uppercase(Locale.ROOT),
            amount = admission.totalCents.toLong(),
            label = "Cover at $venueName",
        )
        return PaymentSheet.Configuration.Builder(venueName)
            .googlePay(googlePay)
            .build()
    }

    private companion object {
        const val CONFIRM_ATTEMPTS = 4
    }
}

/**
 * The cover checkout for this screen, with Stripe's payment sheet and Square's card entry attached. [onPaid]
 * gets the paid pass; show [CoverCheckoutFailure] alongside for what went wrong.
 */
@Composable
fun rememberCoverCheckout(onPaid: (Admission) -> Unit): CoverCheckout {
    val graph = LocalAppGraph.current
    val context = LocalContext.current.applicationContext
    val activity = LocalActivity.current
    val checkout = viewModel(key = "cover-checkout") {
        CoverCheckout(context, graph.api, graph.passes, graph.squareCardEntry)
    }

    val sheet = remember(checkout) {
        PaymentSheet.Builder(
            object : PaymentSheetResultCallback {
                override fun onPaymentSheetResult(paymentSheetResult: PaymentSheetResult) = checkout.onResult(paymentSheetResult)
            },
        )
    }.build()
    DisposableEffect(checkout, sheet) {
        checkout.present = { clientSecret, configuration -> sheet.presentWithPaymentIntent(clientSecret, configuration) }
        onDispose { checkout.present = null }
    }
    DisposableEffect(checkout, activity) {
        checkout.startCardEntry = activity?.let { host ->
            { applicationId -> graph.squareCardEntry.start(host, applicationId) }
        }
        onDispose { checkout.startCardEntry = null }
    }

    val state by checkout.state.collectAsStateWithLifecycle()
    val currentOnPaid by rememberUpdatedState(onPaid)
    LaunchedEffect(state) {
        val paid = state as? CoverCheckout.State.Paid ?: return@LaunchedEffect
        checkout.dismiss()
        currentOnPaid(paid.admission)
    }
    return checkout
}

/** Why paying cover didn't work, when it didn't. */
@Composable
fun CoverCheckoutFailure(checkout: CoverCheckout) {
    val state by checkout.state.collectAsStateWithLifecycle()
    val failed = state as? CoverCheckout.State.Failed ?: return
    AlertDialog(
        onDismissRequest = checkout::dismiss,
        confirmButton = { TextButton(onClick = checkout::dismiss) { Text("OK") } },
        title = { Text("Couldn't pay cover") },
        text = { Text(failed.message) },
    )
}
