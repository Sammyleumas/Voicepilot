package com.voicepilot.core.history

import com.voicepilot.core.model.ActionRequest
import kotlinx.coroutines.flow.Flow

enum class HistoryStatus(val label: String) {
    COMPLETED("Completed"),
    PREPARED("Prepared"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    ANSWERED("Answered"),
    UNSUPPORTED("Not understood"),
}

/** One entry in the automation history. Stored locally only. */
data class HistoryEntry(
    val id: Long = 0,
    val timestampMillis: Long,
    val utterance: String,
    val intent: String,
    val actionTitle: String?,
    val appLabel: String?,
    val status: HistoryStatus,
    val resultMessage: String,
    val errorReason: String? = null,
    val source: String,
    /** The request that can be retried, when the action is retryable. */
    val retryRequest: ActionRequest? = null,
)

interface HistoryRepository {
    fun observe(limit: Int = 200): Flow<List<HistoryEntry>>
    suspend fun add(entry: HistoryEntry)
    suspend fun delete(id: Long)
    suspend fun clear()
    suspend fun deleteOlderThan(cutoffMillis: Long)
}
