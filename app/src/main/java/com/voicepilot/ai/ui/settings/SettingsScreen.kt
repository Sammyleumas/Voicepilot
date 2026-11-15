package com.voicepilot.ai.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.BuildConfig
import com.voicepilot.ai.ai.AiAvailability
import com.voicepilot.ai.data.SecretStore
import com.voicepilot.ai.platform.AndroidCapabilities
import com.voicepilot.ai.ui.assistant.rememberCapabilityRequester
import com.voicepilot.ai.ui.assistant.startSafely
import com.voicepilot.ai.ui.components.ChoiceDialog
import com.voicepilot.ai.ui.components.ConfirmDialog
import com.voicepilot.ai.ui.components.GroupCard
import com.voicepilot.ai.ui.components.SectionHeader
import com.voicepilot.ai.ui.components.SettingRow
import com.voicepilot.ai.ui.components.SwitchRow
import com.voicepilot.ai.ui.components.TextInputDialog
import com.voicepilot.ai.ui.components.VpTopBar
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.ai.widget.HomeScreenShortcuts
import com.voicepilot.core.action.BuiltInActions
import com.voicepilot.core.model.Capability
import com.voicepilot.core.settings.AiProviderType
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.settings.ConfirmationMode
import com.voicepilot.core.settings.MessagingChannel
import com.voicepilot.core.settings.ResponseStyle
import com.voicepilot.core.settings.ThemePreference
import com.voicepilot.core.voice.WakeWordStatus

private enum class SettingsDialog {
    NAME, ASSISTANT_NAME, LANGUAGE, THEME, STYLE, SPEED, VOICE, PROVIDER, MODEL, GEMINI_KEY, CLOUD_URL, CLOUD_TOKEN,
    CONFIRMATION, RETENTION, COUNTRY, MESSAGING, CLEAR_HISTORY, CLEAR_MEMORY, CLEAR_NOTES, CLEAR_ALL,
}

