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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import social.hotmess.android.ui.theme.ContentMaxWidth
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Formatting
import java.time.Duration
import java.time.Instant

/*
 * The shared chat kit (design system Chat.md): ChatThread of ChatBubbles, RoomBanner, ChatComposer,
 * ChatLineRow for flat previews, and PresenceDot. None of it knows about venues or sockets; callers
 * pass a room name and map their own message model to [ChatThreadMessage].
 */

/** Whether someone can be reached right now. Offline draws nothing. */
enum class Presence { ONLINE, PUSH, OFFLINE }

/**
 * The presence dot: a solid `presence-online` disc, or a `presence-push` ring with a hollow centre,
 * each on a 2dp `surface-raised` ring. [size] is the dot without its ring: 10 on 28 avatars, 12 on
 * 40 and up. Offline and null draw nothing.
 */
@Composable
fun PresenceDot(state: Presence?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    if (state == null || state == Presence.OFFLINE) return
    val ring = tokens.surfaceRaised
    val online = tokens.presenceOnline
    val push = tokens.presencePush
    val edge = tokens.presencePushEdge
    val label = if (state == Presence.ONLINE) "In chat now" else "Gets notifications"
    Canvas(modifier.size(size + 4.dp).semantics { contentDescription = label }) {
        val outer = this.size.minDimension / 2f
        val inner = outer - 2.dp.toPx()
        drawCircle(ring, radius = outer)
        if (state == Presence.ONLINE) {
            drawCircle(online, radius = inner)
        } else {
            drawCircle(push, radius = inner)
            // The hollow centre, about half the dot (the web's radial gradient stops at 32–36%).
            drawCircle(ring, radius = inner * 0.48f)
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
    val text: String,
    /** When it was sent; without it there's no time divider before it and no 5-minute grouping cut. */
    val sentAt: Instant?,
    val own: Boolean,
)

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

/** Same sender, same side, under 5 minutes apart (or untimed), and no divider between them. */
private fun sameGroup(messages: List<ChatThreadMessage>, index: Int): Boolean {
    if (index == 0) return false
    val previous = messages[index - 1]
    val message = messages[index]
    if (previous.senderId != message.senderId || previous.own != message.own) return false
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
 * by sender (under 5 minutes apart), the sender's name above an incoming group and their avatar
 * beside its last bubble, and a time divider at the top and after any 15-minute gap. Empty, it
 * says hello to everyone in [roomName].
 */
@Composable
fun ChatThread(
    messages: List<ChatThreadMessage>,
    roomName: String,
    modifier: Modifier = Modifier,
    presence: Map<String, Presence> = emptyMap(),
    state: LazyListState = rememberLazyListState(),
) {
    Box(modifier.background(tokens.surface)) {
        if (messages.isEmpty()) {
            Message(Icons.AutoMirrored.Rounded.Chat, "Say hello.", "Everyone at $roomName can see what you write here.")
        } else {
            Transcript(messages, presence, state)
        }
    }
}

@Composable
private fun BoxScope.Transcript(messages: List<ChatThreadMessage>, presence: Map<String, Presence>, state: LazyListState) {
    val entries = remember(messages) { threadEntries(messages) }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) state.animateScrollToItem(entries.lastIndex) }
    BoxWithConstraints(Modifier.fillMaxSize().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter)) {
        val bubbleMax = min(280.dp, maxWidth * 0.75f)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s3),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is ThreadEntry.Divider -> TimeDivider(entry.label)
                    is ThreadEntry.BubbleEntry -> BubbleRow(entry, bubbleMax, presence[entry.message.senderId])
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
private fun BubbleRow(entry: ThreadEntry.BubbleEntry, bubbleMax: Dp, presence: Presence?) {
    val message = entry.message
    Row(
        Modifier.fillMaxWidth().padding(bottom = if (entry.last) Space.s2 else 2.dp),
        horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!message.own) {
            if (entry.last) {
                Avatar(message.avatarUrl, message.senderName.orEmpty(), size = 28.dp, presence = presence)
            } else {
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.width(Space.s2))
        }
        Column(horizontalAlignment = if (message.own) Alignment.End else Alignment.Start) {
            val name = message.senderName?.takeIf { it.isNotBlank() }
            if (!message.own && entry.first && name != null) {
                Text(
                    name,
                    style = HotMessType.caption,
                    color = tokens.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = bubbleMax).padding(start = Space.s3, bottom = 2.dp),
                )
            }
            ChatBubble(message.text, own = message.own, last = entry.last, maxWidth = bubbleMax)
        }
    }
}

/**
 * One chat bubble: `accent` / `on-accent` for your own, `surface-raised` / `ink` with a small
 * shadow for everyone else's. The last bubble of a group drops its corner nearest the sender to
 * `radius-sm`.
 */
@Composable
fun ChatBubble(text: String, own: Boolean, last: Boolean, modifier: Modifier = Modifier, maxWidth: Dp = 280.dp) {
    val tail = CornerSize(4.dp)
    val shape = when {
        !last -> Radius.bubble
        own -> Radius.bubble.copy(bottomEnd = tail)
        else -> Radius.bubble.copy(bottomStart = tail)
    }
    Text(
        text,
        style = HotMessType.body,
        color = if (own) tokens.onAccent else tokens.ink,
        modifier = modifier
            .widthIn(max = maxWidth)
            .then(if (own) Modifier else Modifier.shadow(1.dp, shape))
            .clip(shape)
            .background(if (own) tokens.accent else tokens.surfaceRaised)
            .padding(horizontal = Space.s3, vertical = Space.s2),
    )
}

/** Why the reader is (or isn't fully) in a room. Only one shows; range wins. */
enum class RoomBannerKind { RANGE, CONNECTING, OFFLINE }

/** The strip under a chat room's title. */
@Composable
fun RoomBanner(kind: RoomBannerKind, roomName: String, modifier: Modifier = Modifier) {
    when (kind) {
        RoomBannerKind.RANGE -> Row(
            modifier.fillMaxWidth().background(tokens.warningSoft).padding(horizontal = Space.s4, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = tokens.warning, modifier = Modifier.size(20.dp))
            Text(
                "You're not at $roomName. You're in this chat because you're an admin.",
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

/**
 * A flat, bubble-less chat line (Now's "Small talk"): a 28 avatar, then the name and a
 * pre-formatted time on one caption line, then the text in two lines.
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
) {
    val name = author?.takeIf { it.isNotBlank() } ?: "Someone"
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(avatarUrl, author.orEmpty(), size = 28.dp, presence = presence)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    name,
                    style = HotMessType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
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
