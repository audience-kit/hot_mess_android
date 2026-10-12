package social.hotmess.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import social.hotmess.android.ui.HotMessApp
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.NotificationBanner
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.core.AppRoute
import social.hotmess.core.DeepLink
import social.hotmess.core.PingPush

class MainActivity : ComponentActivity() {
    private val graph by lazy { appGraph }
    private val links = Channel<AppRoute>(Channel.CONFLATED)
    private val linkFlow = links.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) handle(intent)

        setContent {
            val branding by graph.brand.branding.collectAsStateWithLifecycle()
            HotMessTheme(branding = branding?.theme) {
                CompositionLocalProvider(LocalAppGraph provides graph) {
                    Box(Modifier.fillMaxSize()) {
                        HotMessApp(links = linkFlow, onSignIn = ::signIn)
                        // A notification that came over the cable while the app is open; a tap opens it as a push's would.
                        NotificationBanner(graph.notifications) { notification -> openPush(notification.data) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // Square's card entry still starts with startActivityForResult, so its result arrives here.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        graph.squareCardEntry.onActivityResult(requestCode, data)
    }

    override fun onResume() {
        super.onResume()
        // A redirect arrives through onNewIntent first, so still waiting here means the tab was closed.
        graph.session.signInAbandoned()
    }

    private fun signIn() {
        val url = graph.session.beginSignIn() ?: return
        CustomTabsIntent.Builder()
            .setEphemeralBrowsingEnabled(graph.configuration.privateSignIn)
            .build()
            .launchUrl(this, url.toUri())
    }

    private fun handle(intent: Intent?) {
        // A tapped push: Firebase's own notification or ours both carry its data (`url` and friends) as extras.
        val extras = intent?.extras
        if (intent != null && extras != null) {
            if (openPush(PingPush.KEYS.associateWith { extras.getString(it) })) {
                PingPush.KEYS.forEach { intent.removeExtra(it) }
                return
            }
        }
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (graph.session.handleRedirect(url)) return
        DeepLink.route(url)?.let { links.trySend(it) }
    }

    /**
     * Opens a push by its data: the `url` it carries is the route (a Ping's goes to Now, which reloads). `kind`
     * is analytics only and never decides anything. False when the data has no route the app knows.
     */
    private fun openPush(data: Map<String, String?>): Boolean {
        val route = data[PingPush.URL]?.let(DeepLink::route)
            // A push from before `url` was sent still names its Ping.
            ?: PingPush.parse(data)?.let { AppRoute.NowPing(it.pingId) }
            ?: return false
        if (route is AppRoute.NowPing) graph.pingUpdated(route.pingId)
        links.trySend(route)
        return true
    }
}