private val LANGUAGES = listOf("en-NG" to "English (Nigeria)", "en-GB" to "English (UK)", "en-US" to "English (US)", "en-GH" to "English (Ghana)", "en-KE" to "English (Kenya)")

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenIntegrations: () -> Unit,
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val secrets by viewModel.secretsPresent.collectAsStateWithLifecycle()
    val ai by viewModel.aiAvailability.collectAsStateWithLifecycle()
    val tokens by viewModel.tokenStats.collectAsStateWithLifecycle()
    val usage by viewModel.usageSnapshot.collectAsStateWithLifecycle()
    val caps by viewModel.capabilities.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val entitlements by viewModel.entitlements.collectAsStateWithLifecycle()
    val wake by viewModel.wakeStatus.collectAsStateWithLifecycle()
    val exactAlarms by viewModel.exactAlarms.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val request = rememberCapabilityRequester { viewModel.refresh() }
    val capabilities = remember(context) { AndroidCapabilities(context.applicationContext) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }

    Scaffold(
        topBar = { VpTopBar("Settings", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
        ) {
            SectionHeader("General")
            GroupCard {
                SettingRow("Your name", s.userName.ifBlank { "Not set" }, Icons.Filled.AccountCircle, onClick = { dialog = SettingsDialog.NAME })
                SettingRow("Assistant name", s.assistantName, Icons.Filled.Badge, onClick = { dialog = SettingsDialog.ASSISTANT_NAME })
                SettingRow("Language", LANGUAGES.firstOrNull { it.first == s.language }?.second ?: s.language, Icons.Filled.Language, onClick = { dialog = SettingsDialog.LANGUAGE })
                SettingRow("Theme", s.theme.label, Icons.Filled.DarkMode, onClick = { dialog = SettingsDialog.THEME })
                SettingRow("Response style", s.responseStyle.label, Icons.Filled.Tune, onClick = { dialog = SettingsDialog.STYLE })
                SettingRow("Country calling code", "+${s.defaultCountryCode} — used to complete local numbers", Icons.Filled.Phone, onClick = { dialog = SettingsDialog.COUNTRY })
            }

            SectionHeader("Voice")
            GroupCard {
                SettingRow(
                    "Microphone",
                    if (caps[Capability.MICROPHONE] == true) "Allowed while you use SL" else "Not allowed — you can still type",
                    Icons.Filled.Mic,
                    onClick = { if (caps[Capability.MICROPHONE] == true) context.startSafely(capabilities.appDetailsSettings()) else request(setOf(Capability.MICROPHONE)) },
                )
                SwitchRow("Spoken responses", "Read replies aloud", s.spokenResponses, { v -> viewModel.update { it.copy(spokenResponses = v) } }, Icons.AutoMirrored.Filled.VolumeUp)
                SettingRow("Voice", s.voiceName ?: "System default", Icons.Filled.RecordVoiceOver, onClick = { viewModel.loadVoices(); dialog = SettingsDialog.VOICE })
                SwitchRow(
                    "Use the phone's speech popup",
                    "Turn on if voice input says the recognizer is busy. SL also switches automatically when it has to.",
                    s.useSystemSpeechDialog,
                    { v -> viewModel.update { it.copy(useSystemSpeechDialog = v) } },
                    Icons.Filled.Mic,
                )
                SettingRow("Speech speed", "${"%.1f".format(s.speechRate)}×", Icons.Filled.Speed, onClick = { dialog = SettingsDialog.SPEED })
                SwitchRow(
                    "Hands-free mode",
                    when (wake) {
                        WakeWordStatus.Listening -> "Listening for “${s.wakePhrase}” · stops after 30 min or when the screen turns off"
                        WakeWordStatus.Paused -> "Paused while you speak"
                        is WakeWordStatus.Unavailable -> (wake as WakeWordStatus.Unavailable).reason
                        WakeWordStatus.Disabled -> "Say “Hey SL” without touching the phone. Uses more battery."
                    },
                    wake == WakeWordStatus.Listening || wake == WakeWordStatus.Paused,
                    { enabled ->
                        if (enabled && caps[Capability.MICROPHONE] != true) request(setOf(Capability.MICROPHONE))
                        else viewModel.setHandsFree(enabled)
                    },
                    Icons.Filled.RecordVoiceOver,
                )
                SettingRow(
                    "Add Talk button to home screen",
                    "A small round SL mic button. Tap it to start talking.",
                    Icons.Filled.Widgets,
                    onClick = {
                        val added = HomeScreenShortcuts.pinWidget(context) || HomeScreenShortcuts.pinShortcut(context)
                        if (!added) viewModel.showMessage("Your launcher can't add it automatically. Long-press the home screen, choose Widgets, and drag “Talk to SL” onto it.")
                    },
                )
                SettingRow(
                    "Add “Talk to SL” icon",
                    "An app-style shortcut icon on your home screen",
                    Icons.Filled.Mic,
                    onClick = {
                        if (!HomeScreenShortcuts.pinShortcut(context)) {
                            viewModel.showMessage("Your launcher can't add shortcuts automatically. Long-press the SL app icon and drag “Talk” to your home screen.")
                        }
                    },
                )
                SettingRow(
                    "Default assistant",
                    "Make SL open with the assist gesture or long-press Home",
                    Icons.Filled.Widgets,
                    onClick = { context.startSafely(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                )
            }

            SectionHeader("AI")
            GroupCard {
                SettingRow(
                    "AI provider",
                    "${s.aiProvider.label} · ${aiStatusText(ai)}",
                    Icons.Filled.AutoAwesome,
                    onClick = { dialog = SettingsDialog.PROVIDER },
                )
                if (s.aiProvider != AiProviderType.LOCAL_ONLY) {
                    SettingRow("Model", s.aiModel, Icons.Filled.Tune, onClick = { dialog = SettingsDialog.MODEL })
                }
                if (s.aiProvider == AiProviderType.GEMINI_DIRECT) {
                    SettingRow(
                        "Gemini API key",
                        if (secrets[SecretStore.Secret.GEMINI_API_KEY] == true) "Stored encrypted on this device" else "Not set — get one from Google AI Studio",
                        Icons.Filled.Key,
                        onClick = { dialog = SettingsDialog.GEMINI_KEY },
                    )
                }
                if (s.aiProvider == AiProviderType.VOICEPILOT_CLOUD) {
                    SettingRow("Cloud URL", s.cloudBaseUrl.ifBlank { BuildConfig.CLOUD_BASE_URL.ifBlank { "Not configured" } }, Icons.Filled.Cloud, onClick = { dialog = SettingsDialog.CLOUD_URL })
                    SettingRow(
                        "Access token",
                        if (secrets[SecretStore.Secret.CLOUD_TOKEN] == true) "Stored encrypted on this device" else "Not set",
                        Icons.Filled.Key,
                        onClick = { dialog = SettingsDialog.CLOUD_TOKEN },
                    )
                }
                SettingRow(
                    "Usage today",
                    "${usage.used} of ${usage.limit} AI requests · this session: ${tokens.requests} requests, ${tokens.promptTokens + tokens.outputTokens} tokens",
                    Icons.Filled.Speed,
                )
            }

            SectionHeader("Automation")
            GroupCard {
                SettingRow("Default messaging app", "${s.defaultMessagingApp.label} — used when you don't name an app", Icons.Filled.Phone, onClick = { dialog = SettingsDialog.MESSAGING })
                SettingRow("Ask before running", s.confirmationMode.label, Icons.Filled.VerifiedUser, onClick = { dialog = SettingsDialog.CONFIRMATION })
                SwitchRow(
                    "Send replies without asking",
                    if (s.confirmationMode == ConfirmationMode.NEVER) {
                        "Replies to messages are sent as soon as you say them. Only turn on if you trust what SL hears."
                    } else {
                        "Available when “Ask before running” is set to Never ask."
                    },
                    s.sendWithoutAsking && s.confirmationMode == ConfirmationMode.NEVER,
                    { v -> viewModel.update { it.copy(sendWithoutAsking = v) } },
                    Icons.Filled.Security,
                    enabled = s.confirmationMode == ConfirmationMode.NEVER,
                )
                SettingRow("Integrations", "${BuiltInActions.all.size - s.disabledActions.size} of ${BuiltInActions.all.size} actions enabled", Icons.Filled.Widgets, onClick = onOpenIntegrations)
                SettingRow("Routines", "Multi-step workflows you start with a phrase", Icons.AutoMirrored.Filled.PlaylistPlay, onClick = onOpenRoutines)
                SettingRow("Notification assistant", if (s.notificationAssistantEnabled) "On" else "Off", Icons.Filled.Notifications, onClick = onOpenNotifications)
                PermissionRows(caps, exactAlarms, onGrant = { request(setOf(it)) }, onExactAlarms = { context.startSafely(capabilities.exactAlarmSettings()) })
            }

            SectionHeader("Privacy")
            GroupCard {
                SwitchRow("Save command history", "Stored only on this device", s.historyEnabled, { v -> viewModel.update { it.copy(historyEnabled = v) } }, Icons.Filled.History)
                SettingRow("Keep history for", "${s.historyRetentionDays} days", Icons.Filled.History, onClick = { dialog = SettingsDialog.RETENTION })
                SwitchRow("Personal memory", "Remember nicknames like “my brother”", s.memoryEnabled, { v -> viewModel.update { it.copy(memoryEnabled = v) } }, Icons.Filled.Memory)
                SettingRow("Manage memory", null, Icons.Filled.Memory, onClick = onOpenMemory)
                SettingRow("Clear history", null, Icons.Filled.DeleteForever, onClick = { dialog = SettingsDialog.CLEAR_HISTORY })
                SettingRow("Clear memory", null, Icons.Filled.DeleteForever, onClick = { dialog = SettingsDialog.CLEAR_MEMORY })
                SettingRow("Delete notes and tasks", null, Icons.Filled.DeleteForever, onClick = { dialog = SettingsDialog.CLEAR_NOTES })
                SettingRow("Delete all SL data", "History, memory, notes, cached notifications and stored keys", Icons.Filled.Security, onClick = { dialog = SettingsDialog.CLEAR_ALL })
            }

            SectionHeader("Subscription")
            GroupCard {
                SettingRow(
                    "${entitlements.plan.label} plan",
                    "${entitlements.dailyAiRequests} AI requests/day · up to ${entitlements.maxPlanSteps} steps per request",
                    Icons.Filled.Star,
                    onClick = onOpenSubscription,
                )
            }

            SectionHeader("About")
            Text(
                "SL ${BuildConfig.VERSION_NAME}. On-device commands work offline. AI requests send only your command text to the provider you chose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xxl),
            )
        }
    }

    SettingsDialogs(dialog, s, voices.map { it.name }, viewModel) { dialog = null }
}

@Composable
private fun PermissionRows(caps: Map<Capability, Boolean>, exactAlarms: Boolean, onGrant: (Capability) -> Unit, onExactAlarms: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    listOf(Capability.CONTACTS, Capability.POST_NOTIFICATIONS, Capability.DND_POLICY).forEach { cap ->
        val granted = caps[cap] == true
        SettingRow(
            cap.displayName,
            AndroidCapabilities.DESCRIPTIONS[cap],
            Icons.Filled.Security,
            onClick = if (granted) null else ({ onGrant(cap) }),
            trailing = { Text(if (granted) "Allowed" else "Allow", color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) },
        )
    }
    SettingRow(
        "Exact reminder times",
        if (exactAlarms) "Allowed" else "Reminders may arrive a few minutes late",
        Icons.Filled.Security,
        onClick = if (exactAlarms) null else onExactAlarms,
        trailing = { Text(if (exactAlarms) "Allowed" else "Allow", color = if (exactAlarms) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) },
    )
}

private fun aiStatusText(ai: AiAvailability) = when (ai) {
    AiAvailability.OFF -> "offline commands only"
    AiAvailability.NOT_CONFIGURED -> "needs setup"
    AiAvailability.READY -> "ready"
}

@Composable
private fun SettingsDialogs(
    dialog: SettingsDialog?,
    s: AssistantSettings,
    voices: List<String>,
    vm: SettingsViewModel,
    close: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        SettingsDialog.NAME -> TextInputDialog("Your name", s.userName, close, { v -> vm.update { it.copy(userName = v) }; close() }, maxLength = 40)
        SettingsDialog.ASSISTANT_NAME -> TextInputDialog("Assistant name", s.assistantName, close, { v -> vm.update { it.copy(assistantName = v.ifBlank { "SL" }) }; close() }, maxLength = 30)
        SettingsDialog.COUNTRY -> TextInputDialog("Country calling code", s.defaultCountryCode, close, { v -> vm.update { it.copy(defaultCountryCode = v.filter(Char::isDigit).ifBlank { "234" }) }; close() }, keyboardType = KeyboardType.Number, supporting = "234 for Nigeria, 44 for the UK, 1 for the US.", maxLength = 4)
        SettingsDialog.LANGUAGE -> ChoiceDialog("Language", LANGUAGES.map { it.first }, s.language, { tag -> LANGUAGES.first { it.first == tag }.second }, close, { v -> vm.update { it.copy(language = v) }; close() })
        SettingsDialog.THEME -> ChoiceDialog("Theme", ThemePreference.entries, s.theme, { it.label }, close, { v -> vm.update { it.copy(theme = v) }; close() })
        SettingsDialog.STYLE -> ChoiceDialog("Response style", ResponseStyle.entries, s.responseStyle, { it.label }, close, { v -> vm.update { it.copy(responseStyle = v) }; close() })
        SettingsDialog.SPEED -> ChoiceDialog("Speech speed", listOf(0.75f, 1.0f, 1.25f, 1.5f), s.speechRate, { "${it}×" }, close, { v -> vm.update { it.copy(speechRate = v) }; vm.previewVoice(); close() })
        SettingsDialog.VOICE -> ChoiceDialog(
            "Voice", listOf<String?>(null) + voices, s.voiceName, { it ?: "System default" }, close,
            { v -> vm.update { it.copy(voiceName = v) }; vm.previewVoice(); close() },
        )
        SettingsDialog.PROVIDER -> ChoiceDialog(
            "AI provider", AiProviderType.entries, s.aiProvider, { it.label }, close, { v -> vm.update { it.copy(aiProvider = v) }; close() },
            description = {
                when (it) {
                    AiProviderType.LOCAL_ONLY -> "Private and offline. Understands common commands."
                    AiProviderType.GEMINI_DIRECT -> "Flexible requests using your own Google Gemini API key."
                    AiProviderType.VOICEPILOT_CLOUD -> "Uses a SL backend that keeps the provider key on the server."
                }
            },
        )
        SettingsDialog.MODEL -> ChoiceDialog("Model", AssistantSettings.GEMINI_MODELS, s.aiModel, { it }, close, { v -> vm.update { it.copy(aiModel = v) }; close() })
        SettingsDialog.GEMINI_KEY -> TextInputDialog(
            "Gemini API key", "", close, { v -> vm.setSecret(SecretStore.Secret.GEMINI_API_KEY, v); close() },
            secret = true,
            supporting = "Encrypted with the Android Keystore and only sent to Google. Leave empty and save to remove it.",
        )
        SettingsDialog.CLOUD_URL -> TextInputDialog("Cloud URL", s.cloudBaseUrl, close, { v -> vm.update { it.copy(cloudBaseUrl = v) }; close() }, keyboardType = KeyboardType.Uri, supporting = "HTTPS address of your SL backend.")
        SettingsDialog.CLOUD_TOKEN -> TextInputDialog("Access token", "", close, { v -> vm.setSecret(SecretStore.Secret.CLOUD_TOKEN, v); close() }, secret = true, maxLength = 4000)
        SettingsDialog.CONFIRMATION -> ChoiceDialog(
            "Ask before running", ConfirmationMode.entries, s.confirmationMode, { it.label }, close, { v -> vm.update { it.copy(confirmationMode = v) }; close() },
            description = {
                when (it) {
                    ConfirmationMode.ALWAYS -> "Shows a preview of every action first."
                    ConfirmationMode.MEDIUM_AND_HIGH -> "Recommended. Messages, calls and calendar events show a preview; simple actions run straight away."
                    ConfirmationMode.HIGH_ONLY -> "Only actions that send something for you ask first."
                    ConfirmationMode.NEVER -> "Everything runs as soon as you say it. Sending replies still asks unless you also turn on “Send replies without asking”."
                }
            },
        )
        SettingsDialog.MESSAGING -> ChoiceDialog(
            "Default messaging app", MessagingChannel.entries, s.defaultMessagingApp, { it.label }, close, { v -> vm.update { it.copy(defaultMessagingApp = v) }; close() },
            description = { "Say “on Telegram”, “by SMS” or another app's name to choose a different one each time." },
        )
        SettingsDialog.RETENTION -> ChoiceDialog("Keep history for", listOf(1, 7, 30, 90, 365), s.historyRetentionDays, { "$it days" }, close, { v -> vm.update { it.copy(historyRetentionDays = v) }; close() })
        SettingsDialog.CLEAR_HISTORY -> ConfirmDialog("Clear history?", "All command history on this device will be deleted.", "Clear", close, vm::clearHistory)
        SettingsDialog.CLEAR_MEMORY -> ConfirmDialog("Clear memory?", "Nicknames and preferences SL remembered will be deleted.", "Clear", close, vm::clearMemory)
        SettingsDialog.CLEAR_NOTES -> ConfirmDialog("Delete notes and tasks?", "This can't be undone.", "Delete", close, vm::clearNotes)
        SettingsDialog.CLEAR_ALL -> ConfirmDialog("Delete all data?", "History, memory, notes, tasks, cached notifications and stored keys will be deleted from this device.", "Delete everything", close, vm::clearAllData)
    }
}
