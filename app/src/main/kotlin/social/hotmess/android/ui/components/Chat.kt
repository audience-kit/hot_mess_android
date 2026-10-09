package social.hotmess.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.hotmess.android.ui.theme.ContentMaxWidth
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ChatKind
import social.hotmess.core.ChatRole
import social.hotmess.core.Formatting
import social.hotmess.core.RoomPerson
import social.hotmess.core.SharedEvent
import social.hotmess.core.firstNameForDisplay
import java.time.Duration
import java.time.Instant

/*
 * The shared chat kit (design system Chat.md): ChatThread of ChatBubbles, RoomBanner, ChatComposer,
 * ChatPeek for a read-only glimpse of a room, ChatLineRow for flat lines, and PresenceDot. None of
 * it knows about venues or sockets; callers pass a room name and map their own message model to [ChatThreadMessage].
 */

/** Whether someone can be reached right now. Offline draws nothing. */
enum class Presence { ONLINE, PUSH, OFFLINE }

/** The API's presence (GraphQL's `presence`) as the dot draws it; offline and unknown draw nothing. */
fun presenceOf(value: com.audiencekit.Presence?): Presence? = when (value) {
    com.audiencekit.Presence.ONLINE -> Presence.ONLINE
    com.audiencekit.Presence.PUSH -> Presence.PUSH
    com.audiencekit.Presence.OFFLINE, null -> null
}

/**
 * The presence dot: a solid `presence-online` disc, or a `presence-push` ring with a hollow centre,
 * each on a 2dp [ring] (`surface-raised` when unspecified; the surface it sits on). [size] is the
 * dot without its ring: 10 on 28 avatars, 12 on 40 and up. Offline and null draw nothing.
 */
@Composable
fun PresenceDot(state: Presence?, modifier: Modifier = Modifier, size: Dp = 10.dp, ring: Color = Color.Unspecified) {
    if (state == null || state == Presence.OFFLINE) return
    val ringColor = ring.takeOrElse { tokens.surfaceRaised }
    val online = tokens.presenceOnline
    val push = tokens.presencePush
    val edge = tokens.presencePushEdge
    val label = if (state == Presence.ONLINE) "In chat now" else "Gets notifications"
    Canvas(modifier.size(size + 4.dp).semantics { contentDescription = label }) {
        val outer = this.size.minDimension / 2f
        val inner = outer - 2.dp.toPx()
        drawCircle(ringColor, radius = outer)
        if (state == Presence.ONLINE) {
            drawCircle(online, radius = inner)
        } else {
            drawCircle(push, radius = inner)
            // The hollow centre, about half the dot (the web's radial gradient stops at 32–36%).
            drawCircle(ringColor, radius = inner * 0.48f)
            val stroke = 1.dp.toPx()
            drawCircle(edge, radius = inner - stroke / 2f, style = Stroke(width = stroke))
        }
    }
}

/** One message as ChatThread draws it. Map from whatever the room's own model is. */
data class ChatThreadMessage(
    val id: String,
    /** Groups consecutive messages; usually the sender's user id. */
    val senderId: String,
    /** Shown above the first bubble of an incoming group, when known. */
    val senderName: String?,
    val avatarUrl: String?,
    /** The message in words; for a rich message its summary, which shows when [rich] can't be drawn. */
    val text: String,
    /** When it was sent; without it there's no time divider before it and no 5-minute grouping cut. */
    val sentAt: Instant?,
    val own: Boolean,
    /** Who the sender is in the room: a RoleTag after their name, and an `accent-soft` bubble for venue and host. */
    val role: ChatRole? = null,
    /** Posted as the venue: its name and photo in a rounded square, with no presence. */
    val asPlace: Boolean = false,
    /** A rich message's card, drawn in place of the bubble; null for plain text. */
    val rich: RichContent? = null,
) {
    /** Whether the room still shows it: a special disappears at its end time. */
    fun isShowing(at: Instant): Boolean {
        val content = rich ?: return true
        val ends = content.endsAt ?: return true
        return content.kind != ChatKind.SPECIAL || ends.isAfter(at)
    }
}

/**
 * What a rich message (RichMessage) shows besides its summary: an announcement's [title], [body] and
 * optional photo, a shared [event], a photo with its caption ([body]), or a special's [title] and [body]
 * until [endsAt]. [pinned] marks the announcement pinned under the room's title.
 */
