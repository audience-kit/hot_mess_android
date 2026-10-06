package social.hotmess.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import social.hotmess.android.ui.HotMessApp
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.core.AppRoute
import social.hotmess.core.DeepLink

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
                    HotMessApp(links = linkFlow, onSignIn = ::signIn)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // A redirect arrives through onNewIntent first, so still waiting here means the tab was closed.
        graph.session.signInAbandoned()
    }

    private fun signIn() {
        lifecycleScope.launch {
            val url = graph.session.beginSignIn() ?: return@launch
            CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, url.toUri())
        }
    }

    private fun handle(intent: Intent?) {
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (graph.session.handleRedirect(url)) return
        DeepLink.route(url)?.let { links.trySend(it) }
    }
}
