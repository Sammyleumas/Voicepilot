package com.voicepilot.ai.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history", indices = [Index("timestamp")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val utterance: String,
    val intent: String,
    val actionTitle: String?,
    val appLabel: String?,
    val status: String,
    val resultMessage: String,
    val errorReason: String?,
    val source: String,
    /** JSON of the retryable ActionRequest (no resolved phone numbers). */
    val retryJson: String?,
)

@Entity(tableName = "memory", indices = [Index(value = ["kind", "key"], unique = true)])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val key: String,
    val value: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Newline-separated trigger phrases. */
    val triggers: String,
    /** JSON array of ActionRequest. */
    val stepsJson: String,
    val enabled: Boolean,
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val due: String?,
    val done: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAtMillis: Long,
    val delivered: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "usage")
data class UsageEntity(
    /** ISO date, e.g. 2026-11-15. */
    @PrimaryKey val day: String,
    val aiRequests: Int,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE kind = :kind AND `key` = :key LIMIT 1")
    suspend fun find(kind: String, key: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryEntity): Long

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memory")
    suspend fun clear()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY name")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY name")
    suspend fun all(): List<RoutineEntity>

    @Upsert
    suspend fun upsert(entity: RoutineEntity): Long

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Insert
    suspend fun insertNote(note: NoteEntity): Long

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM tasks ORDER BY done, createdAt DESC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Insert
    suspend fun insertTask(task: TaskEntity): Long

    @Query("UPDATE tasks SET done = :done WHERE id = :id")
    suspend fun setTaskDone(id: Long, done: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE delivered = 0")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders ORDER BY triggerAtMillis DESC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("UPDATE reminders SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface UsageDao {
    @Query("SELECT aiRequests FROM usage WHERE day = :day")
    suspend fun count(day: String): Int?

    @Upsert
    suspend fun upsert(entity: UsageEntity)
}

@Database(
    entities = [
        HistoryEntity::class, MemoryEntity::class, RoutineEntity::class, NoteEntity::class,
        TaskEntity::class, ReminderEntity::class, UsageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VoicePilotDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun memory(): MemoryDao
    abstract fun routines(): RoutineDao
    abstract fun notes(): NotesDao
    abstract fun reminders(): ReminderDao
    abstract fun usage(): UsageDao

    companion object {
        const val NAME = "voicepilot.db"
    }
}
