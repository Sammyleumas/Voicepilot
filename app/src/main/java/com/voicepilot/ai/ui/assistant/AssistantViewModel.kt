package com.voicepilot.ai.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicepilot.ai.ai.AiAvailability
import com.voicepilot.ai.ai.AiProviderFactory
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.platform.AndroidCapabilities
import com.voicepilot.ai.platform.AndroidSpeechSynthesizer
import com.voicepilot.ai.voice.WakeEvents
import com.voicepilot.core.conversation.AssistantSession
import com.voicepilot.core.conversation.AssistantStatus
import com.voicepilot.core.conversation.ChatMessage
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.model.Capability
import com.voicepilot.core.planner.ActionDescriber
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.time.Clock
import com.voicepilot.core.voice.RecognitionConfig
import com.voicepilot.core.voice.SpeechError
import com.voicepilot.core.voice.SpeechEvent
import com.voicepilot.core.voice.SpeechRecognitionEngine
import com.voicepilot.core.voice.SpeechSynthesizer
import com.voicepilot.core.voice.WakeDetection
import com.voicepilot.core.voice.WakeWordStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceState(
    val listening: Boolean = false,
    val partial: String = "",
    val level: Float = 0f,
    val error: String? = null,
    val needsMicPermission: Boolean = false,
    /** Incremented to ask the screen to open the phone's own speech popup. */
    val systemDialogRequest: Int = 0,
)

