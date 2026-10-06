package social.hotmess.android.session

import android.content.Context
import android.util.Log
import com.audiencekit.AudienceKitClient
import com.audiencekit.DeviceDescription
import com.audiencekit.SessionEvent
import com.audiencekit.android.current
import com.facebook.AccessToken
import com.facebook.login.LoginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.AppConfiguration
import social.hotmess.android.push.PushRegistrar
import social.hotmess.core.ApiError
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
 * Facebook Login runs in the activity (it needs an activity result); its token comes here, and the
 * SDK exchanges it for an AudienceKit session kept in the Android Keystore. When the API ends the
 * session, the app goes back to sign-in.
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

    /** Whether this build can sign in: Facebook needs a client token (see app/build.gradle.kts). */
    val isFacebookConfigured: Boolean
        get() = context.getString(social.hotmess.android.R.string.facebook_client_token).isNotBlank()

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
        // A stored session is enough on its own; only fall back to Facebook when there isn't one.
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

        val facebookToken = AccessToken.getCurrentAccessToken()?.takeUnless { it.isExpired }?.token
        if (facebookToken == null) {
            _state.value = AuthState.SignedOut
            return
        }
        exchange(facebookToken)
    }

    fun beginSignIn() {
        _state.value = AuthState.SigningIn
    }

    fun signInCancelled() {
        _state.value = AuthState.SignedOut
    }

    fun signInFailed(message: String) {
        _state.value = AuthState.Failed(message)
    }

    /** Exchanges the token Facebook Login returned for an AudienceKit session. */
    fun completeSignIn(facebookToken: String) {
        scope.launch { exchange(facebookToken) }
    }

    private suspend fun exchange(facebookToken: String) {
        _state.value = AuthState.SigningIn
        try {
            val result = audienceKit.signIn(facebookToken, DeviceDescription.current(context))
            val id = social.hotmess.core.RecordId.normalize(result.user.id)
            _user.value = id?.let { User(it, result.user.name.orEmpty()) }
            if (_user.value?.name.isNullOrBlank()) _user.value = runCatching { api.me() }.getOrNull() ?: _user.value
            signedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HotMess", "Sign-in failed: ${e.message}")
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
        LoginManager.getInstance().logOut()
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
    }
}
