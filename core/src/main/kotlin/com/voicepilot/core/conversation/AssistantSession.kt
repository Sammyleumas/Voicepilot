package com.voicepilot.core.conversation

import com.voicepilot.core.action.ActionExecutor
import com.voicepilot.core.action.ParamSpec
import com.voicepilot.core.action.ParamType
import com.voicepilot.core.action.Params
import com.voicepilot.core.action.Preflight
import com.voicepilot.core.contacts.CandidateChooser
import com.voicepilot.core.contacts.ContactCandidate
import com.voicepilot.core.contacts.ContactResolution
import com.voicepilot.core.contacts.ContactResolver
import com.voicepilot.core.contacts.PhoneNumberNormalizer
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.history.HistoryStatus
import com.voicepilot.core.interpret.CommandInterpreter
import com.voicepilot.core.interpret.ConversationContext
import com.voicepilot.core.interpret.Interpretation
import com.voicepilot.core.interpret.InterpretationSource
import com.voicepilot.core.interpret.MessageRewriter
import com.voicepilot.core.memory.RoutineMatcher
import com.voicepilot.core.memory.RoutineRepository
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.FailureReason
import com.voicepilot.core.model.RiskLevel
import com.voicepilot.core.planner.ActionDescriber
import com.voicepilot.core.planner.PlanEvent
import com.voicepilot.core.planner.PlanReport
import com.voicepilot.core.planner.PlanRunner
import com.voicepilot.core.planner.PlanStep
import com.voicepilot.core.planner.TaskPlan
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.subscription.Entitlements
import com.voicepilot.core.text.NumberWords
import com.voicepilot.core.text.TextNormalizer
import com.voicepilot.core.text.YesNo
import com.voicepilot.core.text.YesNoParser
import com.voicepilot.core.time.Clock
import com.voicepilot.core.time.TimeExpressionParser
import com.voicepilot.core.voice.WakePhraseMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** The assistant's externally visible state, rendered by the UI. */
sealed interface AssistantStatus {
    data object Idle : AssistantStatus
    data object Processing : AssistantStatus
    data object Planning : AssistantStatus
    data class AwaitingInput(val question: String) : AssistantStatus
    data class AwaitingChoice(val question: String, val options: List<String>) : AssistantStatus
    data class AwaitingConfirmation(val plan: TaskPlan, val prompt: String) : AssistantStatus
    data class Executing(val step: Int, val total: Int, val label: String) : AssistantStatus
    data class NeedsPermission(val capabilities: Set<Capability>, val message: String) : AssistantStatus
    data class Success(val message: String) : AssistantStatus
    data class Error(val message: String, val hint: String? = null) : AssistantStatus
}

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val timestampMillis: Long,
    val plan: TaskPlan? = null,
    val report: PlanReport? = null,
)

/** What to say back to the user after handling an input. */
data class AssistantReply(val text: String, val status: AssistantStatus)

/**
 * Multi-turn conversation engine. It interprets input, fills missing details by
 * asking short questions, resolves contacts without guessing, asks for
 * confirmation according to policy, runs the plan and reports truthfully.
 *
 * All methods are safe to call from the main thread; work is suspending.
 */
