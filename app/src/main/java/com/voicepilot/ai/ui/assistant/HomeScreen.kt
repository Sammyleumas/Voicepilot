package com.voicepilot.ai.ui.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.ai.AiAvailability
import com.voicepilot.ai.ui.components.MicButton
import com.voicepilot.ai.ui.components.MicState
import com.voicepilot.ai.ui.components.SectionHeader
import com.voicepilot.ai.ui.components.StatusPill
import com.voicepilot.ai.ui.components.VpCard
import com.voicepilot.ai.ui.components.Waveform
import com.voicepilot.ai.ui.theme.LocalStatusColors
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.core.conversation.AssistantStatus
import com.voicepilot.core.history.HistoryStatus
import com.voicepilot.core.model.Capability

private val QUICK_ACTIONS = listOf(
    "Read my messages",
    "Check my airtime balance",
    "Set an alarm for 6:30 am",
    "Play some Afrobeats",
    "Turn on the flashlight",
    "What can you do?",
)

@Composable
fun HomeScreen(
    viewModel: AssistantViewModel,
    onOpenConversation: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalStatusColors.current
    val requestMic = rememberCapabilityRequester { granted -> if (granted) viewModel.startListening() }
    WakeListenerEffect(viewModel)
    SystemSpeechFallback(viewModel)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(availabilityText(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenNotes) { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "Notes and tasks") }
                IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, contentDescription = "Automation history") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }

            Spacer(Modifier.height(Spacing.xxl))
            Text(state.greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
            Text("How can I help you?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)

            Spacer(Modifier.height(Spacing.xl))
            val pillColor = when {
                state.voice.listening -> colors.listening
                state.status is AssistantStatus.Error -> colors.danger
                state.status is AssistantStatus.AwaitingConfirmation || state.status is AssistantStatus.NeedsPermission -> colors.warning
                state.busy -> MaterialTheme.colorScheme.secondary
                else -> colors.success
            }
            StatusPill(statusLabel(state.status, state.voice), pillColor)

            Spacer(Modifier.height(Spacing.lg))
            MicButton(
                state = when {
                    state.voice.listening -> MicState.LISTENING
                    state.busy -> MicState.BUSY
                    else -> MicState.IDLE
                },
                onClick = {
                    if (state.voice.needsMicPermission && !state.voice.listening) requestMic(setOf(Capability.MICROPHONE))
                    else viewModel.onMicTapped()
                },
            )
            Waveform(level = state.voice.level, active = state.voice.listening, modifier = Modifier.widthIn(max = 320.dp))

            Spacer(Modifier.height(Spacing.md))
            Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    state.voice.listening -> Text(
                        state.voice.partial.ifEmpty { "Say something like “Set an alarm for 7am”" },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = if (state.voice.partial.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    state.voice.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.voice.error!!, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        if (state.voice.needsMicPermission) {
                            TextButton(onClick = { requestMic(setOf(Capability.MICROPHONE)) }) { Text("Allow microphone") }
                        }
                    }
                    else -> AssistantTurn(
                        status = state.status,
                        onConfirm = viewModel::confirm,
                        onChoose = viewModel::choose,
                        onRetryPermissions = viewModel::retryAfterPermissions,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))
            TextButton(onClick = onOpenConversation) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Text("  Type or view conversation")
            }

            SectionHeader("Quick actions")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                QUICK_ACTIONS.forEach { action ->
                    AssistChip(
                        onClick = { viewModel.submit(action) },
                        label = { Text(action) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }

            SectionHeader("Recent") {
                if (state.recent.isNotEmpty()) TextButton(onClick = onOpenHistory) { Text("See all") }
            }
            if (state.recent.isEmpty()) {
                Text(
                    "Your recent commands will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                VpCard {
                    state.recent.forEachIndexed { i, entry ->
                        if (i > 0) Spacer(Modifier.height(Spacing.md))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.utterance, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.resultMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.padding(start = Spacing.sm))
                            Text(
                                entry.status.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = when (entry.status) {
                                    HistoryStatus.COMPLETED, HistoryStatus.PREPARED, HistoryStatus.ANSWERED -> colors.success
                                    HistoryStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> colors.danger
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

private fun availabilityText(state: AssistantUiState): String = buildString {
    append(if (state.speechAvailable) "Voice ready" else "Voice unavailable — type instead")
    append(" · ")
    append(
        when (state.ai) {
            AiAvailability.OFF -> "On-device commands"
            AiAvailability.NOT_CONFIGURED -> "AI not set up"
            AiAvailability.READY -> "AI ready"
        },
    )
}
