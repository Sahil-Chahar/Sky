package com.example.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.InstalledModelEntity
import com.example.data.local.ModelRoleSlot
import com.example.data.local.SkySettingsState
import com.example.data.local.ToolRiskTier
import com.example.tools.SkyToolCatalog
import com.example.ui.SkyAppViewModel
import com.example.ui.components.NeuSurface
import com.example.ui.components.SiriWakeStyle
import com.example.ui.components.SkyEmblem
import com.example.ui.theme.SkyTheme
import com.example.ui.theme.SkyThemeMode
import com.example.ui.theme.SkyThemePreset
import java.util.Locale

@Composable
fun GuidedSetupWizardScreen(
    onBack: () -> Unit,
    onMarkCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spec = SkyTheme.spec

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var guidedSequenceStep by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.toTypedArray()
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTrigger++
        guidedSequenceStep = 1
    }

    val hasMic = remember(refreshTrigger) { isPermGranted(context, Manifest.permission.RECORD_AUDIO) }
    val hasContacts = remember(refreshTrigger) { isPermGranted(context, Manifest.permission.READ_CONTACTS) }
    val hasPhoneSms = remember(refreshTrigger) {
        isPermGranted(context, Manifest.permission.CALL_PHONE) &&
            isPermGranted(context, Manifest.permission.SEND_SMS)
    }
    val hasCalendar = remember(refreshTrigger) {
        isPermGranted(context, Manifest.permission.READ_CALENDAR) &&
            isPermGranted(context, Manifest.permission.WRITE_CALENDAR)
    }
    val hasDndPolicy = remember(refreshTrigger) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.isNotificationPolicyAccessGranted == true
    }
    val hasWriteSettings = remember(refreshTrigger) { Settings.System.canWrite(context) }
    val hasBatteryExemption = remember(refreshTrigger) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val hasAccessibility = remember(refreshTrigger) {
        SkyAppViewModel.isAccessibilityServiceEnabled(context)
    }
    val hasAssistantRole = remember(refreshTrigger) {
        SkyAppViewModel.isAssistantRoleHeld(context)
    }

    BackHandler { onBack() }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sky Guided Setup Wizard",
                    style = MaterialTheme.typography.titleLarge,
                    color = spec.primaryText
                )
                Text(
                    text = "Grant permissions upfront so device tasks never show popups",
                    style = MaterialTheme.typography.labelMedium,
                    color = spec.mutedText
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Primary Guided Sequence Action Card
            item {
                NeuSurface(
                    depth = 2,
                    shape = RoundedCornerShape(24.dp),
                    cornerRadiusDp = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Guided Multi-Step Setup Sequence",
                            style = MaterialTheme.typography.titleMedium,
                            color = spec.primaryText
                        )
                        Text(
                            text = "Because Android special privileges (Write Settings, DND, Accessibility, Battery, and Digital Assistant Role) each open their own system Settings screen, tap below to advance step-by-step.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = spec.mutedText
                        )

                        NeuSurface(
                            depth = 1,
                            accentFill = true,
                            shape = CircleShape,
                            cornerRadiusDp = 24.dp,
                            onClick = {
                                when {
                                    !hasMic || !hasContacts || !hasPhoneSms || !hasCalendar -> {
                                        permLauncher.launch(runtimePermissions)
                                    }
                                    !hasDndPolicy -> {
                                        safeStartSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                    }
                                    !hasWriteSettings -> {
                                        safeStartSettings(
                                            context,
                                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    }
                                    !hasBatteryExemption -> {
                                        safeStartSettings(
                                            context,
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    }
                                    !hasAccessibility -> {
                                        safeStartSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    }
                                    !hasAssistantRole -> {
                                        safeStartSettings(context, Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    }
                                    else -> {
                                        onMarkCompleted()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wizard_guided_sequence_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (hasMic && hasAssistantRole) {
                                    "Finish Setup"
                                } else {
                                    "Start / Continue Guided Setup (Step ${guidedSequenceStep + 1})"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = spec.onAccentText,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                    }
                }
            }

            // Checklist Items
            item {
                SetupChecklistRow(
                    title = "1. Microphone, Contacts, Phone, SMS & Calendar",
                    subtitle = "Required for 16 kHz Whistle STT and silent call/SMS/calendar execution.",
                    isGranted = hasMic && hasContacts && hasPhoneSms && hasCalendar,
                    actionLabel = "Grant Runtime",
                    onClick = { permLauncher.launch(runtimePermissions) }
                )
            }

            item {
                SetupChecklistRow(
                    title = "2. Do Not Disturb Policy Access",
                    subtitle = "Allows `do_not_disturb` tool to toggle interruption filters silently.",
                    isGranted = hasDndPolicy,
                    actionLabel = "Open DND Settings",
                    onClick = {
                        safeStartSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    }
                )
            }

            item {
                SetupChecklistRow(
                    title = "3. Modify System Settings (Write Settings)",
                    subtitle = "Allows `brightness` tool to set display brightness without UI.",
                    isGranted = hasWriteSettings,
                    actionLabel = "Allow Write",
                    onClick = {
                        safeStartSettings(
                            context,
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    }
                )
            }

            item {
                SetupChecklistRow(
                    title = "4. Battery Optimization Exemption",
                    subtitle = "Keeps `SkyVoiceInteractionService` warm for < 300 ms power-button wake.",
                    isGranted = hasBatteryExemption,
                    actionLabel = "Exempt Battery",
                    onClick = {
                        safeStartSettings(
                            context,
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                    }
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    SetupChecklistRow(
                        title = "5a. Android 13+ Allow Restricted Settings (Sideloaded APK)",
                        subtitle = "Before enabling Accessibility on Android 13+, open App Info → tap ⋮ (top-right) → 'Allow restricted settings'.",
                        isGranted = hasAccessibility,
                        actionLabel = "Open App Info",
                        onClick = {
                            safeStartSettings(
                                context,
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        }
                    )
                }
            }

            item {
                SetupChecklistRow(
                    title = "5b. Sky Accessibility Service (Best-Effort Quick Settings)",
                    subtitle = "Used only to click Wi-Fi / Bluetooth Quick Settings tiles; falls back to Connectivity / Bluetooth Settings if off.",
                    isGranted = hasAccessibility,
                    actionLabel = "Accessibility",
                    onClick = {
                        safeStartSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                )
            }

            item {
                SetupChecklistRow(
                    title = "6. Make Sky Your Default Digital Assistant (ROLE_ASSISTANT)",
                    subtitle = "Deep-links to Default Digital Assistant (`ACTION_VOICE_INPUT_SETTINGS`).\n" +
                        "• Pixel: Settings → System → Gestures → Press & hold power button → Digital assistant.\n" +
                        "• Samsung: Settings → Advanced features → Side button → Press and hold → Digital assistant.\n" +
                        "Replaces Gemini/Bixby on that gesture and is 100% reversible anytime.",
                    isGranted = hasAssistantRole,
                    actionLabel = "Choose Assistant",
                    onClick = {
                        safeStartSettings(context, Settings.ACTION_VOICE_INPUT_SETTINGS)
                    }
                )
            }

            item {
                NeuSurface(
                    depth = 1,
                    shape = CircleShape,
                    cornerRadiusDp = 24.dp,
                    onClick = onMarkCompleted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wizard_done_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Save & Return to Sky",
                        style = MaterialTheme.typography.labelLarge,
                        color = spec.accentText,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupChecklistRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    val spec = SkyTheme.spec
    NeuSurface(
        depth = 1,
        useCachedShadowForList = true,
        shape = RoundedCornerShape(20.dp),
        cornerRadiusDp = 20.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (isGranted) "Granted" else "Not granted",
                tint = if (isGranted) spec.successFill else spec.mutedText,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = spec.primaryText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = spec.mutedText
                )
            }
            NeuSurface(
                depth = if (isGranted) -1 else 1,
                useCachedShadowForList = true,
                shape = CircleShape,
                cornerRadiusDp = 18.dp,
                onClick = onClick
            ) {
                Text(
                    text = if (isGranted) "Granted" else actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isGranted) spec.successText else spec.accentText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: SkySettingsState,
    installedModels: List<InstalledModelEntity>,
    onOpenDrawer: () -> Unit,
    onNavigateBackToChat: () -> Unit,
    onUpdateSettings: ((SkySettingsState) -> SkySettingsState) -> Unit,
    onSelectDefaultModel: (InstalledModelEntity) -> Unit,
    onOpenSetupWizard: () -> Unit,
    onOpenPlayground: () -> Unit,
    onWipeAllData: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = SkyTheme.spec
    var showAdvanced by remember { mutableStateOf(false) }
    var showTasksConfig by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    BackHandler { onNavigateBackToChat() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(spec.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Top Bar
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
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = spec.primaryText
            )
            NeuSurface(
                depth = 1,
                shape = CircleShape,
                cornerRadiusDp = 24.dp,
                onClick = onOpenSetupWizard,
                modifier = Modifier.testTag("settings_reopen_wizard_button")
            ) {
                Text(
                    text = "Wizard",
                    style = MaterialTheme.typography.labelLarge,
                    color = spec.accentText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Group 1: Appearance (Sky Light / Sky Dark / System)
            item {
                NeuSurface(
                    depth = 1,
                    shape = RoundedCornerShape(24.dp),
                    cornerRadiusDp = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Appearance · Neo 3D Neumorphism",
                            style = MaterialTheme.typography.titleMedium,
                            color = spec.primaryText
                        )

                        // Light / Dark / System Mode
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SkyThemeMode.entries.forEach { mode ->
                                val selected = settings.themeMode == mode
                                NeuSurface(
                                    depth = if (selected) -1 else 1,
                                    pressed = selected,
                                    shape = CircleShape,
                                    cornerRadiusDp = 20.dp,
                                    onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("theme_mode_${mode.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) spec.accentText else spec.primaryText,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                }
                            }
                        }

                        // Theme Preset Switcher (Sky v1 default + ThemeSpec presets)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SkyThemePreset.entries.forEach { preset ->
                                val selected = settings.themePreset == preset
                                NeuSurface(
                                    depth = if (selected) -1 else 1,
                                    pressed = selected,
                                    shape = CircleShape,
                                    cornerRadiusDp = 20.dp,
                                    onClick = { onUpdateSettings { it.copy(themePreset = preset) } },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("theme_preset_${preset.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) spec.accentText else spec.primaryText,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Group 2: Voice & Assistant (Model Slots, Siri Glow vs Siri Orb, VAD Endpoint, TTS)
            item {
                NeuSurface(
                    depth = 1,
                    shape = RoundedCornerShape(24.dp),
                    cornerRadiusDp = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Voice & Assistant",
                            style = MaterialTheme.typography.titleMedium,
                            color = spec.primaryText
                        )

                        // Siri Wake Animation Style Picker
                        SiriWakeStyle.entries.forEach { style ->
                            val selected = settings.siriWakeStyle == style
                            NeuSurface(
                                depth = if (selected) -1 else 1,
                                pressed = selected,
                                shape = RoundedCornerShape(18.dp),
                                cornerRadiusDp = 18.dp,
                                onClick = { onUpdateSettings { it.copy(siriWakeStyle = style) } },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wake_style_${style.id}")
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = style.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) spec.accentText else spec.primaryText
                                    )
                                    Text(
                                        text = style.subtitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = spec.mutedText
                                    )
                                }
                            }
                        }

                        // Active Model per Role Slot
                        ModelRoleSlot.entries.forEach { slot ->
                            val active = installedModels.firstOrNull {
                                it.roleSlot == slot.id && it.isActiveDefault
                            } ?: installedModels.firstOrNull { it.roleSlot == slot.id }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = slot.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.mutedText
                                )
                                Text(
                                    text = active?.displayName ?: "Not installed",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = spec.accentText,
                                    modifier = Modifier.clickable {
                                        if (active != null) onSelectDefaultModel(active)
                                    }
                                )
                            }
                        }

                        // VAD Endpointing Slider (600–1000 ms, default 650 ms)
                        Column {
                            Text(
                                text = "VAD Silence Endpoint: ${settings.vadSilenceMs} ms (from end-of-speech)",
                                style = MaterialTheme.typography.labelLarge,
                                color = spec.primaryText
                            )
                            Slider(
                                value = settings.vadSilenceMs.toFloat(),
                                onValueChange = { v ->
                                    onUpdateSettings { it.copy(vadSilenceMs = v.toInt()) }
                                },
                                valueRange = 550f..1100f
                            )
                        }

                        // TTS Speech Rate & Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Speak Assistant Replies (Android TTS)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = spec.primaryText
                            )
                            NeuSurface(
                                depth = if (settings.ttsEnabled) -1 else 1,
                                shape = CircleShape,
                                cornerRadiusDp = 18.dp,
                                onClick = { onUpdateSettings { it.copy(ttsEnabled = !it.ttsEnabled) } }
                            ) {
                                Text(
                                    text = if (settings.ttsEnabled) "ON" else "OFF",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (settings.ttsEnabled) spec.accentText else spec.mutedText,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Group 3: Tasks & Risk-Tier Controls (Per-Tool Enable + Tier 2 "Run Instantly" Override)
            item {
                NeuSurface(
                    depth = 1,
                    shape = RoundedCornerShape(24.dp),
                    cornerRadiusDp = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTasksConfig = !showTasksConfig },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Device Tasks & Tier 2 Countdown Controls",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = spec.primaryText
                                )
                                Text(
                                    text = "Per-task on/off & 'Run instantly' toggle for Tier 2 (calls/SMS)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.mutedText
                                )
                            }
                            Icon(
                                imageVector = if (showTasksConfig) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = "Toggle task settings",
                                tint = spec.primaryText
                            )
                        }

                        if (showTasksConfig) {
                            SkyToolCatalog.BuiltInTools.forEach { tool ->
                                val isEnabled = tool.name !in settings.disabledTools
                                val isTier2 = tool.riskTier == ToolRiskTier.TIER_2
                                val runInstantly = tool.name in settings.instantTier2Tools

                                NeuSurface(
                                    depth = -1,
                                    useCachedShadowForList = true,
                                    shape = RoundedCornerShape(16.dp),
                                    cornerRadiusDp = 16.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${tool.title} (${tool.riskTier.displayName})",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = spec.primaryText
                                                )
                                                Text(
                                                    text = if (tool.isReversible) "Reversible (Supports Undo)" else "Non-reversible",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = spec.mutedText
                                                )
                                            }
                                            NeuSurface(
                                                depth = if (isEnabled) -1 else 1,
                                                useCachedShadowForList = true,
                                                shape = CircleShape,
                                                cornerRadiusDp = 16.dp,
                                                onClick = {
                                                    onUpdateSettings { state ->
                                                        val next = if (isEnabled) {
                                                            state.disabledTools + tool.name
                                                        } else {
                                                            state.disabledTools - tool.name
                                                        }
                                                        state.copy(disabledTools = next)
                                                    }
                                                }
                                            ) {
                                                Text(
                                                    text = if (isEnabled) "Enabled" else "Off",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (isEnabled) spec.successText else spec.mutedText,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                )
                                            }
                                        }

                                        if (isTier2) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Run instantly (skip 3s countdown)",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = spec.warningText
                                                )
                                                NeuSurface(
                                                    depth = if (runInstantly) -1 else 1,
                                                    useCachedShadowForList = true,
                                                    shape = CircleShape,
                                                    cornerRadiusDp = 16.dp,
                                                    onClick = {
                                                        onUpdateSettings { state ->
                                                            val next = if (runInstantly) {
                                                                state.instantTier2Tools - tool.name
                                                            } else {
                                                                state.instantTier2Tools + tool.name
                                                            }
                                                            state.copy(instantTier2Tools = next)
                                                        }
                                                    },
                                                    modifier = Modifier.testTag("tier2_instant_${tool.name}")
                                                ) {
                                                    Text(
                                                        text = if (runInstantly) "Instant" else "3s Countdown",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = if (runInstantly) spec.warningText else spec.accentText,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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

            // Group 4: Advanced (Collapsed by default)
            item {
                NeuSurface(
                    depth = 1,
                    shape = RoundedCornerShape(24.dp),
                    cornerRadiusDp = 24.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvanced = !showAdvanced }
                                .testTag("settings_advanced_toggle"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Advanced (Sampling, Playground & Wipe Data)",
                                style = MaterialTheme.typography.titleMedium,
                                color = spec.primaryText
                            )
                            Icon(
                                imageVector = if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = "Toggle Advanced Settings",
                                tint = spec.primaryText
                            )
                        }

                        if (showAdvanced) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Temperature: %.2f  |  Top-P: %.2f  |  Threads: %d  |  Context: %d",
                                    settings.llmTemperature,
                                    settings.llmTopP,
                                    settings.llmThreads,
                                    settings.llmContextSize
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = spec.mutedText
                            )
                            Slider(
                                value = settings.llmTemperature,
                                onValueChange = { v -> onUpdateSettings { it.copy(llmTemperature = v) } },
                                valueRange = 0.0f..1.5f
                            )

                            NeuSurface(
                                depth = 1,
                                shape = CircleShape,
                                cornerRadiusDp = 20.dp,
                                onClick = onOpenPlayground,
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Science,
                                        contentDescription = null,
                                        tint = spec.accentText
                                    )
                                    Text(
                                        text = "Open Needle 3 + Whistle Tool Playground",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = spec.accentText
                                    )
                                }
                            }

                            // Wipe All Data Button
                            NeuSurface(
                                depth = 1,
                                shape = CircleShape,
                                cornerRadiusDp = 20.dp,
                                onClick = {
                                    if (confirmWipe) {
                                        onWipeAllData()
                                        confirmWipe = false
                                    } else {
                                        confirmWipe = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_wipe_all_data_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteForever,
                                        contentDescription = null,
                                        tint = spec.errorText
                                    )
                                    Text(
                                        text = if (confirmWipe) {
                                            "Tap Again to Confirm Wipe All Data"
                                        } else {
                                            "Wipe All Local Chats, Embeddings, Models & Keys"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = spec.errorText
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
fun AboutSkyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = SkyTheme.spec
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
                text = "About Sky",
                style = MaterialTheme.typography.titleLarge,
                color = spec.primaryText
            )
        }

        NeuSurface(
            depth = 1,
            shape = RoundedCornerShape(24.dp),
            cornerRadiusDp = 24.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SkyEmblem(size = 84.dp, animateFloat = true)
                Text(
                    text = "Sky · On-Device Android Assistant",
                    style = MaterialTheme.typography.titleLarge,
                    color = spec.primaryText
                )
                Text(
                    text = "Package: com.sky.assistant · 100% On-Device AI Inference",
                    style = MaterialTheme.typography.labelMedium,
                    color = spec.accentText,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• Speech Engine: Cactus Compute Whistle (`whistle.cact`, 16 kHz mono STT)\n" +
                        "• Task & Embedding Engine: Cactus Compute Needle 3 (`needle3.cact`, grammar-constrained tool calling + 384-d `needle_embed` vectors)\n" +
                        "• Conversational Reply Engine: `llama.cpp` JNI (`libllama_jni.so`, GGUF streaming with SmolLM2 / Qwen / Llama / Gemma)\n" +
                        "• Privacy Guarantee: Zero analytics, zero telemetry, never logs tokens or transcripts. Network is used only for Hugging Face Hub downloads and explicit `web_search` commands.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = spec.mutedText
                )
            }
        }
    }
}

private fun isPermGranted(context: Context, perm: String): Boolean {
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun safeStartSettings(context: Context, action: String, dataUri: Uri? = null) {
    try {
        val intent = Intent(action).apply {
            if (dataUri != null) data = dataUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }
}
