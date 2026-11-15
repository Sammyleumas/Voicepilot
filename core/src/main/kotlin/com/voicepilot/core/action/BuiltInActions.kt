package com.voicepilot.core.action

import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.OutcomeKind
import com.voicepilot.core.model.RiskLevel

/** Parameter names shared between interpreters, handlers and tests. */
object Params {
    const val APP = "app"
    const val PAGE = "page"
    const val MODE = "mode"
    const val ENABLED = "enabled"
    const val LEVEL = "level"
    const val DIRECTION = "direction"
    const val TIME = "time"
    const val LABEL = "label"
    const val SECONDS = "seconds"
    const val TEXT = "text"
    const val AT = "at"
    const val TITLE = "title"
    const val START = "start"
    const val DURATION_MINUTES = "duration_minutes"
    const val LOCATION = "location"
    const val DUE = "due"
    const val RECIPIENT = "recipient"
    const val RECIPIENT_NAME = "recipient_name"
    const val PHONE = "phone"
    const val MESSAGE = "message"
    const val QUERY = "query"
    const val ALIAS = "alias"
    const val CONTACT = "contact"
}

object SettingsPages {
    val ALL = setOf(
        "general", "wifi", "bluetooth", "display", "sound", "battery", "location",
        "apps", "notifications", "accessibility", "network", "airplane", "date", "security", "storage",
    )
}

/** The definitions of every built-in, allowlisted action. */
object BuiltInActions {

