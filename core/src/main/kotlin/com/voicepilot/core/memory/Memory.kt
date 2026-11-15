package com.voicepilot.core.memory

import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.text.NameMatcher
import com.voicepilot.core.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

enum class MemoryKind(val label: String) {
    CONTACT_ALIAS("Contact nickname"),
    APP_ALIAS("App shortcut"),
    PREFERENCE("Preference"),
}

/** One user-approved memory. Never contains message contents. */
data class MemoryItem(
    val id: Long = 0,
    val kind: MemoryKind,
    val key: String,
    val value: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
)

interface MemoryRepository {
    fun observeAll(): Flow<List<MemoryItem>>
    suspend fun find(kind: MemoryKind, key: String): MemoryItem?
    suspend fun upsert(item: MemoryItem): Long
    suspend fun delete(id: Long)
    suspend fun clear()

    companion object {
        fun normalizeKey(key: String): String = TextNormalizer.normalize(key).removePrefix("my ").trim()
    }
}

/** A user-defined multi-step workflow, e.g. "Prepare my phone for work". */
@Serializable
data class Routine(
    val id: Long = 0,
    val name: String,
    val triggerPhrases: List<String>,
    val steps: List<ActionRequest>,
    val enabled: Boolean = true,
)

interface RoutineRepository {
    fun observeAll(): Flow<List<Routine>>
    suspend fun all(): List<Routine>
    suspend fun upsert(routine: Routine): Long
    suspend fun delete(id: Long)
}

/** Matches an utterance against enabled routines' trigger phrases and names. */
object RoutineMatcher {
    fun match(utterance: String, routines: List<Routine>): Routine? {
        val u = TextNormalizer.normalize(utterance)
            .removePrefix("please ").removePrefix("run ").removePrefix("start ").removeSuffix(" routine").trim()
        if (u.isEmpty()) return null
        return routines.asSequence()
            .filter { it.enabled && it.steps.isNotEmpty() }
            .map { r -> r to (r.triggerPhrases + r.name).maxOf { NameMatcher.score(u, it).let { s -> if (TextNormalizer.normalize(it) == u) 1.0 else s } } }
            .filter { it.second >= 0.88 }
            .maxByOrNull { it.second }
            ?.first
    }
}

/** Simple thread-safe in-memory repository, used for tests and previews. */
class InMemoryMemoryRepository : MemoryRepository {
    private val items = kotlinx.coroutines.flow.MutableStateFlow<List<MemoryItem>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<MemoryItem>> = items

    override suspend fun find(kind: MemoryKind, key: String): MemoryItem? =
        items.value.firstOrNull { it.kind == kind && it.key == MemoryRepository.normalizeKey(key) }

    override suspend fun upsert(item: MemoryItem): Long = synchronized(this) {
        val id = if (item.id == 0L) nextId++ else item.id
        items.value = items.value.filterNot { it.id == id } + item.copy(id = id, key = MemoryRepository.normalizeKey(item.key))
        id
    }

    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
    override suspend fun clear() { items.value = emptyList() }
}
