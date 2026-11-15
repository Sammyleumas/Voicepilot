package com.voicepilot.ai.ui.onboarding

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.data.SecretStore
import com.voicepilot.ai.ui.assistant.rememberCapabilityRequester
import com.voicepilot.ai.ui.assistant.startSafely
import com.voicepilot.ai.ui.components.PrimaryButton
import com.voicepilot.ai.ui.components.SecondaryButton
import com.voicepilot.ai.ui.components.VpCard
import com.voicepilot.ai.ui.settings.SettingsViewModel
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.core.model.Capability
import com.voicepilot.core.settings.AiProviderType

private const val STEPS = 8

/**
 * First-run flow. Permissions are explained before they're requested, every
 * step can be skipped, and only the microphone is asked for here.
 */
@Composable
fun OnboardingScreen(viewModel: SettingsViewModel, onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val caps by viewModel.capabilities.collectAsStateWithLifecycle()
    val next = { step = (step + 1).coerceAtMost(STEPS - 1) }
    val finish = {
        viewModel.update { it.copy(onboardingComplete = true) }
        onFinished()
    }
    BackHandler(enabled = step > 0) { step-- }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        // Progress
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEPS) { i ->
                Box(
                    Modifier.weight(1f).height(3.dp).background(
                        if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
                )
            }
        }
        Box(Modifier.weight(1f).semantics { contentDescription = "Step ${step + 1} of $STEPS" }) {
            AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboarding") { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 560.dp)) {
                        when (s) {
                            0 -> Welcome(next)
                            1 -> Features(next)
                            2 -> Privacy(next)
                            3 -> VoiceStep(caps[Capability.MICROPHONE] == true, viewModel::refresh, next)
                            4 -> AboutYou(settings.userName, { name -> viewModel.update { it.copy(userName = name) } }, next)
                            5 -> AiStep(settings.aiProvider, { p -> viewModel.update { it.copy(aiProvider = p) } }, { key -> viewModel.setSecret(SecretStore.Secret.GEMINI_API_KEY, key) }, next)
                            6 -> AssistantStep(next)
                            else -> Done(finish)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Header(icon: ImageVector, title: String, body: String) {
    Spacer(Modifier.height(Spacing.xxl))
    Box(
        Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp)) }
    Spacer(Modifier.height(Spacing.xl))
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.sm))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun Bullet(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.Actions(primary: String, onPrimary: () -> Unit, secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Spacer(Modifier.height(Spacing.xl))
    PrimaryButton(primary, onPrimary)
    if (secondary != null && onSecondary != null) {
        Spacer(Modifier.height(Spacing.sm))
        TextButton(onClick = onSecondary, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(secondary) }
    }
    Spacer(Modifier.height(Spacing.xl))
}

@Composable
private fun ColumnScope.Welcome(next: () -> Unit) {
    Spacer(Modifier.height(Spacing.xxl))
    Text("VOICEPILOT AI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
    Header(Icons.Filled.Mic, "Your phone should understand you", "Speak naturally. VoicePilot turns what you say into actions on your phone — and always tells you exactly what it did.")
    Actions("Get started", next)
}

@Composable
private fun ColumnScope.Features(next: () -> Unit) {
    Header(Icons.Filled.AutoAwesome, "What I can do", "Works offline for everyday commands. Add AI for anything more flexible.")
    VpCard {
        Bullet(Icons.Filled.Apps, "Apps & settings", "“Open YouTube”, “Open Wi‑Fi settings”, “Turn on the flashlight”")
        Bullet(Icons.Filled.Alarm, "Time", "“Wake me at 6:30”, “Start a 10 minute timer”, “Remind me to call Mum at 6pm”")
        Bullet(Icons.AutoMirrored.Filled.Chat, "WhatsApp & calls", "“Send Samuel a WhatsApp message saying I'm on my way” — you tap Send")
        Bullet(Icons.AutoMirrored.Filled.PlaylistPlay, "Routines", "“I'm going to sleep” turns on Do Not Disturb and sets your alarm")
    }
    Actions("Continue", next)
}

@Composable
private fun ColumnScope.Privacy(next: () -> Unit) {
    Header(Icons.Filled.Shield, "Private by design", "You stay in control of what VoicePilot can see and do.")
    VpCard {
        Bullet(Icons.Filled.PhoneAndroid, "On-device first", "History, notes, memory and contacts stay on this phone. Nothing is backed up to the cloud.")
        Bullet(Icons.Filled.AutoAwesome, "AI is optional", "If you turn it on, only the text of your command is sent to the provider you choose.")
        Bullet(Icons.Filled.TouchApp, "You approve sensitive actions", "Messages, calendar events and other sensitive steps always show you a preview first.")
        Bullet(Icons.Filled.Lock, "Permissions when needed", "VoicePilot asks for access only when you use a feature, and explains why.")
    }
    Actions("I understand", next)
}

@Composable
private fun ColumnScope.VoiceStep(granted: Boolean, refresh: () -> Unit, next: () -> Unit) {
    val request = rememberCapabilityRequester { refresh() }
    Header(Icons.Filled.Mic, "Talk to VoicePilot", "The microphone is used only while you're talking to VoicePilot, and Android shows an indicator whenever it's on.")
    if (granted) {
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.sm))
            Text("Microphone allowed")
        }
        Actions("Continue", next)
    } else {
        Actions("Allow microphone", { request(setOf(Capability.MICROPHONE)) }, "Skip — I'll type instead", next)
    }
}

