package com.example

import com.example.data.local.ToolRiskTier
import com.example.engine.ChunkedEmbeddingQueue
import com.example.engine.EmbeddingJob
import com.example.hub.HuggingFaceHubClient
import com.example.hub.ModelDownloadManager
import com.example.tools.ParsedToolCall
import com.example.tools.RouteAction
import com.example.tools.SkyToolCatalog
import com.example.ui.theme.SkyThemePalettes
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    /**
     * 1. WCAG 2.1 Contrast Token Verification Test:
     * Asserts that every ThemeSpec (Sky, Clay, Graphite in Light & Dark modes) meets:
     *  - >= 4.5:1 for primaryText on bg
     *  - >= 4.5:1 for mutedText on bg
     *  - >= 4.5:1 for accentText on bg
     *  - >= 4.5:1 for onAccentText on accentFill
     *  - >= 4.5:1 for statusText (successText, warningText, errorText) on bg
     *  - >= 3.0:1 for accent UI fills on bg
     */
    @Test
    fun verifyWcagContrastRatiosAcrossAllThemePalettes() {
        for (spec in SkyThemePalettes.AllSpecs) {
            val tag = "${spec.preset.displayName}-${if (spec.isDark) "Dark" else "Light"}"

            val primaryRatio = SkyThemePalettes.wcagContrastRatio(spec.primaryText, spec.bg)
            assertTrue("$tag primaryText on bg ($primaryRatio) < 4.5", primaryRatio >= 4.5)

            val mutedRatio = SkyThemePalettes.wcagContrastRatio(spec.mutedText, spec.bg)
            assertTrue("$tag mutedText on bg ($mutedRatio) < 4.5", mutedRatio >= 4.5)

            val accentTextRatio = SkyThemePalettes.wcagContrastRatio(spec.accentText, spec.bg)
            assertTrue("$tag accentText on bg ($accentTextRatio) < 4.5", accentTextRatio >= 4.5)

            val onAccentRatio = SkyThemePalettes.wcagContrastRatio(spec.onAccentText, spec.accentFill)
            assertTrue("$tag onAccentText on accentFill ($onAccentRatio) < 4.5", onAccentRatio >= 4.5)

            val successTextRatio = SkyThemePalettes.wcagContrastRatio(spec.successText, spec.bg)
            assertTrue("$tag successText on bg ($successTextRatio) < 4.5", successTextRatio >= 4.5)

            val warningTextRatio = SkyThemePalettes.wcagContrastRatio(spec.warningText, spec.bg)
            assertTrue("$tag warningText on bg ($warningTextRatio) < 4.5", warningTextRatio >= 4.5)

            val errorTextRatio = SkyThemePalettes.wcagContrastRatio(spec.errorText, spec.bg)
            assertTrue("$tag errorText on bg ($errorTextRatio) < 4.5", errorTextRatio >= 4.5)

            val accentUiRatio = SkyThemePalettes.wcagContrastRatio(spec.accent, spec.bg)
            assertTrue("$tag accent UI on bg ($accentUiRatio) < 3.0", accentUiRatio >= 3.0)
        }
    }

    /**
     * 2. Risk-Tier Routing, Screen Context Security Gate, and Reversibility Test:
     *  - Tier 1 executes immediately at confidence >= 0.70, or >= 0.45 with trigger keyword
     *  - Tier 2 (send_sms, place_call) requires confidence >= 0.85 and starts a 3s countdown
     *  - Tier 2 requires unlock when isDeviceLocked == true
     *  - Tier 2 executes immediately when overridden in instantTier2Tools
     *  - Screen context query (onHandleAssist) forces RouteToReplyModel with toolsDisabledForScreenContext = true
     *  - Reversible-only Undo flags match specification
     */
    @Test
    fun verifyRiskTierRoutingAndReversibilityFlags() {
        // Screen context security gate: untrusted AssistStructure must NEVER trigger tools
        val highConfidenceSms = ParsedToolCall(
            toolName = "send_sms",
            arguments = mapOf("recipient" to "Mom", "message" to "Hello"),
            argumentsJson = "{\"recipient\":\"Mom\",\"message\":\"Hello\"}",
            confidence = 0.99f,
            matchedTriggerKeyword = true
        )
        val screenRoute = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = highConfidenceSms,
            isScreenContextQuery = true,
            isDeviceLocked = false
        )
        assertTrue(screenRoute is RouteAction.RouteToReplyModel)
        assertTrue((screenRoute as RouteAction.RouteToReplyModel).toolsDisabledForScreenContext)

        // Tier 1 standard confidence (>= 0.70)
        val flashlightCall = ParsedToolCall(
            toolName = "flashlight",
            arguments = mapOf("state" to "on"),
            argumentsJson = "{\"state\":\"on\"}",
            confidence = 0.72f,
            matchedTriggerKeyword = false
        )
        val tier1Exec = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = flashlightCall,
            isScreenContextQuery = false,
            isDeviceLocked = false
        )
        assertTrue(tier1Exec is RouteAction.ExecuteImmediately)

        // Tier 1 trigger keyword floor (>= 0.45 when matchedTriggerKeyword == true)
        val timerTriggerCall = ParsedToolCall(
            toolName = "set_timer",
            arguments = mapOf("seconds" to "300"),
            argumentsJson = "{\"seconds\":300}",
            confidence = 0.52f,
            matchedTriggerKeyword = true
        )
        val tier1TriggerExec = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = timerTriggerCall,
            isScreenContextQuery = false,
            isDeviceLocked = false
        )
        assertTrue(tier1TriggerExec is RouteAction.ExecuteImmediately)

        // Tier 1 below threshold routes to Reply model
        val lowTier1 = timerTriggerCall.copy(confidence = 0.40f)
        val tier1Fallback = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = lowTier1,
            isScreenContextQuery = false,
            isDeviceLocked = false
        )
        assertTrue(tier1Fallback is RouteAction.RouteToReplyModel)

        // Tier 2 below 0.85 routes to Reply model
        val smsBelow85 = highConfidenceSms.copy(confidence = 0.80f)
        val smsFallback = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = smsBelow85,
            isScreenContextQuery = false,
            isDeviceLocked = false
        )
        assertTrue(smsFallback is RouteAction.RouteToReplyModel)

        // Tier 2 >= 0.85 on unlocked device -> 3s cancelable countdown
        val smsCountdown = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = highConfidenceSms,
            isScreenContextQuery = false,
            isDeviceLocked = false
        )
        assertTrue(smsCountdown is RouteAction.StartTier2Countdown)
        assertEquals(3, (smsCountdown as RouteAction.StartTier2Countdown).countdownSeconds)

        // Tier 2 on locked device -> RequireUnlockForTier2
        val smsLocked = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = highConfidenceSms,
            isScreenContextQuery = false,
            isDeviceLocked = true
        )
        assertTrue(smsLocked is RouteAction.RequireUnlockForTier2)

        // Tier 2 with per-tool "Run instantly" override -> ExecuteImmediately
        val smsInstant = SkyToolCatalog.evaluateToolRouteDecision(
            candidateCall = highConfidenceSms,
            isScreenContextQuery = false,
            isDeviceLocked = false,
            instantTier2Tools = setOf("send_sms")
        )
        assertTrue(smsInstant is RouteAction.ExecuteImmediately)

        // Verify Undo reversibility flags across tools
        val nonReversibleTools = listOf("set_alarm", "set_timer", "open_app", "navigate_to", "web_search", "send_sms", "place_call")
        for (name in nonReversibleTools) {
            val tool = SkyToolCatalog.byName(name)
            assertNotNull("Expected tool $name", tool)
            assertFalse("Tool $name must NOT claim Undo reversibility", tool!!.isReversible)
        }

        val reversibleTools = listOf("flashlight", "volume", "brightness", "do_not_disturb", "add_task", "complete_task", "take_note", "create_calendar_event")
        for (name in reversibleTools) {
            val tool = SkyToolCatalog.byName(name)
            assertNotNull("Expected tool $name", tool)
            assertTrue("Tool $name must support Undo reversibility", tool!!.isReversible)
        }

        // Verify Tier 2 classification
        assertEquals(ToolRiskTier.TIER_2, SkyToolCatalog.byName("send_sms")?.riskTier)
        assertEquals(ToolRiskTier.TIER_2, SkyToolCatalog.byName("place_call")?.riskTier)
    }

    /**
     * 3. Chunked Non-Preemptible `needle_embed` Queue Gating Test:
     * Asserts that while a voice session is active (`setVoiceSessionActive(true)`),
     * NO new embedding jobs start, and once the voice session ends (`setVoiceSessionActive(false)`),
     * all pending jobs drain and complete on the single-thread native executor.
     */
    @Test
    fun verifyChunkedEmbeddingQueueDefersWhileVoiceSessionActive() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val latch = CountDownLatch(3)

        val queue = ChunkedEmbeddingQueue(
            singleThreadDispatcher = dispatcher,
            embedFn = { FloatArray(384) { 0.1f } },
            onPersistEmbedding = { _, _ ->
                latch.countDown()
            }
        )

        // Activate voice session BEFORE enqueuing jobs
        queue.setVoiceSessionActive(true)

        queue.enqueue(EmbeddingJob("MESSAGE", 1L, 10L, "First message to embed", isGhostMode = false))
        queue.enqueue(EmbeddingJob("MESSAGE", 2L, 10L, "Second message to embed", isGhostMode = false))
        queue.enqueue(EmbeddingJob("NOTE", 3L, null, "Third note to embed", isGhostMode = false))
        // Ghost mode job should be ignored immediately
        queue.enqueue(EmbeddingJob("MESSAGE", 4L, 10L, "Private ghost message", isGhostMode = true))

        Thread.sleep(120)

        // Assert zero jobs started or completed while voice session is active
        assertEquals(0, queue.startedJobsDuringActiveVoiceSession.get())
        assertEquals(0, queue.completedJobsCount.get())
        assertEquals(3, queue.pendingCount())

        // End voice session -> pending jobs must now drain and complete
        queue.setVoiceSessionActive(false)
        val completedInTime = latch.await(3, TimeUnit.SECONDS)
        assertTrue("Expected all 3 queued embedding jobs to complete after voice session ended", completedInTime)
        assertEquals(0, queue.startedJobsDuringActiveVoiceSession.get())
        assertEquals(3, queue.completedJobsCount.get())
        assertEquals(0, queue.pendingCount())

        executor.shutdownNow()
    }

    /**
     * 4. Hugging Face Hub JSON Parsing, Split-GGUF Detection, and HTTP Range Resume Offset Test.
     */
    @Test
    fun verifyHuggingFaceHubParsingAndResumeOffset() {
        val hubClient = HuggingFaceHubClient()

        val sampleModelsJson = """
            [
              {"id": "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF", "downloads": 185000, "likes": 920, "gated": false},
              {"id": "meta-llama/Llama-3.2-1B-Instruct", "downloads": 500000, "likes": 2100, "gated": "auto"}
            ]
        """.trimIndent()

        val parsedModels = hubClient.parseModelsJsonArray(sampleModelsJson, isTaskSpeechTab = false)
        assertEquals(2, parsedModels.size)
        assertFalse(parsedModels[0].isGated)
        assertTrue(parsedModels[1].isGated)

        val sampleTreeJson = """
            [
              {"path": "README.md", "size": 1200},
              {"path": "smollm2-360m-instruct-q8_0.gguf", "size": 386400000, "lfs": {"size": 386400000, "oid": "abc123sha256"}},
              {"path": "model-00001-of-00002.gguf", "size": 2000000000}
            ]
        """.trimIndent()

        val parsedFiles = hubClient.parseRepoTreeJson("HuggingFaceTB/SmolLM2-360M-Instruct-GGUF", sampleTreeJson)
        assertEquals(2, parsedFiles.size)
        assertEquals("smollm2-360m-instruct-q8_0.gguf", parsedFiles[0].fileName)
        assertEquals("Q8_0", parsedFiles[0].quantization)
        assertEquals("abc123sha256", parsedFiles[0].sha256)
        assertEquals(1, parsedFiles[1].splitIndex)
        assertEquals(2, parsedFiles[1].splitTotal)

        // Verify HTTP Range resume offset calculation
        assertEquals(0L, ModelDownloadManager.computeResumeByteOffset(0L, 1000L))
        assertEquals(0L, ModelDownloadManager.computeResumeByteOffset(-50L, 1000L))
        assertEquals(450L, ModelDownloadManager.computeResumeByteOffset(450L, 1000L))
        assertEquals(1000L, ModelDownloadManager.computeResumeByteOffset(1200L, 1000L))
    }
}
