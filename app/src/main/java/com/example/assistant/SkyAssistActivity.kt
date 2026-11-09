package com.example.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.MainActivity
import com.example.R
import com.example.SkyApplication
import com.example.tools.ParsedToolCall
import com.example.tools.RouteAction
import com.example.tools.TaskExecutionOutcome
import com.example.ui.components.AssistantOverlayPhase
import com.example.ui.components.NeuSurface
import com.example.ui.components.SiriWakeOverlay
import com.example.ui.theme.SkyAssistantTheme
import com.example.ui.theme.SkyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Translucent Assistant Activity launched via `ACTION_ASSIST`, Quick Settings Tile,
 * Homescreen Widget, or App Shortcut.
 * Applies `Window.setBackgroundBlurRadius` on API 31+ where supported.
 */
class SkyAssistActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes.blurBehindRadius = 36
                window.setBackgroundBlurRadius(42)
            } catch (_: Throwable) {
            }
        }

        val initialScreenCtx = intent?.getStringExtra("screen_context")

        setContent {
            SkyAssistantOverlayHost(
                initialScreenContext = initialScreenCtx,
                onDismissOverlay = { finish() }
            )
        }
    }
}

@Composable
fun SkyAssistantOverlayHost(
    initialScreenContext: String? = null,
    onDismissOverlay: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val app = remember(context) { SkyApplication.from(context) }
    val broker = app.inferenceBroker
    val settings by app.encryptedPrefs.settings.collectAsState()
    val ttsPulse by broker.ttsEngine.ttsPulse.collectAsState()
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(AssistantOverlayPhase.LISTENING) }
    var micRms by remember { mutableFloatStateOf(0.15f) }
    var liveTranscript by remember { mutableStateOf("") }
    var showKeyboardInput by remember { mutableStateOf(false) }
    var typedQuery by remember { mutableStateOf("") }
    var screenContext by remember { mutableStateOf(initialScreenContext) }
    var isScreenContextMode by remember { mutableStateOf(!initialScreenContext.isNullOrBlank()) }

    var pendingTier2Call by remember { mutableStateOf<ParsedToolCall?>(null) }
    var tier2CountdownRemaining by remember { mutableIntStateOf(3) }
    var tier2CountdownJob by remember { mutableStateOf<Job?>(null) }

    var taskOutcome by remember { mutableStateOf<TaskExecutionOutcome?>(null) }
    var undoStatusMessage by remember { mutableStateOf<String?>(null) }
    var streamedReplyText by remember { mutableStateOf("") }
    var isReplyWarmingUp by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    fun submitQuery(queryText: String, pcmSamples: FloatArray? = null, useScreenCtx: Boolean = isScreenContextMode) {
        activeJob?.cancel()
        activeJob = scope.launch {
            phase = AssistantOverlayPhase.THINKING
            val result = broker.processVoiceOrTextCommand(
                pcm16kMono = pcmSamples,
                textInput = queryText,
                isScreenContextQuery = useScreenCtx,
                isGhostMode = false,
                autoExecuteTier1 = true
            )
            liveTranscript = result.transcript

            when (val route = result.routeAction) {
                is RouteAction.ExecuteImmediately -> {
                    phase = AssistantOverlayPhase.EXECUTING_TASK
                    taskOutcome = result.executionOutcome
                    result.executionOutcome?.spokenSummary?.let { summary ->
                        broker.ttsEngine.speakImmediate(
                            text = summary,
                            speechRate = settings.ttsSpeechRate,
                            pitch = settings.ttsPitch,
                            enabled = settings.ttsEnabled
                        )
                    }
                    AudioVadRecorder.playEarcon(isWake = false)
                }

                is RouteAction.StartTier2Countdown -> {
                    phase = AssistantOverlayPhase.COUNTDOWN_TIER2
                    pendingTier2Call = route.call
                    tier2CountdownRemaining = route.countdownSeconds
                    tier2CountdownJob?.cancel()
                    tier2CountdownJob = scope.launch {
                        while (tier2CountdownRemaining > 0) {
                            delay(1000L)
                            tier2CountdownRemaining--
                        }
                        val callToRun = pendingTier2Call
                        if (callToRun != null) {
                            phase = AssistantOverlayPhase.EXECUTING_TASK
                            val outcome = broker.taskExecutor.executeToolCall(
                                transcript = result.transcript,
                                call = callToRun,
                                sttLatencyMs = result.sttLatencyMs,
                                needleLatencyMs = result.needleLatencyMs,
                                isGhostMode = false
                            )
                            taskOutcome = outcome
                            broker.ttsEngine.speakImmediate(
                                text = outcome.spokenSummary,
                                speechRate = settings.ttsSpeechRate,
                                pitch = settings.ttsPitch,
                                enabled = settings.ttsEnabled
                            )
                            AudioVadRecorder.playEarcon(isWake = false)
                        }
                    }
                }

                is RouteAction.RequireUnlockForTier2 -> {
                    phase = AssistantOverlayPhase.EXECUTING_TASK
                    val msg = "Unlock your phone first to run ${route.tool.title}."
                    taskOutcome = TaskExecutionOutcome(
                        toolName = route.tool.name,
                        title = "Unlock Required · ${route.tool.title}",
                        spokenSummary = msg,
                        executionPathUsed = "Lockscreen Gate (Tier 2 Blocked)",
                        isReversible = false
                    )
                    broker.ttsEngine.speakImmediate(
                        text = msg,
                        speechRate = settings.ttsSpeechRate,
                        pitch = settings.ttsPitch,
                        enabled = settings.ttsEnabled
                    )
                }

                is RouteAction.RouteToReplyModel -> {
                    // Only load Reply GGUF AFTER router returns "no tool"
                    phase = AssistantOverlayPhase.STREAMING_REPLY
                    streamedReplyText = ""
                    isReplyWarmingUp = true
                    broker.streamReplyForQuery(
                        history = emptyList(),
                        userPrompt = result.transcript.ifBlank { queryText },
                        screenContextSnippet = if (useScreenCtx) screenContext else null
                    ).collect { chunk ->
                        isReplyWarmingUp = chunk.isWarmingUp
                        streamedReplyText = chunk.accumulatedContent
                        if (chunk.tokenDelta.isNotEmpty()) {
                            broker.ttsEngine.feedStreamingToken(
                                tokenDelta = chunk.tokenDelta,
                                speechRate = settings.ttsSpeechRate,
                                pitch = settings.ttsPitch,
                                enabled = settings.ttsEnabled
                            )
                        }
                        if (chunk.isFinished) {
                            broker.ttsEngine.flushStreamingSentences(
                                speechRate = settings.ttsSpeechRate,
                                pitch = settings.ttsPitch,
                                enabled = settings.ttsEnabled
                            )
                            AudioVadRecorder.playEarcon(isWake = false)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        AudioVadRecorder.playEarcon(isWake = true)
        val pcm = AudioVadRecorder.recordWithVad(
            context = context,
            silenceEndpointMs = settings.vadSilenceMs,
            onRmsUpdate = { rms -> micRms = rms }
        )
        if (pcm.isNotEmpty() && !showKeyboardInput) {
            submitQuery(queryText = "", pcmSamples = pcm)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tier2CountdownJob?.cancel()
            activeJob?.cancel()
            broker.ttsEngine.stop()
            broker.embeddingQueue.setVoiceSessionActive(false)
        }
    }

    BackHandler {
        onDismissOverlay()
    }

    SkyAssistantTheme(preset = settings.themePreset, mode = settings.themeMode) {
        val spec = SkyTheme.spec

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.44f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onDismissOverlay()
                }
        ) {
            // Perimeter Siri Glow or Top-Center Siri Orb
            SiriWakeOverlay(
                style = settings.siriWakeStyle,
                phase = phase,
                micRms = micRms,
                ttsPulse = ttsPulse,
                modifier = Modifier.fillMaxSize()
            )

            // Foreground interactive controls & cards
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume inside card taps */ },
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Screen Context chip + Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NeuSurface(
                        depth = 1,
                        shape = CircleShape,
                        cornerRadiusDp = 24.dp,
                        onClick = {
                            if (screenContext.isNullOrBlank()) {
                                screenContext = "Current Foreground Screen Hierarchy"
                            }
                            isScreenContextMode = true
                            submitQuery(
                                queryText = "Summarize what is on my screen right now",
                                useScreenCtx = true
                            )
                        },
                        modifier = Modifier.testTag("overlay_ask_screen_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Visibility,
                                contentDescription = "Ask about this screen",
                                tint = spec.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Ask about this screen",
                                style = MaterialTheme.typography.labelLarge,
                                color = spec.primaryText
                            )
                        }
                    }

                    NeuSurface(
                        depth = 1,
                        shape = CircleShape,
                        cornerRadiusDp = 24.dp,
                        onClick = onDismissOverlay,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("overlay_close_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss Sky overlay",
                            tint = spec.primaryText
                        )
                    }
                }

                // Bottom Region: Result cards + Live Transcript + Keyboard / Quick Actions
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Tier 2 3-Second Cancelable Countdown Card
                    AnimatedVisibility(
                        visible = phase == AssistantOverlayPhase.COUNTDOWN_TIER2 && pendingTier2Call != null,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut()
                    ) {
                        val call = pendingTier2Call
                        if (call != null) {
                            NeuSurface(
                                depth = 2,
                                shape = RoundedCornerShape(24.dp),
                                cornerRadiusDp = 24.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tier2_countdown_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (call.toolName == "send_sms") {
                                            "Texting ${call.arguments["recipient"] ?: "Contact"} in ${tier2CountdownRemaining}s…"
                                        } else {
                                            "Calling ${call.arguments["recipient"] ?: "Contact"} in ${tier2CountdownRemaining}s…"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    call.arguments["message"]?.let { body ->
                                        Text(
                                            text = "\"$body\"",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = spec.mutedText
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        NeuSurface(
                                            depth = 1,
                                            shape = CircleShape,
                                            cornerRadiusDp = 24.dp,
                                            onClick = {
                                                tier2CountdownJob?.cancel()
                                                pendingTier2Call = null
                                                phase = AssistantOverlayPhase.IDLE
                                                onDismissOverlay()
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("tier2_cancel_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Cancel",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = spec.errorText,
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            )
                                        }

                                        NeuSurface(
                                            depth = 1,
                                            accentFill = true,
                                            shape = CircleShape,
                                            cornerRadiusDp = 24.dp,
                                            onClick = {
                                                tier2CountdownJob?.cancel()
                                                scope.launch {
                                                    phase = AssistantOverlayPhase.EXECUTING_TASK
                                                    val outcome = broker.taskExecutor.executeToolCall(
                                                        transcript = liveTranscript,
                                                        call = call,
                                                        sttLatencyMs = 10L,
                                                        needleLatencyMs = 8L,
                                                        isGhostMode = false
                                                    )
                                                    taskOutcome = outcome
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("tier2_send_now_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Run Now",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = spec.onAccentText,
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Executed Task Result Card (with Undo for reversible tools)
                    AnimatedVisibility(
                        visible = taskOutcome != null,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut()
                    ) {
                        val outcome = taskOutcome
                        if (outcome != null) {
                            NeuSurface(
                                depth = 2,
                                shape = RoundedCornerShape(24.dp),
                                cornerRadiusDp = 24.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("overlay_task_result_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = outcome.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    Text(
                                        text = undoStatusMessage ?: outcome.spokenSummary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = spec.mutedText
                                    )
                                    Text(
                                        text = "Path: ${outcome.executionPathUsed}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.accentText
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (outcome.isReversible && outcome.logId > 0L && undoStatusMessage == null) {
                                            NeuSurface(
                                                depth = 1,
                                                shape = CircleShape,
                                                cornerRadiusDp = 20.dp,
                                                onClick = {
                                                    scope.launch {
                                                        undoStatusMessage = broker.taskExecutor.undoExecutedTask(outcome.logId)
                                                    }
                                                },
                                                modifier = Modifier.testTag("overlay_undo_button")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                                                        contentDescription = "Undo action",
                                                        tint = spec.accentText,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        text = "Undo",
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = spec.accentText
                                                    )
                                                }
                                            }
                                        }

                                        outcome.shortcutActionLabel?.let { label ->
                                            NeuSurface(
                                                depth = 1,
                                                shape = CircleShape,
                                                cornerRadiusDp = 20.dp,
                                                onClick = {
                                                    broker.taskExecutor.launchShortcutAction(outcome.toolName)
                                                }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                        contentDescription = label,
                                                        tint = spec.primaryText,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = label,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = spec.primaryText
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Translucent Glass Streaming Reply Card (Copy / Share / Continue in Chat / Save Note)
                    AnimatedVisibility(
                        visible = phase == AssistantOverlayPhase.STREAMING_REPLY,
                        enter = fadeIn() + slideInVertically { it / 2 },
                        exit = fadeOut()
                    ) {
                        NeuSurface(
                            depth = 2,
                            shape = RoundedCornerShape(24.dp),
                            cornerRadiusDp = 24.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overlay_reply_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (isReplyWarmingUp && streamedReplyText.isEmpty()) {
                                    Text(
                                        text = "Warming up local Reply GGUF model…",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = spec.accentText
                                    )
                                } else {
                                    Text(
                                        text = streamedReplyText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = spec.primaryText
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NeuSurface(
                                        depth = 1,
                                        shape = CircleShape,
                                        cornerRadiusDp = 20.dp,
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText("Sky Reply", streamedReplyText))
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ContentCopy,
                                                contentDescription = "Copy reply",
                                                tint = spec.primaryText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Copy", style = MaterialTheme.typography.labelMedium, color = spec.primaryText)
                                        }
                                    }

                                    NeuSurface(
                                        depth = 1,
                                        shape = CircleShape,
                                        cornerRadiusDp = 20.dp,
                                        onClick = {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, streamedReplyText)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Sky Reply").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Share,
                                                contentDescription = "Share reply",
                                                tint = spec.primaryText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Share", style = MaterialTheme.typography.labelMedium, color = spec.primaryText)
                                        }
                                    }

                                    NeuSurface(
                                        depth = 1,
                                        shape = CircleShape,
                                        cornerRadiusDp = 20.dp,
                                        onClick = {
                                            scope.launch {
                                                app.repository.addNote(
                                                    title = liveTranscript.ifBlank { "Sky Voice Note" }.take(36),
                                                    body = streamedReplyText
                                                )
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.BookmarkAdd,
                                                contentDescription = "Save as note",
                                                tint = spec.primaryText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text("Save Note", style = MaterialTheme.typography.labelMedium, color = spec.primaryText)
                                        }
                                    }

                                    NeuSurface(
                                        depth = 1,
                                        accentFill = true,
                                        shape = CircleShape,
                                        cornerRadiusDp = 20.dp,
                                        onClick = {
                                            val chatIntent = Intent(context, MainActivity::class.java).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                putExtra("initial_prompt", liveTranscript)
                                            }
                                            context.startActivity(chatIntent)
                                            onDismissOverlay()
                                        }
                                    ) {
                                        Text(
                                            text = "Continue",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = spec.onAccentText,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Bottom Live Transcript + Keyboard Input Pill
                    NeuSurface(
                        depth = 2,
                        shape = RoundedCornerShape(28.dp),
                        cornerRadiusDp = 28.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = when (phase) {
                                            AssistantOverlayPhase.LISTENING -> "Listening (16 kHz Whistle)…"
                                            AssistantOverlayPhase.THINKING -> "Thinking (Needle 3)…"
                                            AssistantOverlayPhase.COUNTDOWN_TIER2 -> "Confirming Tier 2 Action…"
                                            AssistantOverlayPhase.EXECUTING_TASK -> "Executed Instantly"
                                            AssistantOverlayPhase.STREAMING_REPLY -> "Sky Reply"
                                            else -> "Ready"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.accentText,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = liveTranscript.ifBlank {
                                            "Say \"Set a timer for 5 minutes\", \"Turn on flashlight\", or ask anything…"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = spec.primaryText
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    NeuSurface(
                                        depth = if (showKeyboardInput) -1 else 1,
                                        shape = CircleShape,
                                        cornerRadiusDp = 24.dp,
                                        onClick = { showKeyboardInput = !showKeyboardInput },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("overlay_keyboard_toggle"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Keyboard,
                                            contentDescription = "Type command instead of speaking",
                                            tint = spec.primaryText
                                        )
                                    }

                                    NeuSurface(
                                        depth = 1,
                                        accentFill = true,
                                        shape = CircleShape,
                                        cornerRadiusDp = 24.dp,
                                        onClick = {
                                            scope.launch {
                                                phase = AssistantOverlayPhase.LISTENING
                                                val pcm = AudioVadRecorder.recordWithVad(
                                                    context = context,
                                                    silenceEndpointMs = settings.vadSilenceMs,
                                                    onRmsUpdate = { micRms = it }
                                                )
                                                submitQuery(queryText = "", pcmSamples = pcm)
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("overlay_mic_retry_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Mic,
                                            contentDescription = "Speak command",
                                            tint = spec.onAccentText
                                        )
                                    }
                                }
                            }

                            if (showKeyboardInput) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NeuSurface(
                                        depth = -1,
                                        shape = RoundedCornerShape(18.dp),
                                        cornerRadiusDp = 18.dp,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        BasicTextField(
                                            value = typedQuery,
                                            onValueChange = { typedQuery = it },
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = spec.primaryText
                                            ),
                                            cursorBrush = SolidColor(spec.accent),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                                .testTag("overlay_text_input"),
                                            decorationBox = { inner ->
                                                if (typedQuery.isEmpty()) {
                                                    Text(
                                                        text = "Type a task or question…",
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = spec.mutedText
                                                    )
                                                }
                                                inner()
                                            }
                                        )
                                    }

                                    NeuSurface(
                                        depth = 1,
                                        accentFill = true,
                                        shape = CircleShape,
                                        cornerRadiusDp = 24.dp,
                                        onClick = {
                                            if (typedQuery.isNotBlank()) {
                                                val q = typedQuery.trim()
                                                typedQuery = ""
                                                submitQuery(queryText = q)
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("overlay_send_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.Send,
                                            contentDescription = "Send command",
                                            tint = spec.onAccentText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Quick Settings Tile (`SkyQuickSettingsTileService`) launching the translucent Sky Voice overlay.
 */
class SkyQuickSettingsTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SkyAssistActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    101,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
        }
    }
}

/**
 * Homescreen Widget (`SkyWidgetProvider`) with one-tap launch into Sky Chat or Sky Voice Overlay.
 */
class SkyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.sky_widget_layout)

            val chatIntent = Intent(context, MainActivity::class.java)
            val chatPi = PendingIntent.getActivity(
                context,
                201,
                chatIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_prompt_text, chatPi)

            val voiceIntent = Intent(context, SkyAssistActivity::class.java)
            val voicePi = PendingIntent.getActivity(
                context,
                202,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_voice_btn, voicePi)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
