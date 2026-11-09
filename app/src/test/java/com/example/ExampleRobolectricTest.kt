package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ChatMessageEntity
import com.example.data.local.SkyDatabase
import com.example.data.local.TaskExecutionLogEntity
import com.example.data.repository.SkyRepository
import com.example.tools.ParsedToolCall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: SkyDatabase
    private lateinit var repository: SkyRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SkyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val tempModelsDir = File(context.cacheDir, "test_models").apply { mkdirs() }
        repository = SkyRepository(db.skyDao(), tempModelsDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Verifies that Ghost Mode (incognito) writes ZERO rows to `chat_sessions`,
     * `chat_messages`, `embeddings`, and `task_execution_logs`.
     */
    @Test
    fun verifyGhostModeWritesZeroDatabaseRows() = runBlocking {
        val ghostSessionId = repository.createOrUpdateSession(
            sessionId = null,
            title = "Secret Ghost Session",
            isGhostMode = true
        )
        assertEquals(-1L, ghostSessionId)

        val ghostMsgId = repository.saveMessage(
            message = ChatMessageEntity(
                sessionId = 1L,
                role = "user",
                content = "Do not persist this message"
            ),
            isGhostMode = true
        )
        assertEquals(-1L, ghostMsgId)

        repository.saveEmbedding(
            sourceType = "MESSAGE",
            sourceId = 1L,
            sessionId = 1L,
            text = "Ghost embedding text",
            vector = FloatArray(384) { 0.5f },
            isGhostMode = true
        )

        val ghostLogId = repository.recordTaskLog(
            log = TaskExecutionLogEntity(
                transcript = "Turn on the flashlight",
                toolName = "flashlight",
                argumentsJson = "{\"state\":\"on\"}",
                riskTier = "tier_1",
                confidence = 0.96f,
                resultSummary = "Flashlight turned ON",
                executionPathUsed = "CameraManager.setTorchMode",
                sttLatencyMs = 45L,
                needleLatencyMs = 38L,
                executionLatencyMs = 8L,
                isReversible = true,
                undoPayloadJson = "{\"previousTorch\":false}"
            ),
            isGhostMode = true
        )
        assertEquals(-1L, ghostLogId)

        assertEquals(0, db.skyDao().getAllEmbeddings().size)
        assertEquals(0, repository.getMessagesSnapshot(1L).size)
    }

    /**
     * Verifies M0 Native Spike pipeline (Whistle 16 kHz transcription -> Needle 3 grammar-constrained
     * tool call + 384-d semantic embedding -> GGUF verification) executes end-to-end on the
     * dedicated single-thread native dispatcher.
     */
    @Test
    fun verifyM0NativeSpikeAndSemanticSearchRoundTrip() = runBlocking {
        val app = context as SkyApplication
        val report = app.inferenceBroker.runM0NativeSpike(roleAssistantHeld = false)

        assertNotNull(report)
        assertTrue("Expected M0 Native Spike to pass: ${report.details}", report.passed)
        assertEquals("set_timer", report.needleToolName)
        assertEquals(384, report.embeddingDimension)
        assertTrue(report.needleConfidence >= 0.85f)

        // Verify non-ghost session + semantic search via 384-d cosine similarity
        val sessionId = repository.createOrUpdateSession(
            sessionId = null,
            title = "Trip Planning",
            isGhostMode = false
        )
        assertTrue(sessionId > 0L)
        val msgId = repository.saveMessage(
            message = ChatMessageEntity(
                sessionId = sessionId,
                role = "user",
                content = "Pack hiking boots for Yosemite trail"
            ),
            isGhostMode = false
        )
        assertTrue(msgId > 0L)

        val vec = app.inferenceBroker.computeQueryEmbedding("Pack hiking boots for Yosemite trail")
        repository.saveEmbedding(
            sourceType = "MESSAGE",
            sourceId = msgId,
            sessionId = sessionId,
            text = "Pack hiking boots for Yosemite trail",
            vector = vec,
            isGhostMode = false
        )

        val queryVec = app.inferenceBroker.computeQueryEmbedding("Pack hiking boots for Yosemite trail")
        val hits = repository.semanticSearch(
            queryVector = queryVec,
            queryText = "Yosemite",
            topK = 5
        )
        assertTrue("Expected semantic search hit", hits.isNotEmpty())
        assertEquals(sessionId, hits.first().sessionId)

        // Verify silent task execution + Undo on reversible tool
        val outcome = app.inferenceBroker.taskExecutor.executeToolCall(
            transcript = "Add buy coffee beans to my tasks",
            call = ParsedToolCall(
                toolName = "add_task",
                arguments = mapOf("title" to "Buy coffee beans"),
                argumentsJson = "{\"title\":\"Buy coffee beans\"}",
                confidence = 0.94f,
                matchedTriggerKeyword = true
            ),
            sttLatencyMs = 40L,
            needleLatencyMs = 35L,
            isGhostMode = false
        )
        assertEquals("add_task", outcome.toolName)
        assertTrue(outcome.isReversible)
        assertNotNull(outcome.undoPayloadJson)
    }
}
