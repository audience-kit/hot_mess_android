package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.BugReportRequest

/**
 * "Report a problem": what went wrong, in the person's words, sent to the AudienceKit API with the app's version
 * and, if they agree, the last few errors the app saw. Opened from Me, and from a failed sign-in ([screen] says which).
 */
@Composable
fun ReportProblemDialog(screen: String, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val signedIn = graph.session.isSignedIn
    var details by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var includeDiagnostics by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    if (sent) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            title = { Text("Thanks for letting us know", style = HotMessType.heading) },
            text = { Text("Your report is on its way to the Hot Mess team.", style = HotMessType.body) },
            containerColor = tokens.surfaceRaised,
        )
        return
    }

    fun send() {
        sending = true
        failure = null
        scope.launch {
            val configuration = graph.configuration
            val device = graph.errors.device
            try {
                graph.api.reportProblem(
                    BugReportRequest(
                        description = details.trim(),
                        email = email.trim().ifEmpty { null },
                        screen = screen,
                        version = device.version,
                        build = device.build,
                        osVersion = device.osVersion,
                        device = device.model,
                        host = configuration.audienceHost,
                        diagnostics = if (includeDiagnostics) {
                            BugReportRequest.Diagnostics(
                                signedIn = signedIn,
                                environment = configuration.environment.value,
                                locale = graph.location.locale.value?.name,
                                recentErrors = graph.errors.recentErrors(),
                            )
                        } else {
                            null
                        },
                    ),
                    signedIn = signedIn,
                )
                sent = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "Couldn't send your report. Check your connection and try again."
            } finally {
                sending = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem", style = HotMessType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(DESCRIPTION_LIMIT) },
                    placeholder = { Text("What went wrong? Say what you were doing and what you expected.") },
                    shape = Radius.md,
                    textStyle = HotMessType.body,
                    minLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("report.description"),
                )
                if (!signedIn) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it.take(254) },
                        placeholder = { Text("Email, so we can get back to you (optional)") },
                        shape = Radius.md,
                        textStyle = HotMessType.body,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("report.email"),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeDiagnostics,
                        onCheckedChange = { includeDiagnostics = it },
                        colors = CheckboxDefaults.colors(checkedColor = tokens.accent),
                    )
                    Text("Include diagnostics", style = HotMessType.body, color = tokens.ink)
                }
                Text(
                    "Your app version, phone and the last few errors Hot Mess saw. Never your messages or where you are.",
                    style = HotMessType.caption,
                    color = tokens.inkMuted,
                )
                failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::send, enabled = details.isNotBlank() && !sending, modifier = Modifier.testTag("report.send")) {
                Text(if (sending) "Sending…" else "Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = tokens.surfaceRaised,
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = tokens.focus,
    unfocusedBorderColor = tokens.border,
    cursorColor = tokens.accent,
)

private const val DESCRIPTION_LIMIT = 5_000