@Composable
private fun ColumnScope.AboutYou(current: String, save: (String) -> Unit, next: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    Header(Icons.Filled.PhoneAndroid, "What should I call you?", "Used for greetings only. You can change it later.")
    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Your first name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Actions("Continue", { save(name.trim()); next() }, "Skip", next)
}

@Composable
private fun ColumnScope.AiStep(current: AiProviderType, choose: (AiProviderType) -> Unit, saveKey: (String) -> Unit, next: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    Header(Icons.Filled.AutoAwesome, "Choose how I understand you", "Start on-device and add AI any time in Settings.")
    listOf(
        AiProviderType.LOCAL_ONLY to "Private, works offline, understands common commands. Recommended to start.",
        AiProviderType.GEMINI_DIRECT to "Understands free-form requests using your own Google Gemini API key.",
    ).forEach { (type, body) ->
        VpCard(Modifier.padding(vertical = Spacing.xs).selectable(selected = current == type, role = Role.RadioButton) { choose(type) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = current == type, onClick = null)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(type.label, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (current == AiProviderType.GEMINI_DIRECT) {
        Spacer(Modifier.height(Spacing.md))
        OutlinedTextField(
            key, { key = it.trim().take(200) },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Stored encrypted with the Android Keystore. Sent only to Google.") },
        )
    }
    Actions("Continue", { if (key.isNotBlank()) saveKey(key); next() })
}

@Composable
private fun ColumnScope.AssistantStep(next: () -> Unit) {
    val context = LocalContext.current
    Header(Icons.Filled.TouchApp, "Open VoicePilot faster", "Choose the ways you'd like to start talking.")
    VpCard {
        Bullet(Icons.Filled.PhoneAndroid, "Default assistant", "Long-press Home or use the assist gesture to open VoicePilot. Set it in Android's assistant settings.")
        Spacer(Modifier.height(Spacing.sm))
        SecondaryButton("Open assistant settings", onClick = { context.startSafely(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) })
        Spacer(Modifier.height(Spacing.md))
        Bullet(Icons.Filled.Apps, "Quick Settings tile", "Add “Talk to VoicePilot” from the Quick Settings edit menu.")
        Bullet(Icons.Filled.Mic, "Hands-free “Hey VoicePilot”", "Optional, in Settings › Voice. Works while the screen is on and stops after 30 minutes to save battery.")
    }
    Actions("Continue", next)
}

@Composable
private fun ColumnScope.Done(finish: () -> Unit) {
    Header(Icons.Filled.CheckCircle, "You're all set", "Tap the microphone and try one of these:")
    VpCard {
        listOf("“Set an alarm for 6:30 am”", "“Open WhatsApp”", "“Remind me to drink water in 30 minutes”", "“What can you do?”").forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.xs))
        }
    }
    Actions("Start using VoicePilot", finish)
}
