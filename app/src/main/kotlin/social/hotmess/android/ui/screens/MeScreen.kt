package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.item
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.location.LocationAccess
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.DangerRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.Section
import social.hotmess.android.ui.openAppSettings
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens

private enum class Confirm { RESET, SIGN_OUT }

/** You: who you're signed in as, location access, feedback, about the app, and signing out. */
@Composable
fun MeScreen() {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val user by graph.session.user.collectAsStateWithLifecycle()
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val access by graph.location.access.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val configuration = graph.configuration

    LaunchedEffect(Unit) { graph.session.refreshUser() }

    ScreenScaffold(title = "Me") {
        Feed {
            item {
                Section(null) {
                    Row(
                        Modifier.fillMaxWidth().padding(Space.s4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        val current = user
                        Avatar(current?.let { configuration.avatarUrl(it.id) }, current?.name ?: "", size = 64.dp)
                        Column(Modifier.weight(1f)) {
                            Text(current?.name ?: "Signed in", style = HotMessType.heading, color = tokens.ink)
                            locale?.name?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
                        }
                    }
                }
            }
            item {
                Section("Location") {
                    InfoRow(
                        "Location access",
                        when (access) {
                            LocationAccess.ALLOWED -> "On"
                            LocationAccess.DENIED -> "Off"
                            LocationAccess.NOT_REQUESTED -> "Not asked yet"
                        },
                        icon = Icons.Rounded.LocationOn,
                    )
                    if (access == LocationAccess.DENIED) {
                        RowDivider()
                        InfoRow("Open settings", onClick = context::openAppSettings, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                    }
                }
            }
            item {
                Section("Feedback") {
                    InfoRow(
                        "Send feedback",
                        icon = Icons.Rounded.Email,
                        onClick = { context.openUrl("mailto:feedback@hotmess.social?subject=Hot%20Mess%20for%20Android%20${configuration.versionName}") },
                    )
                }
            }
            item {
                Section("About") {
                    InfoRow("Version", "${configuration.versionName} (${configuration.versionCode})")
                    RowDivider()
                    InfoRow("Facebook", configuration.facebookEnvironment)
                    RowDivider()
                    InfoRow("Server", configuration.baseUrl.removePrefix("https://").removePrefix("http://"))
                }
            }
            item {
                Section(null) {
                    DangerRow("Reset all data") { confirm = Confirm.RESET }
                    RowDivider()
                    DangerRow("Sign out") { confirm = Confirm.SIGN_OUT }
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (action == Confirm.RESET) "Reset all data?" else "Sign out?") },
            text = {
                Text(
                    if (action == Confirm.RESET) {
                        "This clears saved places and cached photos on this phone and signs you out."
                    } else {
                        "You'll need to sign in with Facebook again to use Hot Mess."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (action == Confirm.RESET) graph.session.resetLocalData()
                    graph.session.signOut()
                }) {
                    Text(if (action == Confirm.RESET) "Reset" else "Sign out", color = tokens.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
