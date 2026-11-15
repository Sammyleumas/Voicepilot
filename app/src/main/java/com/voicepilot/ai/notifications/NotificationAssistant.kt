package com.voicepilot.ai.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.di.ApplicationScope
import com.voicepilot.core.notifications.NotificationFilter
import com.voicepilot.core.notifications.NotificationItem
import com.voicepilot.core.notifications.NotificationSummarizer
import com.voicepilot.core.voice.SpeechSynthesizer
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store of notifications from apps the user allowed. Nothing is
 * written to disk or sent over the network. Cleared when the process ends.
 */
@Singleton
class NotificationHub @Inject constructor(@ApplicationContext private val context: Context) {

    private class Entry(val item: NotificationItem, val reply: Notification.Action?, val contentIntent: PendingIntent?)

    private val entries = LinkedHashMap<String, Entry>()
    private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected(value: Boolean) {
        _connected.value = value
        if (!value) clear()
    }

    @Synchronized
    fun post(item: NotificationItem, reply: Notification.Action?, contentIntent: PendingIntent?) {
        entries.remove(item.key)
        entries[item.key] = Entry(item, reply, contentIntent)
        while (entries.size > MAX_ITEMS) entries.remove(entries.keys.first())
        publish()
    }

    @Synchronized
    fun remove(key: String) {
        if (entries.remove(key) != null) publish()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        publish()
    }

    fun summary(): String = NotificationSummarizer.summarize(items.value)

    /** Sends [text] through the notification's own reply action. Returns false if unsupported. */
    @Synchronized
    fun reply(key: String, text: String): Boolean {
        val entry = entries[key] ?: return false
        val action = entry.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val bundle = Bundle()
        inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    @Synchronized
    fun open(key: String): Boolean = try {
        entries[key]?.contentIntent?.send() != null
    } catch (_: PendingIntent.CanceledException) {
        false
    }

    private fun publish() {
        _items.update { entries.values.map { it.item }.sortedByDescending { it.postedAtMillis } }
    }

    private companion object {
        const val MAX_ITEMS = 50
    }
}

/**
 * Receives notifications only after the user grants notification access in
 * system settings, and only keeps those from apps on the user's allow list.
 */
@AndroidEntryPoint
class VoicePilotNotificationListener : NotificationListenerService() {

    @Inject lateinit var hub: NotificationHub
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var speech: SpeechSynthesizer
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onListenerConnected() {
        hub.setConnected(true)
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it, announce = false) }
    }

    override fun onListenerDisconnected() {
        hub.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, announce = true)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = hub.remove(sbn.key)

    private fun handle(sbn: StatusBarNotification, announce: Boolean) {
        val s = settings.current
        if (!s.notificationAssistantEnabled) return
        if (!NotificationFilter.isAllowed(sbn.packageName, s.notificationAllowedPackages)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val item = NotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title,
            text = text,
            postedAtMillis = sbn.postTime,
            isMessaging = n.category == Notification.CATEGORY_MESSAGE || sbn.packageName in NotificationFilter.MESSAGING_PACKAGES,
            canReply = reply != null,
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
        )
        hub.post(item, reply, n.contentIntent)
        if (announce && s.announceNotifications) {
            // Announces the sender only; message content is read only when the user asks.
            scope.launch { speech.speak(NotificationSummarizer.announce(item, readContent = false), s.speechRate, s.voiceName) }
        }
    }

    private fun appLabel(pkg: String): String = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }
}
