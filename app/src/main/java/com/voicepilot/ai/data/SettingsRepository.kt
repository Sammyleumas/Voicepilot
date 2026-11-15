package com.voicepilot.ai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voicepilot.ai.di.ApplicationScope
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.settings.AssistantSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists [AssistantSettings] with DataStore and exposes them as a hot StateFlow. */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = context.settingsStore

    val settings: StateFlow<AssistantSettings> = store.data
        .map(::fromPreferences)
        .stateIn(scope, SharingStarted.Eagerly, initialValue())

    /** Synchronous snapshot for domain code that needs the current value. */
    val current: AssistantSettings get() = settings.value

    suspend fun update(transform: (AssistantSettings) -> AssistantSettings) {
        store.edit { prefs ->
            val next = transform(fromPreferences(prefs))
            write(prefs, next)
        }
    }

    /** Reads the stored value once at start-up so the first frame isn't based on defaults. */
    private fun initialValue(): AssistantSettings = runBlocking { fromPreferences(store.data.first()) }

    private fun fromPreferences(p: Preferences): AssistantSettings {
        val d = AssistantSettings()
        return AssistantSettings(
            userName = p[K.USER_NAME] ?: d.userName,
            assistantName = p[K.ASSISTANT_NAME] ?: d.assistantName,
            language = p[K.LANGUAGE] ?: d.language,
            theme = enumOr(p[K.THEME], d.theme),
            responseStyle = enumOr(p[K.RESPONSE_STYLE], d.responseStyle),
            spokenResponses = p[K.SPOKEN] ?: d.spokenResponses,
            speechRate = p[K.SPEECH_RATE] ?: d.speechRate,
            voiceName = p[K.VOICE_NAME],
            wakeWordEnabled = p[K.WAKE_ENABLED] ?: d.wakeWordEnabled,
            wakePhrase = p[K.WAKE_PHRASE] ?: d.wakePhrase,
            aiProvider = enumOr(p[K.AI_PROVIDER], d.aiProvider),
            aiModel = p[K.AI_MODEL] ?: d.aiModel,
            cloudBaseUrl = p[K.CLOUD_URL] ?: d.cloudBaseUrl,
            confirmationMode = enumOr(p[K.CONFIRMATION], d.confirmationMode),
            disabledActions = p[K.DISABLED_ACTIONS].orEmpty().mapNotNull { ActionId.fromWireName(it) }.toSet(),
            memoryEnabled = p[K.MEMORY] ?: d.memoryEnabled,
            historyEnabled = p[K.HISTORY] ?: d.historyEnabled,
            historyRetentionDays = p[K.RETENTION] ?: d.historyRetentionDays,
            notificationAssistantEnabled = p[K.NOTIF_ENABLED] ?: d.notificationAssistantEnabled,
            announceNotifications = p[K.NOTIF_ANNOUNCE] ?: d.announceNotifications,
            notificationAllowedPackages = p[K.NOTIF_PACKAGES] ?: d.notificationAllowedPackages,
            defaultCountryCode = p[K.COUNTRY_CODE] ?: d.defaultCountryCode,
            defaultMessagingApp = enumOr(p[K.MESSAGING_APP], d.defaultMessagingApp),
            sendWithoutAsking = p[K.SEND_WITHOUT_ASKING] ?: d.sendWithoutAsking,
            useSystemSpeechDialog = p[K.SYSTEM_SPEECH] ?: d.useSystemSpeechDialog,
            onboardingComplete = p[K.ONBOARDED] ?: d.onboardingComplete,
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: AssistantSettings) {
        p[K.USER_NAME] = s.userName.take(40)
        p[K.ASSISTANT_NAME] = s.assistantName.take(30)
        p[K.LANGUAGE] = s.language
        p[K.THEME] = s.theme.name
        p[K.RESPONSE_STYLE] = s.responseStyle.name
        p[K.SPOKEN] = s.spokenResponses
        p[K.SPEECH_RATE] = s.speechRate.coerceIn(0.5f, 2.0f)
        val voice = s.voiceName
        if (voice != null) p[K.VOICE_NAME] = voice else p.remove(K.VOICE_NAME)
        p[K.WAKE_ENABLED] = s.wakeWordEnabled
        p[K.WAKE_PHRASE] = s.wakePhrase.take(40)
        p[K.AI_PROVIDER] = s.aiProvider.name
        p[K.AI_MODEL] = s.aiModel
        p[K.CLOUD_URL] = s.cloudBaseUrl.trim()
        p[K.CONFIRMATION] = s.confirmationMode.name
        p[K.DISABLED_ACTIONS] = s.disabledActions.map { it.wireName }.toSet()
        p[K.MEMORY] = s.memoryEnabled
        p[K.HISTORY] = s.historyEnabled
        p[K.RETENTION] = s.historyRetentionDays.coerceIn(1, 365)
        p[K.NOTIF_ENABLED] = s.notificationAssistantEnabled
        p[K.NOTIF_ANNOUNCE] = s.announceNotifications
        p[K.NOTIF_PACKAGES] = s.notificationAllowedPackages
        p[K.COUNTRY_CODE] = s.defaultCountryCode.filter { it.isDigit() }.take(4)
        p[K.ONBOARDED] = s.onboardingComplete
        p[K.MESSAGING_APP] = s.defaultMessagingApp.name
        p[K.SEND_WITHOUT_ASKING] = s.sendWithoutAsking
        p[K.SYSTEM_SPEECH] = s.useSystemSpeechDialog
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private object K {
        val USER_NAME = stringPreferencesKey("user_name")
        val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
        val RESPONSE_STYLE = stringPreferencesKey("response_style")
        val SPOKEN = booleanPreferencesKey("spoken_responses")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
        val WAKE_PHRASE = stringPreferencesKey("wake_phrase")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val CLOUD_URL = stringPreferencesKey("cloud_url")
        val CONFIRMATION = stringPreferencesKey("confirmation_mode")
        val DISABLED_ACTIONS = stringSetPreferencesKey("disabled_actions")
        val MEMORY = booleanPreferencesKey("memory_enabled")
        val HISTORY = booleanPreferencesKey("history_enabled")
        val RETENTION = intPreferencesKey("history_retention_days")
        val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
        val NOTIF_ANNOUNCE = booleanPreferencesKey("notif_announce")
        val NOTIF_PACKAGES = stringSetPreferencesKey("notif_packages")
        val COUNTRY_CODE = stringPreferencesKey("country_code")
        val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val MESSAGING_APP = stringPreferencesKey("default_messaging_app")
        val SEND_WITHOUT_ASKING = booleanPreferencesKey("send_without_asking")
        val SYSTEM_SPEECH = booleanPreferencesKey("use_system_speech_dialog")
    }
}
