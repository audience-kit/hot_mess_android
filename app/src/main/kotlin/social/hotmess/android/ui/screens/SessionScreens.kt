package social.hotmess.android.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.R
import social.hotmess.android.session.AuthState
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.SpectrumBar
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.VersionInfo

/** While the session is restored. */
@Composable
fun LaunchScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.login_background), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        CircularProgressIndicator(color = Color.White)
    }
}

/** Sign-in, skinned with the audience's branding, always dark over the photo. */
@Composable
fun LoginScreen(onSignIn: () -> Unit) {
    val graph = LocalAppGraph.current
    val state by graph.session.state.collectAsStateWithLifecycle()
    val branding by graph.brand.branding.collectAsStateWithLifecycle()
    val signingIn = state == AuthState.SigningIn

    HotMessTheme(branding = branding?.theme, darkTheme = true) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // The app icon's figure, already dark enough for white text (hot_mess_ios Design/LoginArt).
            Image(
                painterResource(R.drawable.login_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            SpectrumBar(Modifier.statusBarsPadding())
            Text(
                branding?.audience?.name ?: "Hot Mess",
                style = HotMessType.display,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
            )

            Column(
                Modifier.fillMaxSize().navigationBarsPadding().padding(Space.s8),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Text(
                        branding?.theme?.tagline ?: "Queer nights out, all in one place.",
                        style = HotMessType.heading,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.padding(Space.s4))
                    if (signingIn) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        PrimaryButton("Continue with Facebook", onClick = onSignIn, modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "We use your Facebook profile to find your friends and the nights near you.",
                        style = HotMessType.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            val failure = state as? AuthState.Failed
            if (failure != null) {
                AlertDialog(
                    onDismissRequest = { graph.session.signInCancelled() },
                    confirmButton = { TextButton(onClick = { graph.session.signInCancelled() }) { Text("OK") } },
                    title = { Text("We couldn't sign you in", style = HotMessType.heading) },
                    text = { Text(failure.message, style = HotMessType.body) },
                    containerColor = tokens.surfaceRaised,
                )
            }
        }
    }
}

/** When the API no longer serves this build. */
@Composable
fun UpdateRequiredScreen(versionInfo: VersionInfo?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Message(
        icon = Icons.Rounded.SystemUpdate,
        title = "Time to update",
        text = versionInfo?.minimumVersion?.let { "Update Hot Mess to version $it or later to keep going." }
            ?: "Update Hot Mess to keep going.",
        action = "Open Google Play" to { context.openUrl("market://details?id=${context.packageName}") },
    )
}
