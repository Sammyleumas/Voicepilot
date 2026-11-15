package com.voicepilot.ai.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voicepilot.ai.notifications.VoicePilotNotificationListener
import com.voicepilot.core.action.CapabilityChecker
import com.voicepilot.core.model.Capability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How a capability is granted: a runtime permission dialog or a system settings page. */
sealed interface GrantMethod {
    data class RuntimePermission(val permission: String) : GrantMethod
    data class SettingsPage(val intent: Intent) : GrantMethod
    data object AlwaysGranted : GrantMethod
}

/** Maps abstract capabilities to Android permissions and special access. */
@Singleton
class AndroidCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) : CapabilityChecker {

    override fun isGranted(capability: Capability): Boolean = when (capability) {
        Capability.MICROPHONE -> granted(Manifest.permission.RECORD_AUDIO)
        Capability.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)
        Capability.POST_NOTIFICATIONS ->
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        Capability.NOTIFICATION_LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        Capability.DND_POLICY ->
            context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    }

    fun grantMethod(capability: Capability): GrantMethod = when (capability) {
        Capability.MICROPHONE -> GrantMethod.RuntimePermission(Manifest.permission.RECORD_AUDIO)
        Capability.CONTACTS -> GrantMethod.RuntimePermission(Manifest.permission.READ_CONTACTS)
        Capability.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                GrantMethod.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                GrantMethod.SettingsPage(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        Capability.NOTIFICATION_LISTENER -> GrantMethod.SettingsPage(notificationListenerSettings())
        Capability.DND_POLICY -> GrantMethod.SettingsPage(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun exactAlarmSettings(): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    fun appDetailsSettings(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun defaultAssistantSettings(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun notificationListenerSettings(): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, VoicePilotNotificationListener::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        val DESCRIPTIONS: Map<Capability, String> = mapOf(
            Capability.MICROPHONE to "Hear your voice commands while you're using VoicePilot. You can always type instead.",
            Capability.CONTACTS to "Find the right person when you ask to message or call someone. Contacts are read on-device and never uploaded.",
            Capability.POST_NOTIFICATIONS to "Show the reminders you create and the hands-free session indicator.",
            Capability.NOTIFICATION_LISTENER to "Optional: read out and summarise notifications from apps you choose. Nothing is uploaded or stored by default.",
            Capability.DND_POLICY to "Turn Do Not Disturb on or off when you ask, for example at bedtime.",
        )
    }
}
