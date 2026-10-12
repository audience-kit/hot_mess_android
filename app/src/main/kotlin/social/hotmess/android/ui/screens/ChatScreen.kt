package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import social.hotmess.android.AppGraph
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.ChatComposer
import social.hotmess.android.ui.components.ChatThread
import social.hotmess.android.ui.components.ChatThreadMessage
import social.hotmess.android.ui.components.HereNowStrip
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.Presence
import social.hotmess.android.ui.components.RichContent
import social.hotmess.android.ui.components.RoomBanner
import social.hotmess.android.ui.components.RoomBannerKind
import social.hotmess.core.AppRoute
import social.hotmess.core.ChatRoom
import social.hotmess.core.FriendDirectory
import social.hotmess.core.RecordId
import social.hotmess.core.RoomPeople
import social.hotmess.core.VenueChatConnection
import social.hotmess.core.VenueMessage

/**
 * The live chat room for one venue, or one locale, kept across configuration changes. Only people at
 * the venue (or out in the locale) get in, so the position is reported when the room opens and every
 * few minutes while it's open.
 */
class VenueChatModel(private val graph: AppGraph, private val room: ChatRoom) : ViewModel() {
    /** AWAY: the server says this person isn't at the venue (or in the locale), or has left. */
    enum class Status { CONNECTING, CONNECTED, OFFLINE, AWAY }

    private val _messages = MutableStateFlow<List<VenueMessage>>(emptyList())
    val messages: StateFlow<List<VenueMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** They're in the room from outside its place, which only admins can do. */
    private val _outOfRange = MutableStateFlow(false)
    val outOfRange: StateFlow<Boolean> = _outOfRange.asStateFlow()

    /**
     * Who is in the room now, by lowercase user id, with their names and photos where the room sends
     * them: the roster on joining, then presence frames. Empty while disconnected.
     */
    private val _people = MutableStateFlow(RoomPeople())
    val people: StateFlow<RoomPeople> = _people.asStateFlow()

    /**
     * People outside the room whom a notification reaches, by lowercase user id: a yellow dot. The room
     * only says so about the viewer's friends, and to admins.
     */
    private val _reachable = MutableStateFlow<Set<String>>(emptySet())
    val reachable: StateFlow<Set<String>> = _reachable.asStateFlow()

