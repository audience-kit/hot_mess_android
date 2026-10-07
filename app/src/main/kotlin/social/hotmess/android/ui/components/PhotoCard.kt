package social.hotmess.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Opacity
import social.hotmess.android.ui.theme.PhotoColors
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Event
import social.hotmess.core.Formatting
import social.hotmess.core.Friend
import social.hotmess.core.Person
import social.hotmess.core.Rsvp
import social.hotmess.core.Venue
import kotlin.math.max

// PhotoCard and the cards built on it (design system PhotoCard): venues, events and people as photos
// with their text written over them, its colour and scrim chosen per card by the brightness check.

/** Tone and scrim changes fade over 150ms, ease-out; the photo itself never fades in. */
internal fun <T> toneAnimation() = tween<T>(durationMillis = 150, easing = EaseOut)

private val CardTitle = HotMessType.heading.copy(fontSize = 20.sp, lineHeight = 24.sp)
private val FeaturedCardTitle = HotMessType.heading.copy(fontSize = 22.sp, lineHeight = 26.sp)
private val PersonCardTitle = HotMessType.heading.copy(fontSize = 18.sp, lineHeight = 22.sp)
private val CardDetail = HotMessType.subheading
private val PillText = HotMessType.bodySmall.copy(fontWeight = FontWeight.SemiBold)

/** Text sits 18 from the card's sides and 14 from its bottom; corner items 12 in. */
private val TextInset = 18.dp
private val TextBottom = 14.dp
private val CornerInset = 12.dp

/** The brightness check samples the text band inset this far from the card's sides, and this far above the text. */
private val BandInset = 14.dp
private val BandAbove = 8.dp

/**
 * The gradient behind a photo's text: solid [color] at [opacity] over the bottom [band] pixels,
 * fading out over [fade] above it. With [flat], one even layer over the whole area instead.
 */
@Composable
fun Scrim(color: Color, opacity: Float, band: Float, fade: Dp, modifier: Modifier = Modifier, flat: Boolean = false) {
    Box(
        modifier.drawWithCache {
            val solid = color.copy(alpha = opacity)
            val clear = color.copy(alpha = 0f)
            val bandHeight = band.coerceIn(0f, size.height)
            val total = minOf(size.height, bandHeight + fade.toPx())
            val top = size.height - total
            val fadeFraction = if (total > 0f) (total - bandHeight) / total else 0f
            val brush = Brush.verticalGradient(
                0f to clear,
                fadeFraction to solid,
                1f to solid,
                startY = top,
                endY = size.height,
            )
            onDrawBehind {
                if (opacity <= 0f) return@onDrawBehind
                if (flat) {
                    drawRect(solid)
                } else if (total > 0f) {
                    drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, total))
                }
            }
        },
    )
}

/**
 * A small label on dark `glass` with `on-photo` text, for a card's top corners. It reads on any
 * photo, so it needs no brightness check of its own. Android has no backdrop blur under it.
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    shape: Shape = Radius.pill,
    padding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.clip(shape).background(PhotoColors.glass).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        CompositionLocalProvider(LocalContentColor provides PhotoColors.onPhoto, LocalTextStyle provides PillText) {
            content()
        }
    }
}

@Composable
fun GlassPill(text: String, modifier: Modifier = Modifier) {
    GlassPill(modifier) { Text(text, maxLines = 1) }
}

/**
 * A rounded card filled by a photo, with [content] written over it in whichever colour the photo can
 * carry, like a small hero.
 *
 * The area behind [content] is read with the brightness check: white text when it reaches the target,
 * `photo-ink` only when the photo needs no scrim, otherwise white over the lightest scrim that does.
 * [leading] and [trailing] sit in the top corners and bring their own backgrounds (GlassPill). With
 * no photo the card is the accent gradient. [topClearance] keeps the corner items clear of the text;
 * [blur] softens photos too small or square to fill the card sharply (Android 12 and later); with
 * [centered] the text sits in the middle rather than at the bottom, over one flat scrim.
 */
@Composable
fun PhotoCard(
    url: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minHeight: Dp = Sizes.photoCard,
    topClearance: Dp = 52.dp,
    blur: Dp = 0.dp,
    centered: Boolean = false,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var textHeight by remember { mutableIntStateOf(0) }

    val band: Rect? = if (size.width == 0 || textHeight == 0) {
        null
    } else {
        with(density) {
            val height = size.height.toFloat()
            val bottom = TextBottom.toPx()
            val textTop = if (centered) {
                val clearance = topClearance.toPx()
                clearance + (height - bottom - clearance - textHeight) / 2f
            } else {
                height - bottom - textHeight
            }
            Rect(BandInset.toPx(), max(0f, textTop - BandAbove.toPx()), size.width - BandInset.toPx(), height)
        }
    }

    val tone = rememberPhotoTone(url, size, band, kind = "card|$minHeight|$blur")
    val textColor by animateColorAsState(tone.textColor, toneAnimation(), label = "text")
    val scrimColor by animateColorAsState(tone.scrimColor, toneAnimation(), label = "scrim colour")
    val scrimOpacity by animateFloatAsState(tone.scrim.toFloat(), toneAnimation(), label = "scrim")

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(Radius.photo)
            .background(PhotoColors.placeholder)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .onSizeChanged { size = it },
    ) {
        PhotoLayer(url, Modifier.matchParentSize().then(if (blur > 0.dp) Modifier.blur(blur, BlurredEdgeTreatment.Rectangle) else Modifier))
        Scrim(
            scrimColor,
            scrimOpacity,
            band = band?.height ?: 0f,
            fade = 40.dp,
            flat = centered,
            modifier = Modifier.matchParentSize(),
        )
        Column(
            Modifier
                .align(if (centered) Alignment.CenterStart else Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = TextInset, end = TextInset, top = topClearance, bottom = TextBottom)
                .onSizeChanged { textHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides textColor) { content() }
        }
        Box(Modifier.align(Alignment.TopStart).padding(CornerInset)) { leading() }
        Box(Modifier.align(Alignment.TopEnd).padding(CornerInset)) { trailing() }
    }
}

