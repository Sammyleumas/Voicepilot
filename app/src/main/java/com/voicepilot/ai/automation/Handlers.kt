package com.voicepilot.ai.automation

import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import com.voicepilot.ai.data.NotesRepository
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.notifications.NotificationHub
import com.voicepilot.core.messaging.UssdCodes
import android.view.KeyEvent
import com.voicepilot.ai.platform.AndroidAppCatalog
import com.voicepilot.core.action.ActionHandler
import com.voicepilot.core.action.Params
import com.voicepilot.core.integrations.AppResolver
import com.voicepilot.core.integrations.WhatsAppLinks
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.FailureReason
import com.voicepilot.core.planner.ActionDescriber
import com.voicepilot.core.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import kotlin.math.roundToInt

private fun failed(message: String, reason: FailureReason, recoverable: Boolean = false) =
    ActionResult.Failed(message, reason, recoverable)

// ------------------------------------------------------------------ apps & system

class OpenAppHandler @Inject constructor(
    private val catalog: AndroidAppCatalog,
    private val launcher: ActivityLauncher,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val spoken = request.param(Params.APP)!!
        val app = AppResolver(catalog).resolve(spoken)
            ?: return failed("I couldn't find an app called “$spoken” on this phone.", FailureReason.APP_NOT_INSTALLED, true)
        val intent = launcher.launchIntentFor(app.packageName)
            ?: return failed("${app.label} can't be opened directly.", FailureReason.UNSUPPORTED_ON_DEVICE)
        return if (launcher.start(intent)) ActionResult.Completed("Opening ${app.label}.")
        else failed("I couldn't open ${app.label}.", FailureReason.UNKNOWN, true)
    }
}

class OpenSettingsHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val page = request.param(Params.PAGE) ?: "general"
        val candidates = intentsFor(page) + Intent(Settings.ACTION_SETTINGS)
        val intent = candidates.firstOrNull(launcher::canResolve)
            ?: return failed("This phone doesn't expose that settings page.", FailureReason.UNSUPPORTED_ON_DEVICE)
        if (!launcher.start(intent)) return failed("I couldn't open Settings.", FailureReason.UNKNOWN, true)
        val name = if (page == "general") "Settings" else "${ActionDescriber.pageName(page)} settings"
        return ActionResult.Completed("Opening $name.")
    }

    private fun intentsFor(page: String): List<Intent> = when (page) {
        "wifi" -> listOf(Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS))
        "network" -> listOf(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), Intent(Settings.ACTION_WIRELESS_SETTINGS))
        "bluetooth" -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        "display" -> listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        "sound" -> listOf(Intent(Settings.Panel.ACTION_VOLUME), Intent(Settings.ACTION_SOUND_SETTINGS))
        "battery" -> listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        "location" -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        "apps" -> listOf(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        "notifications" -> listOf(Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS"), Intent("android.settings.NOTIFICATION_SETTINGS"))
        "accessibility" -> listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        "airplane" -> listOf(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        "date" -> listOf(Intent(Settings.ACTION_DATE_SETTINGS))
        "security" -> listOf(Intent(Settings.ACTION_SECURITY_SETTINGS))
        "storage" -> listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        else -> emptyList()
    }
}

class CameraHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val video = request.param(Params.MODE) == "video"
        val intent = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("I couldn't find a camera app.", FailureReason.APP_NOT_INSTALLED)
        }
        return ActionResult.Prepared(if (video) "The camera is open in video mode." else "The camera is open.")
    }
}

class WebSearchHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val query = request.param(Params.QUERY)!!
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
        val intent = listOf(search, browser).firstOrNull(launcher::canResolve)
            ?: return failed("I couldn't find a browser.", FailureReason.APP_NOT_INSTALLED)
        return if (launcher.start(intent)) ActionResult.Prepared("Here are web results for “$query”.")
        else failed("I couldn't open the browser.", FailureReason.UNKNOWN, true)
    }
}

// ------------------------------------------------------------------ device controls

class FlashlightHandler @Inject constructor(@ApplicationContext private val context: Context) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val on = request.param(Params.ENABLED) == "true"
        val manager = context.getSystemService(CameraManager::class.java)
        return try {
            val id = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return failed("This phone doesn't have a flashlight I can control.", FailureReason.UNSUPPORTED_ON_DEVICE)
            manager.setTorchMode(id, on)
            ActionResult.Completed(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: CameraAccessException) {
            failed("The flashlight is busy, probably because another app is using the camera.", FailureReason.UNSUPPORTED_ON_DEVICE, true)
        }
    }
}