data class RichContent(
    val kind: ChatKind,
    val title: String? = null,
    val body: String? = null,
    val photoUrl: String? = null,
    val event: SharedEvent? = null,
    val endsAt: Instant? = null,
    val pinned: Boolean = false,
) {
    /** Whether it has what its card needs; otherwise the summary shows as a bubble. */
    val drawable: Boolean
        get() = when (kind) {
            ChatKind.TEXT -> false
            ChatKind.ANNOUNCEMENT, ChatKind.SPECIAL -> !title.isNullOrBlank()
            ChatKind.EVENT -> event != null
            ChatKind.PHOTO -> photoUrl != null
        }
}

private sealed interface ThreadEntry {
    val key: String

    data class Divider(override val key: String, val label: String) : ThreadEntry

    data class BubbleEntry(val message: ChatThreadMessage, val first: Boolean, val last: Boolean) : ThreadEntry {
        override val key: String get() = message.id
    }
}

private val GroupGap: Duration = Duration.ofMinutes(5)
private val DividerGap: Duration = Duration.ofMinutes(15)

private fun gap(a: ChatThreadMessage, b: ChatThreadMessage): Duration? {
    val from = a.sentAt ?: return null
    val to = b.sentAt ?: return null
    return Duration.between(from, to)
}

private fun dividerBefore(messages: List<ChatThreadMessage>, index: Int): Boolean {
    val message = messages[index]
    if (message.sentAt == null) return false
    if (index == 0) return true
    val between = gap(messages[index - 1], message) ?: return false
    return between >= DividerGap
}

/**
 * Same sender, side and role, under 5 minutes apart (or untimed), and no divider between them. Rich
 * messages stand alone, each with its sender's name and avatar.
 */
private fun sameGroup(messages: List<ChatThreadMessage>, index: Int): Boolean {
    if (index == 0) return false
    val previous = messages[index - 1]
    val message = messages[index]
    if (previous.senderId != message.senderId || previous.own != message.own) return false
    if (previous.role != message.role || previous.asPlace != message.asPlace) return false
    if (previous.rich != null || message.rich != null) return false
    if (dividerBefore(messages, index)) return false
    val between = gap(previous, message) ?: return true
    return between < GroupGap
}

private fun threadEntries(messages: List<ChatThreadMessage>): List<ThreadEntry> {
    val entries = ArrayList<ThreadEntry>(messages.size + 4)
    messages.forEachIndexed { index, message ->
        if (dividerBefore(messages, index)) {
            entries += ThreadEntry.Divider("divider-${message.id}", Formatting.shortTime(message.sentAt!!))
        }
        val first = !sameGroup(messages, index)
        val last = index == messages.lastIndex || !sameGroup(messages, index + 1)
        entries += ThreadEntry.BubbleEntry(message, first, last)
    }
    return entries
}

/**
 * A chat room's transcript, oldest first and kept scrolled to the newest message: bubbles grouped
 * by sender (under 5 minutes apart), the sender's name (and RoleTag) above an incoming group and their
 * avatar beside its last bubble, and a time divider at the top and after any 15-minute gap. Rich
 * messages stand alone as RichMessage cards; a special disappears at its end time; the pinned
 * announcement also shows as a PinnedBar on top, which scrolls to it. [onOpenEvent] opens a shared
 * event by id. Empty, it says hello to everyone in [roomName], or out in it when [isLocale] (a city's room).
 */
@Composable
fun ChatThread(
    messages: List<ChatThreadMessage>,
    roomName: String,
    modifier: Modifier = Modifier,
    presence: Map<String, Presence> = emptyMap(),
    state: LazyListState = rememberLazyListState(),
    isLocale: Boolean = false,
    onOpenEvent: (String) -> Unit = {},
) {
    val clock by rememberMinuteClock()
    val visible = remember(messages, clock) { messages.filter { it.isShowing(clock) } }
    val entries = remember(visible) { threadEntries(visible) }
    val pinned = remember(visible) { visible.lastOrNull { it.rich?.kind == ChatKind.ANNOUNCEMENT && it.rich?.pinned == true } }
    val scope = rememberCoroutineScope()
    Column(modifier.background(tokens.surface)) {
        if (pinned != null) {
            PinnedBar(pinned.senderName, pinned.rich?.title ?: pinned.text) {
                val index = entries.indexOfFirst { it.key == pinned.id }
                if (index >= 0) scope.launch { state.animateScrollToItem(index) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                Message(
                    Icons.AutoMirrored.Rounded.Chat,
                    "Say hello.",
                    if (isLocale) "Everyone out in $roomName can see what you write here." else "Everyone at $roomName can see what you write here.",
                )
            } else {
                Transcript(entries, presence, state, onOpenEvent)
            }
        }
    }
}

/** Now, updated every minute, so specials leave the room when they end. */
@Composable
private fun rememberMinuteClock(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(60_000L)
        value = Instant.now()
    }
}