/** The photo, filling and cropped to its box, or the accent gradient when there's none. Never fades in. */
@Composable
internal fun PhotoLayer(url: String?, modifier: Modifier) {
    if (url != null) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(tokens.accent, tokens.accentStrong))))
    }
}

/** A friend's face with the white ring avatars on photos wear. */
private fun Modifier.photoRing(width: Dp = 2.dp) = border(width, Color.White.copy(alpha = Opacity.ring), CircleShape)

/** Overlapping friend photos (26, overlapping by 7), for the corner of a card. Hidden from screen readers; cards describe them. */
@Composable
fun FriendFaces(friends: List<Friend>, modifier: Modifier = Modifier, limit: Int = 4) {
    val configuration = LocalAppGraph.current.configuration
    val shown = friends.take(limit)
    Box(modifier.clearAndSetSemantics { }) {
        shown.forEachIndexed { index, friend ->
            Avatar(
                configuration.avatarUrl(friend.id),
                friend.name,
                size = Sizes.avatarXs,
                modifier = Modifier.padding(start = (Sizes.avatarXs - 7.dp) * index).photoRing(),
            )
        }
    }
}

/**
 * A venue as a photo card: its name and [detail] (its summary by default) over its photo, with
 * [pill] top right (its distance by default, or a friend count) and [corner] top left (friend faces).
 */
@Composable
fun VenueCard(
    venue: Venue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    pill: String? = venue.distance?.let { Formatting.distance(it) },
    corner: @Composable () -> Unit = {},
) {
    PhotoCard(
        url = venue.photoUrl,
        onClick = onClick,
        modifier = modifier,
        leading = corner,
        trailing = { if (pill != null) GlassPill(pill) },
    ) {
        Text(venue.name, style = CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            detail ?: venue.summary ?: venue.locale?.displayName ?: "No address yet",
            style = CardDetail,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An event as a photo card over its cover: the date top left, your RSVP top right (Going or
 * Interested only), and the name with when and where at the bottom. Featured events are taller.
 */
@Composable
fun EventCard(event: Event, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PhotoCard(
        url = event.coverPhotoUrl,
        onClick = onClick,
        modifier = modifier,
        minHeight = if (event.isFeatured) Sizes.photoCardFeatured else Sizes.photoCard,
        topClearance = 68.dp,
        leading = {
            GlassPill(
                shape = Radius.md,
                padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = Formatting.dateTime(event.startAt) },
            ) {
                Column(Modifier.widthIn(min = 30.dp).clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Formatting.monthAbbreviation(event.startAt), style = HotMessType.caption)
                    Text(Formatting.dayOfMonth(event.startAt), style = CardTitle)
                }
            }
        },
        trailing = {
            if (event.rsvp == Rsvp.ATTENDING || event.rsvp == Rsvp.MAYBE) {
                GlassPill {
                    Icon(event.rsvp.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(event.rsvp.title, maxLines = 1)
                }
            }
        },
    ) {
        Text(event.name, style = if (event.isFeatured) FeaturedCardTitle else CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(Formatting.eventSubtitle(event), style = CardDetail, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A person as a shorter card: their picture enlarged and blurred behind a sharp 56 avatar, with their
 * name and role (or [detail]) beside it, and [friends] with them top right. Profile pictures are small
 * square faces, so filling a wide card with one sharply would crop the head and show the pixels.
 */
@Composable
fun PersonCard(
    person: Person,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    friends: List<Friend> = emptyList(),
) {
    PhotoCard(
        url = person.pictureUrl,
        onClick = onClick,
        modifier = modifier.semantics {
            if (friends.isNotEmpty()) stateDescription = withFriends(friends)
        },
        minHeight = Sizes.personCard,
        topClearance = TextBottom,
        blur = 24.dp,
        centered = true,
        trailing = { if (friends.isNotEmpty()) FriendFaces(friends, limit = 3) },
    ) {
        Row(
            Modifier.padding(end = if (friends.isEmpty()) 0.dp else 64.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(person.pictureUrl, person.name, size = Sizes.avatarLg, modifier = Modifier.photoRing())
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(person.name, style = PersonCardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (detail ?: person.role)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = CardDetail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "With Alex Kim and Sam Lee": the friends on a card, by their full names. */
private fun withFriends(friends: List<Friend>): String = "With ${friendNames(friends)}"

/**
 * Friends' full names as a list: "Alex Kim", "Alex Kim and Sam Lee", "Alex Kim, Sam Lee and Jo
 * Park". Friends see each other's full names; where it doesn't fit, the text truncates.
 */
fun friendNames(friends: List<Friend>): String {
    val names = friends.map { it.name }.filter { it.isNotBlank() }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/** A section title on the wash: subheading weight, `ink-muted`, inset 20 to line up with the rows. */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = HotMessType.subheading,
        color = tokens.inkMuted,
        modifier = modifier.padding(horizontal = DetailInset).semantics { heading() },
    )
}

/**
 * A titled stack of cards in a [Feed], one lazy item per card so long lists stay cheap. The title
 * sits with the first card; a section with no [items] draws nothing. [section] keeps keys unique
 * when the same item appears in two sections.
 */
fun <T> LazyListScope.cardSection(
    section: String,
    title: String?,
    items: List<T>,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> "$section/${key(item)}" }) { index, item ->
        if (index == 0 && title != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                SectionTitle(title)
                card(item)
            }
        } else {
            card(item)
        }
    }
}
