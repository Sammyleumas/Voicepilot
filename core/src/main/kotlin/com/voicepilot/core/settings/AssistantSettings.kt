package com.voicepilot.core.settings

import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.RiskLevel

/** Which risk levels require explicit approval before running. HIGH always does. */
enum class ConfirmationMode(val label: String) {
    ALWAYS("Every action"),
    MEDIUM_AND_HIGH("Messages, events and other sensitive actions"),
    HIGH_ONLY("Only high-risk actions"),
}

enum class AiProviderType(val label: String) {
    /** Only offline, on-device rule matching. */
    LOCAL_ONLY("On-device only"),
    /** Gemini called directly with a key the user supplies (stored encrypted on-device). */
    GEMINI_DIRECT("Gemini (your API key)"),
    /** VoicePilot backend proxy that holds the provider key server-side. */
    VOICEPILOT_CLOUD("VoicePilot Cloud"),
}

/** Where "send X a message" goes when the user doesn't name an app. */
enum class MessagingChannel(val label: String) { WHATSAPP("WhatsApp"), SMS("SMS (text message)") }

enum class ResponseStyle(val label: String) { CONCISE("Concise"), FRIENDLY("Friendly"), DETAILED("Detailed") }

enum class ThemePreference(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

/**
 * User-controlled settings that influence assistant behaviour. Persisted by the
 * app (DataStore); kept here so domain logic stays testable.
 */
data class AssistantSettings(
    val userName: String = "",
    val assistantName: String = "VoicePilot",
    val language: String = "en-NG",
    val theme: ThemePreference = ThemePreference.DARK,
    val responseStyle: ResponseStyle = ResponseStyle.FRIENDLY,
    val spokenResponses: Boolean = true,
    val speechRate: Float = 1.0f,
    val voiceName: String? = null,
    val wakeWordEnabled: Boolean = false,
    val wakePhrase: String = "hey voicepilot",
    val aiProvider: AiProviderType = AiProviderType.LOCAL_ONLY,
    val aiModel: String = DEFAULT_GEMINI_MODEL,
    val cloudBaseUrl: String = "",
    val confirmationMode: ConfirmationMode = ConfirmationMode.MEDIUM_AND_HIGH,
    val disabledActions: Set<ActionId> = emptySet(),
    val memoryEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val historyRetentionDays: Int = 30,
    val notificationAssistantEnabled: Boolean = false,
    val announceNotifications: Boolean = false,
    val notificationAllowedPackages: Set<String> = emptySet(),
    /** Calling code used to complete local numbers, e.g. 234 for Nigeria. */
    val defaultCountryCode: String = "234",
    val defaultMessagingApp: MessagingChannel = MessagingChannel.WHATSAPP,
    val onboardingComplete: Boolean = false,
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        val GEMINI_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro")
    }
}

object ConfirmationPolicy {
    fun requiresConfirmation(risk: RiskLevel, mode: ConfirmationMode): Boolean = when (risk) {
        RiskLevel.HIGH -> true
        RiskLevel.MEDIUM -> mode != ConfirmationMode.HIGH_ONLY
        RiskLevel.LOW -> mode == ConfirmationMode.ALWAYS
    }
}
