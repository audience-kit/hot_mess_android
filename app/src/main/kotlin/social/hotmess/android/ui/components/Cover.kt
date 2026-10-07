package social.hotmess.android.ui.components

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audiencekit.Admission
import com.audiencekit.CoverPass
import kotlinx.coroutines.delay
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.CoverAction
import social.hotmess.core.CoverOffer
import social.hotmess.core.Formatting
import social.hotmess.core.PassQr
import social.hotmess.core.QrMatrix
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Cover charge: the price row on venues and events, and the pass's live QR code.

/**
 * "Cover $11.12 tonight · from 9pm", with "Pay cover", "Show pass" or "Pay at the door". [paying] shows
 * a spinner in place of the button while the payment starts.
 */
@Composable
fun CoverRow(offer: CoverOffer, paying: Boolean, onPay: () -> Unit, onShowPass: (Admission) -> Unit) {
    RowButton(null) {
        Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(20.dp))
        Text(offer.line, style = HotMessType.body, color = tokens.ink, modifier = Modifier.weight(1f))
        when (offer.action) {
            CoverAction.PAY ->
                if (paying) {
                    CircularProgressIndicator(color = tokens.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                } else {
                    PrimaryButton("Pay cover", onPay)
                }
            CoverAction.SHOW_PASS -> offer.pass?.let { pass -> PrimaryButton("Show pass", { onShowPass(pass) }) }
            CoverAction.PAY_AT_DOOR -> Text("Pay at the door", style = HotMessType.bodySmall, color = tokens.inkMuted)
            CoverAction.PRICE_ONLY -> Unit
        }
    }
}

/**
 * The pass's QR code right now, made on the phone from its secret every 30 seconds so it works with no
 * signal; null without a secret (another person's pass) or when the secret isn't valid.
 */
@Composable
fun rememberPassCode(admission: Admission): String? {
    var code by remember(admission.id, admission.passSecret) { mutableStateOf(admission.passCodeNow()) }
    LaunchedEffect(admission.id, admission.passSecret) {
        while (true) {
            code = admission.passCodeNow()
            val now = System.currentTimeMillis()
            val untilNext = CoverPass.WINDOW_SECONDS * 1000 - now % (CoverPass.WINDOW_SECONDS * 1000)
            delay(untilNext + 50)
        }
    }
    return code
}

private fun Admission.passCodeNow(): String? = runCatching { passCode(System.currentTimeMillis() / 1000) }.getOrNull()

/** A QR code, dark modules on white whatever the theme, so any scanner reads it. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix: QrMatrix = remember(text) { PassQr.encode(text) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(Radius.lg)
            .background(Color.White)
            .semantics { contentDescription = "Pass QR code" },
    ) {
        val module = size.width / matrix.size
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    // A hair of overlap so no seams show between modules.
                    drawRect(Color.Black, topLeft = Offset(x * module, y * module), size = Size(module + 0.5f, module + 0.5f))
                }
            }
        }
    }
}

/**
 * The audience's spectrum sliding slowly sideways: a screenshot can't move, so door staff can tell a live
 * pass at a glance.
 */
@Composable
fun LiveBand(modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val colors = tokens.spectrum
    val transition = rememberInfiniteTransition(label = "live band")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6_000, easing = LinearEasing)),
        label = "live band phase",
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        val start = phase * size.width
        drawRect(
            Brush.linearGradient(
                colors = colors + colors.first(),
                start = Offset(start, 0f),
                end = Offset(start + size.width, 0f),
                tileMode = TileMode.Repeated,
            ),
        )
    }
}

/** The time to the second, ticking, under the code: "Live · 9:42:13pm". */
@Composable
fun LiveClock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000)
        }
    }
    val time = remember(now) {
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US))
            .replace(" AM", "am").replace(" PM", "pm")
    }
    Text("Live · $time", style = HotMessType.caption, color = tokens.inkMuted, textAlign = TextAlign.Center, modifier = modifier)
}

/** A paid pass as a card: where and when, the live code and band. Tapping it opens the full pass. */
@Composable
fun PassCard(pass: Admission, venueName: String, onOpen: () -> Unit) {
    val code = rememberPassCode(pass)
    Card(Modifier.clickable(role = Role.Button, onClick = onOpen)) {
        LiveBand()
        Column(
            Modifier.fillMaxWidth().padding(Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Text("Your pass", style = HotMessType.caption, color = tokens.accentInk)
            Text(venueName, style = HotMessType.heading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formatting.night(pass.night)} · ${Formatting.money(pass.totalCents, pass.currency)} paid",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
            )
            if (code != null) {
                Box(Modifier.widthIn(max = 200.dp).fillMaxWidth()) { QrCode(code, Modifier.fillMaxWidth()) }
            }
            Text("Tap to open it full screen at the door", style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
    }
}

/** Keeps the screen on and at full brightness while shown, so the door's scanner reads the code. */
@Composable
fun KeepScreenBright() {
    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(view, activity) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        view.keepScreenOn = true
        window?.let { it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL } }
        onDispose {
            view.keepScreenOn = false
            window?.let { it.attributes = it.attributes.apply { screenBrightness = previous } }
        }
    }
}

/** The "Skip the line" card on Now: tonight's cover at the venue you're at, and the way to pay it. */
@Composable
fun SkipTheLineCard(offer: CoverOffer, paying: Boolean, onPay: () -> Unit) {
    Card {
        LiveBand(height = 4.dp)
        Row(
            Modifier.fillMaxWidth().padding(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Skip the line, pay cover", style = HotMessType.heading, color = tokens.ink)
                Text(offer.line, style = HotMessType.bodySmall, color = tokens.inkMuted)
            }
            if (paying) {
                CircularProgressIndicator(color = tokens.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            } else {
                PrimaryButton("Pay", onPay)
            }
        }
    }
}
