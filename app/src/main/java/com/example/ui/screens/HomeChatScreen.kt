package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.assistant.SkyAssistActivity
import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.InstalledModelEntity
import com.example.data.local.ModelRoleSlot
import com.example.data.repository.SemanticSearchResult
import com.example.ui.PendingChatTier2Countdown
import com.example.ui.SkyDestination
import com.example.ui.components.NeuSurface
import com.example.ui.components.SkyEmblem
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.SkyTheme
import java.util.Calendar
import java.util.Locale

@Composable
fun SkyNavigationDrawerContent(
    currentDestination: SkyDestination,
    sessions: List<ChatSessionEntity>,
    activeSessionId: Long?,
    semanticResults: List<SemanticSearchResult>,
    onNavigate: (SkyDestination) -> Unit,
    onSelectSession: (Long) -> Unit,
    onNewChat: () -> Unit,
    onTogglePinSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onSemanticSearch: (String) -> Unit,
    onLaunchVoiceOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = SkyTheme.spec
    var searchQuery by remember { mutableStateOf("") }

    val now = System.currentTimeMillis()
    val oneDayMs = 24L * 3600L * 1000L
    val sevenDaysMs = 7L * oneDayMs

    val todaySessions = remember(sessions, now) {
        sessions.filter { now - it.updatedAt < oneDayMs }
    }
    val yesterdaySessions = remember(sessions, now) {
        sessions.filter { (now - it.updatedAt) in oneDayMs until (2 * oneDayMs) }
    }
    val thisWeekSessions = remember(sessions, now) {
        sessions.filter { (now - it.updatedAt) in (2 * oneDayMs) until sevenDaysMs }
    }
    val earlierSessions = remember(sessions, now) {
        sessions.filter { (now - it.updatedAt) >= sevenDaysMs }
    }

    NeuSurface(
        depth = 2,
        shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        cornerRadiusDp = 28.dp,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 290.dp, max = 330.dp)
            .testTag("sky_navigation_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Drawer Header + New Chat button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sky",
                    style = MaterialTheme.typography.headlineMedium,
                    color = spec.primaryText
                )
                NeuSurface(
                    depth = 1,
                    shape = CircleShape,
                    cornerRadiusDp = 20.dp,
                    onClick = onNewChat,
                    modifier = Modifier.testTag("drawer_new_chat_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "New chat",
                            tint = spec.accentText,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "New",
                            style = MaterialTheme.typography.labelLarge,
                            color = spec.accentText
                        )
                    }
                }
            }

            // Primary Navigation Rows (Chat, Tasks, Models, Settings, Sky Voice · Hands-free, About)
            DrawerNavRow(
                icon = Icons.AutoMirrored.Rounded.Chat,
                label = "Chat",
                selected = currentDestination == SkyDestination.CHAT,
                tag = "drawer_nav_chat",
                onClick = { onNavigate(SkyDestination.CHAT) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            DrawerNavRow(
                icon = Icons.Rounded.TaskAlt,
                label = "Tasks",
                selected = currentDestination == SkyDestination.TASKS,
                tag = "drawer_nav_tasks",
                onClick = { onNavigate(SkyDestination.TASKS) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            DrawerNavRow(
                icon = Icons.Rounded.Memory,
                label = "Models",
                selected = currentDestination == SkyDestination.MODELS,
                tag = "drawer_nav_models",
                onClick = { onNavigate(SkyDestination.MODELS) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            DrawerNavRow(
                icon = Icons.Rounded.Settings,
                label = "Settings",
                selected = currentDestination == SkyDestination.SETTINGS,
                tag = "drawer_nav_settings",
                onClick = { onNavigate(SkyDestination.SETTINGS) }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Highlighted "Sky Voice · Hands-free" card with subtitle
            NeuSurface(
                depth = 1,
                accentFill = true,
                shape = RoundedCornerShape(22.dp),
                cornerRadiusDp = 22.dp,
                onClick = onLaunchVoiceOverlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("drawer_sky_voice_row")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Sky Voice Hands-free",
                        tint = spec.onAccentText,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sky Voice · Hands-free",
                            style = MaterialTheme.typography.titleMedium,
                            color = spec.onAccentText
                        )
                        Text(
                            text = "Long-press power · Whistle + Needle 3",
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.onAccentText.copy(alpha = 0.88f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            DrawerNavRow(
                icon = Icons.Rounded.Info,
                label = "About",
                selected = currentDestination == SkyDestination.ABOUT,
                tag = "drawer_nav_about",
                onClick = { onNavigate(SkyDestination.ABOUT) }
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = spec.hairline)
            Spacer(modifier = Modifier.height(12.dp))

            // Semantic Search Input (`needle_embed`)
            NeuSurface(
                depth = -1,
                shape = CircleShape,
                cornerRadiusDp = 22.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Semantic chat search",
                        tint = spec.mutedText,
                        modifier = Modifier.size(18.dp)
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            onSemanticSearch(it)
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = spec.primaryText),
                        cursorBrush = SolidColor(spec.accent),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("drawer_semantic_search_input"),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Semantic search (needle_embed)…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.mutedText
                                )
                            }
                            inner()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search",
                            tint = spec.mutedText,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    searchQuery = ""
                                    onSemanticSearch("")
                                }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // History grouped Today / Yesterday / This week / Earlier (or Semantic Search hits)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (searchQuery.isNotBlank()) {
                    item {
                        Text(
                            text = "Semantic Matches (${semanticResults.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.mutedText,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(semanticResults, key = { "${it.sourceType}_${it.sourceId}" }) { hit ->
                        NeuSurface(
                            depth = 1,
                            useCachedShadowForList = true,
                            shape = RoundedCornerShape(16.dp),
                            cornerRadiusDp = 16.dp,
                            onClick = {
                                hit.sessionId?.let(onSelectSession)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "${hit.sourceType} · ${String.format(Locale.US, "%.0f%% match", hit.score * 100)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.accentText
                                )
                                Text(
                                    text = hit.textPreview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.primaryText,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    if (todaySessions.isNotEmpty()) {
                        item { DrawerSectionHeader("Today") }
                        items(todaySessions, key = { it.id }) { session ->
                            SessionHistoryRow(
                                session = session,
                                isSelected = session.id == activeSessionId,
                                onSelect = { onSelectSession(session.id) },
                                onTogglePin = { onTogglePinSession(session.id) },
                                onDelete = { onDeleteSession(session.id) }
                            )
                        }
                    }
                    if (yesterdaySessions.isNotEmpty()) {
                        item { DrawerSectionHeader("Yesterday") }
                        items(yesterdaySessions, key = { it.id }) { session ->
                            SessionHistoryRow(
                                session = session,
                                isSelected = session.id == activeSessionId,
                                onSelect = { onSelectSession(session.id) },
                                onTogglePin = { onTogglePinSession(session.id) },
                                onDelete = { onDeleteSession(session.id) }
                            )
                        }
                    }
                    if (thisWeekSessions.isNotEmpty()) {
                        item { DrawerSectionHeader("This week") }
                        items(thisWeekSessions, key = { it.id }) { session ->
                            SessionHistoryRow(
                                session = session,
                                isSelected = session.id == activeSessionId,
                                onSelect = { onSelectSession(session.id) },
                                onTogglePin = { onTogglePinSession(session.id) },
                                onDelete = { onDeleteSession(session.id) }
                            )
                        }
                    }
                    if (earlierSessions.isNotEmpty()) {
                        item { DrawerSectionHeader("Earlier") }
                        items(earlierSessions, key = { it.id }) { session ->
                            SessionHistoryRow(
                                session = session,
                                isSelected = session.id == activeSessionId,
                                onSelect = { onSelectSession(session.id) },
                                onTogglePin = { onTogglePinSession(session.id) },
                                onDelete = { onDeleteSession(session.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val spec = SkyTheme.spec
    NeuSurface(
        depth = if (selected) -1 else 0,
        pressed = selected,
        shape = RoundedCornerShape(18.dp),
        cornerRadiusDp = 18.dp,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) spec.accentText else spec.primaryText,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) spec.accentText else spec.primaryText
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = SkyTheme.spec.mutedText,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
private fun SessionHistoryRow(
    session: ChatSessionEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    val spec = SkyTheme.spec
    NeuSurface(
        depth = if (isSelected) -1 else 1,
        useCachedShadowForList = true,
        shape = RoundedCornerShape(16.dp),
        cornerRadiusDp = 16.dp,
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) spec.accentText else spec.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = "Pin conversation",
                    tint = if (session.isPinned) spec.accentText else spec.mutedText,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onTogglePin() }
                )
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete conversation",
                    tint = spec.mutedText,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDelete() }
                )
            }
        }
    }
}

/**
 * Home Chat Screen following Reference UI Layout B:
 *  - Top-left: raised neumorphic hamburger button
 *  - Top-right: private-chat (ghost) toggle button
 *  - Empty state: centered sun-and-cloud `SkyEmblem` + one-line time-of-day greeting + one button
 *  - Bottom: large floating `NeuSurface(depth = 2)` input card rising above the keyboard with:
 *    - Text field
 *    - Row beneath: round `+` button, active Reply model chip (tap to switch), Whistle dictation mic button,
 *      and primary round voice button on the right (morphs into Send when typing).
 */
@Composable
fun HomeChatScreen(
    messages: List<ChatMessageEntity>,
    installedModels: List<InstalledModelEntity>,
    isGhostMode: Boolean,
    isStreaming: Boolean,
    streamingContent: String,
    streamingThink: String?,
    streamingTokensPerSec: Float,
    isReplyWarmingUp: Boolean,
    thermalBanner: String?,
    isDictating: Boolean,
    pendingTier2Countdown: PendingChatTier2Countdown?,
    setupWizardCompleted: Boolean,
    onOpenDrawer: () -> Unit,
    onToggleGhostMode: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStopStreaming: () -> Unit,
    onRegenerateLast: () -> Unit,
    onStartDictation: ((String) -> Unit) -> Unit,
    onLaunchVoiceOverlay: () -> Unit,
    onSwitchActiveReplyModel: (InstalledModelEntity) -> Unit,
    onOpenModelHub: () -> Unit,
    onOpenSetupWizard: () -> Unit,
    onUndoTask: (Long, ChatMessageEntity) -> Unit,
    onConfirmTier2: () -> Unit,
    onCancelTier2: () -> Unit,
    onLaunchToolShortcut: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spec = SkyTheme.spec
    var inputText by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showModelPickerSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val replyModels = remember(installedModels) {
        installedModels.filter { it.roleSlot == ModelRoleSlot.REPLY.id }
    }
    val activeReplyModel = remember(replyModels) {
        replyModels.firstOrNull { it.isActiveDefault } ?: replyModels.firstOrNull()
    }

    val greetingLine = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning — speak a task or ask anything."
            in 12..17 -> "Good afternoon — how can Sky help on-device?"
            else -> "Good evening — ready for private local chat & tasks."
        }
    }

    LaunchedEffect(messages.size, streamingContent) {
        val total = messages.size + (if (isStreaming) 1 else 0)
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(spec.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
    ) {
        // Top App Bar: Hamburger top-left, Private Chat (Ghost) top-right
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
                modifier = Modifier
                    .size(48.dp)
                    .testTag("home_hamburger_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation drawer",
                    tint = spec.primaryText
                )
            }

            if (isGhostMode) {
                NeuSurface(
                    depth = -1,
                    shape = CircleShape,
                    cornerRadiusDp = 20.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VisibilityOff,
                            contentDescription = null,
                            tint = spec.accentText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Private Ghost Chat · Not Saved",
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.accentText
                        )
                    }
                }
            } else if (!setupWizardCompleted) {
                NeuSurface(
                    depth = 1,
                    shape = CircleShape,
                    cornerRadiusDp = 20.dp,
                    onClick = onOpenSetupWizard,
                    modifier = Modifier.testTag("home_setup_wizard_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Complete assistant setup",
                            tint = spec.accentText,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Assistant Setup",
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.accentText
                        )
                    }
                }
            }

            NeuSurface(
                depth = if (isGhostMode) -1 else 1,
                pressed = isGhostMode,
                shape = CircleShape,
                cornerRadiusDp = 24.dp,
                onClick = onToggleGhostMode,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("home_ghost_mode_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.VisibilityOff,
                    contentDescription = "Toggle Private Ghost Chat",
                    tint = if (isGhostMode) spec.accentText else spec.primaryText
                )
            }
        }

        // Optional Thermal Status Warning Banner
        if (!thermalBanner.isNullOrBlank()) {
            NeuSurface(
                depth = -1,
                shape = RoundedCornerShape(16.dp),
                cornerRadiusDp = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
            ) {
                Text(
                    text = thermalBanner,
                    style = MaterialTheme.typography.labelMedium,
                    color = spec.warningText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Center Area: Empty State OR Conversation Stream
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty() && !isStreaming && pendingTier2Countdown == null) {
                // Reference UI B Empty State: Centered SkyEmblem + One-Line Greeting + One Button
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SkyEmblem(size = 104.dp, animateFloat = true)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = greetingLine,
                        style = MaterialTheme.typography.titleLarge,
                        color = spec.primaryText
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    NeuSurface(
                        depth = 1,
                        shape = CircleShape,
                        cornerRadiusDp = 24.dp,
                        onClick = onLaunchVoiceOverlay,
                        modifier = Modifier.testTag("empty_state_voice_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Launch Sky Voice",
                                tint = spec.accentText,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Wake Sky Voice",
                                style = MaterialTheme.typography.labelLarge,
                                color = spec.accentText
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat_messages_list"),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            message = msg,
                            context = context,
                            onUndoTask = { logId -> onUndoTask(logId, msg) },
                            onRegenerate = onRegenerateLast,
                            onLaunchShortcut = onLaunchToolShortcut
                        )
                    }

                    // Active Tier 2 3-Second Cancelable Countdown inside Chat
                    if (pendingTier2Countdown != null) {
                        item(key = "chat_tier2_countdown") {
                            NeuSurface(
                                depth = 2,
                                shape = RoundedCornerShape(24.dp),
                                cornerRadiusDp = 24.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chat_tier2_countdown_card")
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = if (pendingTier2Countdown.call.toolName == "send_sms") {
                                            "Sending SMS to ${pendingTier2Countdown.call.arguments["recipient"] ?: "Contact"} in ${pendingTier2Countdown.remainingSeconds}s…"
                                        } else {
                                            "Calling ${pendingTier2Countdown.call.arguments["recipient"] ?: "Contact"} in ${pendingTier2Countdown.remainingSeconds}s…"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        NeuSurface(
                                            depth = 1,
                                            shape = CircleShape,
                                            cornerRadiusDp = 20.dp,
                                            onClick = onCancelTier2,
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Cancel",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = spec.errorText,
                                                modifier = Modifier.padding(vertical = 10.dp)
                                            )
                                        }
                                        NeuSurface(
                                            depth = 1,
                                            accentFill = true,
                                            shape = CircleShape,
                                            cornerRadiusDp = 20.dp,
                                            onClick = onConfirmTier2,
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Run Now",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = spec.onAccentText,
                                                modifier = Modifier.padding(vertical = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Active Streaming Assistant Message
                    if (isStreaming) {
                        item(key = "streaming_bubble") {
                            StreamingAssistantBubble(
                                content = streamingContent,
                                thinkContent = streamingThink,
                                tokensPerSec = streamingTokensPerSec,
                                isWarmingUp = isReplyWarmingUp,
                                onStop = onStopStreaming
                            )
                        }
                    }
                }
            }
        }

        // Expandable Attach (+) or Model Switcher Sheets above the input card
        AnimatedVisibility(
            visible = showAttachSheet,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            NeuSurface(
                depth = 1,
                shape = RoundedCornerShape(22.dp),
                cornerRadiusDp = 22.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Quick Prompts & Context",
                        style = MaterialTheme.typography.labelLarge,
                        color = spec.mutedText
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickPromptChip("Set timer 5m") {
                            showAttachSheet = false
                            onSendMessage("Set a timer for 5 minutes")
                        }
                        QuickPromptChip("Turn on torch") {
                            showAttachSheet = false
                            onSendMessage("Turn on the flashlight")
                        }
                        QuickPromptChip("Battery status") {
                            showAttachSheet = false
                            onSendMessage("What is my battery status")
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showModelPickerSheet,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            NeuSurface(
                depth = 1,
                shape = RoundedCornerShape(22.dp),
                cornerRadiusDp = 22.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Reply GGUF Model",
                            style = MaterialTheme.typography.labelLarge,
                            color = spec.mutedText
                        )
                        Text(
                            text = "Browse Hub →",
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.accentText,
                            modifier = Modifier.clickable {
                                showModelPickerSheet = false
                                onOpenModelHub()
                            }
                        )
                    }
                    for (m in replyModels) {
                        NeuSurface(
                            depth = if (m.isActiveDefault) -1 else 1,
                            useCachedShadowForList = true,
                            shape = RoundedCornerShape(16.dp),
                            cornerRadiusDp = 16.dp,
                            onClick = {
                                onSwitchActiveReplyModel(m)
                                showModelPickerSheet = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = m.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (m.isActiveDefault) spec.accentText else spec.primaryText,
                                    fontWeight = if (m.isActiveDefault) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = m.quantization,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.mutedText
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Floating Input Card (`NeuSurface(depth = 2)`) — Reference UI B
        NeuSurface(
            depth = 2,
            shape = RoundedCornerShape(28.dp),
            cornerRadiusDp = 28.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("home_input_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Inset text field
                NeuSurface(
                    depth = -1,
                    shape = RoundedCornerShape(20.dp),
                    cornerRadiusDp = 20.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = spec.primaryText),
                        cursorBrush = SolidColor(spec.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .testTag("home_chat_input_field"),
                        decorationBox = { inner ->
                            if (inputText.isEmpty()) {
                                Text(
                                    text = if (isDictating) {
                                        "Listening via Whistle 16 kHz…"
                                    } else {
                                        "Message Sky or type a device task…"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = spec.mutedText
                                )
                            }
                            inner()
                        }
                    )
                }

                // Bottom Action Row: "+" button, active Reply model chip, Mic button, Voice <-> Send morph button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Round "+" button
                        NeuSurface(
                            depth = if (showAttachSheet) -1 else 1,
                            shape = CircleShape,
                            cornerRadiusDp = 24.dp,
                            onClick = {
                                showAttachSheet = !showAttachSheet
                                showModelPickerSheet = false
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_attach_plus_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Open quick prompts and context sheet",
                                tint = spec.primaryText
                            )
                        }

                        // Active Reply Model Chip (tap to switch)
                        NeuSurface(
                            depth = if (showModelPickerSheet) -1 else 1,
                            shape = CircleShape,
                            cornerRadiusDp = 24.dp,
                            onClick = {
                                showModelPickerSheet = !showModelPickerSheet
                                showAttachSheet = false
                            },
                            modifier = Modifier.testTag("home_active_model_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = spec.accentText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = activeReplyModel?.displayName?.take(18) ?: "SmolLM2 360M",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = spec.primaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Whistle Dictation Mic Button
                        NeuSurface(
                            depth = if (isDictating) -1 else 1,
                            pressed = isDictating,
                            shape = CircleShape,
                            cornerRadiusDp = 24.dp,
                            onClick = {
                                onStartDictation { transcript ->
                                    inputText = if (inputText.isBlank()) {
                                        transcript
                                    } else {
                                        "$inputText $transcript"
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_dictation_mic_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = "Dictate with Whistle STT",
                                tint = if (isDictating) spec.accentText else spec.primaryText
                            )
                        }

                        // Primary Round Voice <-> Send Morph Button
                        val hasTypedText = inputText.isNotBlank()
                        NeuSurface(
                            depth = 2,
                            accentFill = true,
                            shape = CircleShape,
                            cornerRadiusDp = 24.dp,
                            onClick = {
                                if (isStreaming) {
                                    onStopStreaming()
                                } else if (hasTypedText) {
                                    val toSend = inputText.trim()
                                    inputText = ""
                                    onSendMessage(toSend)
                                } else {
                                    onLaunchVoiceOverlay()
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("home_voice_or_send_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedContent(
                                targetState = when {
                                    isStreaming -> "STOP"
                                    hasTypedText -> "SEND"
                                    else -> "VOICE"
                                },
                                transitionSpec = {
                                    (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith fadeOut()
                                },
                                label = "voiceSendMorph"
                            ) { mode ->
                                when (mode) {
                                    "STOP" -> Icon(
                                        imageVector = Icons.Rounded.Stop,
                                        contentDescription = "Stop generation",
                                        tint = spec.onAccentText
                                    )
                                    "SEND" -> Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = "Send message",
                                        tint = spec.onAccentText
                                    )
                                    else -> Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = "Start hands-free Sky Voice mode",
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

@Composable
private fun QuickPromptChip(label: String, onClick: () -> Unit) {
    val spec = SkyTheme.spec
    NeuSurface(
        depth = 1,
        shape = CircleShape,
        cornerRadiusDp = 20.dp,
        onClick = onClick
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = spec.primaryText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    context: Context,
    onUndoTask: (Long) -> Unit,
    onRegenerate: () -> Unit,
    onLaunchShortcut: (String) -> Unit
) {
    val spec = SkyTheme.spec
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    var thinkExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        NeuSurface(
            depth = 1,
            useCachedShadowForList = true,
            accentFill = isUser,
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isUser) 22.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 22.dp
            ),
            cornerRadiusDp = 22.dp,
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Collapsible <think> block for assistant messages
                if (!message.thinkContent.isNullOrBlank()) {
                    NeuSurface(
                        depth = -1,
                        useCachedShadowForList = true,
                        shape = RoundedCornerShape(14.dp),
                        cornerRadiusDp = 14.dp,
                        onClick = { thinkExpanded = !thinkExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Reasoning (<think>)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.accentText
                                )
                                Icon(
                                    imageVector = if (thinkExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = "Toggle reasoning block",
                                    tint = spec.accentText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (thinkExpanded) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = message.thinkContent,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.mutedText
                                )
                            }
                        }
                    }
                }

                // Inline Executed Task Card
                if (isTool) {
                    Text(
                        text = message.toolResultSummary ?: "Executed ${message.toolName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = spec.accentText
                    )
                    if (!message.toolArgsJson.isNullOrBlank()) {
                        Text(
                            text = "Args: ${message.toolArgsJson}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = RobotoMonoFontFamily,
                            color = spec.mutedText
                        )
                    }
                }

                // Markdown & Code Block Formatted Message Content
                FormattedMarkdownMessage(
                    text = message.content,
                    textColor = if (isUser) spec.onAccentText else spec.primaryText,
                    context = context
                )

                // Tool Undo button (strictly for reversible tools) or Shortcut button
                if (isTool) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.canUndo && !message.isUndone && (message.taskLogId ?: -1L) > 0L) {
                            NeuSurface(
                                depth = 1,
                                useCachedShadowForList = true,
                                shape = CircleShape,
                                cornerRadiusDp = 18.dp,
                                onClick = { onUndoTask(message.taskLogId!!) },
                                modifier = Modifier.testTag("chat_task_undo_${message.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                                        contentDescription = "Undo task",
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
                        } else if (message.isUndone) {
                            Text(
                                text = "Undone",
                                style = MaterialTheme.typography.labelMedium,
                                color = spec.successText
                            )
                        }

                        if (message.toolName in listOf("set_alarm", "set_timer", "navigate_to", "web_search")) {
                            NeuSurface(
                                depth = 1,
                                useCachedShadowForList = true,
                                shape = CircleShape,
                                cornerRadiusDp = 18.dp,
                                onClick = { message.toolName?.let(onLaunchShortcut) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = "Open app shortcut",
                                        tint = spec.primaryText,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Open App",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.primaryText
                                    )
                                }
                            }
                        }
                    }
                }

                // Assistant footer: tokens/s + Copy / Share / Regenerate
                if (!isUser && !isTool) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message.tokensPerSec?.let {
                                String.format(Locale.US, "%.1f tok/s · On-Device", it)
                            } ?: "On-Device GGUF",
                            style = MaterialTheme.typography.labelMedium,
                            color = spec.mutedText
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy message",
                                tint = spec.mutedText,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("Sky Message", message.content))
                                    }
                            )
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share message",
                                tint = spec.mutedText,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, message.content)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share"))
                                    }
                            )
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Regenerate reply",
                                tint = spec.mutedText,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRegenerate() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingAssistantBubble(
    content: String,
    thinkContent: String?,
    tokensPerSec: Float,
    isWarmingUp: Boolean,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val spec = SkyTheme.spec
    NeuSurface(
        depth = 1,
        useCachedShadowForList = true,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp),
        cornerRadiusDp = 22.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isWarmingUp && content.isBlank()) {
                Text(
                    text = "Warming up local GGUF Reply model…",
                    style = MaterialTheme.typography.labelLarge,
                    color = spec.accentText
                )
            }
            if (!thinkContent.isNullOrBlank()) {
                Text(
                    text = "Thinking: $thinkContent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = spec.mutedText
                )
            }
            FormattedMarkdownMessage(
                text = "$content ▍",
                textColor = spec.primaryText,
                context = context
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "%.1f tok/s", tokensPerSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = spec.accentText
                )
                Text(
                    text = "Stop",
                    style = MaterialTheme.typography.labelLarge,
                    color = spec.errorText,
                    modifier = Modifier.clickable { onStop() }
                )
            }
        }
    }
}

@Composable
private fun FormattedMarkdownMessage(
    text: String,
    textColor: androidx.compose.ui.graphics.Color,
    context: Context
) {
    val spec = SkyTheme.spec
    val parts = remember(text) { text.split("```") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, segment ->
            if (index % 2 == 1) {
                // Syntax-highlighted Code Block with Copy button
                val firstLineBreak = segment.indexOf('\n')
                val lang = if (firstLineBreak != -1) segment.substring(0, firstLineBreak).trim() else "code"
                val codeBody = if (firstLineBreak != -1) segment.substring(firstLineBreak + 1).trimEnd() else segment

                NeuSurface(
                    depth = -1,
                    useCachedShadowForList = true,
                    shape = RoundedCornerShape(14.dp),
                    cornerRadiusDp = 14.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.ifBlank { "code" }.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = spec.accentText
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Sky Code", codeBody))
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy code block",
                                    tint = spec.accentText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Copy",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.accentText
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = codeBody,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = RobotoMonoFontFamily,
                            color = spec.primaryText
                        )
                    }
                }
            } else if (segment.isNotBlank()) {
                Text(
                    text = segment.trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
            }
        }
    }
}
