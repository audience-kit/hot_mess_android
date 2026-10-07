package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.Admission
import com.audiencekit.AdmissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.Card
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.DangerRow
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.KeepScreenBright
import social.hotmess.android.ui.components.LiveBand
import social.hotmess.android.ui.components.LiveClock
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.QrCode
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.components.rememberPassCode
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.Formatting
import social.hotmess.core.firstNameForDisplay
import social.hotmess.core.statusText

/**
 * A cover pass, to show at the door: where and when, who it's for, and a QR code that changes every 30
 * seconds over a band that never stops moving. The screen stays on and bright while it's open.
 */
@Composable
fun PassScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val passes by graph.passes.passes.collectAsStateWithLifecycle()
    val user by graph.session.user.collectAsStateWithLifecycle()
    val pass = passes.firstOrNull { it.id == id }
    var loadError by remember { mutableStateOf<String?>(null) }
    var confirmRefund by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    // Freshen the pass (scanned in, refunded) when there's signal; the copy already here works without.
    LaunchedEffect(id) {
        try {
            graph.passes.replaceAll(graph.api.admissions())
            if (graph.passes[id] == null) loadError = "That pass isn't available any more."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = ApiError.of(e).message
        }
    }

    fun act(block: suspend () -> Admission) {
        if (working) return
        working = true
        scope.launch {
            try {
                graph.passes.put(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                working = false
            }
        }
    }

    ScreenScaffold(title = "Pass", onBack = navigator::back) {
        when {
            pass != null -> Feed {
                item {
                    PassFace(
                        pass,
                        holderName = pass.userName ?: user?.firstName ?: "",
                        holderPhoto = pass.userPhotoUrl ?: user?.id?.let(graph.configuration::avatarUrl),
                    )
                }
                if (pass.status == AdmissionStatus.PENDING) {
                    item {
                        Section(null) {
                            EmptyRow("Your payment is still going through. Your code shows up here once it has.")
                            Box(Modifier.fillMaxWidth().padding(Space.s4)) {
                                if (working) {
                                    CircularProgressIndicator(color = tokens.accent, modifier = Modifier.align(Alignment.Center))
                                } else {
                                    PrimaryButton("Check again", { act { graph.api.confirmCover(pass.id) } }, Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
                if (pass.isRefundable) {
                    item {
                        Section(null) {
                            if (working) {
                                Box(Modifier.fillMaxWidth().padding(Space.s3), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = tokens.accent, modifier = Modifier.size(24.dp))
                                }
                            } else {
                                DangerRow("Refund ${Formatting.money(pass.totalCents, pass.currency)}") { confirmRefund = true }
                            }
                            EmptyRow("You can get your money back until you're scanned in.")
                        }
                    }
                }
            }
            loadError != null -> Message(Icons.Rounded.ErrorOutline, "Couldn't load your pass", loadError.orEmpty())
            else -> Box(Modifier.padding(Space.s8).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
        }
    }

    if (confirmRefund && pass != null) {
        val amount = Formatting.money(pass.totalCents, pass.currency)
        AlertDialog(
            onDismissRequest = { confirmRefund = false },
            title = { Text("Refund your cover?") },
            text = { Text("$amount goes back to your card, and this pass stops working. Refunds take 5 to 10 days to show up.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRefund = false
                    act { graph.api.refundAdmission(pass.id) }
                }) { Text("Refund", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRefund = false }) { Text("Keep it") } },
        )
    }

    actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
        )
    }
}

/** The pass itself. A paid pass shows its live code; any other says where it stands instead. */
@Composable
private fun PassFace(pass: Admission, holderName: String, holderPhoto: String?) {
    val live = pass.status == AdmissionStatus.PAID
    val code = if (live) rememberPassCode(pass) else null
    if (live) KeepScreenBright()

    Card {
        if (live) LiveBand(height = 16.dp)
        Column(
            Modifier.fillMaxWidth().padding(Space.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(pass.venue?.name ?: "Cover", style = HotMessType.title, color = tokens.ink, textAlign = TextAlign.Center)
            Text(
                listOfNotNull(Formatting.night(pass.night), pass.event?.name).joinToString(" · "),
                style = HotMessType.body,
                color = tokens.inkMuted,
                textAlign = TextAlign.Center,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Avatar(holderPhoto, holderName, size = 56.dp)
                Column {
                    Text(holderName.firstNameForDisplay(), style = HotMessType.heading, color = tokens.ink)
                    Text(
                        "${pass.statusText} · ${Formatting.money(pass.totalCents, pass.currency)}",
                        style = HotMessType.bodySmall,
                        color = if (live) tokens.success else tokens.inkMuted,
                    )
                }
            }
            if (code != null) {
                Box(Modifier.widthIn(max = 360.dp).fillMaxWidth()) { QrCode(code, Modifier.fillMaxWidth()) }
                LiveClock()
                Text(
                    "Show this at the door. The code changes every 30 seconds, so screenshots don't work.",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                    textAlign = TextAlign.Center,
                )
            } else if (live) {
                EmptyRow("This pass's code can only be shown on the phone that paid for it.")
            }
        }
        if (live) LiveBand(height = 16.dp)
    }
}

/** Every cover you've paid in the app, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassesScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<Admission>>("passes")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val passes by graph.passes.passes.collectAsStateWithLifecycle()

    fun load(refresh: Boolean = false) = loader.load(Unit, refresh) { graph.api.admissions().also(graph.passes::replaceAll) }

    LaunchedEffect(Unit) { load() }

    ScreenScaffold(title = "Passes", onBack = navigator::back) {
        LoadStateView(state, onRetry = { load() }) {
            PullToRefreshBox(refreshing, onRefresh = { load(refresh = true) }) {
                if (passes.isEmpty()) {
                    Message(
                        Icons.Rounded.ConfirmationNumber,
                        "No passes yet",
                        "When you pay a venue's cover in the app, your pass shows up here.",
                    )
                } else {
                    Feed {
                        item {
                            Section("Your passes") {
                                CardRows(passes, divider = 72.dp) { pass -> PassRow(pass) { navigator.openPass(pass.id) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PassRow(pass: Admission, onClick: () -> Unit) {
    RowButton(onClick) {
        RemoteImage(pass.venue?.photoUrl, Modifier.size(44.dp).clip(Radius.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pass.venue?.name ?: "Cover", style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formatting.night(pass.night)} · ${pass.statusText}",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(Formatting.money(pass.totalCents, pass.currency), style = HotMessType.bodySmall, color = tokens.inkMuted)
    }
}