data class AssistantUiState(
    val status: AssistantStatus = AssistantStatus.Idle,
    val voice: VoiceState = VoiceState(),
    val messages: List<ChatMessage> = emptyList(),
    val recent: List<HistoryEntry> = emptyList(),
    val settings: AssistantSettings = AssistantSettings(),
    val ai: AiAvailability = AiAvailability.OFF,
    val speechAvailable: Boolean = true,
    val wake: WakeWordStatus = WakeWordStatus.Disabled,
    val greeting: String = "",
) {
    val busy: Boolean
        get() = status is AssistantStatus.Processing || status is AssistantStatus.Planning || status is AssistantStatus.Executing
}

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val session: AssistantSession,
    private val recognizer: SpeechRecognitionEngine,
    private val synthesizer: SpeechSynthesizer,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository,
    private val aiFactory: AiProviderFactory,
    private val wakeEvents: WakeEvents,
    private val capabilities: AndroidCapabilities,
    private val clock: Clock,
) : ViewModel() {

    private val voice = MutableStateFlow(VoiceState())
    private val ai = MutableStateFlow(AiAvailability.OFF)
    private var listenJob: Job? = null
    private var workJob: Job? = null

    val state: StateFlow<AssistantUiState> = combine(
        combine(session.status, session.messages, ::Pair),
        combine(voice, history.observe(RECENT_LIMIT), ::Pair),
        settingsRepo.settings,
        ai,
        wakeEvents.status,
    ) { (status, messages), (voiceState, recent), settings, aiState, wake ->
        AssistantUiState(
            status = status,
            voice = voiceState,
            messages = messages,
            recent = recent,
            settings = settings,
            ai = aiState,
            speechAvailable = recognizer.isAvailable(),
            wake = wake,
            greeting = greeting(settings.userName),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState(greeting = greeting(settingsRepo.current.userName)))

    /** Wake phrase detections; collected by the visible screen only (see [WakeListenerEffect]). */
    val wakeDetections = wakeEvents.detections

    init {
        refreshAi()
    }

    fun onWake(detection: WakeDetection) {
        val command = detection.trailingCommand
        if (command != null) submit(command, fromVoice = true) else startListening()
    }

    fun screenVisible(visible: Boolean) {
        wakeEvents.foregroundScreens = (wakeEvents.foregroundScreens + if (visible) 1 else -1).coerceAtLeast(0)
    }

    fun refreshAi() {
        viewModelScope.launch { ai.value = aiFactory.availability() }
    }

    fun onMicTapped() {
        when {
            voice.value.listening -> stopListening()
            state.value.busy -> cancel()
            else -> startListening()
        }
    }

    private var dialogRequests = 0

    fun startListening() {
        if (!capabilities.isGranted(Capability.MICROPHONE)) {
            voice.value = VoiceState(needsMicPermission = true, error = SpeechError.PERMISSION_DENIED.userMessage)
            return
        }
        if (settingsRepo.current.useSystemSpeechDialog) {
            synthesizer.stop()
            voice.value = VoiceState(systemDialogRequest = ++dialogRequests)
            return
        }
        synthesizer.stop()
        listenJob?.cancel()
        wakeEvents.setMicBusy(true)
        listenJob = viewModelScope.launch {
            voice.value = VoiceState(listening = true)
            var finalText: String? = null
            try {
                recognizer.listen(RecognitionConfig(languageTag = settingsRepo.current.language)).collect { event ->
                    when (event) {
                        is SpeechEvent.Level -> voice.update { it.copy(level = event.value) }
                        is SpeechEvent.Partial -> voice.update { it.copy(partial = event.text) }
                        is SpeechEvent.Final -> finalText = event.text
                        is SpeechEvent.Error -> voice.value = if (event.error in FALLBACK_ERRORS) {
                            // The in-app recognizer can't run on this phone right now: use the phone's own speech popup.
                            VoiceState(systemDialogRequest = ++dialogRequests)
                        } else {
                            VoiceState(
                                error = event.error.userMessage,
                                needsMicPermission = event.error == SpeechError.PERMISSION_DENIED,
                            )
                        }
                        else -> Unit
                    }
                }
            } finally {
                wakeEvents.setMicBusy(false)
                voice.update { it.copy(listening = false, level = 0f) }
            }
            finalText?.let {
                voice.value = VoiceState()
                submit(it, fromVoice = true)
            }
        }
    }

    /** Result from the phone's speech popup (null when cancelled). */
    fun onSystemDialogResult(text: String?) {
        voice.value = VoiceState()
        if (!text.isNullOrBlank()) submit(text, fromVoice = true)
    }

    fun onSystemDialogUnavailable() {
        voice.value = VoiceState(error = "Voice input isn't available on this phone. Please type your request, or install Google's speech services.")
    }

    fun stopListening() {
        listenJob?.cancel()
        voice.value = VoiceState()
    }

    fun dismissVoiceError() = voice.update { it.copy(error = null, needsMicPermission = false) }

    /** Typed or recognized input. Voice input keeps the conversation hands-free. */
    fun submit(text: String, fromVoice: Boolean = false) {
        if (text.isBlank()) return
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val reply = session.submit(text)
            respond(reply.text, reply.status, fromVoice)
        }
    }

    fun confirm(approved: Boolean) = perform { session.confirm(approved) }
    fun choose(index: Int) = perform { session.choose(index) }
    fun retryAfterPermissions() = perform { session.retryAfterPermissions() }

    fun retry(entry: HistoryEntry) {
        val request = entry.retryRequest ?: return
        perform { session.retry(request, entry.utterance) }
    }

    fun cancel() {
        listenJob?.cancel()
        workJob?.cancel()
        synthesizer.stop()
        session.cancel()
        voice.value = VoiceState()
    }

    fun clearConversation() {
        cancel()
        session.clearConversation()
    }

    fun describe(entry: HistoryEntry): String = entry.retryRequest?.let { ActionDescriber.describe(it) } ?: entry.utterance

    private fun perform(block: suspend () -> com.voicepilot.core.conversation.AssistantReply) {
        workJob?.cancel()
        workJob = viewModelScope.launch {
            val reply = block()
            respond(reply.text, reply.status, fromVoice = false)
        }
    }

    private suspend fun respond(text: String, status: AssistantStatus, fromVoice: Boolean) {
        val s = settingsRepo.current
        if (s.spokenResponses) {
            (synthesizer as? AndroidSpeechSynthesizer)?.languageTag = s.language
            synthesizer.speak(text, s.speechRate, s.voiceName)
        }
        // Keep a spoken conversation going when the assistant asked something.
        val expectsAnswer = status is AssistantStatus.AwaitingInput ||
            status is AssistantStatus.AwaitingConfirmation ||
            status is AssistantStatus.AwaitingChoice
        if (fromVoice && expectsAnswer) startListening()
    }

    private fun greeting(name: String): String {
        val hour = clock.localNow().hour
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        return if (name.isBlank()) "$part." else "$part, $name."
    }

    override fun onCleared() {
        synthesizer.stop()
        wakeEvents.setMicBusy(false)
        super.onCleared()
    }

    private companion object {
        val FALLBACK_ERRORS = setOf(SpeechError.BUSY, SpeechError.CLIENT, SpeechError.UNAVAILABLE, SpeechError.LANGUAGE_UNAVAILABLE)
        const val RECENT_LIMIT = 5
    }
}