class AssistantSession(
    private val interpreter: CommandInterpreter,
    private val executor: ActionExecutor,
    private val planRunner: PlanRunner,
    private val contacts: ContactResolver,
    private val routines: RoutineRepository?,
    private val history: HistoryRepository,
    private val settings: () -> AssistantSettings,
    private val entitlements: () -> Entitlements,
    private val clock: Clock,
    /** Received messages for replies; null when the notification assistant isn't available. */
    private val inbox: com.voicepilot.core.messaging.MessageInbox? = null,
) {
    private val _status = MutableStateFlow<AssistantStatus>(AssistantStatus.Idle)
    val status: StateFlow<AssistantStatus> = _status.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val mutex = Mutex()
    private val ids = AtomicLong(1)
    private val time = TimeExpressionParser(clock)
    private var pending: Pending? = null
    private val turns = ArrayDeque<Pair<String, String>>()

    private class Draft(
        val utterance: String,
        val intent: String,
        val source: InterpretationSource,
        val requests: MutableList<ActionRequest>,
        val preface: String?,
    ) {
        var prefaceShown = false
    }

    private sealed interface Pending {
        val draft: Draft

        data class Param(override val draft: Draft, val step: Int, val spec: ParamSpec, val attempts: Int = 0) : Pending
        data class Contact(override val draft: Draft, val step: Int, val candidates: List<ContactCandidate>, val attempts: Int = 0) : Pending
        data class Confirm(override val draft: Draft, val plan: TaskPlan) : Pending
        data class Capabilities(override val draft: Draft, val missing: Set<Capability>) : Pending
    }

    /** Handles typed or recognized text. */
    suspend fun submit(rawInput: String): AssistantReply = mutex.withLock {
        val input = WakePhraseMatcher(settings().wakePhrase).stripWakePhrase(rawInput).trim()
        if (input.isEmpty()) return@withLock reply("I'm listening. What would you like to do?", AssistantStatus.AwaitingInput("What would you like to do?"), addToChat = false)
        addMessage(ChatRole.USER, input)
        try {
            if (isCancelWord(input) && pending != null) {
                val draft = pending!!.draft
                pending = null
                record(draft, null, HistoryStatus.CANCELLED, "Cancelled by you.")
                return@withLock reply("Okay, cancelled.", AssistantStatus.Idle)
            }
            when (val p = pending) {
                null -> handleNew(input)
                is Pending.Param -> answerParam(p, input)
                is Pending.Contact -> answerContact(p, input)
                is Pending.Confirm -> answerConfirm(p, input)
                is Pending.Capabilities -> { pending = null; handleNew(input) }
            }
        } catch (e: CancellationException) {
            pending = null
            _status.value = AssistantStatus.Idle
            throw e
        }
    }

    /** Called by UI buttons. */
    suspend fun confirm(approved: Boolean): AssistantReply = submit(if (approved) "yes" else "no")

    /** Picks a candidate from an on-screen list. */
    suspend fun choose(index: Int): AssistantReply = submit((index + 1).toString())

    /** Retry after the user granted missing permissions. */
    suspend fun retryAfterPermissions(): AssistantReply = mutex.withLock {
        val p = pending as? Pending.Capabilities ?: return@withLock reply("There's nothing to retry.", AssistantStatus.Idle, addToChat = false)
        pending = null
        advance(p.draft)
    }

    /** Re-runs a request from history (goes through the full validation and confirmation pipeline). */
    suspend fun retry(request: ActionRequest, utterance: String): AssistantReply = mutex.withLock {
        pending = null
        addMessage(ChatRole.USER, "Retry: ${ActionDescriber.describe(request)}")
        advance(Draft(utterance, request.action.wireName, InterpretationSource.LOCAL_RULES, mutableListOf(request), null))
    }

    /** Drops any pending question or confirmation. */
    fun cancel() {
        pending = null
        _status.value = AssistantStatus.Idle
    }

    fun clearConversation() {
        cancel()
        turns.clear()
        _messages.value = emptyList()
    }

    // ------------------------------------------------------------------ flow

    private suspend fun handleNew(input: String): AssistantReply {
        _status.value = AssistantStatus.Processing
        val routine = routines?.let { repo -> RoutineMatcher.match(input, repo.all()) }
        val interpretation = if (routine != null) {
            Interpretation.Actions("routine:${routine.name}", routine.steps, InterpretationSource.ROUTINE, "Running your “${routine.name}” routine.")
        } else {
            interpreter.interpret(input, context())
        }
        return when (interpretation) {
            is Interpretation.Answer -> {
                recordSimple(input, "answer", HistoryStatus.ANSWERED, interpretation.text, interpretation.source)
                reply(interpretation.text, AssistantStatus.Success(interpretation.text))
            }
            is Interpretation.Clarify -> reply(interpretation.question, AssistantStatus.AwaitingInput(interpretation.question))
            is Interpretation.Unsupported -> {
                val msg = if (interpretation.source == InterpretationSource.LOCAL_RULES || interpretation.reason.length > 40) {
                    interpretation.reason.takeIf { it.length > 40 } ?: "Sorry, I can't do that yet."
                } else {
                    "Sorry, I can't help with that yet."
                }
                recordSimple(input, "unsupported", HistoryStatus.UNSUPPORTED, msg, interpretation.source)
                reply(msg, AssistantStatus.Error(msg, "Say \"what can you do\" to see examples."))
            }
            is Interpretation.Actions -> {
                val max = entitlements().maxPlanSteps
                if (interpretation.requests.size > max) {
                    val msg = "That needs ${interpretation.requests.size} steps, but your plan supports up to $max per request. " +
                        "Try splitting it up, or upgrade to Premium for longer workflows."
                    return reply(msg, AssistantStatus.Error(msg))
                }
                advance(Draft(input, interpretation.intent, interpretation.source, interpretation.requests.toMutableList(), interpretation.preface))
            }
        }
    }

    /** Walks every step, asking for whatever is missing, then confirms or executes. */
    private suspend fun advance(draft: Draft): AssistantReply {
        _status.value = AssistantStatus.Planning
        val steps = mutableListOf<PlanStep>()
        for (i in draft.requests.indices) {
            var request = draft.requests[i]
            val definition = executor.definition(request.action)
                ?: return fail(draft, "That action isn't supported.", FailureReason.BLOCKED_BY_POLICY)

            // Resolve recipients before asking other questions so we fail fast on unknown contacts.
            if (definition.spec(Params.PHONE) != null && request.param(Params.RECIPIENT) != null && request.param(Params.PHONE) == null) {
                when (val outcome = resolveRecipient(draft, i, request)) {
                    is RecipientOutcome.Updated -> { request = outcome.request; draft.requests[i] = request }
                    is RecipientOutcome.Stop -> return outcome.reply
                }
            }

            // Pin a reply to one concrete conversation so the confirmation names the person.
            if (request.action == com.voicepilot.core.model.ActionId.REPLY_TO_MESSAGE && executor.preflight(request) !is Preflight.NeedsCapabilities) {
                val target = inbox?.replyTarget(request.param(Params.SENDER), request.param(Params.APP))
                    ?: return fail(draft, "I can't find a message to reply to. Turn on the notification assistant and allow the app, then try again.", FailureReason.NOT_FOUND, recoverable = true)
                request = request.with(Params.SENDER, target.sender).with(Params.APP, target.appLabel)
                draft.requests[i] = request
            }

            when (val p = executor.preflight(request)) {
                is Preflight.Rejected -> return fail(draft, p.result.message, p.result.reason)
                is Preflight.NeedsInput -> {
                    val spec = p.missing.first()
                    val question = questionFor(request, spec)
                    pending = Pending.Param(draft, i, spec)
                    val text = listOfNotNull(draft.preface.takeIf { !draft.prefaceShown }, question).joinToString(" ")
                    draft.prefaceShown = true
                    return reply(text, AssistantStatus.AwaitingInput(question))
                }
                is Preflight.NeedsCapabilities -> {
                    pending = Pending.Capabilities(draft, p.missing)
                    val msg = "To ${ActionDescriber.describe(request).replaceFirstChar { it.lowercase() }}, VoicePilot needs " +
                        "${p.missing.joinToString(" and ") { it.displayName }} access. Grant it, then tap Retry."
                    return reply(msg, AssistantStatus.NeedsPermission(p.missing, msg))
                }
                is Preflight.Ready -> steps += PlanStep(i, p.request, p.definition, needsConfirmation = p.needsConfirmation)
            }
        }
        val plan = TaskPlan(title = draft.intent, steps = steps)
        if (plan.needsConfirmation) {
            pending = Pending.Confirm(draft, plan)
            val prompt = confirmationPrompt(plan, draft.preface)
            return reply(prompt, AssistantStatus.AwaitingConfirmation(plan, prompt), plan = plan)
        }
        return execute(draft, plan)
    }

    private sealed interface RecipientOutcome {
        data class Updated(val request: ActionRequest) : RecipientOutcome
        data class Stop(val reply: AssistantReply) : RecipientOutcome
    }

    private suspend fun resolveRecipient(draft: Draft, step: Int, request: ActionRequest): RecipientOutcome {
        val spoken = request.param(Params.RECIPIENT)!!
        val digits = spoken.filter { it.isDigit() }
        if (digits.length >= 7 && digits.length >= spoken.count { it.isLetter() }) {
            val intl = PhoneNumberNormalizer.toInternationalDigits(spoken, settings().defaultCountryCode)
                ?: return RecipientOutcome.Stop(fail(draft, "“$spoken” doesn't look like a valid phone number.", FailureReason.INVALID_PARAMETERS))
            return RecipientOutcome.Updated(request.with(Params.PHONE, intl).with(Params.RECIPIENT_NAME, spoken))
        }
        // Contacts permission is checked by preflight via conditional capabilities.
        val p = executor.preflight(request)
        if (p is Preflight.NeedsCapabilities && Capability.CONTACTS in p.missing) {
            pending = Pending.Capabilities(draft, p.missing)
            val msg = "To find $spoken in your contacts, VoicePilot needs Contacts access. Grant it, then tap Retry. " +
                "You can also say the phone number instead."
            return RecipientOutcome.Stop(reply(msg, AssistantStatus.NeedsPermission(p.missing, msg)))
        }
        return when (val r = contacts.resolve(spoken)) {
            is ContactResolution.Resolved -> RecipientOutcome.Updated(applyCandidate(request, r.candidate))
            is ContactResolution.NotFound -> RecipientOutcome.Stop(
                fail(draft, "I couldn't find “$spoken” in your contacts. Try their full name as saved in your phone.", FailureReason.NOT_FOUND, recoverable = true),
            )
            is ContactResolution.Ambiguous -> {
                pending = Pending.Contact(draft, step, r.candidates)
                RecipientOutcome.Stop(askChoice(r.candidates, spoken))
            }
        }
    }

    private fun applyCandidate(request: ActionRequest, c: ContactCandidate): ActionRequest {
        val intl = PhoneNumberNormalizer.toInternationalDigits(c.phone.number, settings().defaultCountryCode) ?: c.phone.number.filter(Char::isDigit)
        return request.with(Params.PHONE, intl).with(Params.RECIPIENT_NAME, c.contact.displayName)
    }

    private fun askChoice(candidates: List<ContactCandidate>, spoken: String): AssistantReply {
        val options = candidates.map { it.description }
        val question = if (candidates.size == 1) {
            "Did you mean ${options.first()}?"
        } else {
            "I found ${candidates.size} matches for “$spoken”: " +
                options.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString("; ") + ". Which one?"
        }
        return reply(question, AssistantStatus.AwaitingChoice(question, options))
    }

    private suspend fun answerParam(p: Pending.Param, input: String): AssistantReply {
        val request = p.draft.requests[p.step]
        val value = parseValue(p.spec, input, request)
        if (value == null) {
            if (p.attempts >= 1 || input.split(' ').size > 6) {
                // Probably a new command rather than an answer.
                pending = null
                return handleNew(input)
            }
            pending = p.copy(attempts = p.attempts + 1)
            val again = "Sorry, I didn't get that. ${retryHint(p.spec)}"
            return reply(again, AssistantStatus.AwaitingInput(again))
        }
        pending = null
        p.draft.requests[p.step] = request.with(p.spec.name, value)
        return advance(p.draft)
    }

    private suspend fun answerContact(p: Pending.Contact, input: String): AssistantReply {
        if (p.candidates.size > 1 && YesNoParser.parse(input) == YesNo.NO) {
            pending = null
            record(p.draft, null, HistoryStatus.CANCELLED, "No contact chosen.")
            return reply("Okay, I won't contact anyone.", AssistantStatus.Idle)
        }
        val chosen = if (p.candidates.size == 1) {
            when (YesNoParser.parse(input)) {
                YesNo.YES -> p.candidates.first()
                YesNo.NO -> { pending = null; record(p.draft, null, HistoryStatus.CANCELLED, "Contact not confirmed."); return reply("Okay, I won't use that contact.", AssistantStatus.Idle) }
                YesNo.UNKNOWN -> CandidateChooser.choose(input, p.candidates)
            }
        } else {
            CandidateChooser.choose(input, p.candidates)
        }
        if (chosen == null) {
            if (p.attempts >= 1) {
                pending = null
                return reply("I still couldn't tell which contact you meant, so I stopped. Please try again with the full name.", AssistantStatus.Error("Contact not selected."))
            }
            pending = p.copy(attempts = p.attempts + 1)
            val q = "Please say the number of the contact, for example “the first one”."
            return reply(q, AssistantStatus.AwaitingChoice(q, p.candidates.map { it.description }))
        }
        pending = null
        p.draft.requests[p.step] = applyCandidate(p.draft.requests[p.step], chosen)
        return advance(p.draft)
    }

    private suspend fun answerConfirm(p: Pending.Confirm, input: String): AssistantReply = when (YesNoParser.parse(input)) {
        YesNo.YES -> { pending = null; execute(p.draft, p.plan) }
        YesNo.NO -> {
            pending = null
            record(p.draft, null, HistoryStatus.CANCELLED, "Cancelled before running.")
            reply("Okay, I won't do that.", AssistantStatus.Idle)
        }
        YesNo.UNKNOWN -> if (input.split(' ').size >= 3) {
            pending = null
            handleNew(input)
        } else {
            val q = "Should I go ahead? Please say yes or no."
            reply(q, AssistantStatus.AwaitingConfirmation(p.plan, q))
        }
    }

    private suspend fun execute(draft: Draft, plan: TaskPlan): AssistantReply {
        var report: PlanReport? = null
        planRunner.run(plan).collect { event ->
            when (event) {
                is PlanEvent.StepStarted -> _status.value = AssistantStatus.Executing(event.step.index + 1, event.total, event.step.label)
                is PlanEvent.StepFinished -> Unit
                is PlanEvent.Finished -> report = event.report
            }
        }
        val r = report ?: return fail(draft, "Nothing was run.", FailureReason.UNKNOWN)
        r.outcomes.forEach { o ->
            val status = when (o.result) {
                is ActionResult.Completed -> HistoryStatus.COMPLETED
                is ActionResult.Prepared -> HistoryStatus.PREPARED
                is ActionResult.Failed -> HistoryStatus.FAILED
                is ActionResult.Cancelled -> HistoryStatus.CANCELLED
            }
            record(draft, o.step, status, o.result.message, (o.result as? ActionResult.Failed)?.reason?.name)
        }
        val text = summarize(r)
        val finalStatus = if (r.allSucceeded) AssistantStatus.Success(text) else AssistantStatus.Error(text, if (r.succeeded.isNotEmpty()) "Some steps finished." else null)
        return reply(text, finalStatus, report = r)
    }

    // ------------------------------------------------------------- helpers

    private fun parseValue(spec: ParamSpec, input: String, request: ActionRequest): String? {
        val text = input.trim().trimEnd('.', '!', '?')
        if (text.isEmpty()) return null
        return when (val type = spec.type) {
            ParamType.Time -> time.parseTimeOfDay(
                normalizeTimeAnswer(text),
                preferMorning = request.param(Params.LABEL)?.contains("wake", ignoreCase = true) == true,
            )?.toString()
            ParamType.DateTime -> time.parseDateTime(normalizeTimeAnswer(text))?.toString()
            is ParamType.Integer -> when (spec.name) {
                Params.SECONDS -> time.parseDuration(text)?.seconds?.toString()
                    ?: NumberWords.parse(text)?.let { (it * 60).toString() } // bare number = minutes
                else -> Regex("\\d+").find(text)?.value ?: NumberWords.parse(TextNormalizer.normalize(text).removeSuffix(" percent"))?.toString()
            }?.takeIf { it.toInt() in type.min..type.max }
            ParamType.Bool -> when {
                Regex("\\b(on|enable|start|yes)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "true"
                Regex("\\b(off|disable|stop|no)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "false"
                else -> null
            }
            is ParamType.Choice -> TextNormalizer.normalize(text).let { n -> type.values.firstOrNull { n == it || n.contains(it) } }
            ParamType.Phone -> PhoneNumberNormalizer.toInternationalDigits(text, settings().defaultCountryCode)
            ParamType.Ussd -> com.voicepilot.core.messaging.UssdCodes.fromSpeech(text)
            is ParamType.Text -> when (spec.name) {
                Params.MESSAGE -> MessageRewriter.toDirectSpeech(text, thirdPerson = true)
                Params.RECIPIENT -> text.removePrefix("to ").removePrefix("To ")
                else -> text.replaceFirstChar { it.uppercaseChar() }
            }.takeIf { it.isNotBlank() && it.length <= type.maxLength }
        }
    }

    /** "7" alone is a valid answer to "what time?", so give the parser a hint. */
    private fun normalizeTimeAnswer(text: String): String {
        val t = text.lowercase()
        return if (Regex("^\\s*(\\d{1,2}|${NumberWords.PATTERN})\\s*$").matches(t)) "at $t" else t
    }

    private fun retryHint(spec: ParamSpec): String = when (spec.type) {
        ParamType.Time -> "What time? For example, “6:30 AM”."
        ParamType.DateTime -> "When? For example, “tomorrow at 9am” or “in 2 hours”."
        is ParamType.Integer -> if (spec.name == Params.SECONDS) "How long? For example, “10 minutes”." else spec.question
        else -> spec.question
    }

    private fun questionFor(request: ActionRequest, spec: ParamSpec): String {
        val template = request.prompts[spec.name] ?: spec.question
        val who = request.param(Params.RECIPIENT_NAME)?.substringBefore(' ') ?: request.param(Params.RECIPIENT)?.let(::displayRecipient) ?: "them"
        return template.replace("{recipient}", who)
    }

    private fun displayRecipient(spoken: String): String {
        val s = spoken.trim()
        return if (s.startsWith("my ", ignoreCase = true)) "your ${s.drop(3)}" else s.replaceFirstChar { it.uppercaseChar() }
    }

    private fun confirmationPrompt(plan: TaskPlan, preface: String?): String {
        val today = clock.localNow().toLocalDate()
        val body = if (plan.steps.size == 1) {
            ActionDescriber.describe(plan.steps.first().request, today) + "."
        } else {
            "Here's the plan: " + plan.steps.mapIndexed { i, s -> "${i + 1}. ${ActionDescriber.describe(s.request, today)}" }.joinToString("; ") + "."
        }
        val handOff = plan.steps.any { it.definition.outcome == com.voicepilot.core.model.OutcomeKind.HANDS_OFF && it.needsConfirmation }
        val note = when {
            plan.steps.any { it.request.action == com.voicepilot.core.model.ActionId.PREPARE_WHATSAPP_MESSAGE } ->
                " You'll tap Send in WhatsApp to deliver it."
            plan.steps.any { it.request.action == com.voicepilot.core.model.ActionId.REPLY_TO_MESSAGE } ->
                " This sends right away through the app's reply button."
            plan.steps.any { it.request.action == com.voicepilot.core.model.ActionId.PREPARE_SMS } ->
                " You'll tap Send in your messaging app to deliver it."
            plan.steps.any { it.request.action == com.voicepilot.core.model.ActionId.SHARE_MESSAGE_TO_APP } ->
                " In ${plan.steps.first { it.request.action == com.voicepilot.core.model.ActionId.SHARE_MESSAGE_TO_APP }.request.param(Params.APP)}, you'll pick the chat and tap Send."
            handOff -> " You'll finish the last step in the other app."
            else -> ""
        }
        val risk = if (plan.risk == RiskLevel.HIGH) " This is a sensitive action." else ""
        val found = plan.steps.firstNotNullOfOrNull { it.request.param(Params.RECIPIENT_NAME) }
            ?.takeIf { plan.steps.any { s -> s.request.param(Params.RECIPIENT) != null && s.request.param(Params.RECIPIENT)!!.any(Char::isLetter) } }
            ?.let { "I found $it. " } ?: ""
        val intro = preface?.takeIf { plan.steps.size > 1 }?.let { "$it " } ?: ""
        return "$intro$found$body$note$risk Shall I go ahead?".replace("  ", " ").trim()
    }

    private fun summarize(r: PlanReport): String {
        if (r.outcomes.size == 1) return r.outcomes.first().result.message
        val done = r.succeeded.map { it.result.message.trimEnd('.') }
        val failed = r.failed.map { "${it.step.label.replaceFirstChar { c -> c.lowercase() }} (${it.result.message.trimEnd('.')})" }
        val skipped = r.skipped.map { it.step.label.replaceFirstChar { c -> c.lowercase() } }
        return buildString {
            if (done.isNotEmpty()) append(done.joinToString(". ") + ". ")
            if (failed.isNotEmpty()) append("I couldn't ${failed.joinToString("; ")}. ")
            if (skipped.isNotEmpty()) append("Skipped: ${skipped.joinToString(", ")}.")
        }.trim()
    }

    private suspend fun fail(draft: Draft, message: String, reason: FailureReason, recoverable: Boolean = false): AssistantReply {
        pending = null
        record(draft, null, HistoryStatus.FAILED, message, reason.name)
        return reply(message, AssistantStatus.Error(message, if (recoverable) "You can try again with different details." else null))
    }

    private fun isCancelWord(input: String): Boolean =
        TextNormalizer.normalize(input) in setOf("cancel", "stop", "never mind", "nevermind", "forget it", "cancel that", "abort", "leave am", "forget am", "cancel am")

    private fun context(): ConversationContext {
        val s = settings()
        return ConversationContext(
            recentTurns = turns.toList(),
            userName = s.userName,
            assistantName = s.assistantName,
            nowIso = clock.localNow().withSecond(0).withNano(0).toString(),
            timeZone = clock.zone().id,
        )
    }

    private fun reply(
        text: String,
        status: AssistantStatus,
        addToChat: Boolean = true,
        plan: TaskPlan? = null,
        report: PlanReport? = null,
    ): AssistantReply {
        _status.value = status
        if (addToChat) {
            addMessage(ChatRole.ASSISTANT, text, plan, report)
            val lastUser = _messages.value.lastOrNull { it.role == ChatRole.USER }?.text
            if (lastUser != null) {
                turns.addLast(lastUser to text)
                while (turns.size > 6) turns.removeFirst()
            }
        }
        return AssistantReply(text, status)
    }

    private fun addMessage(role: ChatRole, text: String, plan: TaskPlan? = null, report: PlanReport? = null) {
        val msg = ChatMessage(ids.getAndIncrement(), role, text, clock.now().toEpochMilli(), plan, report)
        _messages.update { (it + msg).takeLast(MAX_MESSAGES) }
    }

    private suspend fun record(draft: Draft, step: PlanStep?, status: HistoryStatus, message: String, error: String? = null) {
        if (!settings().historyEnabled) return
        val request = step?.request ?: draft.requests.firstOrNull()
        val definition = request?.let { executor.definition(it.action) }
        history.add(
            HistoryEntry(
                timestampMillis = clock.now().toEpochMilli(),
                utterance = draft.utterance,
                intent = draft.intent,
                actionTitle = definition?.title,
                appLabel = definition?.appLabel ?: request?.param(Params.APP),
                status = status,
                resultMessage = message,
                errorReason = error,
                source = draft.source.name,
                retryRequest = request?.takeIf { status == HistoryStatus.FAILED || status == HistoryStatus.CANCELLED || status == HistoryStatus.COMPLETED || status == HistoryStatus.PREPARED }
                    ?.let { it.copy(params = it.params - Params.PHONE - Params.RECIPIENT_NAME, prompts = emptyMap()) },
            ),
        )
    }

    private suspend fun recordSimple(utterance: String, intent: String, status: HistoryStatus, message: String, source: InterpretationSource) {
        if (!settings().historyEnabled) return
        history.add(
            HistoryEntry(
                timestampMillis = clock.now().toEpochMilli(),
                utterance = utterance,
                intent = intent,
                actionTitle = null,
                appLabel = null,
                status = status,
                resultMessage = message,
                source = source.name,
            ),
        )
    }

    private companion object {
        const val MAX_MESSAGES = 100
    }
}
