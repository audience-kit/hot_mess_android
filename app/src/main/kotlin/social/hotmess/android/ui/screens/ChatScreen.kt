package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.AppGraph
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.theme.ContentMaxWidth
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ChatProtocol
import social.hotmess.core.VenueChatConnection
import social.hotmess.core.VenueMessage

/** The live chat room for one venue, kept across configuration changes. */
class VenueChatModel(private val graph: AppGraph, private val venueId: String) : ViewModel() {
    enum class Status { CONNECTING, CONNECTED, OFFLINE }

    private val _messages = MutableStateFlow<List<VenueMessage>>(emptyList())
    val messages: StateFlow<List<VenueMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var connection: VenueChatConnection? = null
    private var job: Job? = null

    /** Connects while the screen is showing, reconnecting after a drop. */
    fun connect() {
        if (job?.isActive == true) return
        val url = ChatProtocol.realtimeUrl(graph.configuration.baseUrl) ?: run {
            _status.value = Status.OFFLINE
            return
        }
        job = viewModelScope.launch {
            var backoff = 1_000L
            while (true) {
                _status.value = Status.CONNECTING
                val current = VenueChatConnection(venueId, url, graph.session.sessionToken)
                connection = current
                current.events().collect { event ->
                    when (event) {
                        VenueChatConnection.Event.Connected -> {
                            _status.value = Status.CONNECTED
                            backoff = 1_000L
                        }
                        is VenueChatConnection.Event.Received -> _messages.value = _messages.value + event.message
                        is VenueChatConnection.Event.Disconnected -> _status.value = Status.OFFLINE
                    }
                }
                connection = null
                _status.value = Status.OFFLINE
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        connection = null
    }

    fun send(body: String): Boolean {
        val text = body.trim()
        val userId = graph.session.user.value?.id ?: return false
        if (text.isEmpty()) return false
        return connection?.send(text, userId, graph.configuration.avatarUrl(userId)) ?: false
    }

    override fun onCleared() = disconnect()
}

@Composable
fun VenueChatScreen(venueId: String, venueName: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val model = viewModel(key = "chat-$venueId") { VenueChatModel(graph, venueId) }
    val messages by model.messages.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val user by graph.session.user.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()

    DisposableEffect(model) {
        model.connect()
        onDispose { model.disconnect() }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }

    fun send() {
        if (model.send(draft)) draft = ""
    }

    ScreenScaffold(title = venueName, onBack = navigator::back) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            if (status != VenueChatModel.Status.CONNECTED) {
                Text(
                    if (status == VenueChatModel.Status.CONNECTING) "Connecting…" else "Offline. Reconnecting…",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                    modifier = Modifier.fillMaxWidth().background(tokens.controlFill).padding(horizontal = Space.s4, vertical = Space.s1),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    Message(Icons.AutoMirrored.Rounded.Chat, "Say hello.", "Everyone at $venueName can see what you write here.")
                } else {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
                        verticalArrangement = Arrangement.spacedBy(Space.s2),
                        contentPadding = PaddingValues(Space.s4),
                    ) {
                        items(messages, key = { it.id }) { message -> Bubble(message, message.isOutgoing(user?.id)) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(tokens.surfaceRaised).padding(Space.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Message") },
                    shape = Radius.md,
                    textStyle = HotMessType.body,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.focus,
                        unfocusedBorderColor = tokens.border,
                        cursorColor = tokens.accent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = ::send, enabled = draft.isNotBlank() && status == VenueChatModel.Status.CONNECTED) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = if (draft.isNotBlank()) tokens.accentInk else tokens.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: VenueMessage, outgoing: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!outgoing) {
            Avatar(message.avatarUrl, "", size = 28.dp)
            Spacer(Modifier.width(Space.s2))
        }
        Text(
            message.body,
            style = HotMessType.body,
            color = if (outgoing) tokens.onAccent else tokens.ink,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(Radius.lg)
                .background(if (outgoing) tokens.accent else tokens.surfaceRaised)
                .padding(horizontal = Space.s3, vertical = Space.s2),
        )
    }
}