class VolumeHandler @Inject constructor(@ApplicationContext private val context: Context) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return failed("This device doesn't allow apps to change the volume.", FailureReason.UNSUPPORTED_ON_DEVICE)
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val level = request.param(Params.LEVEL)?.toIntOrNull()
        return try {
            when {
                level != null -> {
                    audio.setStreamVolume(stream, (max * level / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
                    ActionResult.Completed("Media volume set to $level%.")
                }
                else -> {
                    val (direction, message) = when (request.param(Params.DIRECTION)) {
                        "up" -> AudioManager.ADJUST_RAISE to "Volume up."
                        "down" -> AudioManager.ADJUST_LOWER to "Volume down."
                        "mute" -> AudioManager.ADJUST_MUTE to "Media muted."
                        else -> AudioManager.ADJUST_UNMUTE to "Media unmuted."
                    }
                    audio.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
                    ActionResult.Completed(message)
                }
            }
        } catch (e: SecurityException) {
            failed("Android blocked the volume change, possibly because Do Not Disturb is on.", FailureReason.PERMISSION_MISSING, true)
        }
    }
}

class DoNotDisturbHandler @Inject constructor(@ApplicationContext private val context: Context) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return failed("VoicePilot needs Do Not Disturb access first.", FailureReason.PERMISSION_MISSING, true)
        }
        val on = request.param(Params.ENABLED) == "true"
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return ActionResult.Completed(if (on) "Do Not Disturb is on (priority only)." else "Do Not Disturb is off.")
    }
}

// ------------------------------------------------------------------ clock

class AlarmHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val time = LocalTime.parse(request.param(Params.TIME)!!)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        request.param(Params.LABEL)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("I couldn't find a clock app that accepts alarms.", FailureReason.APP_NOT_INSTALLED)
        }
        return ActionResult.Completed("I've asked your clock app to set an alarm for ${ActionDescriber.formatTime(time.toString())}.")
    }
}

class TimerHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val seconds = request.param(Params.SECONDS)!!.toInt()
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        request.param(Params.LABEL)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("I couldn't find a clock app that supports timers.", FailureReason.APP_NOT_INSTALLED)
        }
        return ActionResult.Completed("Timer started for ${ActionDescriber.formatDuration(seconds.toLong())}.")
    }
}

// ------------------------------------------------------------------ productivity

class ReminderHandler @Inject constructor(
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val text = request.param(Params.TEXT)!!
        val at = LocalDateTime.parse(request.param(Params.AT)!!)
        val now = clock.localNow()
        if (!at.isAfter(now)) return failed("That time has already passed. When should I remind you?", FailureReason.INVALID_PARAMETERS, true)
        val exact = scheduler.schedule(text, at)
        val whenText = ActionDescriber.formatDateTime(at.toString(), now.toLocalDate())
        val suffix = if (exact) "" else " It may arrive a few minutes late because exact alarms aren't allowed."
        return ActionResult.Completed("I'll remind you to ${text.replaceFirstChar { it.lowercase() }} $whenText.$suffix")
    }
}

class CalendarEventHandler @Inject constructor(
    private val launcher: ActivityLauncher,
    private val clock: Clock,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val title = request.param(Params.TITLE)!!
        val start = LocalDateTime.parse(request.param(Params.START)!!)
        val minutes = request.param(Params.DURATION_MINUTES)?.toLongOrNull() ?: 60L
        val zone = clock.zone()
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusMinutes(minutes).atZone(zone).toInstant().toEpochMilli())
        request.param(Params.LOCATION)?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("I couldn't find a calendar app.", FailureReason.APP_NOT_INSTALLED)
        }
        return ActionResult.Prepared("I've opened your calendar with “$title” filled in. Tap Save to add it.")
    }
}

class NoteHandler @Inject constructor(private val notes: NotesRepository, private val clock: Clock) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        notes.addNote(request.param(Params.TEXT)!!, clock.now().toEpochMilli())
        return ActionResult.Completed("Note saved.")
    }
}

class TaskHandler @Inject constructor(private val notes: NotesRepository, private val clock: Clock) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val title = request.param(Params.TITLE)!!
        notes.addTask(title, request.param(Params.DUE), clock.now().toEpochMilli())
        return ActionResult.Completed("Added “$title” to your tasks.")
    }
}

// ------------------------------------------------------------------ communication

/**
 * Opens WhatsApp Click to Chat with a prefilled message. WhatsApp offers no
 * public API to send from a personal account, so the user taps Send.
 */
