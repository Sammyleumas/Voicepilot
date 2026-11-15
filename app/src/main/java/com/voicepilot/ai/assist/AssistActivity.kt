package com.voicepilot.ai.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.MainActivity
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.ui.assistant.AssistantTurn
import com.voicepilot.ai.ui.assistant.AssistantViewModel
import com.voicepilot.ai.ui.assistant.WakeListenerEffect
import com.voicepilot.ai.ui.assistant.rememberCapabilityRequester
import com.voicepilot.ai.ui.assistant.statusLabel
import com.voicepilot.ai.ui.components.MicButton
import com.voicepilot.ai.ui.components.MicState
import com.voicepilot.ai.ui.components.Waveform
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.ai.ui.theme.VoicePilotTheme
import com.voicepilot.core.model.Capability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Compact assistant sheet shown over the current app when VoicePilot is
 * invoked as the default assistant, from the Quick Settings tile, the app
 * shortcut or a hands-free detection. It starts listening immediately.
 */
@AndroidEntryPoint
class AssistActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    private val assistant: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            VoicePilotTheme(settings.theme) {
                AssistSheet(
                    viewModel = assistant,
                    onDismiss = ::finish,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val command = intent?.getStringExtra(EXTRA_COMMAND)
        if (!settingsRepository.current.onboardingComplete) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (command.isNullOrBlank()) assistant.startListening() else assistant.submit(command, fromVoice = true)
    }

    override fun onStop() {
        assistant.stopListening()
        super.onStop()
    }

    companion object {
        private const val EXTRA_COMMAND = "command"

        fun intent(context: Context, command: String?): Intent =
            Intent(context, AssistActivity::class.java).apply { command?.let { putExtra(EXTRA_COMMAND, it) } }
    }
}

@Composable
private fun AssistSheet(viewModel: AssistantViewModel, onDismiss: () -> Unit, onOpenApp: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val requestMic = rememberCapabilityRequester { granted -> if (granted) viewModel.startListening() }
    WakeListenerEffect(viewModel)
    com.voicepilot.ai.ui.assistant.SystemSpeechFallback(viewModel)
    LaunchedEffect(state.voice.needsMicPermission) {
        if (state.voice.needsMicPermission) requestMic(setOf(Capability.MICROPHONE))
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { /* consume */ }
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(statusLabel(state.status, state.voice), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenApp) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open VoicePilot") }
                IconButton(onClick = { viewModel.cancel(); onDismiss() }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Text(
                when {
                    state.voice.listening -> state.voice.partial.ifEmpty { "Listening…" }
                    state.voice.error != null -> state.voice.error!!
                    else -> ""
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            AssistantTurn(state.status, viewModel::confirm, viewModel::choose, viewModel::retryAfterPermissions, Modifier.padding(vertical = Spacing.md))
            Waveform(state.voice.level, state.voice.listening)
            MicButton(
                state = when {
                    state.voice.listening -> MicState.LISTENING
                    state.busy -> MicState.BUSY
                    else -> MicState.IDLE
                },
                onClick = viewModel::onMicTapped,
                size = 88.dp,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(1000) },
                    placeholder = { Text("Or type here") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                )
                IconButton(onClick = { viewModel.submit(input); input = "" }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}
