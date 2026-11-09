package com.example.ui

import android.app.role.RoleManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.SkyApplication
import com.example.assistant.AudioVadRecorder
import com.example.assistant.SkyAccessibilityService
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.InstalledModelEntity
import com.example.data.local.ModelRoleSlot
import com.example.data.local.NoteEntity
import com.example.data.local.SkySettingsState
import com.example.data.local.TaskExecutionLogEntity
import com.example.data.local.TodoItemEntity
import com.example.data.repository.SemanticSearchResult
import com.example.engine.EmbeddingJob
import com.example.engine.LlamaNativeBridge
import com.example.engine.M0NativeSpikeReport
import com.example.engine.PlaygroundRunResult
import com.example.hub.HubApiResult
import com.example.hub.HubModelFile
import com.example.hub.HubRepoItem
import com.example.tools.ParsedToolCall
import com.example.tools.RouteAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SkyDestination(val title: String) {
    CHAT("Chat"),
    TASKS("Tasks"),
    MODELS("Models"),
    SETTINGS("Settings"),
    SETUP_WIZARD("Setup Wizard"),
    PLAYGROUND("Tool Playground"),
    ABOUT("About Sky")
}

data class PendingChatTier2Countdown(
    val transcript: String,
    val call: ParsedToolCall,
    val remainingSeconds: Int,
    val sttMs: Long,
    val needleMs: Long
)

class SkyAppViewModel(private val app: SkyApplication) : ViewModel() {

    private val repository = app.repository
    private val broker = app.inferenceBroker
    private val encryptedPrefs = app.encryptedPrefs
    private val hubClient = app.hubClient
    private val downloadManager = app.downloadManager

    val settings: StateFlow<SkySettingsState> = encryptedPrefs.settings

    private val _currentDestination = MutableStateFlow(SkyDestination.CHAT)
    val currentDestination: StateFlow<SkyDestination> = _currentDestination.asStateFlow()

