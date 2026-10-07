package social.hotmess.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import social.hotmess.android.R
import social.hotmess.android.ui.theme.ContentMaxWidth
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Opacity
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Event
import social.hotmess.core.Formatting
import social.hotmess.core.LoadState
import social.hotmess.core.Rsvp
import social.hotmess.core.SocialLink
import social.hotmess.core.Track
import social.hotmess.core.initialsForDisplay
import kotlin.math.absoluteValue

// The shared pieces every screen is built from: the `surface` wash with white `surface-raised`
// cards on it, rows inside the cards split by hairline `border` dividers, and the list rows.

/**
 * A feed on the wash: one column up to 680dp with `space-4` gutters, sections `space-4` apart.
 * [top] is the space above the first item.
 */
@Composable
fun Feed(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    top: Dp = Space.s4,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(tokens.surface), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = top, bottom = Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
            content = content,
        )
    }
}

/** A [Feed] that opens on a full-bleed [hero] (a HeroHeader) running up to the top edge. */
@Composable
fun HeroFeed(
    state: LazyListState,
    hero: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Feed(modifier, state = state, top = 0.dp) {
        item(key = "hero") { Box(Modifier.fullBleed(Space.s4)) { hero() } }
        content()
    }
}

/** Lays this out [gutter] wider on each side, out over the feed's side padding. */
private fun Modifier.fullBleed(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = (gutter * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** A `surface-raised` card with `radius-lg` and `shadow-sm`. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Radius.lg,
        color = tokens.surfaceRaised,
        contentColor = tokens.ink,
        shadowElevation = 1.dp,
    ) {
        Column(content = content)
    }
}

/** A hairline between rows in a DetailSection, inset 20 to line up with the rows' text. */
@Composable
fun RowDivider(inset: Dp = DetailInset) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = tokens.border)
}

/** Rows in a DetailSection, with dividers between them. */
@Composable
fun <T> CardRows(items: List<T>, divider: Dp = DetailInset, row: @Composable (T) -> Unit) {
    items.forEachIndexed { index, item ->
        if (index > 0) RowDivider(divider)
        row(item)
    }
}

/** A row that's a button: hover and press are an overlay on whatever's under it. */
@Composable
fun RowButton(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(DetailRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        content = content,
    )
}

/** Muted text in a DetailSection, for an empty section or a note. */
@Composable
fun EmptyRow(text: String) {
    Text(
        text,
        style = HotMessType.body,
        color = tokens.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(DetailRowPadding),
    )
}

/** Plain text as a DetailSection row, such as a description. */
@Composable
fun TextRow(text: String) {
    Text(text, style = HotMessType.body, color = tokens.ink, modifier = Modifier.fillMaxWidth().padding(DetailRowPadding))
}

/** A label on the left and a muted value on the right, optionally with an icon and an action. */
@Composable
fun InfoRow(title: String, value: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: ImageVector? = null) {
    RowButton(onClick) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(20.dp))
        Text(
            title,
            style = HotMessType.body,
            color = if (onClick != null && value == null) tokens.accentInk else tokens.ink,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(value, style = HotMessType.body, color = tokens.inkMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
        }
        if (trailing != null) Icon(trailing, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(16.dp))
    }
}

/** A remote photo, filling its box, with a quiet placeholder while it loads or when there's none. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(tokens.controlFill), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = tokens.inkMuted.copy(alpha = Opacity.placeholderGlyph))
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * A round avatar. Without a photo it shows initials on a spectrum colour, chosen by name. With a
 * [presence], a [PresenceDot] sits at its bottom-right (10 on avatars under 40, 12 from 40 up), ringed
 * in [presenceRing] (`surface-raised` when unspecified; pass the surface the avatar sits on).
 */
@Composable
fun Avatar(
    url: String?,
    name: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    presence: Presence? = null,
    presenceRing: Color = Color.Unspecified,
) {
    val band = (name.hashCode().absoluteValue) % tokens.spectrum.size
    Box(modifier.size(size)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(tokens.spectrum[band])
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.initialsForDisplay(),
                style = HotMessType.subheading.copy(fontSize = HotMessType.subheading.fontSize * (size.value / 44f)),
                color = tokens.onSpectrum[band],
            )
            if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (presence != null && presence != Presence.OFFLINE) {
            PresenceDot(
                presence,
                size = if (size >= 40.dp) 12.dp else 10.dp,
                ring = presenceRing,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp),
            )
        }
    }
}

@Composable
fun TrackRow(track: Track, onClick: (() -> Unit)?) {
    RowButton(onClick) {
        RemoteImage(track.artworkUrl, Modifier.size(64.dp).clip(Radius.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(track.title, style = HotMessType.subheading, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            track.waveformUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    alpha = 0.7f,
                )
            }
        }
    }
}

