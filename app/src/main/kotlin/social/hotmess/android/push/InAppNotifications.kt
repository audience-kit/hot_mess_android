package social.hotmess.android.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.core.CableConnection
import social.hotmess.core.NotificationStreamConnection
import social.hotmess.core.PushNotification
import social.hotmess.core.RecentNotifications

/**
 * Your notifications over the cable (`Subscription.myNotifications`) while the app is in front and
 * signed in, shown in the app instead of as system notifications. FCM still delivers its copy;
 * [HotMessMessagingService] asks [shouldPostSystemCopy] and drops the one already shown here.
 *
 * [HotMessApplication][social.hotmess.android.HotMessApplication] calls [setForeground] from the
 * process lifecycle and [start]/[stop] as the app comes and goes or the session signs in and out.
 * It's one subscription on the app's shared [CableConnection], which reconnects after a drop and runs
 * it again.
 */
class InAppNotifications(
    private val scope: CoroutineScope,
    private val cable: CableConnection,
    private val audienceId: suspend () -> String?,
    /** Called on the main thread for each notification shown here (a Ping reloads Now). */
    private val onShown: (PushNotification) -> Unit,
) {
    private val recent = RecentNotifications()
    private var job: Job? = null

    @Volatile private var foreground = false
    @Volatile private var live = false

    private val _current = MutableStateFlow<PushNotification?>(null)

    /** The notification on screen, if any. */
    val current: StateFlow<PushNotification?> = _current.asStateFlow()

    fun setForeground(value: Boolean) {
        foreground = value
    }

    /** Subscribes, unless it already is. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // The audience comes over HTTP; keep asking until it does.
            var backoff = 1_000L
            var audience: String? = null
            while (audience == null) {
                audience = try {
                    audienceId()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (audience == null) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
            backoff = 1_000L
            while (true) {
                var ended = false
                try {
                    // The cable reconnects and resubscribes on its own after a drop.
                    NotificationStreamConnection(audience, cable).events().collect { event ->
                        when (event) {
                            NotificationStreamConnection.Event.Connected -> {
                                live = true
                                backoff = 1_000L
                            }
                            is NotificationStreamConnection.Event.Received -> receive(event.notification)
                            is NotificationStreamConnection.Event.Disconnected -> live = false
                            NotificationStreamConnection.Event.Rejected -> live = false
                            NotificationStreamConnection.Event.Ended -> ended = true
                        }
                    }
                } finally {
                    live = false
                }
                // Refused (signed out, or a server without the subscription): FCM alone until the app comes
                // back. Only an operation the server ended is subscribed again, after a while.
                if (!ended) return@launch
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Unsubscribes; FCM's copies are system notifications again. */
    fun stop() {
        job?.cancel()
        job = null
        live = false
    }

    /** Signed out: unsubscribed, and nothing left on screen. */
    fun clear() {
        stop()
        _current.value = null
    }

    fun dismiss(notification: PushNotification) {
        _current.compareAndSet(notification, null)
    }

    /** Whether FCM's copy should still be a system notification. Blocks briefly: FCM's thread only. */
    fun shouldPostSystemCopy(notificationId: String?): Boolean =
        recent.shouldPostSystemCopy(notificationId, foreground = foreground, streamLive = live)

    private fun receive(notification: PushNotification) {
        // In the background FCM's copy is the one to show; and it's shown once either way.
        if (!foreground || !recent.claim(notification.notificationId)) return
        onShown(notification)
        if (notification.title != null || notification.body != null) _current.value = notification
    }
}
