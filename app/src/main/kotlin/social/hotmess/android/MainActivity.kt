package social.hotmess.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import social.hotmess.android.ui.HotMessApp
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.core.AppRoute
import social.hotmess.core.DeepLink

class MainActivity : ComponentActivity() {
    private val graph by lazy { appGraph }
    private val callbackManager = CallbackManager.Factory.create()
    private val links = Channel<AppRoute>(Channel.CONFLATED)
    private val linkFlow = links.receiveAsFlow()

    private val facebookLogin = registerForActivityResult(
        LoginManager.getInstance().createLogInActivityResultContract(callbackManager),
    ) { /* Delivered to the callback registered in onCreate. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) = graph.session.completeSignIn(result.accessToken.token)
            override fun onCancel() = graph.session.signInCancelled()
            override fun onError(error: FacebookException) =
                graph.session.signInFailed("Facebook couldn't sign you in. Try again in a moment.")
        })

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

    override fun onDestroy() {
        LoginManager.getInstance().unregisterCallback(callbackManager)
        super.onDestroy()
    }

    private fun signIn() {
        if (!graph.session.isFacebookConfigured) {
            graph.session.signInFailed("Facebook sign-in isn't set up for this build yet.")
            return
        }
        graph.session.beginSignIn()
        facebookLogin.launch(listOf("public_profile", "email", "user_friends"))
    }

    private fun handle(intent: Intent?) {
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        DeepLink.route(url)?.let { links.trySend(it) }
    }
}
