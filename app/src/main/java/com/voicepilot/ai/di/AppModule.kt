package com.voicepilot.ai.di

import android.content.Context
import androidx.room.Room
import com.voicepilot.ai.ai.AiProviderFactory
import com.voicepilot.ai.automation.AlarmHandler
import com.voicepilot.ai.automation.CalendarEventHandler
import com.voicepilot.ai.automation.CameraHandler
import com.voicepilot.ai.automation.DialHandler
import com.voicepilot.ai.automation.DoNotDisturbHandler
import com.voicepilot.ai.automation.FlashlightHandler
import com.voicepilot.ai.automation.NoteHandler
import com.voicepilot.ai.automation.OpenAppHandler
import com.voicepilot.ai.automation.OpenSettingsHandler
import com.voicepilot.ai.automation.ReminderHandler
import com.voicepilot.ai.automation.TaskHandler
import com.voicepilot.ai.automation.TimerHandler
import com.voicepilot.ai.automation.VolumeHandler
import com.voicepilot.ai.automation.WebSearchHandler
import com.voicepilot.ai.automation.ShareToAppHandler
import com.voicepilot.ai.automation.SmsHandler
import com.voicepilot.ai.automation.WhatsAppHandler
import com.voicepilot.ai.billing.SubscriptionRepository
import com.voicepilot.ai.billing.UnconfiguredBillingProvider
import com.voicepilot.ai.data.RoomHistoryRepository
import com.voicepilot.ai.data.RoomMemoryRepository
import com.voicepilot.ai.data.RoomRoutineRepository
import com.voicepilot.ai.data.RoomUsageStore
import com.voicepilot.ai.data.SettingsAwareAliasLookup
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.data.db.VoicePilotDatabase
import com.voicepilot.ai.platform.AndroidAppCatalog
import com.voicepilot.ai.platform.AndroidCapabilities
import com.voicepilot.ai.platform.AndroidContactDirectory
import com.voicepilot.ai.platform.AndroidSpeechRecognitionEngine
import com.voicepilot.ai.platform.AndroidSpeechSynthesizer
import com.voicepilot.ai.platform.HttpUrlConnectionTransport
import com.voicepilot.core.action.ActionExecutor
import com.voicepilot.core.action.ActionHandler
import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.contacts.ContactDirectory
import com.voicepilot.core.contacts.ContactResolver
import com.voicepilot.core.conversation.AssistantSession
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.integrations.AppCatalog
import com.voicepilot.core.interpret.HybridInterpreter
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.llm.HttpTransport
import com.voicepilot.core.llm.RateLimiter
import com.voicepilot.core.memory.MemoryRepository
import com.voicepilot.core.memory.RememberAliasHandler
import com.voicepilot.core.memory.RoutineRepository
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.planner.PlanRunner
import com.voicepilot.core.subscription.BillingProvider
import com.voicepilot.core.subscription.UsageStore
import com.voicepilot.core.subscription.UsageTracker
import com.voicepilot.core.time.Clock
import com.voicepilot.core.voice.SpeechRecognitionEngine
import com.voicepilot.core.voice.SpeechSynthesizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun clock(): Clock = Clock.SYSTEM

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun registry(): ActionRegistry = ActionRegistry()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): VoicePilotDatabase =
        Room.databaseBuilder(context, VoicePilotDatabase::class.java, VoicePilotDatabase.NAME).build()

    @Provides fun historyDao(db: VoicePilotDatabase) = db.history()
    @Provides fun memoryDao(db: VoicePilotDatabase) = db.memory()
    @Provides fun routineDao(db: VoicePilotDatabase) = db.routines()
    @Provides fun notesDao(db: VoicePilotDatabase) = db.notes()
    @Provides fun reminderDao(db: VoicePilotDatabase) = db.reminders()
    @Provides fun usageDao(db: VoicePilotDatabase) = db.usage()

    @Provides @Singleton
    fun handlers(
        openApp: OpenAppHandler,
        openSettings: OpenSettingsHandler,
        camera: CameraHandler,
        flashlight: FlashlightHandler,
        volume: VolumeHandler,
        alarm: AlarmHandler,
        timer: TimerHandler,
        reminder: ReminderHandler,
        calendar: CalendarEventHandler,
        note: NoteHandler,
        task: TaskHandler,
        whatsApp: WhatsAppHandler,
        sms: SmsHandler,
        share: ShareToAppHandler,
        dial: DialHandler,
        dnd: DoNotDisturbHandler,
        webSearch: WebSearchHandler,
        memory: MemoryRepository,
        contacts: ContactDirectory,
        settings: SettingsRepository,
        clock: Clock,
    ): Map<ActionId, ActionHandler> = mapOf(
        ActionId.OPEN_APP to openApp,
        ActionId.OPEN_SETTINGS to openSettings,
        ActionId.LAUNCH_CAMERA to camera,
        ActionId.SET_FLASHLIGHT to flashlight,
        ActionId.SET_VOLUME to volume,
        ActionId.SET_ALARM to alarm,
        ActionId.SET_TIMER to timer,
        ActionId.CREATE_REMINDER to reminder,
        ActionId.CREATE_CALENDAR_EVENT to calendar,
        ActionId.SAVE_NOTE to note,
        ActionId.CREATE_TASK to task,
        ActionId.PREPARE_WHATSAPP_MESSAGE to whatsApp,
        ActionId.PREPARE_SMS to sms,
        ActionId.SHARE_MESSAGE_TO_APP to share,
        ActionId.DIAL_CONTACT to dial,
        ActionId.SET_DO_NOT_DISTURB to dnd,
        ActionId.WEB_SEARCH to webSearch,
        ActionId.REMEMBER_ALIAS to RememberAliasHandler(memory, contacts, { settings.current }, clock),
    )

    @Provides @Singleton
    fun executor(
        registry: ActionRegistry,
        handlers: Map<ActionId, @JvmSuppressWildcards ActionHandler>,
        capabilities: AndroidCapabilities,
        settings: SettingsRepository,
    ): ActionExecutor = ActionExecutor(registry, handlers, capabilities) { settings.current }

    @Provides @Singleton
    fun usageTracker(store: UsageStore, clock: Clock, subscription: SubscriptionRepository): UsageTracker =
        UsageTracker(store, clock) { subscription.entitlements.value }

    @Provides @Singleton
    fun interpreter(clock: Clock, ai: AiProviderFactory, usage: UsageTracker, settings: SettingsRepository): HybridInterpreter =
        HybridInterpreter(
            local = LocalRuleInterpreter(clock) { settings.current.defaultMessagingApp },
            ai = { ai.interpreter() },
            usage = usage,
            rateLimiter = RateLimiter(capacity = 10, refillEveryMillis = 6_000, clock = clock),
        )

    @Provides @Singleton
    fun session(
        interpreter: HybridInterpreter,
        executor: ActionExecutor,
        directory: ContactDirectory,
        memory: RoomMemoryRepository,
        routines: RoutineRepository,
        history: HistoryRepository,
        settings: SettingsRepository,
        subscription: SubscriptionRepository,
        clock: Clock,
    ): AssistantSession = AssistantSession(
        interpreter = interpreter,
        executor = executor,
        planRunner = PlanRunner(executor),
        contacts = ContactResolver(directory, SettingsAwareAliasLookup(memory) { settings.current.memoryEnabled }),
        routines = routines,
        history = history,
        settings = { settings.current },
        entitlements = { subscription.entitlements.value },
        clock = clock,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun history(impl: RoomHistoryRepository): HistoryRepository
    @Binds abstract fun memory(impl: RoomMemoryRepository): MemoryRepository
    @Binds abstract fun routines(impl: RoomRoutineRepository): RoutineRepository
    @Binds abstract fun usage(impl: RoomUsageStore): UsageStore
    @Binds abstract fun contacts(impl: AndroidContactDirectory): ContactDirectory
    @Binds abstract fun apps(impl: AndroidAppCatalog): AppCatalog
    @Binds abstract fun transport(impl: HttpUrlConnectionTransport): HttpTransport
    @Binds abstract fun recognizer(impl: AndroidSpeechRecognitionEngine): SpeechRecognitionEngine
    @Binds abstract fun synthesizer(impl: AndroidSpeechSynthesizer): SpeechSynthesizer
    @Binds abstract fun billing(impl: UnconfiguredBillingProvider): BillingProvider
}
