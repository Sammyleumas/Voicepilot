package com.voicepilot.ai.ai

import com.voicepilot.ai.BuildConfig
import com.voicepilot.ai.data.SecretStore
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.interpret.CommandInterpreter
import com.voicepilot.core.interpret.LlmCommandInterpreter
import com.voicepilot.core.llm.CloudProxyProvider
import com.voicepilot.core.llm.GeminiProvider
import com.voicepilot.core.llm.HttpTransport
import com.voicepilot.core.llm.LlmProvider
import com.voicepilot.core.llm.TokenUsage
import com.voicepilot.core.settings.AiProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Token usage reported by the provider during this app session. */
data class TokenStats(val requests: Int = 0, val promptTokens: Int = 0, val outputTokens: Int = 0)

enum class AiAvailability(val label: String) {
    OFF("On-device commands only"),
    NOT_CONFIGURED("AI provider not set up"),
    READY("AI ready"),
}

/** Builds the AI interpreter that matches the user's current settings. */
@Singleton
class AiProviderFactory @Inject constructor(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val transport: HttpTransport,
    private val registry: ActionRegistry,
) {
    private val _stats = MutableStateFlow(TokenStats())
    val stats: StateFlow<TokenStats> = _stats.asStateFlow()

    private val gemini = GeminiProvider(
        transport = transport,
        apiKey = { secrets.get(SecretStore.Secret.GEMINI_API_KEY) },
        model = { settings.current.aiModel },
    )

    private val cloud = CloudProxyProvider(
        transport = transport,
        baseUrl = { settings.current.cloudBaseUrl.ifBlank { BuildConfig.CLOUD_BASE_URL } },
        authToken = { secrets.get(SecretStore.Secret.CLOUD_TOKEN) },
        model = { settings.current.aiModel },
    )

    fun provider(type: AiProviderType = settings.current.aiProvider): LlmProvider? = when (type) {
        AiProviderType.LOCAL_ONLY -> null
        AiProviderType.GEMINI_DIRECT -> gemini
        AiProviderType.VOICEPILOT_CLOUD -> cloud
    }

    suspend fun availability(): AiAvailability {
        val p = provider() ?: return AiAvailability.OFF
        return if (p.isConfigured()) AiAvailability.READY else AiAvailability.NOT_CONFIGURED
    }

    /** Null when AI is off or not configured; the hybrid interpreter then stays on-device. */
    suspend fun interpreter(): CommandInterpreter? {
        val p = provider() ?: return null
        if (!p.isConfigured()) return null
        return LlmCommandInterpreter(p, registry, ::record)
    }

    private fun record(usage: TokenUsage) {
        _stats.update { it.copy(requests = it.requests + 1, promptTokens = it.promptTokens + usage.promptTokens, outputTokens = it.outputTokens + usage.outputTokens) }
    }
}
