package social.hotmess.android.payments

import android.content.Context
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
import com.audiencekit.CoverPurchase
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
import social.hotmess.core.HotMessApi
import social.hotmess.core.PassBook
import java.util.Locale

/**
 * Pays a venue's cover in the app. `buyCover` starts the payment, Stripe's payment sheet takes it (as a
 * direct charge on the venue's own Stripe account, with Google Pay), then `confirmCover` checks it so
 * the pass works straight away, without waiting for Stripe's webhook.
 *
 * A ViewModel, so a payment in progress survives the screen rotating behind the payment sheet.
 */
class CoverCheckout(
    private val context: Context,
    private val api: HotMessApi,
    private val passes: PassBook,
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

    /** The pass being paid for on the payment sheet. */
    private var pendingId: String? = null

    /** Pays tonight's cover at [venueId], or goes straight to the pass when it's already paid. */
    fun pay(venueId: String, venueName: String) {
        if (_state.value == State.Working) return
        _state.value = State.Working
        viewModelScope.launch {
            try {
                val purchase = api.buyCover(venueId)
                passes.put(purchase.admission)
                val clientSecret = purchase.paymentIntentClientSecret
                when {
                    purchase.admission.isPaid -> _state.value = State.Paid(purchase.admission)
                    clientSecret == null -> confirm(purchase.admission.id)
                    else -> {
                        val present = present ?: throw IllegalStateException("The payment sheet isn't ready.")
                        // Direct charges: the payment is made on the venue's own Stripe account.
                        PaymentConfiguration.init(context, purchase.publishableKey, purchase.stripeAccountId)
                        pendingId = purchase.admission.id
                        present(clientSecret, configuration(venueName, purchase))
                    }
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

    /** Back to idle once the screen has acted on [State.Paid] or shown [State.Failed]. */
    fun dismiss() {
        if (_state.value != State.Working) _state.value = State.Idle
    }

    /** Asks the API to check the payment with Stripe, a few times while it's still processing. */
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

    private fun configuration(venueName: String, purchase: CoverPurchase): PaymentSheet.Configuration {
        val admission = purchase.admission
        val googlePay = PaymentSheet.GooglePayConfiguration(
            environment = if (purchase.publishableKey.startsWith("pk_live")) {
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
 * The cover checkout for this screen, with Stripe's payment sheet attached. [onPaid] gets the paid pass;
 * show [CoverCheckoutFailure] alongside for what went wrong.
 */
@Composable
fun rememberCoverCheckout(onPaid: (Admission) -> Unit): CoverCheckout {
    val graph = LocalAppGraph.current
    val context = LocalContext.current.applicationContext
    val checkout = viewModel(key = "cover-checkout") { CoverCheckout(context, graph.api, graph.passes) }

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
