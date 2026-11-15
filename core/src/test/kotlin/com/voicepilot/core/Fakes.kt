package com.voicepilot.core

import com.voicepilot.core.action.ActionExecutor
import com.voicepilot.core.action.ActionHandler
import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.contacts.AliasLookup
import com.voicepilot.core.contacts.Contact
import com.voicepilot.core.contacts.ContactDirectory
import com.voicepilot.core.contacts.ContactResolver
import com.voicepilot.core.contacts.PhoneNumber
import com.voicepilot.core.conversation.AssistantSession
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.interpret.CommandInterpreter
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.memory.RoutineRepository
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.Capability
import com.voicepilot.core.planner.ActionDescriber
import com.voicepilot.core.planner.PlanRunner
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.subscription.Entitlements
import com.voicepilot.core.subscription.Plan
import com.voicepilot.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDateTime

/** Saturday 15 Nov 2026, 18:00 in Lagos. */
val TEST_NOW: LocalDateTime = LocalDateTime.of(2026, 11, 15, 18, 0)
val testClock: Clock = Clock.fixed(TEST_NOW)

class FakeContacts(vararg contacts: Contact) : ContactDirectory {
    val all = contacts.toList()
    override suspend fun search(query: String): List<Contact> = all
}

fun contact(id: String, name: String, vararg numbers: String) =
    Contact(id, name, numbers.mapIndexed { i, n -> PhoneNumber(n, "mobile", isPrimary = false) })

class FakeAliases(private val map: Map<String, String> = emptyMap()) : AliasLookup {
    override suspend fun contactNameFor(alias: String): String? = map[alias.removePrefix("my ")]
}

class FakeHistory : HistoryRepository {
    val entries = mutableListOf<HistoryEntry>()
    private val flow = MutableStateFlow<List<HistoryEntry>>(emptyList())
    override fun observe(limit: Int): Flow<List<HistoryEntry>> = flow
    override suspend fun add(entry: HistoryEntry) { entries += entry; flow.value = entries.toList() }
    override suspend fun delete(id: Long) {}
    override suspend fun clear() { entries.clear() }
    override suspend fun deleteOlderThan(cutoffMillis: Long) {}
}

class FakeRoutines(private val list: List<Routine>) : RoutineRepository {
    override fun observeAll(): Flow<List<Routine>> = MutableStateFlow(list)
    override suspend fun all() = list
    override suspend fun upsert(routine: Routine) = 0L
    override suspend fun delete(id: Long) {}
}

/** Records executed requests and returns a truthful result per action kind. */
class RecordingHandlers {
    val executed = mutableListOf<ActionRequest>()
    var failOn: Set<ActionId> = emptySet()

    val handlers: Map<ActionId, ActionHandler> = ActionId.entries.associateWith { id ->
        ActionHandler { req ->
            executed += req
            when {
                id in failOn -> ActionResult.Failed("${id.wireName} failed", com.voicepilot.core.model.FailureReason.UNKNOWN)
                id == ActionId.PREPARE_WHATSAPP_MESSAGE -> ActionResult.Prepared("Opened WhatsApp with your message. Tap Send to deliver it.")
                else -> ActionResult.Completed("Done: ${ActionDescriber.describe(req)}.")
            }
        }
    }
}

class TestEnv(
    contacts: FakeContacts = FakeContacts(
        contact("1", "Samuel Okafor", "0803 123 4567"),
        contact("2", "Ada Eze", "+234 805 555 0101"),
        contact("3", "Samuel Adeyemi", "08099990000"),
        contact("4", "Chidi Nwosu", "07011112222"),
    ),
    aliases: Map<String, String> = emptyMap(),
    granted: Set<Capability> = Capability.entries.toSet(),
    var settings: AssistantSettings = AssistantSettings(),
    plan: Plan = Plan.FREE,
    routines: List<Routine> = emptyList(),
    interpreter: CommandInterpreter = LocalRuleInterpreter(testClock),
    inbox: com.voicepilot.core.messaging.MessageInbox? = com.voicepilot.core.messaging.MessageInbox { sender, _ ->
        com.voicepilot.core.messaging.ReplyTarget(sender ?: "Samuel Okafor", "WhatsApp")
    },
) {
    val handlers = RecordingHandlers()
    val history = FakeHistory()
    var grantedCaps = granted
    val executor = ActionExecutor(ActionRegistry(), handlers.handlers, { it in grantedCaps }, { settings })
    val session = AssistantSession(
        interpreter = interpreter,
        executor = executor,
        planRunner = PlanRunner(executor),
        contacts = ContactResolver(contacts, FakeAliases(aliases)),
        routines = FakeRoutines(routines),
        history = history,
        settings = { settings },
        entitlements = { Entitlements.forPlan(plan) },
        clock = testClock,
        inbox = inbox,
    )
}