    private val _isGhostMode = MutableStateFlow(false)
    val isGhostMode: StateFlow<Boolean> = _isGhostMode.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private val _ghostMessages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeMessages: StateFlow<List<ChatMessageEntity>> = _activeSessionId
        .flatMapLatest { id ->
            if (_isGhostMode.value || id == null || id <= 0L) {
                _ghostMessages
            } else {
                repository.observeMessages(id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessions: StateFlow<List<ChatSessionEntity>> = repository.sessionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val todos: StateFlow<List<TodoItemEntity>> = repository.todosFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notes: StateFlow<List<NoteEntity>> = repository.notesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taskLogs: StateFlow<List<TaskExecutionLogEntity>> = repository.taskLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val installedModels: StateFlow<List<InstalledModelEntity>> = repository.installedModelsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadTasks: StateFlow<List<DownloadTaskEntity>> = repository.downloadTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Streaming chat state
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    private val _streamingThink = MutableStateFlow<String?>(null)
    val streamingThink: StateFlow<String?> = _streamingThink.asStateFlow()

    private val _streamingTokensPerSec = MutableStateFlow(0f)
    val streamingTokensPerSec: StateFlow<Float> = _streamingTokensPerSec.asStateFlow()

    private val _isReplyWarmingUp = MutableStateFlow(false)
    val isReplyWarmingUp: StateFlow<Boolean> = _isReplyWarmingUp.asStateFlow()

    private val _thermalBanner = MutableStateFlow<String?>(null)
    val thermalBanner: StateFlow<String?> = _thermalBanner.asStateFlow()

    // Dictation state
    private val _isDictating = MutableStateFlow(false)
    val isDictating: StateFlow<Boolean> = _isDictating.asStateFlow()

    private val _dictationRms = MutableStateFlow(0f)
    val dictationRms: StateFlow<Float> = _dictationRms.asStateFlow()

    // Tier 2 countdown inside chat
    private val _pendingTier2Countdown = MutableStateFlow<PendingChatTier2Countdown?>(null)
    val pendingTier2Countdown: StateFlow<PendingChatTier2Countdown?> = _pendingTier2Countdown.asStateFlow()
    private var tier2Job: Job? = null

    // Semantic Search state
    private val _semanticSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val semanticSearchResults: StateFlow<List<SemanticSearchResult>> = _semanticSearchResults.asStateFlow()

    // Model Hub state
    private val _hubRepos = MutableStateFlow<List<HubRepoItem>>(hubClient.curatedStarterAndRecommended)
    val hubRepos: StateFlow<List<HubRepoItem>> = _hubRepos.asStateFlow()

    private val _hubLoading = MutableStateFlow(false)
    val hubLoading: StateFlow<Boolean> = _hubLoading.asStateFlow()

    private val _gatedRepoBanner = MutableStateFlow<Pair<String, String>?>(null)
    val gatedRepoBanner: StateFlow<Pair<String, String>?> = _gatedRepoBanner.asStateFlow()

    private val _hubStatusMessage = MutableStateFlow<String?>(null)
    val hubStatusMessage: StateFlow<String?> = _hubStatusMessage.asStateFlow()

    // Playground & M0 Spike state
    private val _lastPlaygroundResult = MutableStateFlow<PlaygroundRunResult?>(null)
    val lastPlaygroundResult: StateFlow<PlaygroundRunResult?> = _lastPlaygroundResult.asStateFlow()

    private val _m0SpikeReport = MutableStateFlow<M0NativeSpikeReport?>(null)
    val m0SpikeReport: StateFlow<M0NativeSpikeReport?> = _m0SpikeReport.asStateFlow()

    private var activeGenerationJob: Job? = null

    init {
        viewModelScope.launch {
            runM0NativeSpikeVerification()
        }
    }

    fun navigateTo(destination: SkyDestination) {
        _currentDestination.value = destination
    }

    fun toggleGhostMode() {
        val next = !_isGhostMode.value
        _isGhostMode.value = next
        _ghostMessages.value = emptyList()
        if (next) {
            _activeSessionId.value = -1L
        } else {
            _activeSessionId.value = sessions.value.firstOrNull()?.id
        }
    }

    fun startNewChat(ghost: Boolean = _isGhostMode.value) {
        _isGhostMode.value = ghost
        _ghostMessages.value = emptyList()
        _activeSessionId.value = if (ghost) -1L else null
        _currentDestination.value = SkyDestination.CHAT
    }

    fun selectSession(sessionId: Long) {
        _isGhostMode.value = false
        _activeSessionId.value = sessionId
        _currentDestination.value = SkyDestination.CHAT
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId, newTitle)
        }
    }

    fun togglePinSession(sessionId: Long) {
        viewModelScope.launch {
            repository.togglePinSession(sessionId)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_activeSessionId.value == sessionId) {
                _activeSessionId.value = null
            }
        }
    }

    fun performSemanticSearch(query: String) {
        if (query.isBlank()) {
            _semanticSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val qVec = broker.computeQueryEmbedding(query)
            _semanticSearchResults.value = repository.semanticSearch(qVec, query)
        }
    }

    /**
     * Sends a user prompt through the Whistle/Needle router first:
     *  - If Needle emits a valid device tool call, executes according to Tier 1 / Tier 2 rules.
     *  - Otherwise loads & streams from the active Reply GGUF model via `llama.cpp`.
     */
    fun sendUserMessage(
        rawText: String,
        forceScreenContextMode: Boolean = false,
        screenContextText: String? = null
    ) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val ghost = _isGhostMode.value
            val sessionId = if (ghost) {
                -1L
            } else {
                val ensured = repository.createOrUpdateSession(
                    sessionId = _activeSessionId.value,
                    title = text.take(48),
                    isGhostMode = false
                )
                _activeSessionId.value = ensured
                ensured
            }

            val userMsg = ChatMessageEntity(
                id = System.currentTimeMillis(),
                sessionId = sessionId,
                role = "user",
                content = text
            )

            if (ghost) {
                _ghostMessages.value = _ghostMessages.value + userMsg
            } else {
                val savedId = repository.saveMessage(userMsg.copy(id = 0L), isGhostMode = false)
                broker.embeddingQueue.enqueue(
                    EmbeddingJob(
                        sourceType = "MESSAGE",
                        sourceId = savedId,
                        sessionId = sessionId,
                        text = text,
                        isGhostMode = false
                    )
                )
            }