@Composable
private fun BoxScope.Transcript(
    entries: List<ThreadEntry>,
    presence: Map<String, Presence>,
    state: LazyListState,
    onOpenEvent: (String) -> Unit,
) {
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) state.animateScrollToItem(entries.lastIndex) }
    BoxWithConstraints(Modifier.fillMaxSize().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter)) {
        val bubbleMax = min(280.dp, maxWidth * 0.75f)
        val richMax = min(320.dp, maxWidth * 0.85f)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s3),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is ThreadEntry.Divider -> TimeDivider(entry.label)
                    is ThreadEntry.BubbleEntry -> BubbleRow(
                        entry,
                        bubbleMax,
                        presence[entry.message.senderId],
                        richMax = richMax,
                        onOpenEvent = onOpenEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeDivider(label: String) {
    Text(
        label,
        style = HotMessType.caption,
        color = tokens.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Space.s3, bottom = Space.s1),
    )
}

@Composable
private fun BubbleRow(
    entry: ThreadEntry.BubbleEntry,
    bubbleMax: Dp,
    presence: Presence?,
    groupGap: Dp = Space.s2,
    maxLines: Int = Int.MAX_VALUE,
    ring: Color = Color.Unspecified,
    richMax: Dp = bubbleMax,
    onOpenEvent: (String) -> Unit = {},
) {
    val message = entry.message
    val rich = message.rich?.takeIf { it.drawable }
    Row(
        Modifier.fillMaxWidth().padding(bottom = if (entry.last) groupGap else 2.dp),
        horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!message.own) {
            if (entry.last) {
                Avatar(
                    message.avatarUrl,
                    message.senderName.orEmpty(),
                    size = 28.dp,
                    // A place shows no presence, in a rounded square so it never looks like a person.
                    presence = if (message.asPlace) null else presence,
                    presenceRing = ring,
                    shape = if (message.asPlace) Radius.md else CircleShape,
                )
            } else {
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.width(Space.s2))
        }
        Column(
            modifier = if (rich != null) Modifier.widthIn(max = richMax).fillMaxWidth() else Modifier,
            horizontalAlignment = if (message.own) Alignment.End else Alignment.Start,
        ) {
            val name = message.senderName?.takeIf { it.isNotBlank() }
            if (!message.own && entry.first && (name != null || message.role != null)) {
                Row(
                    Modifier.widthIn(max = if (rich != null) richMax else bubbleMax).padding(start = Space.s3, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (name != null) {
                        Text(
                            name,
                            style = HotMessType.caption,
                            color = tokens.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    RoleTag(message.role)
                }
            }
            if (rich != null) {
                RichMessage(rich, message.senderName, onOpenEvent = onOpenEvent)
            } else {
                ChatBubble(
                    message.text,
                    own = message.own,
                    last = entry.last,
                    maxWidth = bubbleMax,
                    maxLines = maxLines,
                    tinted = !message.own && (message.role == ChatRole.VENUE || message.role == ChatRole.HOST),
                )
            }
        }
    }
}

/**
 * One chat bubble: `accent` / `on-accent` for your own, `surface-raised` / `ink` with a small
 * shadow for everyone else's, or `accent-soft` / `ink` with no shadow when [tinted] (the venue's and
 * hosts'). The last bubble of a group drops its corner nearest the sender to `radius-sm`. [maxLines]
 * clamps the text with an ellipsis (ChatPeek shows two).
 */
@Composable
fun ChatBubble(
    text: String,
    own: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
    maxLines: Int = Int.MAX_VALUE,
    tinted: Boolean = false,
) {
    val tail = CornerSize(4.dp)
    val shape = when {
        !last -> Radius.bubble
        own -> Radius.bubble.copy(bottomEnd = tail)
        else -> Radius.bubble.copy(bottomStart = tail)
    }
    val fill = when {
        own -> tokens.accent
        tinted -> tokens.accentSoft
        else -> tokens.surfaceRaised
    }
    Text(
        text,
        style = HotMessType.body,
        color = if (own) tokens.onAccent else tokens.ink,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = maxWidth)
            .then(if (own || tinted) Modifier else Modifier.shadow(1.dp, shape))
            .clip(shape)
            .background(fill)
            .padding(horizontal = Space.s3, vertical = Space.s2),
    )
}

/**
 * Who a sender is in the room, as a word after their name: "Venue" in solid `accent`, "Host" in
 * `accent-soft` with `accent-ink`, "Staff" in `control-fill`. Nothing for everyone else.
 */
@Composable
fun RoleTag(role: ChatRole?, modifier: Modifier = Modifier) {
    role ?: return
    val (fill, ink) = when (role) {
        ChatRole.VENUE -> tokens.accent to tokens.onAccent
        ChatRole.HOST -> tokens.accentSoft to tokens.accentInk
        ChatRole.STAFF -> tokens.controlFill to tokens.ink
    }
    Text(
        role.label,
        style = RoleTagText,
        color = ink,
        maxLines = 1,
        modifier = modifier.clip(Radius.pill).background(fill).padding(horizontal = 6.dp),
    )
}

private val RoleTagText = HotMessType.caption.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.02.em)
private val RichOverline = HotMessType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.06.em)

