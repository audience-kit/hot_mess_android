package social.hotmess.android.session

import android.content.Context
import android.util.Log
import com.audiencekit.AudienceKitClient
import com.audiencekit.DeviceDescription
import com.audiencekit.SessionEvent
import com.audiencekit.android.current
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.AppConfiguration
import social.hotmess.android.push.PushRegistrar
import social.hotmess.core.ApiError
import social.hotmess.core.FacebookLoginDialog
import social.hotmess.core.HotMessApi
import social.hotmess.core.User
import social.hotmess.core.VersionInfo

sealed interface AuthState {
    /** Still working out whether there's a usable session. */
    data object Restoring : AuthState
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data object SignedIn : AuthState
    data class Failed(val message: String) : AuthState
}

/**
 * Signs people in with Facebook through AudienceKit and keeps track of the session.
 *
 * Sign-in opens Facebook's Login for Business dialog in a Custom Tab (see [FacebookLoginDialog]);
 * the activity hands the redirect back here, and the SDK exchanges its code for an AudienceKit
 * session kept in the Android Keystore. When the API ends the session, the app goes back to sign-in.
 */
class SessionStore(
    private val context: Context,
    private val api: HotMessApi,
    private val audienceKit: AudienceKitClient,
    private val configuration: AppConfiguration,
    private val push: PushRegistrar,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Restoring)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    /** Set when the API no longer serves this build. */
    private val _requiredVersion = MutableStateFlow<VersionInfo?>(null)
    val requiredVersion: StateFlow<VersionInfo?> = _requiredVersion.asStateFlow()

    val isSignedIn: Boolean get() = _state.value == AuthState.SignedIn

    /** The session token, needed outside the SDK only for the chat websocket. */
    val sessionToken: String? get() = audienceKit.sessionToken()

    /** The dialog the Custom Tab is showing, and the state its redirect must carry. */
    private var pendingLogin: Pair<FacebookLoginDialog, String>? = null

    init {
        scope.launch {
            audienceKit.sessionEvents.collect { event ->
                if (event == SessionEvent.SESSION_ENDED && _state.value != AuthState.SignedOut) {
                    _user.value = null
                    _state.value = AuthState.SignedOut
                }
            }
        }
    }

    /** At launch: checks the minimum supported build, then restores the session. */
    suspend fun start() {
        val device = DeviceDescription.current(context)
        val minimum = runCatching { api.minimumVersion(device) }.getOrNull()
        if (minimum != null && configuration.versionCode < minimum.minimumBuild) {
            _requiredVersion.value = minimum
            _state.value = AuthState.SignedOut
            return
        }
        restore()
    }

    private suspend fun restore() {
        if (audienceKit.isSignedIn) {
            try {
                _user.value = api.me()
                signedIn()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Unauthorized) {
                // The SDK has already dropped the token.
            } catch (e: Exception) {
                // A network blip shouldn't sign anyone out; the screens show their own errors.
                signedIn()
                return
            }
        }

        _state.value = AuthState.SignedOut
    }

    /**
     * Starts sign-in and returns the Facebook dialog to open, or null when the build has no Facebook
     * app. Hot Mess is a consumer app, so this is classic Facebook Login on the build's own app.
     */
    fun beginSignIn(): String? {
        _state.value = AuthState.SigningIn
        val appId = configuration.facebookAppId
        if (appId.isBlank()) {
            signInFailed("Facebook sign-in isn't set up for this build yet.")
            return null
        }
        val dialog = FacebookLoginDialog(appId, PERMISSIONS, context.packageName, configuration.facebookLoginConfigId)
        val state = java.util.UUID.randomUUID().toString()
        pendingLogin = dialog to state
        return dialog.url(state)
    }

    /** Takes the dialog's redirect, if [url] is one. */
    fun handleRedirect(url: String): Boolean {
        val (dialog, state) = pendingLogin?.takeIf { it.first.isRedirect(url) } ?: return false
        pendingLogin = null
        when (val result = dialog.result(url, state)) {
            is FacebookLoginDialog.Result.Code -> scope.launch { exchange(result.code, dialog) }
            FacebookLoginDialog.Result.Cancelled -> signInCancelled()
            is FacebookLoginDialog.Result.Failed -> {
                Log.e("HotMess", "Facebook sign-in failed: ${result.reason}")
                signInFailed(result.reason)
            }
        }
        return true
    }

    /** The app came back without a redirect: the Custom Tab was closed. */
    fun signInAbandoned() {
        if (pendingLogin == null || _state.value != AuthState.SigningIn) return
        pendingLogin = null
        signInCancelled()
    }

    fun signInCancelled() {
        _state.value = AuthState.SignedOut
    }

    fun signInFailed(message: String) {
        _state.value = AuthState.Failed(message)
    }

    /** Exchanges the dialog's code for an AudienceKit session, with the app the dialog was for. */
    private suspend fun exchange(code: String, dialog: FacebookLoginDialog) {
        _state.value = AuthState.SigningIn
        try {
            val result = audienceKit.signIn(
                facebookCode = code,
                redirectUri = dialog.redirectUri,
                device = DeviceDescription.current(context),
                facebookAppId = dialog.appId,
            )
            val id = social.hotmess.core.RecordId.normalize(result.user.id)
            _user.value = id?.let { User(it, result.user.name.orEmpty()) }
            if (_user.value?.name.isNullOrBlank()) _user.value = runCatching { api.me() }.getOrNull() ?: _user.value
            signedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("HotMess", "Sign-in failed: ${e.message}")
            _state.value = AuthState.Failed(
                when (e) {
                    is com.audiencekit.AudienceKitException.SignInRejected ->
                        "Facebook said no to this sign-in. Try again, or check that you're using the right Facebook account."
                    is com.audiencekit.AudienceKitException.Network -> "You appear to be offline. Check your connection and try again."
                    else -> "We couldn't sign you in. Try again in a moment."
                },
            )
        }
    }

    private fun signedIn() {
        _state.value = AuthState.SignedIn
        push.register()
    }

    fun signOut() {
        audienceKit.signOut()
        _user.value = null
        _state.value = AuthState.SignedOut
    }

    suspend fun refreshUser() {
        if (!isSignedIn) return
        runCatching { api.me() }.getOrNull()?.let { _user.value = it }
    }

    /** Clears cached preferences and images, leaving the session alone. */
    fun resetLocalData() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** The app's own preferences (the remembered locale and the like). */
        const val PREFERENCES = "social.hotmess.preferences"

        /**
         * Whether sign-in asks for email and user_friends (friends who also use the app at the same venue). Off while
         * Meta's App Review has both pending on the Hot Mess Consumer app, so the betas ask only for public_profile;
         * until it's back on, everyone's friends list is empty and no email is stored. Turn it back on once Meta
         * approves them.
         */
        const val ASKS_FOR_REVIEWED_PERMISSIONS = false

        /** What sign-in asks Facebook for. */
        private val PERMISSIONS =
            listOf("public_profile") +
                if (ASKS_FOR_REVIEWED_PERMISSIONS) listOf("email", "user_friends") else emptyList()
    }
}
