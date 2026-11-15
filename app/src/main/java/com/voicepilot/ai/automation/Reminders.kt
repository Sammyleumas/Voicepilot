package com.voicepilot.ai.automation

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voicepilot.ai.MainActivity
import com.voicepilot.ai.NotificationChannels
import com.voicepilot.ai.R
import com.voicepilot.ai.data.db.ReminderDao
import com.voicepilot.ai.data.db.ReminderEntity
import com.voicepilot.ai.di.ApplicationScope
import com.voicepilot.ai.platform.AndroidCapabilities
import com.voicepilot.core.time.Clock
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Stores reminders in Room and schedules them with AlarmManager. */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ReminderDao,
    private val capabilities: AndroidCapabilities,
    private val clock: Clock,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Returns true when the reminder was scheduled exactly. */
    suspend fun schedule(text: String, at: LocalDateTime): Boolean {
        val millis = at.atZone(clock.zone()).toInstant().toEpochMilli()
        val id = dao.insert(ReminderEntity(text = text, triggerAtMillis = millis, delivered = false, createdAt = clock.now().toEpochMilli()))
        return arm(id, millis)
    }

    /** Re-arms undelivered reminders, e.g. after a reboot. */
    suspend fun rescheduleAll() {
        val now = clock.now().toEpochMilli()
        dao.pending().forEach { r -> arm(r.id, maxOf(r.triggerAtMillis, now + 5_000)) }
    }

    suspend fun cancel(id: Long) {
        alarms.cancel(pendingIntent(id))
        dao.delete(id)
    }

    private fun arm(id: Long, millis: Long): Boolean {
        val pi = pendingIntent(id)
        return if (capabilities.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            true
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            false
        }
    }

    private fun pendingIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var dao: ReminderDao
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                val reminder = dao.get(id) ?: return@launch
                if (reminder.delivered) return@launch
                show(context, reminder)
                dao.markDelivered(id)
            } finally {
                pending.finish()
            }
        }
    }

    private fun show(context: Context, reminder: ReminderEntity) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_voicepilot)
            .setContentTitle("Reminder")
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + reminder.id.toInt(), notification)
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
        private const val NOTIFICATION_BASE = 10_000
    }
}

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: ReminderScheduler
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                scheduler.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