@Composable
fun SocialLinkRow(link: SocialLink, onClick: (() -> Unit)?) {
    RowButton(onClick) {
        val glyph = when (link.network) {
            SocialLink.Network.FACEBOOK -> R.drawable.social_facebook
            SocialLink.Network.INSTAGRAM -> R.drawable.social_instagram
            SocialLink.Network.SOUNDCLOUD -> R.drawable.social_soundcloud
            SocialLink.Network.X -> R.drawable.social_x
            SocialLink.Network.SPOTIFY, SocialLink.Network.APPLE_MUSIC, null -> null
        }
        val music = link.network == SocialLink.Network.SPOTIFY || link.network == SocialLink.Network.APPLE_MUSIC
        if (glyph != null) {
            Image(painterResource(glyph), contentDescription = link.provider, modifier = Modifier.size(24.dp).clip(Radius.sm))
        } else {
            Icon(
                if (music) Icons.Rounded.MusicNote else Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = link.provider,
                tint = tokens.inkMuted,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(link.label, style = HotMessType.body, color = tokens.ink, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(16.dp))
    }
}

/** A 6-stripe band in the audience's spectrum. Decorative only. */
@Composable
fun SpectrumBar(modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Row(modifier.fillMaxWidth().height(height)) {
        tokens.spectrum.forEach { Box(Modifier.weight(1f).fillMaxSize().background(it)) }
    }
}

/** The primary action: an `accent` fill, `radius-md`, 44dp for touch. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Radius.md,
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.accent,
            contentColor = tokens.onAccent,
            disabledContainerColor = tokens.controlFill,
            disabledContentColor = tokens.inkMuted,
        ),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s2))
        }
        Text(text, style = HotMessType.label)
    }
}

/** A secondary action: the grey `control-fill` button. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = Radius.md,
        colors = ButtonDefaults.buttonColors(containerColor = tokens.controlFill, contentColor = tokens.ink),
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        Text(text, style = HotMessType.label)
    }
}

/** A destructive row action in `danger`, always with a word. */
@Composable
fun DangerRow(text: String, onClick: () -> Unit) {
    RowButton(onClick) {
        Text(text, style = HotMessType.label, color = tokens.danger)
    }
}

/** The RSVP choices, side by side; the chosen one sits on `accent-soft`. */
@Composable
fun RsvpPicker(selection: Rsvp, onSelect: (Rsvp) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(Space.s2), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        Rsvp.selectable.forEach { rsvp ->
            val selected = rsvp == selection
            Column(
                Modifier
                    .weight(1f)
                    .clip(Radius.md)
                    .background(if (selected) tokens.accentSoft else Color.Transparent)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(rsvp) })
                    .padding(vertical = Space.s2),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s1),
            ) {
                Icon(rsvp.icon, contentDescription = null, tint = if (selected) tokens.accentInk else tokens.inkMuted)
                Text(rsvp.title, style = HotMessType.bodySmall, color = if (selected) tokens.accentInk else tokens.inkMuted)
            }
        }
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

/** Shows a spinner, the content, or what went wrong with a "Try again" button. */
@Composable
fun <T> LoadStateView(state: LoadState<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize().background(tokens.surface), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = tokens.accent)
        }
        is LoadState.Loaded -> content(state.value)
        is LoadState.Failed -> Message(
            icon = Icons.Rounded.ErrorOutline,
            title = "Something went wrong",
            text = state.message,
            action = if (state.isRetryable) "Try again" to onRetry else null,
        )
    }
}

/** A centred message for an empty or failed screen. */
@Composable
fun Message(icon: ImageVector, title: String, text: String, action: Pair<String, () -> Unit>? = null) {
    Box(Modifier.fillMaxSize().background(tokens.surface).padding(Space.s8), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Icon(icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(40.dp))
            Text(title, style = HotMessType.heading, color = tokens.ink, textAlign = TextAlign.Center)
            Text(text, style = HotMessType.body, color = tokens.inkMuted, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(Space.s1))
                PrimaryButton(action.first, action.second)
            }
        }
    }
}

/** The event date tile: the month as a caption over the day. */
@Composable
fun DateBadge(event: Event) {
    Column(
        Modifier
            .width(48.dp)
            .clip(Radius.md)
            .background(tokens.accentSoft)
            .padding(vertical = Space.s1)
            .semantics { contentDescription = Formatting.dateTime(event.startAt) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(Formatting.monthAbbreviation(event.startAt), style = HotMessType.caption, color = tokens.accentInk)
        Text(Formatting.dayOfMonth(event.startAt), style = HotMessType.title, color = tokens.ink)
    }
}