class WhatsAppHandler @Inject constructor(
    private val catalog: AndroidAppCatalog,
    private val launcher: ActivityLauncher,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val phone = request.param(Params.PHONE)
            ?: return failed("I don't have a phone number for that contact.", FailureReason.INVALID_PARAMETERS, true)
        val name = request.param(Params.RECIPIENT_NAME) ?: request.param(Params.RECIPIENT) ?: "your contact"
        val pkg = listOf(WhatsAppLinks.PACKAGE_CONSUMER, WhatsAppLinks.PACKAGE_BUSINESS).firstOrNull(catalog::isInstalled)
            ?: return failed("WhatsApp isn't installed on this phone.", FailureReason.APP_NOT_INSTALLED)
        val uri = Uri.parse(WhatsAppLinks.clickToChat(phone, request.param(Params.MESSAGE)))
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
        if (!launcher.start(intent)) return failed("I couldn't open WhatsApp.", FailureReason.UNKNOWN, true)
        return ActionResult.Prepared(
            if (request.param(Params.MESSAGE) != null) "I've opened WhatsApp with your message to $name. Tap Send to deliver it."
            else "I've opened your WhatsApp chat with $name.",
        )
    }
}

/** Opens the default SMS app with the number and text filled in; the user taps Send. */
class SmsHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val phone = request.param(Params.PHONE)
            ?: return failed("I don't have a phone number for that contact.", FailureReason.INVALID_PARAMETERS, true)
        val name = request.param(Params.RECIPIENT_NAME) ?: "your contact"
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:+$phone"))
            .putExtra("sms_body", request.param(Params.MESSAGE).orEmpty())
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("I couldn't find an SMS app on this phone.", FailureReason.APP_NOT_INSTALLED)
        }
        return ActionResult.Prepared("I've opened your messages with the text to $name. Tap Send to deliver it.")
    }
}

/**
 * Hands the message text to another app (Telegram, Messenger, Signal…) through
 * Android's standard share intent. Those apps don't let other apps pick the chat,
 * so the user chooses the conversation and taps Send.
 */
class ShareToAppHandler @Inject constructor(
    private val catalog: AndroidAppCatalog,
    private val launcher: ActivityLauncher,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val spoken = request.param(Params.APP)!!
        val app = AppResolver(catalog).resolve(spoken)
            ?: return failed("I couldn't find $spoken on this phone.", FailureReason.APP_NOT_INSTALLED, true)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, request.param(Params.MESSAGE).orEmpty())
            .setPackage(app.packageName)
        if (!launcher.canResolve(intent)) {
            return failed("${app.label} doesn't accept messages from other apps.", FailureReason.UNSUPPORTED_ON_DEVICE)
        }
        if (!launcher.start(intent)) return failed("I couldn't open ${app.label}.", FailureReason.UNKNOWN, true)
        val who = request.param(Params.RECIPIENT)?.let { " Choose $it's chat" } ?: " Choose the chat"
        return ActionResult.Prepared("I've opened ${app.label} with your message.$who and tap Send.")
    }
}

class DialHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val phone = request.param(Params.PHONE)
            ?: return failed("I don't have a phone number for that contact.", FailureReason.INVALID_PARAMETERS, true)
        val name = request.param(Params.RECIPIENT_NAME) ?: "your contact"
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$phone"))
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("This device can't make phone calls.", FailureReason.UNSUPPORTED_ON_DEVICE)
        }
        return ActionResult.Prepared("I've opened the dialer with $name's number. Tap Call to start the call.")
    }
}


// ------------------------------------------------------------------ received messages

/** Reads matching messages aloud. Content is read only because the user asked for it. */
class ReadMessagesHandler @Inject constructor(
    private val hub: NotificationHub,
    private val settings: SettingsRepository,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val s = settings.current
        if (!s.notificationAssistantEnabled || s.notificationAllowedPackages.isEmpty()) {
            return failed(
                "The notification assistant is off. Turn it on and choose apps such as WhatsApp in Settings › Automation › Notification assistant.",
                FailureReason.BLOCKED_BY_POLICY,
                true,
            )
        }
        val sender = request.param(Params.SENDER)
        val app = request.param(Params.APP)
        val found = hub.read(app, sender)
        if (found.isEmpty()) {
            val scope = listOfNotNull(app, sender?.let { "from $it" }).joinToString(" ")
            return ActionResult.Completed(if (scope.isEmpty()) "You have no new messages." else "No new $scope messages.".replace("  ", " "))
        }
        val shown = found.takeLast(MAX_READ)
        val intro = "You have ${found.size} new message${if (found.size == 1) "" else "s"}."
        val body = shown.joinToString(" ") { "From ${it.conversationTitle ?: it.title} on ${it.appLabel}: ${it.text.take(240)}." }
        val more = if (found.size > MAX_READ) " There are ${found.size - MAX_READ} older ones too." else ""
        val replyHint = if (shown.last().canReply) " Say “reply” and your message to answer ${shown.last().conversationTitle ?: shown.last().title}." else ""
        return ActionResult.Completed("$intro $body$more$replyHint")
    }

    private companion object {
        const val MAX_READ = 5
    }
}

