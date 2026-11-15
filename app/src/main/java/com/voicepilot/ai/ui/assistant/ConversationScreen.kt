package com.voicepilot.ai.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.ui.components.EmptyState
import com.voicepilot.ai.ui.components.VpTopBar
import com.voicepilot.ai.ui.components.Waveform
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.core.conversation.ChatMessage
import com.voicepilot.core.conversation.ChatRole
import com.voicepilot.core.model.Capability

/** Full conversation: typed and spoken turns, plans, progress and results. */
@Composable
fun ConversationScreen(viewModel: AssistantViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val requestMic = rememberCapabilityRequester { granted -> if (granted) viewModel.startListening() }
    WakeListenerEffect(viewModel)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        topBar = {
            VpTopBar("Conversation", onBack = onBack) {
                IconButton(onClick = viewModel::clearConversation) { Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear conversation") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Start a conversation",
                            body = "Type a request below or tap the microphone. Try “Remind me to call Mum tomorrow at 9”.",
                            icon = Icons.Filled.Mic,
                        )
                    }
                }
                items(state.messages, key = { it.id }) { Bubble(it) }
                item {
                    Column(Modifier.padding(top = Spacing.sm)) {
                        AssistantTurn(
                            status = state.status,
                            onConfirm = viewModel::confirm,
                            onChoose = viewModel::choose,
                            onRetryPermissions = viewModel::retryAfterPermissions,
                        )
                    }
                }
            }

            if (state.voice.listening) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
                    Waveform(state.voice.level, active = true)
                    Text(state.voice.partial.ifEmpty { "Listening…" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.voice.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (state.voice.needsMicPermission) TextButton(onClick = { requestMic(setOf(Capability.MICROPHONE)) }) { Text("Allow") }
                    IconButton(onClick = viewModel::dismissVoiceError) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(1000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message…") },
                    singleLine = false,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        viewModel.submit(input)
                        input = ""
                    }),
                )
                if (input.isNotBlank()) {
                    FilledIconButton(onClick = { viewModel.submit(input); input = "" }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            if (state.busy) viewModel.cancel()
                            else if (state.voice.needsMicPermission) requestMic(setOf(Capability.MICROPHONE))
                            else viewModel.onMicTapped()
                        },
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            if (state.voice.listening || state.busy) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = when {
                                state.busy -> "Cancel current task"
                                state.voice.listening -> "Stop listening"
                                else -> "Speak"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    val mine = message.role == ChatRole.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp),
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        )
    }
}
