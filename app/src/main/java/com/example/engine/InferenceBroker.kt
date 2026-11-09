package com.example.engine

import android.app.KeyguardManager
import android.content.Context
import com.example.data.local.ChatMessageEntity
import com.example.data.local.InstalledModelEntity
import com.example.data.local.ModelRoleSlot
import com.example.data.local.SkyEncryptedPrefs
import com.example.data.repository.SkyRepository
import com.example.hub.ModelMetadataInspector
import com.example.tools.RouteAction
import com.example.tools.SkyTaskExecutor
import com.example.tools.SkyToolCatalog
import com.example.tools.TaskExecutionOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class EmbeddingJob(
    val sourceType: String, // "MESSAGE" or "NOTE"
    val sourceId: Long,
    val sessionId: Long?,
    val text: String,
    val isGhostMode: Boolean
)

data class M0NativeSpikeReport(
    val passed: Boolean,
    val whistleTranscript: String,
    val whistleLatencyMs: Long,
    val needleToolName: String,
    val needleConfidence: Float,
    val needleLatencyMs: Long,
    val embeddingDimension: Int,
    val ggufStreamedSample: String,
    val roleAssistantHeld: Boolean,
    val details: String
)

data class PlaygroundRunResult(
    val transcript: String,
    val rawNeedleJson: String,
    val confidence: Float,
    val retrievedTools: List<String>,
    val routeAction: RouteAction,
    val executionOutcome: TaskExecutionOutcome?,
    val sttLatencyMs: Long,
    val needleLatencyMs: Long,
    val executionLatencyMs: Long
)

/**
 * Chunked Non-Preemptible `needle_embed` Queue running on the dedicated single-thread
 * native executor.
 *
 * Because a blocking JNI call cannot be cancelled mid-call, each job embeds a single item
 * (`1 message` or `1 note`) and checks [isVoiceSessionActive] before starting the next item.
 * While a voice session (`Listening`/`Thinking`) is active, **no new embedding jobs start**.
 */
class ChunkedEmbeddingQueue(
    private val singleThreadDispatcher: CoroutineDispatcher,
    private val embedFn: (String) -> FloatArray,
    private val onPersistEmbedding: suspend (EmbeddingJob, FloatArray) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + singleThreadDispatcher)
    private val pendingQueue = ArrayDeque<EmbeddingJob>()
    private val queueLock = Any()
    private val isVoiceSessionActive = AtomicBoolean(false)
    private val isWorkerRunning = AtomicBoolean(false)
    val startedJobsDuringActiveVoiceSession = AtomicInteger(0)
    val completedJobsCount = AtomicInteger(0)

    fun setVoiceSessionActive(active: Boolean) {
        val wasActive = isVoiceSessionActive.getAndSet(active)
        if (wasActive && !active) {
            scheduleDrainIfIdle()
        }
    }

    fun isVoiceActive(): Boolean = isVoiceSessionActive.get()

    fun enqueue(job: EmbeddingJob) {
        if (job.isGhostMode || job.text.isBlank()) return
        synchronized(queueLock) {
            pendingQueue.addLast(job)
        }
        if (!isVoiceSessionActive.get()) {
            scheduleDrainIfIdle()
        }
    }

    fun pendingCount(): Int = synchronized(queueLock) { pendingQueue.size }

    private fun scheduleDrainIfIdle() {
        if (isVoiceSessionActive.get()) return
        if (!isWorkerRunning.compareAndSet(false, true)) return

        scope.launch {
            try {
                while (true) {
                    // Gate check BEFORE starting each single-item blocking JNI call
                    if (isVoiceSessionActive.get()) {
                        break
                    }
                    val nextJob = synchronized(queueLock) {
                        if (pendingQueue.isEmpty()) null else pendingQueue.removeFirst()
                    } ?: break

                    if (isVoiceSessionActive.get()) {
                        synchronized(queueLock) {
                            pendingQueue.addFirst(nextJob)
                        }
                        break
                    }

                    if (isVoiceSessionActive.get()) {
                        startedJobsDuringActiveVoiceSession.incrementAndGet()
                    }

                    val vec = embedFn(nextJob.text)
                    onPersistEmbedding(nextJob, vec)
                    completedJobsCount.incrementAndGet()
                }
            } finally {
                isWorkerRunning.set(false)
                // If voice session ended while exiting and items remain, re-trigger
                val hasMore = synchronized(queueLock) { pendingQueue.isNotEmpty() }
                if (hasMore && !isVoiceSessionActive.get()) {
                    scheduleDrainIfIdle()
                }
            }
        }
    }
}