    /** A line on its way to the room; the composer waits for it. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var job: Job? = null
    private var heartbeat: Job? = null

    /**
     * Follows the room on the app's shared cable while the screen is showing. The cable reconnects after
     * a drop and joins the room again on the same flow: offline until then, and ChatJoined refreshes the room.
     * Only when the server ends the room's operation does this subscribe again itself, after a backoff.
     */
    fun connect() {
        if (job?.isActive == true) return
        if (heartbeat?.isActive != true) {
            heartbeat = viewModelScope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    graph.location.reportAgain()
                }
            }
        }
        job = viewModelScope.launch {
            _status.value = Status.CONNECTING
            // The server checks for a recent position at the venue (or in the locale) before letting anyone in.
            graph.location.reportAgain()
            // The cable runs GraphQL for one audience: the configured one, the session's, or the host's.
            var backoff = 1_000L
            var audienceId: String? = null
            while (audienceId == null) {
                audienceId = try {
                    graph.api.client.audienceId()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (audienceId == null) {
                    _status.value = Status.OFFLINE
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                    _status.value = Status.CONNECTING
                }
            }
            backoff = 1_000L
            while (true) {
                var away = false
                var ended = false
                VenueChatConnection(room, audienceId, graph.cable).events().takeWhile { event ->
                    when (event) {
                        VenueChatConnection.Event.Connected -> {
                            _status.value = Status.CONNECTED
                            backoff = 1_000L
                        }
                        is VenueChatConnection.Event.Received -> receive(event.message)
                        is VenueChatConnection.Event.Range -> _outOfRange.value = event.outOfRange
                        is VenueChatConnection.Event.Roster -> {
                            // All the viewer's friends, by full name, so the room and its lines show them that way.
                            graph.api.friends.record(event.friends)
                            _people.value = _people.value.roster(event.online, event.people)
                        }
                        is VenueChatConnection.Event.PresenceChanged -> {
                            _people.value = if (event.online) {
                                _people.value.joined(event.userId, event.name, event.avatarUrl)
                            } else {
                                _people.value.left(event.userId)
                            }
                            val id = event.userId.lowercase()
                            _reachable.value = if (event.reachable) _reachable.value + id else _reachable.value - id
                        }
                        is VenueChatConnection.Event.History -> history(event.messages, event.pinned)
                        is VenueChatConnection.Event.Pin -> pin(event.id, event.announcement)
                        // An admin removed a line, or cleared the room; a pinned announcement goes with its line.
                        is VenueChatConnection.Event.Removed -> _messages.value = _messages.value.filterNot { it.id == event.id }
                        VenueChatConnection.Event.Cleared -> _messages.value = emptyList()
                        is VenueChatConnection.Event.Disconnected -> {
                            // Reconnecting (or over): the next roster says who's here; until then nobody is known to be.
                            _status.value = Status.OFFLINE
                            _people.value = RoomPeople()
                            _reachable.value = emptySet()
                        }
                        VenueChatConnection.Event.Ended -> ended = true
                        VenueChatConnection.Event.Rejected, VenueChatConnection.Event.Left -> away = true
                    }
                    !away
                }.collect()
                if (away) {
                    // Reconnecting won't help until they're back; "Try again" subscribes again.
                    _status.value = Status.AWAY
                    return@launch
                }
                _status.value = Status.OFFLINE
                // Cable drops are the cable's to reconnect; only an operation the server ended is ours to
                // start over. Anything else (signed out, refused) stays offline.
                if (!ended) return@launch
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                _status.value = Status.CONNECTING
            }
        }
    }

    /** Adds a line. A line the room sends again (by its id) replaces the first copy. */
    private fun receive(line: VenueMessage) {
        if (line.reachable && !line.isFromListing) _reachable.value = _reachable.value + line.userId.lowercase()
        // An older server's roster has no names: a line someone sends names them in "Here now".
        if (!line.isFromListing) _people.value = _people.value.described(line.userId, line.name, line.avatarUrl)
        val current = _messages.value
        val index = current.indexOfFirst { it.id == line.id }
        _messages.value = if (index >= 0) current.toMutableList().also { it[index] = line } else current + line
    }

    /**
     * What the room sent on joining replaces what was shown, so a reconnect doesn't repeat lines. A
     * pinned announcement older than the lines the room shows goes first, so its bar has it.
     */
    private fun history(messages: List<VenueMessage>, pinned: VenueMessage?) {
        val lines = messages
        val older = pinned?.takeIf { p -> lines.none { it.id == p.id } }
        _messages.value = listOfNotNull(older) + lines
        _reachable.value = lines.filter { it.reachable && !it.isFromListing }.map { it.userId.lowercase() }.toSet()
    }

    /** An announcement was pinned (replacing whichever was) or unpinned. */
    private fun pin(id: String, announcement: VenueMessage?) {
        val lines = _messages.value.map { line ->
            val pinned = announcement != null && line.id == id
            if (line.pinned == pinned) line else line.copy(pinned = pinned)
        }
        _messages.value = if (announcement != null && lines.none { it.id == id }) lines + announcement else lines
    }

    fun disconnect() {
        job?.cancel()
        job = null
        heartbeat?.cancel()
        heartbeat = null
        _people.value = RoomPeople()
        _reachable.value = emptySet()
    }

    /**
     * Says [body] in the room with `sendMyChatMessage`, then tells [onResult] whether it went. The room
     * delivers the line to everyone following it, this person included, and the copy the mutation returns
     * shows it at once; the room's copy then replaces it by id. A failed send leaves the draft as it was.
     */
    fun send(body: String, onResult: (Boolean) -> Unit) {
        val text = body.trim()
        if (text.isEmpty() || _sending.value || _status.value != Status.CONNECTED) return onResult(false)
        _sending.value = true
        viewModelScope.launch {
            val sent = try {
                graph.api.sendChatMessage(room, text)?.let(::receive)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                _sending.value = false
            }
            onResult(sent)
        }
    }

    override fun onCleared() = disconnect()

    private companion object {
        const val HEARTBEAT_MS = 4 * 60 * 1000L
    }
}