    val all: List<ActionDefinition> = listOf(
        ActionDefinition(
            id = ActionId.OPEN_APP,
            title = "Open app",
            description = "Open an installed application by name.",
            params = listOf(
                ParamSpec(Params.APP, ParamType.Text(80), true, "Name of the app to open", "Which app should I open?"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
        ),
        ActionDefinition(
            id = ActionId.OPEN_SETTINGS,
            title = "Open settings",
            description = "Open a system settings page. Pages: ${SettingsPages.ALL.joinToString()}.",
            params = listOf(
                ParamSpec(Params.PAGE, ParamType.Choice(SettingsPages.ALL), true, "Settings page", "Which settings page should I open?"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            appLabel = "Settings",
        ),
        ActionDefinition(
            id = ActionId.LAUNCH_CAMERA,
            title = "Open camera",
            description = "Open the camera app. Modes: photo, video.",
            params = listOf(
                ParamSpec(Params.MODE, ParamType.Choice(setOf("photo", "video")), false, "Camera mode"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.HANDS_OFF,
            appLabel = "Camera",
        ),
        ActionDefinition(
            id = ActionId.SET_FLASHLIGHT,
            title = "Flashlight",
            description = "Turn the flashlight on or off.",
            params = listOf(
                ParamSpec(Params.ENABLED, ParamType.Bool, true, "true to turn on, false to turn off", "Should I turn the flashlight on or off?"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
        ),
        ActionDefinition(
            id = ActionId.SET_VOLUME,
            title = "Media volume",
            description = "Change media volume. Use level (0-100) or direction (up, down, mute, unmute).",
            params = listOf(
                ParamSpec(Params.LEVEL, ParamType.Integer(0, 100), false, "Volume percentage", "What volume should I set, from 0 to 100?"),
                ParamSpec(Params.DIRECTION, ParamType.Choice(setOf("up", "down", "mute", "unmute")), false, "Relative change"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            requireAnyOf = listOf(Params.LEVEL, Params.DIRECTION),
        ),
        ActionDefinition(
            id = ActionId.SET_ALARM,
            title = "Set alarm",
            description = "Create an alarm in the clock app at a local time (HH:mm).",
            params = listOf(
                ParamSpec(Params.TIME, ParamType.Time, true, "Alarm time, HH:mm 24-hour", "What time should I set the alarm for?"),
                ParamSpec(Params.LABEL, ParamType.Text(60), false, "Alarm label"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            appLabel = "Clock",
        ),
        ActionDefinition(
            id = ActionId.SET_TIMER,
            title = "Set timer",
            description = "Start a countdown timer in the clock app.",
            params = listOf(
                ParamSpec(Params.SECONDS, ParamType.Integer(1, 86_400), true, "Timer length in seconds", "How long should the timer be?"),
                ParamSpec(Params.LABEL, ParamType.Text(60), false, "Timer label"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            appLabel = "Clock",
        ),
        ActionDefinition(
            id = ActionId.CREATE_REMINDER,
            title = "Reminder",
            description = "Create a VoicePilot reminder notification at a local date-time (yyyy-MM-ddTHH:mm).",
            params = listOf(
                ParamSpec(Params.TEXT, ParamType.Text(200), true, "What to remind about", "What should I remind you about?"),
                ParamSpec(Params.AT, ParamType.DateTime, true, "When, yyyy-MM-ddTHH:mm", "When should I remind you?"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            capabilities = setOf(Capability.POST_NOTIFICATIONS),
            appLabel = "VoicePilot",
        ),
        ActionDefinition(
            id = ActionId.CREATE_CALENDAR_EVENT,
            title = "Calendar event",
            description = "Prepare a calendar event in the calendar app for the user to save.",
            params = listOf(
                ParamSpec(Params.TITLE, ParamType.Text(120), true, "Event title", "What is the event called?"),
                ParamSpec(Params.START, ParamType.DateTime, true, "Start, yyyy-MM-ddTHH:mm", "When does it start?"),
                ParamSpec(Params.DURATION_MINUTES, ParamType.Integer(1, 1440), false, "Duration in minutes"),
                ParamSpec(Params.LOCATION, ParamType.Text(120), false, "Location"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.HANDS_OFF,
            appLabel = "Calendar",
        ),
        ActionDefinition(
            id = ActionId.SAVE_NOTE,
            title = "Save note",
            description = "Save a private note inside VoicePilot.",
            params = listOf(
                ParamSpec(Params.TEXT, ParamType.Text(2000), true, "Note content", "What should the note say?"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            appLabel = "VoicePilot",
        ),
        ActionDefinition(
            id = ActionId.CREATE_TASK,
            title = "Create task",
            description = "Add a task to the VoicePilot task list, optionally with a due date-time.",
            params = listOf(
                ParamSpec(Params.TITLE, ParamType.Text(200), true, "Task", "What is the task?"),
                ParamSpec(Params.DUE, ParamType.DateTime, false, "Due, yyyy-MM-ddTHH:mm"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.COMPLETES,
            appLabel = "VoicePilot",
        ),
        ActionDefinition(
            id = ActionId.PREPARE_WHATSAPP_MESSAGE,
            title = "WhatsApp message",
            description = "Open a WhatsApp chat with a prefilled message. The user taps Send in WhatsApp.",
            params = listOf(
                ParamSpec(Params.RECIPIENT, ParamType.Text(80), true, "Who to message, as the user said it", "Who should I send it to?"),
                ParamSpec(Params.MESSAGE, ParamType.Text(1000), true, "Message text written to the recipient", "What would you like me to tell {recipient}?"),
                ParamSpec(Params.PHONE, ParamType.Phone, false, "Resolved phone number (set by VoicePilot)"),
                ParamSpec(Params.RECIPIENT_NAME, ParamType.Text(120), false, "Resolved contact name (set by VoicePilot)"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.HANDS_OFF,
            conditionalCapabilities = mapOf(Params.PHONE to setOf(Capability.CONTACTS)),
            appLabel = "WhatsApp",
        ),
        ActionDefinition(
            id = ActionId.PREPARE_SMS,
            title = "SMS message",
            description = "Open the phone's SMS app with a prefilled text to a contact. The user taps Send.",
            params = listOf(
                ParamSpec(Params.RECIPIENT, ParamType.Text(80), true, "Who to text, as the user said it", "Who should I send it to?"),
                ParamSpec(Params.MESSAGE, ParamType.Text(1000), true, "Message text written to the recipient", "What would you like me to tell {recipient}?"),
                ParamSpec(Params.PHONE, ParamType.Phone, false, "Resolved phone number (set by VoicePilot)"),
                ParamSpec(Params.RECIPIENT_NAME, ParamType.Text(120), false, "Resolved contact name (set by VoicePilot)"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.HANDS_OFF,
            conditionalCapabilities = mapOf(Params.PHONE to setOf(Capability.CONTACTS)),
            appLabel = "Messages",
        ),
        ActionDefinition(
            id = ActionId.SHARE_MESSAGE_TO_APP,
            title = "Message in another app",
            description = "Open another installed app (e.g. Telegram, Messenger, Signal, Instagram, Gmail) with the message text ready to send. The user picks the chat and taps Send.",
            params = listOf(
                ParamSpec(Params.APP, ParamType.Text(80), true, "App name, e.g. Telegram", "Which app should I use?"),
                ParamSpec(Params.MESSAGE, ParamType.Text(2000), true, "Message text", "What would you like me to tell {recipient}?"),
                ParamSpec(Params.RECIPIENT, ParamType.Text(80), false, "Who it's for, as a reminder for the user"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.HANDS_OFF,
        ),
        ActionDefinition(
            id = ActionId.DIAL_CONTACT,
            title = "Call",
            description = "Open the phone dialer with a contact's number. The user taps Call.",
            params = listOf(
                ParamSpec(Params.RECIPIENT, ParamType.Text(80), true, "Who to call", "Who should I call?"),
                ParamSpec(Params.PHONE, ParamType.Phone, false, "Resolved phone number (set by VoicePilot)"),
                ParamSpec(Params.RECIPIENT_NAME, ParamType.Text(120), false, "Resolved contact name (set by VoicePilot)"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.HANDS_OFF,
            conditionalCapabilities = mapOf(Params.PHONE to setOf(Capability.CONTACTS)),
            appLabel = "Phone",
        ),
        ActionDefinition(
            id = ActionId.SET_DO_NOT_DISTURB,
            title = "Do Not Disturb",
            description = "Turn Do Not Disturb on or off.",
            params = listOf(
                ParamSpec(Params.ENABLED, ParamType.Bool, true, "true to enable", "Should I turn Do Not Disturb on or off?"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.COMPLETES,
            capabilities = setOf(Capability.DND_POLICY),
            appLabel = "System",
        ),
        ActionDefinition(
            id = ActionId.WEB_SEARCH,
            title = "Web search",
            description = "Search the web in the user's browser.",
            params = listOf(
                ParamSpec(Params.QUERY, ParamType.Text(300), true, "Search query", "What should I search for?"),
            ),
            risk = RiskLevel.LOW,
            outcome = OutcomeKind.HANDS_OFF,
            appLabel = "Browser",
        ),
        ActionDefinition(
            id = ActionId.REMEMBER_ALIAS,
            title = "Remember contact nickname",
            description = "Remember that a phrase such as 'my brother' refers to a named contact.",
            params = listOf(
                ParamSpec(Params.ALIAS, ParamType.Text(60), true, "The phrase, e.g. 'my brother'", "What nickname should I remember?"),
                ParamSpec(Params.CONTACT, ParamType.Text(120), true, "Contact name", "Which contact is that?"),
            ),
            risk = RiskLevel.MEDIUM,
            outcome = OutcomeKind.COMPLETES,
            appLabel = "VoicePilot",
        ),
    )
}
