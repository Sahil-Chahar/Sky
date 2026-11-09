package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import com.example.assistant.SkyAssistActivity
import com.example.ui.SkyAppViewModel
import com.example.ui.SkyDestination
import com.example.ui.screens.AboutSkyScreen
import com.example.ui.screens.GuidedSetupWizardScreen
import com.example.ui.screens.HomeChatScreen
import com.example.ui.screens.ModelHubScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SkyNavigationDrawerContent
import com.example.ui.screens.TasksScreen
import com.example.ui.screens.ToolPlaygroundScreen
import com.example.ui.theme.SkyAssistantTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SkyAppViewModel by viewModels {
        SkyAppViewModel.factory(SkyApplication.from(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            SkyAssistantTheme(
                preset = settings.themePreset,
                mode = settings.themeMode
            ) {
                SkyMainScaffold(
                    viewModel = viewModel,
                    onLaunchVoiceOverlay = {
                        startActivity(Intent(this, SkyAssistActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data != null && data.scheme == "com.sky.assistant" && data.host == "oauth-callback") {
            viewModel.handleOAuthRedirectUri(data)
            viewModel.navigateTo(SkyDestination.MODELS)
        }
        if (intent.action == "com.sky.assistant.ACTION_PRIVATE_CHAT") {
            viewModel.startNewChat(ghost = true)
        }
        val initialPrompt = intent.getStringExtra("initial_prompt")
        if (!initialPrompt.isNullOrBlank()) {
            viewModel.sendUserMessage(initialPrompt)
        }
    }
}

@Composable
fun SkyMainScaffold(
    viewModel: SkyAppViewModel,
    onLaunchVoiceOverlay: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val destination by viewModel.currentDestination.collectAsState()
    val isGhostMode by viewModel.isGhostMode.collectAsState()
    val activeSessionId by viewModel.activeSessionId.collectAsState()
    val messages by viewModel.activeMessages.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val todos by viewModel.todos.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val taskLogs by viewModel.taskLogs.collectAsState()
    val installedModels by viewModel.installedModels.collectAsState()
    val downloadTasks by viewModel.downloadTasks.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val streamingThink by viewModel.streamingThink.collectAsState()
    val streamingTps by viewModel.streamingTokensPerSec.collectAsState()
    val isReplyWarmingUp by viewModel.isReplyWarmingUp.collectAsState()
    val thermalBanner by viewModel.thermalBanner.collectAsState()
    val isDictating by viewModel.isDictating.collectAsState()
    val pendingTier2Countdown by viewModel.pendingTier2Countdown.collectAsState()
    val semanticResults by viewModel.semanticSearchResults.collectAsState()
    val hubRepos by viewModel.hubRepos.collectAsState()
    val gatedRepoBanner by viewModel.gatedRepoBanner.collectAsState()
    val hubStatusMessage by viewModel.hubStatusMessage.collectAsState()
    val lastPlaygroundResult by viewModel.lastPlaygroundResult.collectAsState()
    val m0Report by viewModel.m0SpikeReport.collectAsState()

    var isDrawerOpen by remember { mutableStateOf(false) }

    // Reference UI A: Drawer slides in from left while main content scales to 0.95f and dims
    val contentScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0.95f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "drawerContentScale"
    )

    BackHandler(enabled = isDrawerOpen) {
        isDrawerOpen = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main Screen Content with Fade-Through transitions
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(contentScale)
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "skyDestinationTransition"
            ) { screen ->
                when (screen) {
                    SkyDestination.CHAT -> HomeChatScreen(
                        messages = messages,
                        installedModels = installedModels,
                        isGhostMode = isGhostMode,
                        isStreaming = isStreaming,
                        streamingContent = streamingContent,
                        streamingThink = streamingThink,
                        streamingTokensPerSec = streamingTps,
                        isReplyWarmingUp = isReplyWarmingUp,
                        thermalBanner = thermalBanner,
                        isDictating = isDictating,
                        pendingTier2Countdown = pendingTier2Countdown,
                        setupWizardCompleted = settings.setupWizardCompleted,
                        onOpenDrawer = { isDrawerOpen = true },
                        onToggleGhostMode = { viewModel.toggleGhostMode() },
                        onSendMessage = { viewModel.sendUserMessage(it) },
                        onStopStreaming = { viewModel.stopStreaming() },
                        onRegenerateLast = { viewModel.regenerateLastResponse() },
                        onStartDictation = { cb -> viewModel.startWhistleDictation(cb) },
                        onLaunchVoiceOverlay = onLaunchVoiceOverlay,
                        onSwitchActiveReplyModel = { viewModel.setDefaultModel(it) },
                        onOpenModelHub = { viewModel.navigateTo(SkyDestination.MODELS) },
                        onOpenSetupWizard = { viewModel.navigateTo(SkyDestination.SETUP_WIZARD) },
                        onUndoTask = { logId, msg -> viewModel.undoTaskByLogId(logId, msg) },
                        onConfirmTier2 = { viewModel.confirmPendingTier2Action() },
                        onCancelTier2 = { viewModel.cancelPendingTier2Action() },
                        onLaunchToolShortcut = { viewModel.launchToolShortcut(it) }
                    )

                    SkyDestination.TASKS -> TasksScreen(
                        todos = todos,
                        notes = notes,
                        taskLogs = taskLogs,
                        m0Report = m0Report,
                        onOpenDrawer = { isDrawerOpen = true },
                        onNavigateBackToChat = { viewModel.navigateTo(SkyDestination.CHAT) },
                        onAddTodo = { viewModel.addTodoItem(it) },
                        onToggleTodo = { viewModel.toggleTodoItem(it) },
                        onDeleteTodo = { viewModel.deleteTodoItem(it) },
                        onAddNote = { title, body -> viewModel.addQuickNote(title, body) },
                        onDeleteNote = { viewModel.deleteNoteItem(it) },
                        onUndoTaskLog = { viewModel.undoTaskByLogId(it) },
                        onOpenPlayground = { viewModel.navigateTo(SkyDestination.PLAYGROUND) },
                        onReRunM0Spike = { viewModel.runM0NativeSpikeVerification() }
                    )

                    SkyDestination.MODELS -> ModelHubScreen(
                        settings = settings,
                        hubRepos = hubRepos,
                        installedModels = installedModels,
                        downloadTasks = downloadTasks,
                        gatedRepoBanner = gatedRepoBanner,
                        hubStatusMessage = hubStatusMessage,
                        onOpenDrawer = { isDrawerOpen = true },
                        onNavigateBackToChat = { viewModel.navigateTo(SkyDestination.CHAT) },
                        onSearchHub = { q, isTask -> viewModel.searchHuggingFaceHub(q, isTask) },
                        onDownloadStarterPack = { viewModel.downloadStarterPack(true) },
                        onStartDownload = { repo, file -> viewModel.startModelDownload(repo, file) },
                        onFetchRepoUrl = { viewModel.fetchAndDownloadRepoUrl(it) },
                        onPauseDownload = { viewModel.pauseDownload(it) },
                        onCancelDownload = { viewModel.cancelDownload(it) },
                        onImportLocalUri = { uri, hint -> viewModel.importLocalModelUri(uri, hint) },
                        onSetDefaultModel = { viewModel.setDefaultModel(it) },
                        onDeleteInstalledModel = { viewModel.deleteInstalledModel(it) },
                        onSaveHfToken = { viewModel.savePersonalAccessToken(it) },
                        onBuildOAuthUrl = { viewModel.buildOAuthAuthorizeIntentUrl(it) },
                        onSignOutHf = { viewModel.signOutHuggingFace() }
                    )

                    SkyDestination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        installedModels = installedModels,
                        onOpenDrawer = { isDrawerOpen = true },
                        onNavigateBackToChat = { viewModel.navigateTo(SkyDestination.CHAT) },
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onSelectDefaultModel = { viewModel.setDefaultModel(it) },
                        onOpenSetupWizard = { viewModel.navigateTo(SkyDestination.SETUP_WIZARD) },
                        onOpenPlayground = { viewModel.navigateTo(SkyDestination.PLAYGROUND) },
                        onWipeAllData = { viewModel.wipeAllAppData() }
                    )

                    SkyDestination.SETUP_WIZARD -> GuidedSetupWizardScreen(
                        onBack = { viewModel.navigateTo(SkyDestination.CHAT) },
                        onMarkCompleted = {
                            viewModel.updateSettings { it.copy(setupWizardCompleted = true) }
                            viewModel.navigateTo(SkyDestination.CHAT)
                        }
                    )

                    SkyDestination.PLAYGROUND -> ToolPlaygroundScreen(
                        lastResult = lastPlaygroundResult,
                        onBack = { viewModel.navigateTo(SkyDestination.TASKS) },
                        onRunCommand = { input, exec -> viewModel.runPlaygroundCommand(input, exec) }
                    )

                    SkyDestination.ABOUT -> AboutSkyScreen(
                        onBack = { viewModel.navigateTo(SkyDestination.CHAT) }
                    )
                }
            }
        }

        // Dim Scrim when Drawer is Open
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isDrawerOpen = false
                    }
            )
        }

        // Left Slide-In Drawer
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut()
        ) {
            SkyNavigationDrawerContent(
                currentDestination = destination,
                sessions = sessions,
                activeSessionId = activeSessionId,
                semanticResults = semanticResults,
                onNavigate = { dest ->
                    isDrawerOpen = false
                    viewModel.navigateTo(dest)
                },
                onSelectSession = { sid ->
                    isDrawerOpen = false
                    viewModel.selectSession(sid)
                },
                onNewChat = {
                    isDrawerOpen = false
                    viewModel.startNewChat(ghost = false)
                },
                onTogglePinSession = { viewModel.togglePinSession(it) },
                onDeleteSession = { viewModel.deleteSession(it) },
                onSemanticSearch = { viewModel.performSemanticSearch(it) },
                onLaunchVoiceOverlay = {
                    isDrawerOpen = false
                    onLaunchVoiceOverlay()
                }
            )
        }
    }
}
