package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.data.local.NoteEntity
import com.example.data.local.TaskExecutionLogEntity
import com.example.data.local.TodoItemEntity
import com.example.engine.M0NativeSpikeReport
import com.example.engine.PlaygroundRunResult
import com.example.tools.RouteAction
import com.example.ui.components.NeuSurface
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.SkyTheme
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun TasksScreen(
    todos: List<TodoItemEntity>,
    notes: List<NoteEntity>,
    taskLogs: List<TaskExecutionLogEntity>,
    m0Report: M0NativeSpikeReport?,
    onOpenDrawer: () -> Unit,
    onNavigateBackToChat: () -> Unit,
    onAddTodo: (String) -> Unit,
    onToggleTodo: (TodoItemEntity) -> Unit,
    onDeleteTodo: (Long) -> Unit,
    onAddNote: (String, String) -> Unit,
    onDeleteNote: (Long) -> Unit,
    onUndoTaskLog: (Long) -> Unit,
    onOpenPlayground: () -> Unit,
    onReRunM0Spike: suspend () -> M0NativeSpikeReport,
    modifier: Modifier = Modifier
) {
    val spec = SkyTheme.spec
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Today To-Dos, 1 = Notes, 2 = History
    var newTodoText by remember { mutableStateOf("") }
    var newNoteBody by remember { mutableStateOf("") }

    BackHandler {
        onNavigateBackToChat()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(spec.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NeuSurface(
                depth = 1,
                shape = CircleShape,
                cornerRadiusDp = 24.dp,
                onClick = onOpenDrawer,
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation drawer",
                    tint = spec.primaryText
                )
            }

            Text(
                text = "Tasks & Execution Log",
                style = MaterialTheme.typography.titleLarge,
                color = spec.primaryText
            )

            NeuSurface(
                depth = 1,
                shape = CircleShape,
                cornerRadiusDp = 24.dp,
                onClick = onOpenPlayground,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tasks_open_playground_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Science,
                    contentDescription = "Open Tool Playground",
                    tint = spec.accentText
                )
            }
        }

        // M0 Native Spike Status Banner
        if (m0Report != null) {
            NeuSurface(
                depth = 1,
                shape = RoundedCornerShape(20.dp),
                cornerRadiusDp = 20.dp,
                onClick = { scope.launch { onReRunM0Spike() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .testTag("m0_spike_status_card")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Verified,
                        contentDescription = null,
                        tint = if (m0Report.passed) spec.successFill else spec.warningFill,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "M0 Native Spike: ${if (m0Report.passed) "PASSED" else "CHECKING"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (m0Report.passed) spec.successText else spec.warningText
                        )
                        Text(
                            text = m0Report.details,
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.mutedText
                        )
                    }
                }
            }
        }

        // Segmented Neumorphic Tabs: Today's To-Dos | Notes | Task History
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                "Today (${todos.count { !it.isCompleted }})",
                "Notes (${notes.size})",
                "History (${taskLogs.size})"
            ).forEachIndexed { idx, label ->
                val selected = activeTab == idx
                NeuSurface(
                    depth = if (selected) -1 else 1,
                    pressed = selected,
                    shape = CircleShape,
                    cornerRadiusDp = 22.dp,
                    onClick = { activeTab = idx },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tasks_tab_$idx"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) spec.accentText else spec.primaryText,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        when (activeTab) {
            0 -> {
                // Today's To-Do List
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NeuSurface(
                        depth = -1,
                        shape = RoundedCornerShape(18.dp),
                        cornerRadiusDp = 18.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        BasicTextField(
                            value = newTodoText,
                            onValueChange = { newTodoText = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = spec.primaryText),
                            cursorBrush = SolidColor(spec.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("tasks_add_todo_input"),
                            decorationBox = { inner ->
                                if (newTodoText.isEmpty()) {
                                    Text(
                                        text = "Add a task for today…",
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
                            if (newTodoText.isNotBlank()) {
                                onAddTodo(newTodoText.trim())
                                newTodoText = ""
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("tasks_add_todo_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add task",
                            tint = spec.onAccentText
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (todos.isEmpty()) {
                        item {
                            Text(
                                text = "No tasks yet — say \"Add task buy groceries\" or add one above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = spec.mutedText,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    items(todos, key = { it.id }) { item ->
                        NeuSurface(
                            depth = 1,
                            useCachedShadowForList = true,
                            shape = RoundedCornerShape(18.dp),
                            cornerRadiusDp = 18.dp,
                            onClick = { onToggleTodo(item) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (item.isCompleted) {
                                            Icons.Rounded.CheckBox
                                        } else {
                                            Icons.Rounded.CheckBoxOutlineBlank
                                        },
                                        contentDescription = "Toggle task completion",
                                        tint = if (item.isCompleted) spec.successFill else spec.accentText
                                    )
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (item.isCompleted) spec.mutedText else spec.primaryText,
                                        textDecoration = if (item.isCompleted) {
                                            TextDecoration.LineThrough
                                        } else {
                                            TextDecoration.None
                                        }
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete task",
                                    tint = spec.mutedText,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { onDeleteTodo(item.id) }
                                )
                            }
                        }
                    }
                }
            }

            1 -> {
                // Saved Voice/Text Notes (indexed via needle_embed)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NeuSurface(
                        depth = -1,
                        shape = RoundedCornerShape(18.dp),
                        cornerRadiusDp = 18.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        BasicTextField(
                            value = newNoteBody,
                            onValueChange = { newNoteBody = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = spec.primaryText),
                            cursorBrush = SolidColor(spec.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            decorationBox = { inner ->
                                if (newNoteBody.isEmpty()) {
                                    Text(
                                        text = "Save a quick note (auto-embedded)…",
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
                            if (newNoteBody.isNotBlank()) {
                                onAddNote(newNoteBody.take(32), newNoteBody.trim())
                                newNoteBody = ""
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Save note",
                            tint = spec.onAccentText
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NeuSurface(
                            depth = 1,
                            useCachedShadowForList = true,
                            shape = RoundedCornerShape(18.dp),
                            cornerRadiusDp = 18.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = note.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = note.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = spec.mutedText
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete note",
                                    tint = spec.mutedText,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { onDeleteNote(note.id) }
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                // Task Execution History Log
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (taskLogs.isEmpty()) {
                        item {
                            Text(
                                text = "No device actions executed yet. Try \"Turn on flashlight\" or \"Set timer for 2 minutes\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = spec.mutedText,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    items(taskLogs, key = { it.id }) { log ->
                        NeuSurface(
                            depth = 1,
                            useCachedShadowForList = true,
                            shape = RoundedCornerShape(20.dp),
                            cornerRadiusDp = 20.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${log.toolName} (${log.riskTier.uppercase()})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    Text(
                                        text = String.format(Locale.US, "conf %.2f", log.confidence),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.accentText
                                    )
                                }
                                Text(
                                    text = log.resultSummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.primaryText
                                )
                                Text(
                                    text = "Path: ${log.executionPathUsed}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.accentText
                                )
                                Text(
                                    text = "Args: ${log.argumentsJson} · STT ${log.sttLatencyMs}ms | Needle ${log.needleLatencyMs}ms | Exec ${log.executionLatencyMs}ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = RobotoMonoFontFamily,
                                    color = spec.mutedText
                                )

                                if (log.isReversible && !log.isUndone) {
                                    NeuSurface(
                                        depth = 1,
                                        useCachedShadowForList = true,
                                        shape = CircleShape,
                                        cornerRadiusDp = 18.dp,
                                        onClick = { onUndoTaskLog(log.id) },
                                        modifier = Modifier.testTag("history_undo_${log.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                                contentDescription = "Undo action",
                                                tint = spec.accentText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Undo",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = spec.accentText
                                            )
                                        }
                                    }
                                } else if (log.isUndone) {
                                    Text(
                                        text = "Status: Undone",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.successText
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

/**
 * Tool Playground Screen (Settings -> Advanced / Tasks):
 * Type or speak a command and inspect the raw Whistle transcript, Needle 3 JSON output,
 * calibrated confidence score, retrieved top-5 tools, risk tier decision, and per-stage latency.
 */
@Composable
fun ToolPlaygroundScreen(
    lastResult: PlaygroundRunResult?,
    onBack: () -> Unit,
    onRunCommand: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = SkyTheme.spec
    var prompt by remember { mutableStateOf("Set a timer for 5 minutes") }
    var executeAction by remember { mutableStateOf(true) }

    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(spec.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NeuSurface(
                depth = 1,
                shape = CircleShape,
                cornerRadiusDp = 24.dp,
                onClick = onBack,
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = spec.primaryText
                )
            }
            Text(
                text = "Needle 3 + Whistle Playground",
                style = MaterialTheme.typography.titleLarge,
                color = spec.primaryText
            )
        }

        NeuSurface(
            depth = -1,
            shape = RoundedCornerShape(20.dp),
            cornerRadiusDp = 20.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = spec.primaryText),
                cursorBrush = SolidColor(spec.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("playground_input_field")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NeuSurface(
                depth = if (executeAction) -1 else 1,
                shape = CircleShape,
                cornerRadiusDp = 20.dp,
                onClick = { executeAction = !executeAction }
            ) {
                Text(
                    text = if (executeAction) "Execute Tier 1: ON" else "Dry-Run Only",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (executeAction) spec.accentText else spec.primaryText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }

            NeuSurface(
                depth = 1,
                accentFill = true,
                shape = CircleShape,
                cornerRadiusDp = 24.dp,
                onClick = { onRunCommand(prompt, executeAction) },
                modifier = Modifier.testTag("playground_run_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Run Needle 3 inference",
                        tint = spec.onAccentText
                    )
                    Text(
                        text = "Run Pipeline",
                        style = MaterialTheme.typography.labelLarge,
                        color = spec.onAccentText
                    )
                }
            }
        }

        if (lastResult != null) {
            NeuSurface(
                depth = 1,
                shape = RoundedCornerShape(22.dp),
                cornerRadiusDp = 22.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = "Per-Stage Latency Breakdown",
                            style = MaterialTheme.typography.labelLarge,
                            color = spec.accentText
                        )
                        Text(
                            text = "Whistle STT: ${lastResult.sttLatencyMs} ms  |  Needle 3: ${lastResult.needleLatencyMs} ms  |  Execution: ${lastResult.executionLatencyMs} ms",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = spec.primaryText
                        )
                    }
                    item {
                        Text(
                            text = "Calibrated Confidence & Contrastive Top-5 Retrieval",
                            style = MaterialTheme.typography.labelLarge,
                            color = spec.accentText
                        )
                        Text(
                            text = "Confidence: ${String.format(Locale.US, "%.3f", lastResult.confidence)}\nRetrieved Top-5 Schemas: ${lastResult.retrievedTools.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = spec.primaryText
                        )
                    }
                    item {
                        val routeDesc = when (val r = lastResult.routeAction) {
                            is RouteAction.ExecuteImmediately -> "TIER 1 -> Execute Immediately (${r.tool.name})"
                            is RouteAction.StartTier2Countdown -> "TIER 2 -> 3s Cancelable Countdown (${r.tool.name})"
                            is RouteAction.RequireUnlockForTier2 -> "TIER 2 -> Blocked on Lockscreen (${r.tool.name})"
                            is RouteAction.RouteToReplyModel -> "Route to Reply GGUF (${r.reason})"
                        }
                        Text(
                            text = "Router Decision: $routeDesc",
                            style = MaterialTheme.typography.labelLarge,
                            color = spec.successText
                        )
                    }
                    item {
                        Text(
                            text = "Raw Needle 3 Grammar-Constrained JSON",
                            style = MaterialTheme.typography.labelLarge,
                            color = spec.accentText
                        )
                        NeuSurface(
                            depth = -1,
                            shape = RoundedCornerShape(14.dp),
                            cornerRadiusDp = 14.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lastResult.rawNeedleJson,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = RobotoMonoFontFamily,
                                color = spec.primaryText,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