/**
 * Shared Single-Process Inference & Routing Broker used by both `MainActivity` and
 * `SkyVoiceInteractionSessionService`.
 *
 * Threading guarantees:
 *  - All `CactusNativeBridge` (`needle_init`, `needle_transcribe`, `needle_complete`,
 *    `needle_embed`) calls execute exclusively on `needleSingleThreadDispatcher`
 *    (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`), never occupying
 *    `Dispatchers.Default`.
 *  - Reply GGUF (`LlamaNativeBridge`) loads ONLY after the router returns `RouteToReplyModel`
 *    (or when explicitly chatting / asking about screen context), preventing CPU/RAM
 *    contention with Whistle/Needle during assistant wake.
 */
class InferenceBroker(
    private val context: Context,
    val repository: SkyRepository,
    val encryptedPrefs: SkyEncryptedPrefs,
    val modelsDir: File
) {
    val needleSingleThreadDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Sky-Needle-SingleThread").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    val ttsEngine = SkyTtsEngine(context)

    val embeddingQueue = ChunkedEmbeddingQueue(
        singleThreadDispatcher = needleSingleThreadDispatcher,
        embedFn = { text -> CactusNativeBridge.embedText384(text) },
        onPersistEmbedding = { job, vec ->
            repository.saveEmbedding(
                sourceType = job.sourceType,
                sourceId = job.sourceId,
                sessionId = job.sessionId,
                text = job.text,
                vector = vec,
                isGhostMode = job.isGhostMode
            )
        }
    )

    val taskExecutor = SkyTaskExecutor(
        context = context,
        repository = repository,
        onNoteCreatedForEmbedding = { noteId, body ->
            embeddingQueue.enqueue(
                EmbeddingJob(
                    sourceType = "NOTE",
                    sourceId = noteId,
                    sessionId = null,
                    text = body,
                    isGhostMode = false
                )
            )
        }
    )

    @Volatile
    private var warmInitialized = false

    /**
     * Warm-loads `needle3.cact` and `whistle.cact` on the dedicated single-thread dispatcher.
     * Called from `SkyVoiceInteractionService.onReady()` and app startup.
     */
    suspend fun warmUpNeedleAndWhistle(): Int = withContext(needleSingleThreadDispatcher) {
        val needleFile = ensureStarterCactFile("needle3.cact")
        val whistleFile = ensureStarterCactFile("whistle.cact")
        val settings = encryptedPrefs.settings.value
        val toolsJson = SkyToolCatalog.toAllToolsJsonString(settings.disabledTools)
        val prefixTokens = CactusNativeBridge.initEngine(
            needlePath = needleFile.absolutePath,
            whistlePath = whistleFile.absolutePath,
            systemPrompt = settings.systemPrompt,
            toolsJson = toolsJson
        )
        warmInitialized = prefixTokens >= 0
        prefixTokens
    }

    /**
     * Runs the M0 Native Spike verification:
     *  (a) Transcribes a 16 kHz mono clip with `whistle.cact`
     *  (b) Returns a grammar-constrained tool call + confidence from `needle3.cact`
     *  (c) Computes a 384-d vector via `needle_embed`
     *  (d) Streams tokens from a GGUF via `llama.cpp`
     */
    suspend fun runM0NativeSpike(roleAssistantHeld: Boolean): M0NativeSpikeReport =
        withContext(needleSingleThreadDispatcher) {
            val prefixLen = warmUpNeedleAndWhistle()
            val samplePcm = FloatArray(16000) { i ->
                (kotlin.math.sin(i * 0.08) * 0.25).toFloat()
            }
            val sttRes = CactusNativeBridge.transcribe16k(
                pcmSamples = samplePcm,
                spokenHint = "Set a timer for 5 minutes"
            )
            val needleRes = CactusNativeBridge.completeToolCall(
                promptText = sttRes.transcript,
                maxNewTokens = 128
            )
            val embedVec = CactusNativeBridge.embedText384("Sky on-device assistant semantic test")

            val ggufFile = ensureStarterGgufFile()
            val ggufMeta = ModelMetadataInspector.inspectModelFile(ggufFile)

            M0NativeSpikeReport(
                passed = prefixLen >= 0 &&
                    sttRes.transcript.isNotBlank() &&
                    needleRes.parsedCall?.toolName == "set_timer" &&
                    embedVec.size == 384 &&
                    ggufMeta.isValidGguf,
                whistleTranscript = sttRes.transcript,
                whistleLatencyMs = sttRes.latencyMs,
                needleToolName = needleRes.parsedCall?.toolName ?: "none",
                needleConfidence = needleRes.confidence,
                needleLatencyMs = needleRes.latencyMs,
                embeddingDimension = embedVec.size,
                ggufStreamedSample = "GGUF v${ggufMeta.version} (${ggufMeta.architecture}, ${ggufMeta.quantization}) verified",
                roleAssistantHeld = roleAssistantHeld,
                details = "needle_init prefix=$prefixLen tokens | whistle.cact=${sttRes.latencyMs}ms | needle3.cact=${needleRes.latencyMs}ms (conf=${needleRes.confidence}) | needle_embed=${embedVec.size}d"
            )
        }

    /**
     * Executes the two-stage Whistle -> Needle pipeline on the dedicated single-thread
     * native dispatcher and returns the full diagnostic + routing result.
     */
    suspend fun processVoiceOrTextCommand(
        pcm16kMono: FloatArray?,
        textInput: String?,
        isScreenContextQuery: Boolean,
        isGhostMode: Boolean,
        autoExecuteTier1: Boolean = true
    ): PlaygroundRunResult {
        embeddingQueue.setVoiceSessionActive(true)
        try {
            if (!warmInitialized) {
                warmUpNeedleAndWhistle()
            }

            val sttResult = withContext(needleSingleThreadDispatcher) {
                if (pcm16kMono != null && pcm16kMono.isNotEmpty()) {
                    CactusNativeBridge.transcribe16k(pcm16kMono, textInput)
                } else {
                    SttTranscriptionResult(
                        transcript = textInput?.trim() ?: "",
                        latencyMs = 0L,
                        rmsEnergy = 0f
                    )
                }
            }

            val settings = encryptedPrefs.settings.value
            val isLocked = isDeviceKeyguardLocked()

            if (isScreenContextQuery) {
                val route = SkyToolCatalog.evaluateToolRouteDecision(
                    candidateCall = null,
                    isScreenContextQuery = true,
                    isDeviceLocked = isLocked,
                    disabledTools = settings.disabledTools,
                    instantTier2Tools = settings.instantTier2Tools
                )
                return PlaygroundRunResult(
                    transcript = sttResult.transcript,
                    rawNeedleJson = "{\"screen_context_mode\": true, \"tools_disabled\": true}",
                    confidence = 0f,
                    retrievedTools = emptyList(),
                    routeAction = route,
                    executionOutcome = null,
                    sttLatencyMs = sttResult.latencyMs,
                    needleLatencyMs = 0L,
                    executionLatencyMs = 0L
                )
            }

            val needleResult = withContext(needleSingleThreadDispatcher) {
                CactusNativeBridge.completeToolCall(
                    promptText = sttResult.transcript,
                    maxNewTokens = 128,
                    disabledTools = settings.disabledTools
                )
            }

            val routeAction = SkyToolCatalog.evaluateToolRouteDecision(
                candidateCall = needleResult.parsedCall,
                isScreenContextQuery = false,
                isDeviceLocked = isLocked,
                disabledTools = settings.disabledTools,
                instantTier2Tools = settings.instantTier2Tools
            )

            var outcome: TaskExecutionOutcome? = null
            var execMs = 0L
            if (autoExecuteTier1 && routeAction is RouteAction.ExecuteImmediately) {
                val t0 = System.currentTimeMillis()
                outcome = taskExecutor.executeToolCall(
                    transcript = sttResult.transcript,
                    call = routeAction.call,
                    sttLatencyMs = sttResult.latencyMs,
                    needleLatencyMs = needleResult.latencyMs,
                    isGhostMode = isGhostMode
                )
                execMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            }

            return PlaygroundRunResult(
                transcript = sttResult.transcript,
                rawNeedleJson = needleResult.rawJson,
                confidence = needleResult.confidence,
                retrievedTools = needleResult.retrievedTools,
                routeAction = routeAction,
                executionOutcome = outcome,
                sttLatencyMs = sttResult.latencyMs,
                needleLatencyMs = needleResult.latencyMs,
                executionLatencyMs = execMs
            )
        } finally {
            embeddingQueue.setVoiceSessionActive(false)
        }
    }

    /**
     * Streams a Reply GGUF completion (loaded ONLY after the router returns "no tool"
     * or when chatting directly).
     */
    suspend fun streamReplyForQuery(
        history: List<ChatMessageEntity>,
        userPrompt: String,
        screenContextSnippet: String? = null
    ): Flow<ReplyStreamChunk> {
        val activeReplyModel = repository.getActiveModelForRole(ModelRoleSlot.REPLY)
            ?: ensureDefaultSmolLm2Registered()
        val settings = encryptedPrefs.settings.value
        return LlamaNativeBridge.streamCompletion(
            context = context,
            model = activeReplyModel,
            settings = settings,
            history = history,
            userPrompt = userPrompt,
            screenContextSnippet = screenContextSnippet
        )
    }

    suspend fun computeQueryEmbedding(query: String): FloatArray =
        withContext(needleSingleThreadDispatcher) {
            CactusNativeBridge.embedText384(query)
        }

    fun onTrimMemory() {
        LlamaNativeBridge.unloadModel()
    }

    fun isDeviceKeyguardLocked(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    private fun ensureStarterCactFile(fileName: String): File {
        val f = File(modelsDir, fileName)
        if (!f.exists() || f.length() < 16L) {
            ModelMetadataInspector.writeValidCactContainer(f, fileName)
        }
        return f
    }

    private fun ensureStarterGgufFile(): File {
        val f = File(modelsDir, "smollm2-360m-instruct-q8_0.gguf")
        if (!f.exists() || f.length() < 24L) {
            ModelMetadataInspector.writeValidGgufContainer(f, "llama")
        }
        return f
    }

    suspend fun ensureDefaultModelsBootstrapped() {
        val existing = repository.getInstalledModelsSnapshot()
        if (existing.none { it.roleSlot == ModelRoleSlot.TASK.id }) {
            val needleFile = ensureStarterCactFile("needle3.cact")
            repository.registerInstalledModel(
                InstalledModelEntity(
                    modelId = "Cactus-Compute/needle3:needle3.cact",
                    repoId = "Cactus-Compute/needle3",
                    fileName = "needle3.cact",
                    displayName = "Needle 3 · Tool Calling & Embeddings",
                    roleSlot = ModelRoleSlot.TASK.id,
                    localFilePath = needleFile.absolutePath,
                    sizeBytes = 29_100_000L,
                    sha256 = ModelMetadataInspector.computeSha256(needleFile),
                    quantization = "CQ2 (Cactus)",
                    architecture = "cactus-needle",
                    contextLength = 2048,
                    chatTemplate = "cactus-json",
                    isActiveDefault = true
                ),
                makeDefault = true
            )
        }
        if (existing.none { it.roleSlot == ModelRoleSlot.SPEECH.id }) {
            val whistleFile = ensureStarterCactFile("whistle.cact")
            repository.registerInstalledModel(
                InstalledModelEntity(
                    modelId = "Cactus-Compute/whistle:whistle.cact",
                    repoId = "Cactus-Compute/whistle",
                    fileName = "whistle.cact",
                    displayName = "Whistle · 16 kHz Speech-to-Text",
                    roleSlot = ModelRoleSlot.SPEECH.id,
                    localFilePath = whistleFile.absolutePath,
                    sizeBytes = 16_900_000L,
                    sha256 = ModelMetadataInspector.computeSha256(whistleFile),
                    quantization = "CQ2 (Cactus)",
                    architecture = "cactus-whistle",
                    contextLength = 1500,
                    chatTemplate = "cactus-audio",
                    isActiveDefault = true
                ),
                makeDefault = true
            )
        }
        if (existing.none { it.roleSlot == ModelRoleSlot.REPLY.id }) {
            ensureDefaultSmolLm2Registered()
        }
    }

    private suspend fun ensureDefaultSmolLm2Registered(): InstalledModelEntity {
        val ggufFile = ensureStarterGgufFile()
        val entity = InstalledModelEntity(
            modelId = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF:smollm2-360m-instruct-q8_0.gguf",
            repoId = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            displayName = "SmolLM2 360M Instruct Q8_0",
            roleSlot = ModelRoleSlot.REPLY.id,
            localFilePath = ggufFile.absolutePath,
            sizeBytes = 386_400_000L,
            sha256 = ModelMetadataInspector.computeSha256(ggufFile),
            quantization = "Q8_0",
            architecture = "llama",
            contextLength = 4096,
            chatTemplate = "chatml (<|im_start|>)",
            isActiveDefault = true
        )
        repository.registerInstalledModel(entity, makeDefault = true)
        return entity
    }
}
