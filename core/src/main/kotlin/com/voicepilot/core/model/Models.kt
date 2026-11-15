package com.voicepilot.core.model

import kotlinx.serialization.Serializable

/** How much harm an action could cause if executed by mistake. */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * The allowlist of every operation VoicePilot can perform.
 *
 * Nothing outside this enum can ever be executed, regardless of what a speech
 * recognizer or language model produces. [wireName] is the stable identifier
 * used in structured AI output and persisted routines.
 */
enum class ActionId(val wireName: String) {
    OPEN_APP("open_app"),
    OPEN_SETTINGS("open_settings"),
    LAUNCH_CAMERA("launch_camera"),
    SET_FLASHLIGHT("set_flashlight"),
    SET_VOLUME("set_volume"),
    SET_ALARM("set_alarm"),
    SET_TIMER("set_timer"),
    CREATE_REMINDER("create_reminder"),
    CREATE_CALENDAR_EVENT("create_calendar_event"),
    SAVE_NOTE("save_note"),
    CREATE_TASK("create_task"),
    PREPARE_WHATSAPP_MESSAGE("prepare_whatsapp_message"),
    PREPARE_SMS("prepare_sms"),
    SHARE_MESSAGE_TO_APP("share_message_to_app"),
    DIAL_CONTACT("dial_contact"),
    SET_DO_NOT_DISTURB("set_do_not_disturb"),
    WEB_SEARCH("web_search"),
    REMEMBER_ALIAS("remember_alias"),
    READ_MESSAGES("read_messages"),
    REPLY_TO_MESSAGE("reply_to_message"),
    PLAY_MEDIA("play_media"),
    MEDIA_CONTROL("media_control"),
    NAVIGATE("navigate"),
    DIAL_USSD("dial_ussd");

    companion object {
        private val byWire = entries.associateBy { it.wireName }
        fun fromWireName(name: String): ActionId? = byWire[name.trim().lowercase()]
    }
}

/**
 * Platform capabilities an action may depend on. The Android layer maps these
 * to runtime permissions or special access (notification listener, DND policy).
 */
enum class Capability(val displayName: String) {
    MICROPHONE("Microphone"),
    CONTACTS("Contacts"),
    POST_NOTIFICATIONS("Notifications"),
    NOTIFICATION_LISTENER("Notification access"),
    DND_POLICY("Do Not Disturb access"),
}

/** A single, structured request to run an allowlisted action. */
@Serializable
data class ActionRequest(
    val action: ActionId,
    val params: Map<String, String> = emptyMap(),
    /** Optional per-parameter clarification questions that override the defaults. */
    val prompts: Map<String, String> = emptyMap(),
) {
    fun param(name: String): String? = params[name]?.takeIf { it.isNotBlank() }
    fun with(name: String, value: String): ActionRequest = copy(params = params + (name to value))
    fun without(name: String): ActionRequest = copy(params = params - name)
}

enum class FailureReason {
    APP_NOT_INSTALLED,
    PERMISSION_MISSING,
    UNSUPPORTED_ON_DEVICE,
    INVALID_PARAMETERS,
    NOT_FOUND,
    NETWORK,
    PROVIDER_ERROR,
    BLOCKED_BY_POLICY,
    UNKNOWN,
}

/**
 * The truthful outcome of an action. [Prepared] exists so the assistant never
 * claims to have done something that the user still has to complete
 * (for example tapping "Send" in WhatsApp).
 */
sealed interface ActionResult {
    val message: String

    data class Completed(override val message: String) : ActionResult
    data class Prepared(override val message: String) : ActionResult
    data class Failed(
        override val message: String,
        val reason: FailureReason,
        val recoverable: Boolean = false,
    ) : ActionResult
    data class Cancelled(override val message: String = "Cancelled.") : ActionResult

    val isSuccess: Boolean get() = this is Completed || this is Prepared
}

/** Whether an action finishes inside VoicePilot or hands over to another app. */
enum class OutcomeKind { COMPLETES, HANDS_OFF }
