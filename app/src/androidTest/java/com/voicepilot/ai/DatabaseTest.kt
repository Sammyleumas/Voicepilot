package com.voicepilot.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicepilot.ai.data.RoomHistoryRepository
import com.voicepilot.ai.data.RoomMemoryRepository
import com.voicepilot.ai.data.RoomRoutineRepository
import com.voicepilot.ai.data.db.VoicePilotDatabase
import com.voicepilot.core.action.Params
import com.voicepilot.core.history.HistoryEntry
import com.voicepilot.core.history.HistoryStatus
import com.voicepilot.core.memory.MemoryItem
import com.voicepilot.core.memory.MemoryKind
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: VoicePilotDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VoicePilotDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun historyRoundTripKeepsRetryRequest() = runTest {
        val repo = RoomHistoryRepository(db.history(), json)
        val request = ActionRequest(ActionId.SET_ALARM, mapOf(Params.TIME to "06:30"))
        repo.add(HistoryEntry(timestampMillis = 1, utterance = "wake me at 6:30", intent = "set_alarm", actionTitle = "Set alarm", appLabel = "Clock", status = HistoryStatus.COMPLETED, resultMessage = "ok", source = "LOCAL_RULES", retryRequest = request))
        val stored = repo.observe(10).first().single()
        assertEquals(request, stored.retryRequest)
        repo.deleteOlderThan(2)
        assertEquals(0, repo.observe(10).first().size)
    }

    @Test fun memoryAliasesAreNormalizedAndReplaced() = runTest {
        val repo = RoomMemoryRepository(db.memory())
        repo.upsert(MemoryItem(kind = MemoryKind.CONTACT_ALIAS, key = "My Brother", value = "Samuel Okafor", createdAtMillis = 1))
        assertEquals("Samuel Okafor", repo.contactNameFor("brother"))
        repo.upsert(MemoryItem(kind = MemoryKind.CONTACT_ALIAS, key = "brother", value = "Tunde", createdAtMillis = 2))
        assertEquals("Tunde", repo.contactNameFor("my brother"))
        repo.clear()
        assertNull(repo.contactNameFor("brother"))
    }

    @Test fun routinesStoreStepsAsJson() = runTest {
        val repo = RoomRoutineRepository(db.routines(), json)
        val steps = listOf(ActionRequest(ActionId.OPEN_APP, mapOf(Params.APP to "Slack")), ActionRequest(ActionId.SET_DO_NOT_DISTURB, mapOf(Params.ENABLED to "true")))
        repo.upsert(Routine(name = "Work", triggerPhrases = listOf("prepare my phone for work"), steps = steps))
        val r = repo.all().single()
        assertEquals(steps, r.steps)
        assertEquals(listOf("prepare my phone for work"), r.triggerPhrases)
    }
}