/** Sends a reply through the notification's own reply button, after the user confirmed it. */
class ReplyHandler @Inject constructor(private val hub: NotificationHub) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val text = request.param(Params.MESSAGE)!!
        val sender = request.param(Params.SENDER)
        val target = hub.replyTarget(sender, request.param(Params.APP))
            ?: return failed("That message is no longer available to reply to. Open the app to answer.", FailureReason.NOT_FOUND, true)
        if (!hub.replyTo(sender, request.param(Params.APP), text)) {
            return failed("${target.appLabel} didn't accept the reply. Open the chat to answer.", FailureReason.UNSUPPORTED_ON_DEVICE, true)
        }
        return ActionResult.Completed("Sent your reply to ${target.sender} through ${target.appLabel}.")
    }
}

// ------------------------------------------------------------------ media and maps

class PlayMediaHandler @Inject constructor(
    private val catalog: AndroidAppCatalog,
    private val launcher: ActivityLauncher,
) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val query = request.param(Params.QUERY)!!
        val appName = request.param(Params.APP)
        val pkg = appName?.let { AppResolver(catalog).resolve(it) }
        if (appName != null && pkg == null) {
            return failed("I couldn't find ${ActionDescriber.appTitle(appName)} on this phone.", FailureReason.APP_NOT_INSTALLED, true)
        }
        val playFromSearch = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .apply { pkg?.let { setPackage(it.packageName) } }
        val youtubeSearch = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
            .apply { if (pkg?.packageName == YOUTUBE) setPackage(YOUTUBE) }
        val spotifySearch = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))).setPackage("com.spotify.music")
        val candidates = when (pkg?.packageName) {
            YOUTUBE -> listOf(youtubeSearch)
            "com.spotify.music" -> listOf(playFromSearch, spotifySearch)
            null -> listOf(playFromSearch, youtubeSearch)
            else -> listOf(playFromSearch, launcher.launchIntentFor(pkg!!.packageName))
        }.filterNotNull()
        val intent = candidates.firstOrNull(launcher::canResolve)
            ?: return failed("I couldn't find a music or video app to play that.", FailureReason.APP_NOT_INSTALLED, true)
        if (!launcher.start(intent)) return failed("I couldn't open the player.", FailureReason.UNKNOWN, true)
        val where = pkg?.label ?: if (intent === youtubeSearch) "YouTube" else "your music app"
        return ActionResult.Prepared(
            if (intent.action == Intent.ACTION_MAIN) "I've opened ${pkg?.label}. It doesn't accept search from other apps, so search for “$query” there."
            else "I've asked $where to play “$query”.",
        )
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
    }
}

class MediaControlHandler @Inject constructor(@ApplicationContext private val context: Context) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val (key, message) = when (request.param(Params.COMMAND)) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE to "Paused."
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT to "Skipped to the next track."
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "Went back a track."
            else -> KeyEvent.KEYCODE_MEDIA_PLAY to "Playing."
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PAUSE && !audio.isMusicActive) {
            return ActionResult.Completed("Nothing is playing right now.")
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return ActionResult.Completed(message)
    }
}

class NavigateHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val destination = request.param(Params.DESTINATION)!!
        val modeLetter = when (request.param(Params.MODE)) {
            "walking" -> "w"
            "transit" -> "r"
            "motorcycle" -> "l"
            else -> "d"
        }
        val googleNav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$modeLetter"))
            .setPackage("com.google.android.apps.maps")
        val anyMap = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination)))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination)))
        val intent = listOf(googleNav, anyMap, web).firstOrNull(launcher::canResolve)
            ?: return failed("I couldn't find a maps app.", FailureReason.APP_NOT_INSTALLED)
        if (!launcher.start(intent)) return failed("I couldn't open maps.", FailureReason.UNKNOWN, true)
        return ActionResult.Prepared(
            if (intent === googleNav) "Starting directions to $destination in Google Maps."
            else "I've opened maps with $destination. Tap Directions to start.",
        )
    }
}

/** Puts a USSD code in the dialer. VoicePilot never places the call itself. */
class UssdHandler @Inject constructor(private val launcher: ActivityLauncher) : ActionHandler {
    override suspend fun execute(request: ActionRequest): ActionResult {
        val code = request.param(Params.CODE)!!
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code)))
        if (!launcher.canResolve(intent) || !launcher.start(intent)) {
            return failed("This device can't dial codes.", FailureReason.UNSUPPORTED_ON_DEVICE)
        }
        val label = request.param(Params.LABEL) ?: UssdCodes.KNOWN[code]
        return ActionResult.Prepared("I've put $code in the dialer${label?.let { " for your $it" } ?: ""}. Tap Call to run it.")
    }
}
