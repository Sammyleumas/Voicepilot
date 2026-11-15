package com.voicepilot.ai.voice

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voicepilot.ai.NotificationChannels
import com.voicepilot.ai.R
import com.voicepilot.ai.assist.AssistActivity
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.core.voice.RecognitionConfig
import com.voicepilot.core.voice.SpeechError
import com.voicepilot.core.voice.SpeechEvent
import com.voicepilot.core.voice.SpeechRecognitionEngine
import com.voicepilot.core.voice.WakeDetection
import com.voicepilot.core.voice.WakePhraseMatcher
import com.voicepilot.core.voice.WakeWordStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Connects the hands-free service to whichever VoicePilot screen is visible. */
@Singleton
class WakeEvents @Inject constructor() {
    private val _detections = MutableSharedFlow<WakeDetection>(extraBufferCapacity = 4)
    val detections: SharedFlow<WakeDetection> = _detections.asSharedFlow()

    private val _status = MutableStateFlow<WakeWordStatus>(WakeWordStatus.Disabled)
    val status: StateFlow<WakeWordStatus> = _status.asStateFlow()

    /** Number of resumed VoicePilot screens able to handle a detection directly. */
    @Volatile var foregroundScreens: Int = 0

    /** Set while a screen is actively listening, so the service yields the microphone. */
    private val _micBusy = MutableStateFlow(false)
    val micBusy: StateFlow<Boolean> = _micBusy.asStateFlow()

    fun setMicBusy(busy: Boolean) { _micBusy.value = busy }
    fun setStatus(status: WakeWordStatus) { _status.value = status }
    fun emit(detection: WakeDetection): Boolean = _detections.tryEmit(detection)
}

/**
 * Optional hands-free session: listens for "Hey VoicePilot" with the device's
 * speech recognizer while a visible notification is shown.
 *
 * Android limits this deliberately: a microphone foreground service may only be
 * started while VoicePilot is in the foreground, and recognizer loops cost
 * battery. The session therefore stops on its own after [MAX_SESSION_MS] or
 * when the screen turns off. Always-on, screen-off hotword detection requires a
 * system-privileged HotwordDetectionService that third-party apps can't use;
 * see docs/ANDROID_PERMISSIONS.md.
 */
@AndroidEntryPoint
class HandsFreeService : LifecycleService() {

    @Inject lateinit var recognizer: SpeechRecognitionEngine
    @Inject lateinit var events: WakeEvents
    @Inject lateinit var settings: SettingsRepository

    private var loop: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            events.setStatus(WakeWordStatus.Unavailable("Microphone permission is required."))
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            // Android 12+ refuses to start a microphone service from the background.
            events.setStatus(WakeWordStatus.Unavailable("Start hands-free mode from the SL app."))
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = lifecycleScope.launch { listenLoop() }
        return START_NOT_STICKY
    }

    private suspend fun listenLoop() {
        val started = System.currentTimeMillis()
        val power = getSystemService(PowerManager::class.java)
        var consecutiveErrors = 0
        events.setStatus(WakeWordStatus.Listening)
        while (lifecycleScope.isActive) {
            if (System.currentTimeMillis() - started > MAX_SESSION_MS || !power.isInteractive) break
            if (events.micBusy.value) {
                events.setStatus(WakeWordStatus.Paused)
                delay(1_000)
                continue
            }
            events.setStatus(WakeWordStatus.Listening)
            val matcher = WakePhraseMatcher(settings.current.wakePhrase)
            var detection: WakeDetection? = null
            var error: SpeechError? = null
            recognizer.listen(RecognitionConfig(languageTag = settings.current.language, partialResults = false)).collect { e ->
                when (e) {
                    is SpeechEvent.Final -> detection = (listOf(e.text) + e.alternatives).firstNotNullOfOrNull(matcher::match)
                    is SpeechEvent.Error -> error = e.error
                    else -> Unit
                }
            }
            when {
                detection != null -> { consecutiveErrors = 0; deliver(detection!!) }
                error == SpeechError.NO_MATCH || error == SpeechError.SPEECH_TIMEOUT || error == null -> consecutiveErrors = 0
                else -> {
                    consecutiveErrors++
                    if (error == SpeechError.PERMISSION_DENIED || error == SpeechError.UNAVAILABLE || consecutiveErrors >= 5) {
                        events.setStatus(WakeWordStatus.Unavailable(error!!.userMessage))
                        break
                    }
                    delay(1_000L * consecutiveErrors)
                }
            }
        }
        stopSelf()
    }

    private fun deliver(detection: WakeDetection) {
        if (events.foregroundScreens > 0 && events.emit(detection)) return
        // In the background Android doesn't allow opening a screen directly, so offer a tap-to-talk alert.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 1,
            AssistActivity.intent(this, detection.trailingCommand),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alert = NotificationCompat.Builder(this, NotificationChannels.ASSISTANT)
            .setSmallIcon(R.drawable.ic_stat_voicepilot)
            .setContentTitle("SL heard you")
            .setContentText(detection.trailingCommand?.let { "Tap to continue: “$it”" } ?: "Tap to talk")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_ID, alert)
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NotificationChannels.SESSION)
            .setSmallIcon(R.drawable.ic_stat_voicepilot)
            .setContentTitle("Hands-free mode is on")
            .setContentText("Listening for “${settings.current.wakePhrase}”. Microphone in use.")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        loop?.cancel()
        events.setStatus(WakeWordStatus.Disabled)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val ALERT_ID = 43
        private const val ACTION_STOP = "com.voicepilot.ai.STOP_HANDS_FREE"
        const val MAX_SESSION_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HandsFreeService::class.java))
        }
    }
}