@Composable
fun VenueChatScreen(room: ChatRoom, venueName: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val model = viewModel(key = "chat-${room.kind}-${room.id}") { VenueChatModel(graph, room) }
    val isLocale = room.kind == ChatRoom.Kind.LOCALE
    val messages by model.messages.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val outOfRange by model.outOfRange.collectAsStateWithLifecycle()
    val people by model.people.collectAsStateWithLifecycle()
    val reachable by model.reachable.collectAsStateWithLifecycle()
    val user by graph.session.user.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }

    DisposableEffect(model) {
        model.connect()
        onDispose { model.disconnect() }
    }

    val sending by model.sending.collectAsStateWithLifecycle()

    fun send() {
        val text = draft
        // Clears only what was sent, not anything typed while it went.
        model.send(text) { sent -> if (sent && draft == text) draft = "" }
    }

    ScreenScaffold(title = venueName, onBack = navigator::back) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            if (status == VenueChatModel.Status.AWAY) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Message(
                        Icons.AutoMirrored.Rounded.Chat,
                        if (isLocale) "Only for people out in $venueName" else "Only for people at $venueName",
                        if (isLocale) {
                            "The room opens when you're out in $venueName and not at a venue. Venues have their own chat."
                        } else {
                            "The room opens when you're there. Your location has to be on so Hot Mess can tell."
                        },
                        action = "Try again" to { model.disconnect(); model.connect() },
                    )
                }
                return@Column
            }
            val banner = when {
                outOfRange -> RoomBannerKind.RANGE
                status == VenueChatModel.Status.CONNECTING -> RoomBannerKind.CONNECTING
                status != VenueChatModel.Status.CONNECTED -> RoomBannerKind.OFFLINE
                else -> null
            }
            if (banner != null) RoomBanner(banner, venueName, isLocale = isLocale)
            val userId = user?.id
            val friends = graph.api.friends
            // Who's here, friends first; a roster can add friends' full names, so it redraws the thread too.
            val hereNow = remember(people, userId) {
                RoomPeople.hereNow(people.byId.values, userId) { friends.fullName(it) }
            }
            HereNowStrip(hereNow)
            val thread = remember(messages, userId, people) { messages.map { it.threadMessage(userId, friends) } }
            // Green for everyone in the room, yellow for friends outside it whom a notification reaches.
            val presence = remember(people, reachable) {
                reachable.associateWith { Presence.PUSH } + people.ids.associateWith { Presence.ONLINE }
            }
            ChatThread(
                thread,
                venueName,
                Modifier.weight(1f).fillMaxWidth(),
                presence = presence,
                isLocale = isLocale,
                onOpenEvent = { id -> navigator.open(AppRoute.EventDetail(RecordId.normalize(id) ?: id.lowercase())) },
            )
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = ::send,
                sendEnabled = draft.isNotBlank() && !sending && status == VenueChatModel.Status.CONNECTED,
            )
        }
    }
}

/**
 * A room's line as ChatThread draws it. A friend shows by their full name (the room sends everyone
 * "First L."), a post as the venue or as a listing by its own name and photo, and a rich message as its card.
 */
private fun VenueMessage.threadMessage(viewerId: String?, friends: FriendDirectory) = ChatThreadMessage(
    id = id,
    // A post as a listing groups apart from the poster's own lines, and takes no presence of theirs.
    senderId = personId?.let { "person:$it" } ?: userId.lowercase(),
    senderName = if (isFromListing) name else friends.displayName(userId, name),
    avatarUrl = avatarUrl,
    text = body,
    sentAt = sentAt,
    own = isOutgoing(viewerId),
    role = role,
    asPlace = isPostedAsVenue,
    rich = if (kind.isRich) {
        RichContent(kind, title = title, body = caption, photoUrl = photoUrl, event = event, endsAt = endsAt, pinned = pinned)
    } else {
        null
    },
)
