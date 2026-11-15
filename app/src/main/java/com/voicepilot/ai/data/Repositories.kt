package com.voicepilot.ai.data

import com.voicepilot.ai.data.db.HistoryDao
import com.voicepilot.ai.data.db.HistoryEntity
import com.voicepilot.ai.data.db.MemoryDao
import com.voicepilot.ai.data.db.MemoryEntity
import com.voicepilot.ai.data.db.NoteEntity
import com.voicepilot.ai.data.db.NotesDao
import com.voicepilot.ai.data.db.RoutineDao
import com.voicepilot.ai.data.db.RoutineEntity
import com.voicepilot.ai.data.db.TaskEntity
import com.voicepilot.ai.data.db.UsageDao
import com.voicepilot.ai.data.db.UsageEntity
import com.voicepilot.core.contacts.AliasLookup
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryRepository
import com.voicepilot.core.history.HistoryStatus
import com.voicepilot.core.memory.MemoryItem
import com.voicepilot.core.memory.MemoryKind
import com.voicepilot.core.memory.MemoryRepository
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.memory.RoutineRepository
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.subscription.UsageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
    private val json: Json,
) : HistoryRepository {

    override fun observe(limit: Int): Flow<List<HistoryEntry>> = dao.observe(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun add(entry: HistoryEntry) {
        dao.insert(
            HistoryEntity(
                timestamp = entry.timestampMillis,
                utterance = entry.utterance.take(MAX_TEXT),
                intent = entry.intent,
                actionTitle = entry.actionTitle,
                appLabel = entry.appLabel,
                status = entry.status.name,
                resultMessage = entry.resultMessage.take(MAX_TEXT),
                errorReason = entry.errorReason,
                source = entry.source,
                retryJson = entry.retryRequest?.let { json.encodeToString(ActionRequest.serializer(), it) },
            ),
        )
    }

    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun clear() = dao.clear()
    override suspend fun deleteOlderThan(cutoffMillis: Long) = dao.deleteOlderThan(cutoffMillis)

    private fun HistoryEntity.toDomain() = HistoryEntry(
        id = id,
        timestampMillis = timestamp,
        utterance = utterance,
        intent = intent,
        actionTitle = actionTitle,
        appLabel = appLabel,
        status = runCatching { HistoryStatus.valueOf(status) }.getOrDefault(HistoryStatus.FAILED),
        resultMessage = resultMessage,
        errorReason = errorReason,
        source = source,
        retryRequest = retryJson?.let { runCatching { json.decodeFromString(ActionRequest.serializer(), it) }.getOrNull() },
    )

    private companion object {
        const val MAX_TEXT = 2000
    }
}

@Singleton
class RoomMemoryRepository @Inject constructor(private val dao: MemoryDao) : MemoryRepository, AliasLookup {

    override fun observeAll(): Flow<List<MemoryItem>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }

    override suspend fun find(kind: MemoryKind, key: String): MemoryItem? =
        dao.find(kind.name, MemoryRepository.normalizeKey(key))?.toDomain()

    override suspend fun upsert(item: MemoryItem): Long = dao.upsert(
        MemoryEntity(
            id = item.id,
            kind = item.kind.name,
            key = MemoryRepository.normalizeKey(item.key),
            value = item.value,
            createdAt = item.createdAtMillis,
            updatedAt = item.updatedAtMillis,
        ),
    )

    override suspend fun delete(id: Long) = dao.delete(id)
    override suspend fun clear() = dao.clear()

    override suspend fun contactNameFor(alias: String): String? =
        find(MemoryKind.CONTACT_ALIAS, alias)?.value

    private fun MemoryEntity.toDomain(): MemoryItem? {
        val k = runCatching { MemoryKind.valueOf(kind) }.getOrNull() ?: return null
        return MemoryItem(id, k, key, value, createdAt, updatedAt)
    }
}

/** Alias lookup that respects the memory toggle. */
class SettingsAwareAliasLookup(
    private val delegate: AliasLookup,
    private val memoryEnabled: () -> Boolean,
) : AliasLookup {
    override suspend fun contactNameFor(alias: String): String? = if (memoryEnabled()) delegate.contactNameFor(alias) else null
}

@Singleton
class RoomRoutineRepository @Inject constructor(
    private val dao: RoutineDao,
    private val json: Json,
) : RoutineRepository {
    private val stepsSerializer = ListSerializer(ActionRequest.serializer())

    override fun observeAll(): Flow<List<Routine>> = dao.observeAll().map { list -> list.mapNotNull { it.toDomain() } }
    override suspend fun all(): List<Routine> = dao.all().mapNotNull { it.toDomain() }

    override suspend fun upsert(routine: Routine): Long = dao.upsert(
        RoutineEntity(
            id = routine.id,
            name = routine.name.trim(),
            triggers = routine.triggerPhrases.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
            stepsJson = json.encodeToString(stepsSerializer, routine.steps),
            enabled = routine.enabled,
        ),
    )

    override suspend fun delete(id: Long) = dao.delete(id)

    private fun RoutineEntity.toDomain(): Routine? = runCatching {
        Routine(id, name, triggers.split('\n').filter { it.isNotBlank() }, json.decodeFromString(stepsSerializer, stepsJson), enabled)
    }.getOrNull()
}

@Singleton
class NotesRepository @Inject constructor(private val dao: NotesDao) {
    fun notes(): Flow<List<NoteEntity>> = dao.observeNotes()
    fun tasks(): Flow<List<TaskEntity>> = dao.observeTasks()
    suspend fun addNote(text: String, now: Long) = dao.insertNote(NoteEntity(text = text, createdAt = now))
    suspend fun addTask(title: String, due: String?, now: Long) = dao.insertTask(TaskEntity(title = title, due = due, done = false, createdAt = now))
    suspend fun setDone(id: Long, done: Boolean) = dao.setTaskDone(id, done)
    suspend fun deleteNote(id: Long) = dao.deleteNote(id)
    suspend fun deleteTask(id: Long) = dao.deleteTask(id)
    suspend fun clearAll() { dao.clearNotes(); dao.clearTasks() }
}

@Singleton
class RoomUsageStore @Inject constructor(private val dao: UsageDao) : UsageStore {
    override suspend fun count(day: LocalDate): Int = dao.count(day.toString()) ?: 0
    override suspend fun increment(day: LocalDate): Int {
        val next = count(day) + 1
        dao.upsert(UsageEntity(day.toString(), next))
        return next
    }
}
