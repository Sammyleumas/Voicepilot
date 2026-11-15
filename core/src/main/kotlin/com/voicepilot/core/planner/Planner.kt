package com.voicepilot.core.planner

import com.voicepilot.core.action.ActionDefinition
import com.voicepilot.core.action.ActionExecutor
import com.voicepilot.core.action.Params
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.FailureReason
import com.voicepilot.core.model.RiskLevel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PlanStep(
    val index: Int,
    val request: ActionRequest,
    val definition: ActionDefinition,
    /** Indices of steps that must succeed first. */
    val dependsOn: Set<Int> = emptySet(),
    val needsConfirmation: Boolean = false,
) {
    val label: String get() = ActionDescriber.describe(request)
}

data class TaskPlan(val title: String, val steps: List<PlanStep>) {
    val risk: RiskLevel get() = steps.maxOfOrNull { it.definition.risk } ?: RiskLevel.LOW
    val needsConfirmation: Boolean get() = steps.any { it.needsConfirmation }
}

data class StepOutcome(val step: PlanStep, val result: ActionResult, val skipped: Boolean = false)

data class PlanReport(val outcomes: List<StepOutcome>) {
    val succeeded get() = outcomes.filter { it.result.isSuccess }
    val failed get() = outcomes.filter { it.result is ActionResult.Failed && !it.skipped }
    val skipped get() = outcomes.filter { it.skipped }
    val cancelled get() = outcomes.filter { it.result is ActionResult.Cancelled }
    val allSucceeded get() = outcomes.isNotEmpty() && outcomes.all { it.result.isSuccess }
}

sealed interface PlanEvent {
    data class StepStarted(val step: PlanStep, val total: Int) : PlanEvent
    data class StepFinished(val outcome: StepOutcome) : PlanEvent
    data class Finished(val report: PlanReport) : PlanEvent
}

/**
 * Runs a plan step by step, honouring dependencies and cancellation. A failed
 * step causes its dependants to be skipped; independent steps still run, and
 * the report lists completed and failed steps separately.
 */
class PlanRunner(private val executor: ActionExecutor) {

    fun run(plan: TaskPlan): Flow<PlanEvent> = flow {
        val outcomes = mutableListOf<StepOutcome>()
        val failedIndices = mutableSetOf<Int>()
        for (step in plan.steps) {
            currentCoroutineContext().ensureActive()
            val blockedBy = step.dependsOn.firstOrNull { it in failedIndices }
            val outcome = if (blockedBy != null) {
                StepOutcome(
                    step,
                    ActionResult.Failed("Skipped because an earlier step failed.", FailureReason.UNKNOWN),
                    skipped = true,
                )
            } else {
                emit(PlanEvent.StepStarted(step, plan.steps.size))
                StepOutcome(step, executor.execute(step.request))
            }
            if (!outcome.result.isSuccess) failedIndices += step.index
            outcomes += outcome
            emit(PlanEvent.StepFinished(outcome))
        }
        emit(PlanEvent.Finished(PlanReport(outcomes)))
    }
}

/** Human-readable descriptions of requests, used for confirmations and progress. */
object ActionDescriber {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun formatTime(hhmm: String): String =
        runCatching { LocalTime.parse(hhmm).format(TIME) }.getOrDefault(hhmm)

    fun formatDateTime(iso: String, today: LocalDate? = null): String = runCatching {
        val dt = LocalDateTime.parse(iso)
        val day = when (dt.toLocalDate()) {
            today -> "today"
            today?.plusDays(1) -> "tomorrow"
            else -> dt.format(DAY)
        }
        "$day at ${dt.format(TIME)}"
    }.getOrDefault(iso)

    fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1L) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1L) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1L) "" else "s"}" },
        ).joinToString(" ").ifEmpty { "0 seconds" }
    }

    fun describe(r: ActionRequest, today: LocalDate? = null): String {
        val p = r.params
        return when (r.action) {
            ActionId.OPEN_APP -> "Open ${p[Params.APP] ?: "an app"}"
            ActionId.OPEN_SETTINGS -> if (p[Params.PAGE] == "general" || p[Params.PAGE] == null) "Open Settings" else "Open ${pageName(p[Params.PAGE]!!)} settings"
            ActionId.LAUNCH_CAMERA -> if (p[Params.MODE] == "video") "Open the camera for video" else "Open the camera"
            ActionId.SET_FLASHLIGHT -> if (p[Params.ENABLED] == "false") "Turn off the flashlight" else "Turn on the flashlight"
            ActionId.SET_VOLUME -> when {
                p[Params.LEVEL] != null -> "Set media volume to ${p[Params.LEVEL]}%"
                p[Params.DIRECTION] == "mute" -> "Mute media volume"
                p[Params.DIRECTION] == "unmute" -> "Unmute media volume"
                else -> "Turn the volume ${p[Params.DIRECTION] ?: "up"}"
            }
            ActionId.SET_ALARM -> "Set an alarm for ${p[Params.TIME]?.let(::formatTime) ?: "a time you choose"}" +
                (p[Params.LABEL]?.let { " ($it)" } ?: "")
            ActionId.SET_TIMER -> "Start a timer for ${p[Params.SECONDS]?.toLongOrNull()?.let(::formatDuration) ?: "a duration you choose"}"
            ActionId.CREATE_REMINDER -> "Remind you to “${p[Params.TEXT]?.replaceFirstChar { it.lowercase() } ?: "…"}” " +
                (p[Params.AT]?.let { formatDateTime(it, today) } ?: "")
            ActionId.CREATE_CALENDAR_EVENT -> "Add “${p[Params.TITLE] ?: "event"}” to your calendar" +
                (p[Params.START]?.let { " for ${formatDateTime(it, today)}" } ?: "")
            ActionId.SAVE_NOTE -> "Save a note: “${p[Params.TEXT] ?: ""}”"
            ActionId.CREATE_TASK -> "Add the task “${p[Params.TITLE] ?: ""}”" + (p[Params.DUE]?.let { ", due ${formatDateTime(it, today)}" } ?: "")
            ActionId.PREPARE_WHATSAPP_MESSAGE -> "Open WhatsApp to ${p[Params.RECIPIENT_NAME] ?: p[Params.RECIPIENT] ?: "a contact"}" +
                (p[Params.MESSAGE]?.let { " with the message “$it”" } ?: "")
            ActionId.PREPARE_SMS -> "Open your SMS app to ${p[Params.RECIPIENT_NAME] ?: p[Params.RECIPIENT] ?: "a contact"}" +
                (p[Params.MESSAGE]?.let { " with the message “$it”" } ?: "")
            ActionId.SHARE_MESSAGE_TO_APP -> "Open ${p[Params.APP] ?: "the app"}" +
                (p[Params.MESSAGE]?.let { " with the message “$it”" } ?: "") +
                (p[Params.RECIPIENT]?.let { " for $it" } ?: "")
            ActionId.DIAL_CONTACT -> "Open the dialer to call ${p[Params.RECIPIENT_NAME] ?: p[Params.RECIPIENT] ?: "a contact"}"
            ActionId.SET_DO_NOT_DISTURB -> if (p[Params.ENABLED] == "false") "Turn off Do Not Disturb" else "Turn on Do Not Disturb"
            ActionId.WEB_SEARCH -> "Search the web for “${p[Params.QUERY] ?: ""}”"
            ActionId.REMEMBER_ALIAS -> "Remember that “${p[Params.ALIAS] ?: ""}” means ${p[Params.CONTACT] ?: "a contact"}"
        }.trim()
    }

    fun pageName(page: String): String = when (page) {
        "wifi" -> "Wi‑Fi"
        "network" -> "Mobile network"
        "date" -> "Date & time"
        "airplane" -> "Airplane mode"
        else -> page.replaceFirstChar { it.uppercaseChar() }
    }
}