/**
 * What hosts, the venue and staff can post besides text (design system RichMessage), each standing
 * alone in the room:
 * - announcement: an `accent-soft` card with an optional 16:9 photo, an "Announcement" overline
 *   ("Pinned announcement" when pinned), the title and the body;
 * - event: the shared event as a photo card that opens it, with the caption under it;
 * - photo: a 4:3 photo up to 240 wide with its caption;
 * - special: an `accent-soft` card with a dashed `accent-ink` edge, a "Special · until 11pm"
 *   overline, the title and the body.
 * [author] names the sender for the photo's description.
 */
@Composable
fun RichMessage(content: RichContent, author: String?, modifier: Modifier = Modifier, onOpenEvent: (String) -> Unit = {}) {
    val body = content.body?.takeIf { it.isNotBlank() }
    when (content.kind) {
        ChatKind.PHOTO -> Column(
            modifier
                .widthIn(max = 240.dp)
                .shadow(1.dp, Radius.bubble)
                .clip(Radius.bubble)
                .background(tokens.surfaceRaised),
        ) {
            val description = body ?: author?.let { "Photo from $it" } ?: "Photo"
            RemoteImage(
                content.photoUrl,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .semantics {
                        contentDescription = description
                        role = Role.Image
                    },
            )
            if (body != null) {
                Text(
                    body,
                    style = HotMessType.body,
                    color = tokens.ink,
                    modifier = Modifier.padding(start = Space.s3, end = Space.s3, top = Space.s2, bottom = 10.dp),
                )
            }
        }
        ChatKind.EVENT -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val event = content.event ?: return@Column
            val start = event.startAt
            PhotoCard(
                url = null,
                onClick = { onOpenEvent(event.id) },
                topClearance = if (start != null) 68.dp else 52.dp,
                leading = {
                    if (start != null) {
                        GlassPill(
                            shape = Radius.md,
                            padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = Formatting.dateTime(start) },
                        ) {
                            Column(Modifier.widthIn(min = 30.dp).clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Formatting.monthAbbreviation(start), style = HotMessType.caption)
                                Text(Formatting.dayOfMonth(start), style = HotMessType.heading)
                            }
                        }
                    }
                },
            ) {
                Text(event.name ?: "An event", style = HotMessType.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (start != null) Text(Formatting.shortTime(start), style = HotMessType.subheading, maxLines = 1)
            }
            if (body != null) {
                Text(body, style = HotMessType.body, color = tokens.ink, modifier = Modifier.padding(horizontal = Space.s1))
            }
        }
        ChatKind.ANNOUNCEMENT -> Column(modifier.fillMaxWidth().clip(Radius.bubble).background(tokens.accentSoft)) {
            if (content.photoUrl != null) {
                RemoteImage(content.photoUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            RichText(if (content.pinned) "Pinned announcement" else "Announcement", content.title, body)
        }
        ChatKind.SPECIAL -> {
            val edge = tokens.accentInk
            val overline = content.endsAt?.let { "Special · until ${Formatting.clock(it)}" } ?: "Special"
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(Radius.bubble)
                    .background(tokens.accentSoft)
                    .drawBehind {
                        val stroke = 1.5.dp.toPx()
                        val radius = 16.dp.toPx() - stroke / 2f
                        drawRoundRect(
                            edge,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    },
            ) {
                RichText(overline, content.title, body)
            }
        }
        ChatKind.TEXT -> Unit
    }
}