            // Route through Needle 3 unless screen context mode forces tools disabled
            val routeRes = broker.processVoiceOrTextCommand(
                pcm16kMono = null,
                textInput = text,
                isScreenContextQuery = forceScreenContextMode,
                isGhostMode = ghost,
                autoExecuteTier1 = true
            )
            _lastPlaygroundResult.value = routeRes

            when (val action = routeRes.routeAction) {
                is RouteAction.ExecuteImmediately -> {
                    val outcome = routeRes.executionOutcome
                    val toolMsg = ChatMessageEntity(
                        id = System.currentTimeMillis() + 1L,
                        sessionId = sessionId,
                        role = "tool",
                        content = outcome?.spokenSummary ?: "Executed ${action.tool.title}",
                        toolName = action.tool.name,
                        toolArgsJson = action.call.argumentsJson,
                        toolResultSummary = "${outcome?.title ?: action.tool.title} · via ${outcome?.executionPathUsed ?: "Direct API"}",
                        taskLogId = outcome?.logId,
                        canUndo = outcome?.isReversible == true && (outcome.logId > 0L)
                    )
                    if (ghost) {
                        _ghostMessages.value = _ghostMessages.value + toolMsg
                    } else {
                        repository.saveMessage(toolMsg.copy(id = 0L), isGhostMode = false)
                    }
                }

                is RouteAction.StartTier2Countdown -> {
                    startChatTier2Countdown(
                        sessionId = sessionId,
                        transcript = text,
                        call = action.call,
                        seconds = action.countdownSeconds,
                        sttMs = routeRes.sttLatencyMs,
                        needleMs = routeRes.needleLatencyMs
                    )
                }

                is RouteAction.RequireUnlockForTier2 -> {
                    val lockedMsg = ChatMessageEntity(
                        id = System.currentTimeMillis() + 1L,
                        sessionId = sessionId,
                        role = "assistant",
                        content = "Please unlock your device first to run **${action.tool.title}**."
                    )
                    if (ghost) {
                        _ghostMessages.value = _ghostMessages.value + lockedMsg
                    } else {
                        repository.saveMessage(lockedMsg.copy(id = 0L), isGhostMode = false)
                    }
                }

                is RouteAction.RouteToReplyModel -> {
                    val historySnapshot = if (ghost) {
                        _ghostMessages.value
                    } else {
                        repository.getMessagesSnapshot(sessionId)
                    }

                    _isStreaming.value = true
                    _streamingContent.value = ""
                    _streamingThink.value = null
                    _streamingTokensPerSec.value = 0f

                    broker.streamReplyForQuery(
                        history = historySnapshot,
                        userPrompt = text,
                        screenContextSnippet = screenContextText
                    ).collect { chunk ->
                        _isReplyWarmingUp.value = chunk.isWarmingUp
                        _streamingContent.value = chunk.accumulatedContent
                        _streamingThink.value = chunk.accumulatedThinkContent
                        _streamingTokensPerSec.value = chunk.tokensPerSec
                        _thermalBanner.value = chunk.thermalWarning
                    }

                    val finalReply = _streamingContent.value
                    val finalThink = _streamingThink.value
                    val finalTps = _streamingTokensPerSec.value
                    _isStreaming.value = false
                    _isReplyWarmingUp.value = false

                    if (finalReply.isNotBlank()) {
                        val assistantMsg = ChatMessageEntity(
                            id = System.currentTimeMillis() + 2L,
                            sessionId = sessionId,
                            role = "assistant",
                            content = finalReply,
                            thinkContent = finalThink,
                            tokensPerSec = finalTps
                        )
                        if (ghost) {
                            _ghostMessages.value = _ghostMessages.value + assistantMsg
                        } else {
                            val savedId = repository.saveMessage(
                                assistantMsg.copy(id = 0L),
                                isGhostMode = false
                            )
                            broker.embeddingQueue.enqueue(
                                EmbeddingJob(
                                    sourceType = "MESSAGE",
                                    sourceId = savedId,
                                    sessionId = sessionId,
                                    text = finalReply,
                                    isGhostMode = false
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startChatTier2Countdown(
        sessionId: Long,
        transcript: String,
        call: ParsedToolCall,
        seconds: Int,
        sttMs: Long,
        needleMs: Long
    ) {
        tier2Job?.cancel()
        _pendingTier2Countdown.value = PendingChatTier2Countdown(
            transcript = transcript,
            call = call,
            remainingSeconds = seconds,
            sttMs = sttMs,
            needleMs = needleMs
        )
        tier2Job = viewModelScope.launch {
            var rem = seconds
            while (rem > 0) {
                delay(1000L)
                rem--
                _pendingTier2Countdown.value = _pendingTier2Countdown.value?.copy(
                    remainingSeconds = rem
                )
            }
            confirmPendingTier2Action(sessionId)
        }
    }

    fun cancelPendingTier2Action() {
        tier2Job?.cancel()
        _pendingTier2Countdown.value = null
    }

    fun confirmPendingTier2Action(sessionIdOverride: Long? = _activeSessionId.value) {
        val pending = _pendingTier2Countdown.value ?: return
        tier2Job?.cancel()
        _pendingTier2Countdown.value = null

        viewModelScope.launch {
            val ghost = _isGhostMode.value
            val outcome = broker.taskExecutor.executeToolCall(
                transcript = pending.transcript,
                call = pending.call,
                sttLatencyMs = pending.sttMs,
                needleLatencyMs = pending.needleMs,
                isGhostMode = ghost
            )
            val sid = sessionIdOverride ?: -1L
            val toolMsg = ChatMessageEntity(
                id = System.currentTimeMillis(),
                sessionId = sid,
                role = "tool",
                content = outcome.spokenSummary,
                toolName = outcome.toolName,
                toolArgsJson = pending.call.argumentsJson,
                toolResultSummary = "${outcome.title} · via ${outcome.executionPathUsed}",
                taskLogId = outcome.logId,
                canUndo = outcome.isReversible && outcome.logId > 0L
            )
            if (ghost) {
                _ghostMessages.value = _ghostMessages.value + toolMsg
            } else if (sid > 0L) {
                repository.saveMessage(toolMsg.copy(id = 0L), isGhostMode = false)
            }
        }
    }

    fun stopStreaming() {
        LlamaNativeBridge.cancelGeneration()
        activeGenerationJob?.cancel()
        _isStreaming.value = false
        _isReplyWarmingUp.value = false
    }

    fun regenerateLastResponse() {
        val msgs = activeMessages.value
        val lastUser = msgs.lastOrNull { it.role == "user" } ?: return
        sendUserMessage(lastUser.content)
    }

    fun startWhistleDictation(onTranscribed: (String) -> Unit) {
        if (_isDictating.value) return
        viewModelScope.launch {
            _isDictating.value = true
            val pcm = AudioVadRecorder.recordWithVad(
                context = app,
                silenceEndpointMs = settings.value.vadSilenceMs,
                onRmsUpdate = { _dictationRms.value = it }
            )
            val res = broker.processVoiceOrTextCommand(
                pcm16kMono = pcm,
                textInput = null,
                isScreenContextQuery = false,
                isGhostMode = _isGhostMode.value,
                autoExecuteTier1 = false
            )
            _isDictating.value = false
            if (res.transcript.isNotBlank()) {
                onTranscribed(res.transcript)
            }
        }
    }

    fun undoTaskByLogId(logId: Long, messageToUpdate: ChatMessageEntity? = null) {
        viewModelScope.launch {
            broker.taskExecutor.undoExecutedTask(logId)
            if (messageToUpdate != null) {
                repository.updateMessage(
                    messageToUpdate.copy(isUndone = true),
                    isGhostMode = _isGhostMode.value
                )
            }
        }
    }

    fun launchToolShortcut(toolName: String) {
        broker.taskExecutor.launchShortcutAction(toolName)
    }

    // --- To-Dos & Notes ---
    fun addTodoItem(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.addTodo(title)
        }
    }

    fun toggleTodoItem(todo: TodoItemEntity) {
        viewModelScope.launch {
            repository.setTodoCompleted(todo.id, !todo.isCompleted)
        }
    }

    fun deleteTodoItem(todoId: Long) {
        viewModelScope.launch {
            repository.deleteTodo(todoId)
        }
    }

    fun addQuickNote(title: String, body: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            val id = repository.addNote(title.ifBlank { body.take(32) }, body)
            broker.embeddingQueue.enqueue(
                EmbeddingJob(
                    sourceType = "NOTE",
                    sourceId = id,
                    sessionId = null,
                    text = body,
                    isGhostMode = _isGhostMode.value
                )
            )
        }
    }

    fun deleteNoteItem(noteId: Long) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
        }
    }

    // --- Playground & M0 Native Spike ---
    fun runPlaygroundCommand(input: String, executeIfMatched: Boolean) {
        viewModelScope.launch {
            val res = broker.processVoiceOrTextCommand(
                pcm16kMono = null,
                textInput = input,
                isScreenContextQuery = false,
                isGhostMode = _isGhostMode.value,
                autoExecuteTier1 = executeIfMatched
            )
            _lastPlaygroundResult.value = res
        }
    }

    suspend fun runM0NativeSpikeVerification(): M0NativeSpikeReport {
        val roleHeld = isAssistantRoleHeld(app)
        val report = broker.runM0NativeSpike(roleHeld)
        _m0SpikeReport.value = report
        return report
    }

    // --- Hugging Face Hub & Downloads ---
    fun searchHuggingFaceHub(query: String, isTaskSpeechTab: Boolean) {
        viewModelScope.launch {
            _hubLoading.value = true
            _gatedRepoBanner.value = null
            val token = encryptedPrefs.getHfToken().takeIf { it.isNotBlank() }
            when (val res = hubClient.searchHubModels(query, isTaskSpeechTab, token)) {
                is HubApiResult.Success -> _hubRepos.value = res.data
                is HubApiResult.GatedRepoError -> {
                    _gatedRepoBanner.value = res.repoId to res.licenseUrl
                }
                is HubApiResult.NetworkError -> {
                    _hubStatusMessage.value = res.message
                }
            }
            _hubLoading.value = false
        }
    }

    fun downloadStarterPack(includeSmolLm2: Boolean = true) {
        downloadManager.downloadStarterPack(includeSmolLm2)
        _hubStatusMessage.value = "Downloading Starter Pack (Needle 3 + Whistle + SmolLM2 360M)…"
    }

    fun startModelDownload(repo: HubRepoItem, file: HubModelFile) {
        downloadManager.startOrResumeDownload(
            repoId = repo.repoId,
            displayName = repo.displayName,
            roleSlot = repo.roleSlot,
            file = file
        )
    }

    fun fetchAndDownloadRepoUrl(repoUrlOrId: String) {
        val clean = repoUrlOrId.trim()
            .removePrefix("https://huggingface.co/")
            .removeSuffix("/")
        if (clean.isBlank()) return
        viewModelScope.launch {
            _hubLoading.value = true
            val token = encryptedPrefs.getHfToken().takeIf { it.isNotBlank() }
            when (val res = hubClient.fetchRepoFiles(clean, token)) {
                is HubApiResult.Success -> {
                    val primaryFile = res.data.firstOrNull()
                    if (primaryFile != null) {
                        val slot = com.example.hub.ModelMetadataInspector.inferRoleSlot(
                            primaryFile.fileName,
                            clean
                        )
                        downloadManager.startOrResumeDownload(
                            repoId = clean,
                            displayName = clean.substringAfterLast("/"),
                            roleSlot = slot,
                            file = primaryFile
                        )
                        _hubStatusMessage.value = "Queued ${primaryFile.fileName} from $clean"
                    } else {
                        _hubStatusMessage.value = "No .gguf or .cact files found in $clean"
                    }
                }
                is HubApiResult.GatedRepoError -> {
                    _gatedRepoBanner.value = res.repoId to res.licenseUrl
                }
                is HubApiResult.NetworkError -> {
                    _hubStatusMessage.value = res.message
                }
            }
            _hubLoading.value = false
        }
    }

    fun pauseDownload(taskId: String) = downloadManager.pauseDownload(taskId)

    fun cancelDownload(taskId: String) = downloadManager.cancelAndDeleteDownload(taskId)

    fun importLocalModelUri(uri: Uri, nameHint: String) {
        viewModelScope.launch {
            val res = downloadManager.importLocalModelUri(uri, nameHint)
            _hubStatusMessage.value = res.fold(
                onSuccess = { "Imported ${it.displayName} (${it.quantization})" },
                onFailure = { "Import failed: ${it.message}" }
            )
        }
    }

    fun setDefaultModel(model: InstalledModelEntity) {
        val slot = ModelRoleSlot.entries.find { it.id == model.roleSlot } ?: ModelRoleSlot.REPLY
        viewModelScope.launch {
            repository.setDefaultModelForRole(model.modelId, slot)
        }
    }

    fun deleteInstalledModel(model: InstalledModelEntity) {
        viewModelScope.launch {
            repository.deleteInstalledModel(model)
        }
    }

    // --- Hugging Face OAuth PKCE & Personal Access Token ---
    fun savePersonalAccessToken(token: String) {
        viewModelScope.launch {
            val clean = token.trim()
            if (clean.isEmpty()) {
                encryptedPrefs.clearHfToken()
                return@launch
            }
            val whoAmI = hubClient.verifyTokenWhoAmI(clean)
            val username = whoAmI.getOrDefault("hf_authenticated")
            encryptedPrefs.saveHfToken(clean, username)
            _hubStatusMessage.value = "Signed in to Hugging Face as @$username (Keystore encrypted)"
        }
    }

    fun buildOAuthAuthorizeIntentUrl(clientId: String): String? {
        val cid = clientId.trim()
        if (cid.isEmpty()) return null
        encryptedPrefs.updateSettings { it.copy(hfOAuthClientId = cid) }
        val pkce = hubClient.buildOAuthPkceRequest(cid)
        encryptedPrefs.savePkceVerifier(pkce.codeVerifier, pkce.state)
        return pkce.authorizeUrl
    }

    fun handleOAuthRedirectUri(uri: Uri) {
        val code = uri.getQueryParameter("code") ?: return
        val state = uri.getQueryParameter("state") ?: return
        val verifier = encryptedPrefs.consumePkceVerifier(state) ?: return
        val clientId = settings.value.hfOAuthClientId
        viewModelScope.launch {
            val tokenRes = hubClient.exchangeAuthorizationCode(
                clientId = clientId,
                code = code,
                codeVerifier = verifier
            )
            tokenRes.onSuccess { accessToken ->
                savePersonalAccessToken(accessToken)
            }.onFailure { err ->
                _hubStatusMessage.value = "OAuth exchange failed: ${err.message}"
            }
        }
    }

    fun signOutHuggingFace() {
        encryptedPrefs.clearHfToken()
        _hubStatusMessage.value = "Signed out of Hugging Face."
    }

    // --- Settings & Wipe All Data ---
    fun updateSettings(transform: (SkySettingsState) -> SkySettingsState) {
        encryptedPrefs.updateSettings(transform)
    }

    fun wipeAllAppData() {
        viewModelScope.launch {
            LlamaNativeBridge.unloadModel()
            repository.wipeAllData()
            encryptedPrefs.wipeAllPreferences()
            _ghostMessages.value = emptyList()
            _activeSessionId.value = null
            broker.ensureDefaultModelsBootstrapped()
        }
    }

    companion object {
        fun isAssistantRoleHeld(context: Context): Boolean {
            return try {
                val rm = context.getSystemService(RoleManager::class.java)
                val roleHeld = rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
                if (roleHeld) return true
                val secureAssist = Settings.Secure.getString(
                    context.contentResolver,
                    "assistant"
                ) ?: ""
                secureAssist.contains(context.packageName)
            } catch (_: Throwable) {
                false
            }
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (SkyAccessibilityService.isInstanceConnected()) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                enabledServices.contains(context.packageName)
            } catch (_: Throwable) {
                false
            }
        }

        fun factory(app: SkyApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SkyAppViewModel(app) as T
                }
            }
    }
}
