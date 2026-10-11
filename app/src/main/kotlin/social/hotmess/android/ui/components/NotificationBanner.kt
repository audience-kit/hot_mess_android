package social.hotmess.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import social.hotmess.android.push.InAppNotifications
import social.hotmess.android.ui.theme.ContentMaxWidth
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.PushNotification

/** How long a notification stays on screen untouched. */
private const val SHOWN_MS = 5_000L

/**
 * A notification that came over the cable while the app is open, as a raised card dropping in at the
 * top: in place of the system banner FCM's copy would have shown. A tap dismisses it and [onOpen]s it.
 */
@Composable
fun NotificationBanner(notifications: InAppNotifications, onOpen: (PushNotification) -> Unit) {
    val current by notifications.current.collectAsStateWithLifecycle()
    // The last one shown, so it can slide away after it's dismissed.
    var shown by remember { mutableStateOf<PushNotification?>(null) }
    if (current != null) shown = current

    LaunchedEffect(current) {
        val notification = current ?: return@LaunchedEffect
        delay(SHOWN_MS)
        notifications.dismiss(notification)
    }

    Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val notification = shown ?: return@AnimatedVisibility
            Surface(
                modifier = Modifier
                    .padding(horizontal = Space.s4, vertical = Space.s2)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                shape = Radius.lg,
                color = tokens.surfaceRaised,
                contentColor = tokens.ink,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            notifications.dismiss(notification)
                            onOpen(notification)
                        }
                        .padding(Space.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    Icon(Icons.Rounded.Notifications, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                        notification.title?.let {
                            Text(it, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        notification.body?.let {
                            Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