/** A rich card's words: the overline in `accent-ink`, then the title and body in `ink`. */
@Composable
private fun RichText(overline: String, title: String?, body: String?) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = Space.s3)) {
        Text(overline.uppercase(), style = RichOverline, color = tokens.accentInk)
        if (!title.isNullOrBlank()) {
            Text(title, style = HotMessType.heading, color = tokens.ink, modifier = Modifier.padding(top = 2.dp))
        }
        if (body != null) {
            Text(body, style = HotMessType.body, color = tokens.ink, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The pinned announcement under a room's title: a pin, the sender in bold and the announcement's
 * title on one line, on `accent-soft`. Tapping it runs [onOpen], which scrolls to the announcement.
 */
@Composable
fun PinnedBar(author: String?, title: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.accentSoft)
            .clickable(role = Role.Button, onClickLabel = "Show the announcement", onClick = onOpen)
            .padding(horizontal = Space.s4, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = tokens.accentInk, modifier = Modifier.size(16.dp))
        Text(
            buildAnnotatedString {
                if (!author.isNullOrBlank()) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(author) }
                    append(" ")
                }
                append(title)
            },
            style = HotMessType.bodySmall,
            color = tokens.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Why the reader is (or isn't fully) in a room. Only one shows; range wins. */
enum class RoomBannerKind { RANGE, CONNECTING, OFFLINE }

/** The strip under a chat room's title. [isLocale] rooms are a city's, which you're in, not at. */
@Composable
fun RoomBanner(kind: RoomBannerKind, roomName: String, modifier: Modifier = Modifier, isLocale: Boolean = false) {
    when (kind) {
        RoomBannerKind.RANGE -> Row(
            modifier.fillMaxWidth().background(tokens.warningSoft).padding(horizontal = Space.s4, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = tokens.warning, modifier = Modifier.size(20.dp))
            Text(
                if (isLocale) {
                    "You're not in $roomName. You're in this chat because you're an admin."
                } else {
                    "You're not at $roomName. You're in this chat because you're an admin."
                },
                style = HotMessType.bodySmall,
                color = tokens.ink,
            )
        }
        RoomBannerKind.CONNECTING, RoomBannerKind.OFFLINE -> Text(
            if (kind == RoomBannerKind.CONNECTING) "Connecting…" else "Offline. Reconnecting…",
            style = HotMessType.bodySmall,
            color = tokens.inkMuted,
            modifier = modifier.fillMaxWidth().background(tokens.controlFill).padding(horizontal = Space.s4, vertical = Space.s1),
        )
    }
}

/**
 * Who else is in the room, under its banner (and above any pinned announcement): "N here now", then a
 * row of faces with first names, in the order given (callers put friends first; see RoomPeople.hereNow).
 * A friend's face has an `accent` ring and a heart badge. Every face carries the online dot. Draws
 * nothing when [people] is empty, so pass everyone but the viewer.
 */
@Composable
fun HereNowStrip(people: List<RoomPerson>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    val hairline = tokens.border
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.surfaceRaised)
            .drawBehind { drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
            .padding(vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Text(
            "${people.size} here now",
            style = HotMessType.caption,
            color = tokens.inkMuted,
            modifier = Modifier.padding(horizontal = Space.s4),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            items(people, key = { it.userId }) { person -> HereNowFace(person) }
        }
    }
}

/** One face in [HereNowStrip]: read out as "Aurora Bell, friend, here now". */
@Composable
private fun HereNowFace(person: RoomPerson) {
    val name = person.name ?: "Someone"
    val description = if (person.friend) "$name, friend, here now" else "$name, here now"
    val ringColor = tokens.accent
    Column(
        Modifier.width(56.dp).clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            if (person.friend) Box(Modifier.matchParentSize().border(2.dp, ringColor, CircleShape))
            Avatar(
                person.avatarUrl,
                name,
                size = 40.dp,
                modifier = Modifier.align(Alignment.Center),
                presence = Presence.ONLINE,
                presenceRing = tokens.surfaceRaised,
            )
            if (person.friend) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(tokens.surfaceRaised, CircleShape)
                        .padding(2.dp)
                        .background(ringColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(9.dp))
                }
            }
        }
        Text(
            name.firstNameForDisplay(),
            style = HotMessType.caption,
            color = if (person.friend) tokens.ink else tokens.inkMuted,
            fontWeight = if (person.friend) FontWeight.SemiBold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The message field and send button pinned under a room. Look only: [onSend] runs from the button
 * and the keyboard's send key; [sendEnabled] is the caller's (empty or offline disables it).
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val hairline = tokens.border
    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.surfaceRaised)
            .drawBehind { drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .padding(horizontal = Space.s3, vertical = Space.s2),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val pill = RoundedCornerShape(20.dp)
        val ink = tokens.ink
        val placeholder = tokens.inkMuted
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = HotMessType.body.copy(color = ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4,
            interactionSource = interaction,
            cursorBrush = SolidColor(tokens.accent),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(pill)
                        .background(tokens.surfaceSunken)
                        .border(if (focused) 2.dp else 1.dp, if (focused) tokens.focus else tokens.borderStrong, pill)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text("Message", style = HotMessType.body, color = placeholder)
                    field()
                }
            },
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (sendEnabled) tokens.accent else tokens.controlFill)
                .clickable(enabled = sendEnabled, role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = if (sendEnabled) tokens.onAccent else tokens.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** ChatPeek's bubbles: grouped like the thread, but with no time dividers drawn. */
private fun peekEntries(messages: List<ChatThreadMessage>): List<ThreadEntry.BubbleEntry> =
    messages.mapIndexed { index, message ->
        val first = !sameGroup(messages, index)
        val last = index == messages.lastIndex || !sameGroup(messages, index + 1)
        ThreadEntry.BubbleEntry(message, first, last)
    }

data class ChatPeekParticipant(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val presence: Presence? = null,
    val isPlace: Boolean = false,
)


/**
 * A read-only glimpse of a chat room (design system ChatPeek): a `surface-sunken` panel with
 * `radius-photo` corners holding [title], "N here now" when [online] is known, the last [limit]
 * [messages] (oldest first) as the room's own ChatBubbles clamped to two lines, and a "Join the
 * chat" call to action. The whole panel is one button that runs [onOpen]; screen readers hear
 * "Open the chat at [room], N recent messages" instead of each bubble. It holds no connection;
 * callers refresh [messages] when their screen appears.
 */

@Composable
fun ChatPeek(
    messages: List<ChatThreadMessage>,
    title: String,
    room: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    online: Int? = null,
    presence: Map<String, Presence> = emptyMap(),
    limit: Int = 3,
    participants: List<ChatPeekParticipant> = emptyList(),
) {
    val entries = remember(messages, limit) { peekEntries(messages.takeLast(limit)) }
    val count = entries.size
    val description = when (count) {
        0 -> "Open the chat at $room, no recent messages"
        1 -> "Open the chat at $room, 1 recent message"
        else -> "Open the chat at $room, $count recent messages"
    }
    val panel = tokens.surfaceSunken
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radius.photo)
            .background(panel)
            .clearAndSetSemantics {
                contentDescription = description + participants.joinToString(prefix = if (participants.isEmpty()) "" else "; ", separator = "; ") {
                    it.name + when (it.presence) {
                        Presence.ONLINE -> ", in chat"
                        Presence.PUSH -> ", reachable by notification"
                        else -> ""
                    }
                }
                role = Role.Button
                onClick { onOpen(); true }
            }
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.s4, vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = HotMessType.subheading.copy(fontWeight = FontWeight.Bold),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (online != null && online > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PresenceDot(Presence.ONLINE, ring = panel)
                    Text("$online here now", style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
                }
            }
        }
        if (participants.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                contentPadding = PaddingValues(vertical = Space.s1),
            ) {
                items(participants, key = { it.id }) { participant ->
                    Avatar(
                        participant.avatarUrl, participant.name, size = 32.dp,
                        presence = participant.presence, presenceRing = panel,
                        shape = if (participant.isPlace) RoundedCornerShape(8.dp) else CircleShape,
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            Text("No one's said anything yet. Say hello.", style = HotMessType.body, color = tokens.inkMuted)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bubbleMax = min(280.dp, maxWidth * 0.75f)
                Column {
                    entries.forEachIndexed { index, entry ->
                        BubbleRow(
                            entry,
                            bubbleMax,
                            presence[entry.message.senderId],
                            groupGap = if (index == entries.lastIndex) 0.dp else Space.s2,
                            maxLines = 2,
                            ring = panel,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entries.isEmpty()) "Start the chat" else "Join the chat",
                style = HotMessType.label,
                color = tokens.accentInk,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = tokens.accentInk,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A flat, bubble-less chat line (the admin transcript's ChatLine): a 28 avatar, then the name (with
 * its RoleTag) and a pre-formatted time on one caption line, then the text in two lines. Rich messages
 * pass their summary as [text].
 */
@Composable
fun ChatLineRow(
    author: String?,
    avatarUrl: String?,
    text: String,
    time: String?,
    modifier: Modifier = Modifier,
    presence: Presence? = null,
    maxLines: Int = 2,
    role: ChatRole? = null,
) {
    val name = author?.takeIf { it.isNotBlank() } ?: "Someone"
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(avatarUrl, author.orEmpty(), size = 28.dp, presence = presence)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = HotMessType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RoleTag(role)
                if (time != null) Text(time, style = HotMessType.caption, color = tokens.inkMuted, maxLines = 1)
            }
            Text(
                text,
                style = HotMessType.body,
                color = tokens.ink,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private val previewStart: Instant = Instant.parse("2026-10-09T21:00:00Z")

private val previewMessages = listOf(
    ChatThreadMessage("1", "sam", "Sam", null, "Anyone else here for the drag show?", previewStart, own = false),
    ChatThreadMessage("2", "sam", "Sam", null, "Front left by the bar", previewStart.plusSeconds(40), own = false),
    ChatThreadMessage("3", "me", "Me", null, "On my way in now", previewStart.plusSeconds(120), own = true),
    ChatThreadMessage("4", "me", "Me", null, "Save me a spot!", previewStart.plusSeconds(150), own = true),
    ChatThreadMessage("5", "alex", "Alex", null, "Second set is starting", previewStart.plusSeconds(20 * 60), own = false),
)

@Preview(name = "Chat thread", widthDp = 360, heightDp = 520)
@Composable
private fun ChatThreadPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            RoomBanner(RoomBannerKind.RANGE, "The Wildrose")
            ChatThread(
                previewMessages,
                "The Wildrose",
                Modifier.weight(1f).fillMaxWidth(),
                presence = mapOf("sam" to Presence.ONLINE, "alex" to Presence.PUSH),
            )
            var draft by remember { mutableStateOf("") }
            ChatComposer(draft, { draft = it }, onSend = {}, sendEnabled = draft.isNotBlank())
        }
    }
}

private val previewRichMessages = listOf(
    ChatThreadMessage(
        "r1", "venue", "Neighbours", null, "Announcement: Coat check closes at midnight", previewStart, own = false,
        role = ChatRole.VENUE, asPlace = true,
        rich = RichContent(
            ChatKind.ANNOUNCEMENT,
            title = "Coat check closes at midnight",
            body = "Grab your things before the late set. Lost and found is at the front door.",
            pinned = true,
        ),
    ),
    ChatThreadMessage("r2", "aurora", "Aurora B.", null, "Noted, thanks!", previewStart.plusSeconds(60), own = false),
    ChatThreadMessage("r3", "kiko", "DJ Kiko", null, "Next week I'm back with the disco set", previewStart.plusSeconds(180), own = false, role = ChatRole.HOST),
    ChatThreadMessage(
        "r4", "kiko", "DJ Kiko", null, "Shared an event: Sunset Social", previewStart.plusSeconds(200), own = false,
        role = ChatRole.HOST,
        rich = RichContent(ChatKind.EVENT, body = "Come through", event = SharedEvent("e1", "Sunset Social", previewStart.plusSeconds(8 * 86_400L))),
    ),
    ChatThreadMessage(
        "r5", "venue", "Neighbours", null, "Special: Two for one wells", previewStart.plusSeconds(300), own = false,
        role = ChatRole.VENUE, asPlace = true,
        rich = RichContent(ChatKind.SPECIAL, title = "Two for one wells", body = "At the back bar", endsAt = Instant.now().plusSeconds(3_600)),
    ),
    ChatThreadMessage("r6", "sam", "Sam O.", null, "Be kind in here, folks.", previewStart.plusSeconds(360), own = false, role = ChatRole.STAFF),
)

@Preview(name = "Roles and rich messages", widthDp = 360, heightDp = 760)
@Composable
private fun ChatThreadRichPreview() {
    HotMessTheme(darkTheme = false) {
        ChatThread(
            previewRichMessages,
            "Neighbours",
            Modifier.fillMaxSize(),
            presence = mapOf("kiko" to Presence.ONLINE, "aurora" to Presence.ONLINE),
        )
    }
}

@Preview(name = "Chat thread, dark", widthDp = 360, heightDp = 520)
@Composable
private fun ChatThreadDarkPreview() {
    HotMessTheme(darkTheme = true) {
        Column(Modifier.fillMaxSize()) {
            RoomBanner(RoomBannerKind.OFFLINE, "The Wildrose")
            ChatThread(previewMessages, "The Wildrose", Modifier.weight(1f).fillMaxWidth())
            ChatComposer("Hi there", {}, onSend = {}, sendEnabled = true)
        }
    }
}

@Preview(name = "Empty room", widthDp = 360, heightDp = 360)
@Composable
private fun ChatThreadEmptyPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            RoomBanner(RoomBannerKind.CONNECTING, "The Wildrose")
            ChatThread(emptyList(), "The Wildrose", Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Preview(name = "Presence and chat lines", widthDp = 360)
@Composable
private fun PresencePreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.background(tokens.surfaceRaised)) {
            Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Avatar(null, "Sam Lee", size = 28.dp, presence = Presence.ONLINE)
                Avatar(null, "Alex Kim", size = 28.dp, presence = Presence.PUSH)
                Avatar(null, "Jo Park", size = 44.dp, presence = Presence.ONLINE)
                Avatar(null, "Ri Moe", size = 44.dp, presence = Presence.PUSH)
                Avatar(null, "No One", size = 44.dp, presence = Presence.OFFLINE)
            }
            ChatLineRow("Sam Lee", null, "Anyone else here for the drag show? Front left by the bar, come say hi.", "2m", presence = Presence.ONLINE)
            ChatLineRow(null, null, "Second set is starting", "14m")
        }
    }
}

@Preview(name = "Chat peek", widthDp = 360)
@Composable
private fun ChatPeekPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.background(tokens.surface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            ChatPeek(
                previewMessages + ChatThreadMessage(
                    "6",
                    "alex",
                    "Alex",
                    null,
                    "Second set is starting and the queue for the bar is already out the door, so grab a drink now if you want one",
                    previewStart.plusSeconds(21 * 60),
                    own = false,
                ),
                title = "Chat",
                room = "The Wildrose",
                onOpen = {},
                online = 12,
                presence = mapOf("alex" to Presence.ONLINE),
            )
            ChatPeek(emptyList(), title = "Small talk", room = "The Wildrose", onOpen = {})
        }
    }
}

@Preview(name = "Chat peek, dark", widthDp = 360)
@Composable
private fun ChatPeekDarkPreview() {
    HotMessTheme(darkTheme = true) {
        Column(Modifier.background(tokens.surface).padding(Space.s4)) {
            ChatPeek(previewMessages, title = "Small talk", room = "The Wildrose", onOpen = {})
        }
    }
}

private val previewHereNow = listOf(
    RoomPerson("aurora", "Aurora Bell", friend = true),
    RoomPerson("wren", "Wren Fox", friend = true),
    RoomPerson("amy", "Amy R."),
    RoomPerson("jo", "Jo P."),
    RoomPerson("kiko", "Kiko M."),
    RoomPerson("zed", "Zed Q."),
)

@Preview(name = "Here now", widthDp = 360, heightDp = 420)
@Composable
private fun HereNowPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            HereNowStrip(previewHereNow)
            ChatThread(previewRichMessages, "Neighbours", Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Preview(name = "Here now, dark", widthDp = 360)
@Composable
private fun HereNowDarkPreview() {
    HotMessTheme(darkTheme = true) {
        HereNowStrip(previewHereNow.take(3))
    }
}
