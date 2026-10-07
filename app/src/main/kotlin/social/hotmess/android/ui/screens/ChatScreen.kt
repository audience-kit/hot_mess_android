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
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.RoomBanner
import social.hotmess.android.ui.components.RoomBannerKind
import social.hotmess.core.ChatProtocol
import social.hotmess.core.VenueChatConnection
import social.hotmess.core.VenueMessage

/**
 * The live chat room for one venue, kept across configuration changes. Only people at the venue get
 * in, so the position is reported when the room opens and every few minutes while it's open.
 */
class VenueChatModel(private val graph: AppGraph, private val venueId: String) : ViewModel() {
    /** AWAY: the server says this person isn't at the venue, or has left it. */
    enum class Status { CONNECTING, CONNECTED, OFFLINE, AWAY }

    private val _messages = MutableStateFlow<List<VenueMessage>>(emptyList())
    val messages: StateFlow<List<VenueMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** They're in the room from outside the venue, which only admins can do. */
    private val _outOfRange = MutableStateFlow(false)
    val outOfRange: StateFlow<Boolean> = _outOfRange.asStateFlow()

    private var connection: VenueChatConnection? = null
    private var job: Job? = null
    private var heartbeat: Job? = null

    /** Connects while the screen is showing, reconnecting after a drop. */
    fun connect() {
        if (job?.isActive == true) return
        val url = ChatProtocol.realtimeUrl(graph.configuration.baseUrl) ?: run {
            _status.value = Status.OFFLINE
            return
        }
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
            // The server checks for a recent position at the venue before letting anyone in.
            graph.location.reportAgain()
            var backoff = 1_000L
            while (true) {
                _status.value = Status.CONNECTING
                val current = VenueChatConnection(venueId, url, graph.session.sessionToken)
                connection = current
                var away = false
                current.events().takeWhile { event ->
                    when (event) {
                        VenueChatConnection.Event.Connected -> {
                            _status.value = Status.CONNECTED
                            backoff = 1_000L
                        }
                        is VenueChatConnection.Event.Received -> _messages.value = _messages.value + event.message.let {
                            it.copy(avatarUrl = it.avatarUrl ?: graph.configuration.avatarUrl(it.userId))
                        }
                        is VenueChatConnection.Event.Range -> _outOfRange.value = event.outOfRange
                        is VenueChatConnection.Event.Disconnected -> _status.value = Status.OFFLINE
                        VenueChatConnection.Event.Rejected, VenueChatConnection.Event.Left -> away = true
                    }
                    !away
                }.collect()
                connection = null
                if (away) {
                    // Reconnecting won't help until they're back; "Try again" reconnects.
                    _status.value = Status.AWAY
                    return@launch
                }
                _status.value = Status.OFFLINE
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        heartbeat?.cancel()
        heartbeat = null
        connection = null
    }

    fun send(body: String): Boolean {
        val text = body.trim()
        if (text.isEmpty()) return false
        return connection?.send(text) ?: false
    }

    override fun onCleared() = disconnect()

    private companion object {
        const val HEARTBEAT_MS = 4 * 60 * 1000L
    }
}

@Composable
fun VenueChatScreen(venueId: String, venueName: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val model = viewModel(key = "chat-$venueId") { VenueChatModel(graph, venueId) }
    val messages by model.messages.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val outOfRange by model.outOfRange.collectAsStateWithLifecycle()
    val user by graph.session.user.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }

    DisposableEffect(model) {
        model.connect()
        onDispose { model.disconnect() }
    }

    fun send() {
        if (model.send(draft)) draft = ""
    }

    ScreenScaffold(title = venueName, onBack = navigator::back) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            if (status == VenueChatModel.Status.AWAY) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Message(
                        Icons.AutoMirrored.Rounded.Chat,
                        "Only for people at $venueName",
                        "The room opens when you're there. Your location has to be on so Hot Mess can tell.",
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
            if (banner != null) RoomBanner(banner, venueName)
            val userId = user?.id
            val thread = remember(messages, userId) {
                // The socket's lines carry no name or time yet, so there are no names or dividers.
                messages.map {
                    ChatThreadMessage(
                        id = it.id,
                        senderId = it.userId,
                        senderName = null,
                        avatarUrl = it.avatarUrl,
                        text = it.body,
                        sentAt = null,
                        own = it.isOutgoing(userId),
                    )
                }
            }
            ChatThread(thread, venueName, Modifier.weight(1f).fillMaxWidth())
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = ::send,
                sendEnabled = draft.isNotBlank() && status == VenueChatModel.Status.CONNECTED,
            )
        }
    }
}
