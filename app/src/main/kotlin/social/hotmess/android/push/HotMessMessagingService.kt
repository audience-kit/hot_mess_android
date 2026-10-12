package social.hotmess.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import social.hotmess.android.MainActivity
import social.hotmess.android.R
import social.hotmess.android.appGraph
import social.hotmess.core.AppRoute
import social.hotmess.core.DeepLink
import social.hotmess.core.PingPush
import social.hotmess.core.PushNotification

/**
 * Firebase hands new tokens here. In the background Firebase shows notifications itself, and tapping
 * one opens [MainActivity] with the message's data (`url`, `ping_id`; `kind` for analytics) as extras. In the
 * foreground Firebase shows nothing, so a push whose `url` the app routes is shown here, with the same extras,
 * and a Ping's reloads Now, unless the same notification (by `notification_id`) came over the cable and
 * [InAppNotifications] showed it.
 */
class HotMessMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val graph = applicationContext.appGraph
        if (graph.session.isSignedIn) graph.push.send(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val route = message.data[PingPush.URL]?.let(DeepLink::route)
            ?: PingPush.parse(message.data)?.let { AppRoute.NowPing(it.pingId) }
            ?: return
        val graph = applicationContext.appGraph
        // The in-app copy was shown (and reloaded Now) already, or gets a moment to be: drop this one.
        if (!graph.notifications.shouldPostSystemCopy(message.data[PushNotification.NOTIFICATION_ID])) return
        if (route is AppRoute.NowPing) graph.pingUpdated(route.pingId)
        val notification = message.notification ?: return
        show(applicationContext, notification.title, notification.body, message.data)
    }

    companion object {
        const val PING_CHANNEL = "pings"

        private fun show(context: Context, title: String?, body: String?, data: Map<String, String>) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            if (!granted) return

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(PING_CHANNEL, "Pings", NotificationManager.IMPORTANCE_HIGH))

            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data.forEach { (key, value) -> open.putExtra(key, value) }
            val id = (data[PingPush.URL] ?: data[PingPush.PING_ID])?.hashCode() ?: 0
            val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(context, PING_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (_: SecurityException) {
            }
        }
    }
}
