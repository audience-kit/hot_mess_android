package social.hotmess.android.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import social.hotmess.android.BuildConfig
import social.hotmess.core.HotMessApi

/**
 * Registers the device for push with Firebase Cloud Messaging and hands the token to the API
 * (`registerDevice`) with the app's package name, as the iOS app does with its APNs token and bundle ID.
 *
 * Push needs a Firebase app; without its settings in local.properties (see app/build.gradle.kts)
 * this does nothing and the rest of the app works as usual.
 */
class PushRegistrar(private val context: Context, private val api: HotMessApi, private val scope: CoroutineScope) {
    private val isConfigured: Boolean =
        listOf(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APPLICATION_ID, BuildConfig.FIREBASE_API_KEY)
            .all { it.isNotBlank() }

    private fun firebase(): FirebaseApp? {
        if (!isConfigured) return null
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        return runCatching { FirebaseApp.initializeApp(context, options) }.getOrNull()
    }

    /** Fetches the current token and registers it. Best-effort: a failure never blocks the UI. */
    fun register() {
        firebase() ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> send(token) }
    }

    /** A new token from [HotMessMessagingService]. */
    fun send(token: String) {
        scope.launch {
            try {
                api.registerForPush(token, appId = context.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HotMess", "Push registration failed: ${e.message}")
            }
        }
    }
}
