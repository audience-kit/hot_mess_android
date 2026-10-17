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
import social.hotmess.core.PingPush
import social.hotmess.core.RecentChatPush

/**
 * Firebase hands new tokens here. In the background Firebase shows notifications itself, and tapping
 * one opens [MainActivity] with the message's data (`kind`, `ping_id`, or a recent chat's `url`) as
 * extras. In the foreground Firebase shows nothing, so a Ping push is shown here, with the same extras,
 * and Now reloads; likewise a push about a chat room the user just left, which opens the room.
 */
class HotMessMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val graph = applicationContext.appGraph
        if (graph.session.isSignedIn) graph.push.send(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        RecentChatPush.parse(message.data)?.let { push ->
            val notification = message.notification ?: return
            val id = (push.messageId ?: push.url).hashCode()
            show(applicationContext, notification.title, notification.body, message.data, CHAT_CHANNEL, "Chat rooms", id)
            return
        }
        val push = PingPush.parse(message.data) ?: return
        applicationContext.appGraph.pingUpdated(push.pingId)
        val notification = message.notification ?: return
        show(applicationContext, notification.title, notification.body, message.data, PING_CHANNEL, "Pings", push.pingId?.hashCode() ?: 0)
    }

    companion object {
        const val PING_CHANNEL = "pings"
        const val CHAT_CHANNEL = "chat"

        private fun show(
            context: Context,
            title: String?,
            body: String?,
            data: Map<String, String>,
            channel: String,
            channelName: String,
            id: Int,
        ) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            if (!granted) return

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))

            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data.forEach { (key, value) -> open.putExtra(key, value) }
            val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val notification = NotificationCompat.Builder(context, channel)
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
