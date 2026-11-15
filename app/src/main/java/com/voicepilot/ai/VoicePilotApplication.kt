package com.voicepilot.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.di.ApplicationScope
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.time.Clock
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

object NotificationChannels {
    const val REMINDERS = "reminders"
    const val SESSION = "hands_free_session"
    const val ASSISTANT = "assistant_alerts"
}

@HiltAndroidApp
class VoicePilotApplication : Application() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var clock: Clock
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        createChannels()
        enforceRetention()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(NotificationChannels.REMINDERS, getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = getString(R.string.channel_reminders_desc) },
                NotificationChannel(NotificationChannels.SESSION, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.channel_session_desc); setShowBadge(false) },
                NotificationChannel(NotificationChannels.ASSISTANT, getString(R.string.channel_assistant), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = getString(R.string.channel_assistant_desc) },
            ),
        )
    }

    /** Deletes history older than the user's retention setting. */
    private fun enforceRetention() {
        scope.launch {
            val days = settings.current.historyRetentionDays.toLong()
            val cutoff = clock.now().minus(Duration.ofDays(days)).toEpochMilli()
            history.deleteOlderThan(cutoff)
        }
    }
}
