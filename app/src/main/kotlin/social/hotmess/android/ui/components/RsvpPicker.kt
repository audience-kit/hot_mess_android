package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Rsvp

/**
 * The design system's RSVP picker: Going, Interested and Not going side by side, each an icon over
 * its word. The chosen one sits on `accent-soft` with `accent-ink` text in the label weight; the
 * others are `ink-muted`. The same component is `RSVPPicker` on iOS.
 */
@Composable
fun RsvpPicker(selection: Rsvp, onSelect: (Rsvp) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().padding(Space.s2).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Rsvp.selectable.forEach { rsvp ->
            val selected = rsvp == selection
            val color = if (selected) tokens.accentInk else tokens.inkMuted
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(Radius.md)
                    .background(if (selected) tokens.accentSoft else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) {
                        if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(rsvp)
                    }
                    .padding(vertical = Space.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s1, Alignment.CenterVertically),
            ) {
                Icon(rsvp.icon, contentDescription = null, tint = color)
                Text(
                    rsvp.title,
                    style = HotMessType.bodySmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Your RSVP as a pill over an event's photo, shown only for Going and Interested. */
@Composable
fun RsvpBadge(rsvp: Rsvp, modifier: Modifier = Modifier) {
    if (rsvp != Rsvp.ATTENDING && rsvp != Rsvp.MAYBE) return
    GlassPill(modifier) {
        Icon(rsvp.icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(rsvp.title, maxLines = 1)
    }
}

val Rsvp.title: String
    get() = when (this) {
        Rsvp.ATTENDING -> "Going"
        Rsvp.MAYBE -> "Interested"
        Rsvp.DECLINED -> "Not going"
        Rsvp.UNSURE -> "Undecided"
    }

val Rsvp.icon: ImageVector
    get() = when (this) {
        Rsvp.ATTENDING -> Icons.Rounded.CheckCircle
        Rsvp.MAYBE -> Icons.Rounded.Star
        Rsvp.DECLINED -> Icons.Rounded.Cancel
        Rsvp.UNSURE -> Icons.AutoMirrored.Rounded.HelpOutline
    }
