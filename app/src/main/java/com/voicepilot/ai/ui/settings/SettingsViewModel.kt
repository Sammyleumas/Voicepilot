package com.voicepilot.ai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicepilot.ai.ai.AiAvailability
import com.voicepilot.ai.ai.AiProviderFactory
import com.voicepilot.ai.ai.TokenStats
import com.voicepilot.ai.billing.SubscriptionRepository
import com.voicepilot.ai.data.NotesRepository
import com.voicepilot.ai.data.SecretStore
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.notifications.NotificationHub
import com.voicepilot.ai.platform.AndroidAppCatalog
import com.voicepilot.ai.platform.AndroidCapabilities
import com.voicepilot.ai.voice.HandsFreeService
import com.voicepilot.ai.voice.WakeEvents
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.integrations.InstalledApp
import com.voicepilot.core.memory.MemoryRepository
import com.voicepilot.core.model.Capability
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.subscription.Entitlements
import com.voicepilot.core.subscription.UsageSnapshot
import com.voicepilot.core.subscription.UsageTracker
import com.voicepilot.core.voice.SpeechSynthesizer
import com.voicepilot.core.voice.VoiceOption
import com.voicepilot.core.voice.WakeWordStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared by Settings, Onboarding and the privacy/automation sub-screens. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    private val ai: AiProviderFactory,
    private val usage: UsageTracker,
    private val subscription: SubscriptionRepository,
    private val history: HistoryRepository,
    private val memory: MemoryRepository,
    private val notes: NotesRepository,
    private val capabilitiesChecker: AndroidCapabilities,
    private val synthesizer: SpeechSynthesizer,
    private val apps: AndroidAppCatalog,
    private val wakeEvents: WakeEvents,
    val notificationHub: NotificationHub,
) : ViewModel() {

    val settings: StateFlow<AssistantSettings> = settingsRepo.settings
    val secretsPresent: StateFlow<Map<SecretStore.Secret, Boolean>> = secrets.present
    val tokenStats: StateFlow<TokenStats> = ai.stats
    val entitlements: StateFlow<Entitlements> = subscription.entitlements
    val wakeStatus: StateFlow<WakeWordStatus> = wakeEvents.status
    val billingAvailable: Boolean get() = subscription.billing.isAvailable

    private val _aiAvailability = MutableStateFlow(AiAvailability.OFF)
    val aiAvailability: StateFlow<AiAvailability> = _aiAvailability.asStateFlow()

    private val _usage = MutableStateFlow(UsageSnapshot(0, entitlements.value.dailyAiRequests))
    val usageSnapshot: StateFlow<UsageSnapshot> = _usage.asStateFlow()

    private val _capabilities = MutableStateFlow<Map<Capability, Boolean>>(emptyMap())
    val capabilities: StateFlow<Map<Capability, Boolean>> = _capabilities.asStateFlow()

    private val _exactAlarms = MutableStateFlow(true)
    val exactAlarms: StateFlow<Boolean> = _exactAlarms.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads permission and provider status; call when a screen resumes. */
    fun refresh() {
        _capabilities.value = Capability.entries.associateWith(capabilitiesChecker::isGranted)
        _exactAlarms.value = capabilitiesChecker.canScheduleExactAlarms()
        viewModelScope.launch {
            _aiAvailability.value = ai.availability()
            _usage.value = usage.snapshot()
        }
    }

    fun update(transform: (AssistantSettings) -> AssistantSettings) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            _aiAvailability.value = ai.availability()
        }
    }

    fun setSecret(secret: SecretStore.Secret, value: String) {
        viewModelScope.launch {
            secrets.put(secret, value)
            _aiAvailability.value = ai.availability()
            _message.value = if (value.isBlank()) "Removed." else "Saved securely on this device."
        }
    }

    fun loadVoices() {
        viewModelScope.launch { _voices.value = synthesizer.voices() }
    }

    fun previewVoice() {
        viewModelScope.launch {
            val s = settings.value
            synthesizer.speak("Hi${if (s.userName.isNotBlank()) " ${s.userName}" else ""}, I'm ${s.assistantName}.", s.speechRate, s.voiceName)
        }
    }

    fun loadApps() {
        viewModelScope.launch { _installedApps.value = apps.launchableApps() }
    }

    fun setHandsFree(enabled: Boolean) {
        update { it.copy(wakeWordEnabled = enabled) }
        if (enabled) HandsFreeService.start(context) else HandsFreeService.stop(context)
    }

    fun clearHistory() = launchWithMessage("History cleared.") { history.clear() }
    fun clearMemory() = launchWithMessage("Memory cleared.") { memory.clear() }
    fun clearNotes() = launchWithMessage("Notes and tasks deleted.") { notes.clearAll() }
    fun clearNotificationCache() = launchWithMessage("Notification cache cleared.") { notificationHub.clear() }

    fun clearAllData() = launchWithMessage("All local data deleted.") {
        history.clear(); memory.clear(); notes.clearAll(); notificationHub.clear()
        SecretStore.Secret.entries.forEach { secrets.clear(it) }
    }

    fun consumeMessage() { _message.value = null }

    /** Starts the store purchase flow; only offered when billing is configured. */
    fun startPurchase() {
        viewModelScope.launch {
            if (!subscription.billing.launchPurchase(PREMIUM_PRODUCT_ID)) _message.value = "Purchases aren't available yet."
        }
    }

    private companion object {
        const val PREMIUM_PRODUCT_ID = "voicepilot_premium_monthly"
    }

    private fun launchWithMessage(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            _message.value = message
        }
    }
}
