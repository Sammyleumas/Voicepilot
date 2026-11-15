package com.voicepilot.ai.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicepilot.ai.automation.ReminderScheduler
import com.voicepilot.ai.billing.SubscriptionRepository
import com.voicepilot.ai.data.NotesRepository
import com.voicepilot.ai.data.db.NoteEntity
import com.voicepilot.ai.data.db.ReminderEntity
import com.voicepilot.ai.data.db.ReminderDao
import com.voicepilot.ai.data.db.TaskEntity
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.interpret.Interpretation
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.memory.MemoryItem
import com.voicepilot.core.memory.MemoryKind
import com.voicepilot.core.memory.MemoryRepository
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.memory.RoutineRepository
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.time.Clock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RoutineDraftResult {
    data class Saved(val name: String) : RoutineDraftResult
    data class Invalid(val message: String) : RoutineDraftResult
}

/** History, memory, routines, notes, tasks and reminders. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val memory: MemoryRepository,
    private val routines: RoutineRepository,
    private val notes: NotesRepository,
    private val reminders: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val subscription: SubscriptionRepository,
    private val clock: Clock,
) : ViewModel() {

    val historyEntries: StateFlow<List<HistoryEntry>> = history.observe(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val memories: StateFlow<List<MemoryItem>> = memory.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val routineList: StateFlow<List<Routine>> = routines.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val noteList: StateFlow<List<NoteEntity>> = notes.notes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val taskList: StateFlow<List<TaskEntity>> = notes.tasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val reminderList: StateFlow<List<ReminderEntity>> = reminders.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun deleteHistory(id: Long) = viewModelScope.launch { history.delete(id) }
    fun deleteMemory(id: Long) = viewModelScope.launch { memory.delete(id) }
    fun deleteNote(id: Long) = viewModelScope.launch { notes.deleteNote(id) }
    fun deleteTask(id: Long) = viewModelScope.launch { notes.deleteTask(id) }
    fun setTaskDone(id: Long, done: Boolean) = viewModelScope.launch { notes.setDone(id, done) }
    fun cancelReminder(id: Long) = viewModelScope.launch { scheduler.cancel(id) }
    fun deleteRoutine(id: Long) = viewModelScope.launch { routines.delete(id) }
    fun setRoutineEnabled(routine: Routine, enabled: Boolean) = viewModelScope.launch { routines.upsert(routine.copy(enabled = enabled)) }

    fun addAlias(alias: String, contactName: String) = viewModelScope.launch {
        if (alias.isBlank() || contactName.isBlank()) return@launch
        val now = clock.now().toEpochMilli()
        val existing = memory.find(MemoryKind.CONTACT_ALIAS, alias)
        memory.upsert(MemoryItem(existing?.id ?: 0, MemoryKind.CONTACT_ALIAS, alias, contactName.trim(), existing?.createdAtMillis ?: now, now))
        _message.value = "Saved."
    }

    /**
     * Builds routine steps from plain commands, one per line, using the same
     * on-device interpreter as voice commands so every step is an allowlisted action.
     */
    fun saveRoutine(id: Long, name: String, triggers: String, commands: String, onResult: (RoutineDraftResult) -> Unit) {
        viewModelScope.launch {
            val result = parseRoutine(name, triggers, commands)
            if (result is ParsedRoutine.Error) return@launch onResult(RoutineDraftResult.Invalid(result.message))
            val ok = result as ParsedRoutine.Ok
            val limits = subscription.entitlements.value
            val existing = routines.all()
            if (id == 0L && existing.size >= limits.maxRoutines) {
                return@launch onResult(RoutineDraftResult.Invalid("Your plan allows ${limits.maxRoutines} routines. Delete one or upgrade to Premium."))
            }
            routines.upsert(Routine(id = id, name = name.trim(), triggerPhrases = ok.triggers, steps = ok.steps))
            onResult(RoutineDraftResult.Saved(name.trim()))
        }
    }

    private sealed interface ParsedRoutine {
        data class Ok(val triggers: List<String>, val steps: List<ActionRequest>) : ParsedRoutine
        data class Error(val message: String) : ParsedRoutine
    }

    private fun parseRoutine(name: String, triggers: String, commands: String): ParsedRoutine {
        if (name.isBlank()) return ParsedRoutine.Error("Give the routine a name.")
        val triggerList = triggers.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(name.trim()) }
        val interpreter = LocalRuleInterpreter(clock)
        val steps = mutableListOf<ActionRequest>()
        val lines = commands.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ParsedRoutine.Error("Add at least one step, one command per line.")
        for (line in lines) {
            when (val r = interpreter.interpretText(line)) {
                is Interpretation.Actions -> steps += r.requests
                else -> return ParsedRoutine.Error("I don't understand the step “$line”. Try a simple command like “open Slack”.")
            }
        }
        return ParsedRoutine.Ok(triggerList, steps)
    }
}
