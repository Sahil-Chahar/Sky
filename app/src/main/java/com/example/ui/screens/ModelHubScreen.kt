package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.InstalledModelEntity
import com.example.data.local.SkySettingsState
import com.example.hub.HubModelFile
import com.example.hub.HubRepoItem
import com.example.hub.ModelMetadataInspector
import com.example.hub.RamFitBadge
import com.example.ui.components.NeuSurface
import com.example.ui.theme.SkyTheme
import java.util.Locale

@Composable
fun ModelHubScreen(
    settings: SkySettingsState,
    hubRepos: List<HubRepoItem>,
    installedModels: List<InstalledModelEntity>,
    downloadTasks: List<DownloadTaskEntity>,
    gatedRepoBanner: Pair<String, String>?,
    hubStatusMessage: String?,
    onOpenDrawer: () -> Unit,
    onNavigateBackToChat: () -> Unit,
    onSearchHub: (String, Boolean) -> Unit,
    onDownloadStarterPack: () -> Unit,
    onStartDownload: (HubRepoItem, HubModelFile) -> Unit,
    onFetchRepoUrl: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onImportLocalUri: (Uri, String) -> Unit,
    onSetDefaultModel: (InstalledModelEntity) -> Unit,
    onDeleteInstalledModel: (InstalledModelEntity) -> Unit,
    onSaveHfToken: (String) -> Unit,
    onBuildOAuthUrl: (String) -> String?,
    onSignOutHf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spec = SkyTheme.spec

    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Reply GGUF, 1 = Task/Speech .cact, 2 = Installed
    var searchQuery by remember { mutableStateOf("") }
    var repoUrlInput by remember { mutableStateOf("") }
    var showAuthCard by remember { mutableStateOf(false) }
    var tokenPasteInput by remember { mutableStateOf("") }
    var oauthClientIdInput by remember(settings.hfOAuthClientId) {
        mutableStateOf(settings.hfOAuthClientId)
    }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val nameHint = uri.lastPathSegment ?: "local_model.gguf"
            onImportLocalUri(uri, nameHint)
        }
    }

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
                text = "Hugging Face Model Hub",
                style = MaterialTheme.typography.titleLarge,
                color = spec.primaryText
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeuSurface(
                    depth = if (showAuthCard) -1 else 1,
                    shape = CircleShape,
                    cornerRadiusDp = 24.dp,
                    onClick = { showAuthCard = !showAuthCard },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("hub_auth_toggle_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = "Hugging Face Account Sign-In",
                        tint = if (settings.hasHfToken) spec.successFill else spec.primaryText
                    )
                }

                NeuSurface(
                    depth = 1,
                    shape = CircleShape,
                    cornerRadiusDp = 24.dp,
                    onClick = { safLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("hub_import_saf_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = "Import local .gguf or .cact file via SAF",
                        tint = spec.accentText
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. One-Tap Starter Pack Card (Needle 3 + Whistle + SmolLM2 360M Q8_0)
            item(key = "starter_pack_card") {
                NeuSurface(
                    depth = 2,
                    shape = RoundedCornerShape(24.dp),
                    cornerRadiusDp = 24.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_starter_pack_card")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "One-Tap Starter Pack (Ungated)",
                            style = MaterialTheme.typography.titleMedium,
                            color = spec.primaryText
                        )
                        Text(
                            text = "Includes Needle 3 (`needle3.cact` 29 MB) + Whistle (`whistle.cact` 17 MB) + SmolLM2 360M Instruct Q8_0 (`386 MB`) for instant voice tasks & local chat.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = spec.mutedText
                        )
                        NeuSurface(
                            depth = 1,
                            accentFill = true,
                            shape = CircleShape,
                            cornerRadiusDp = 22.dp,
                            onClick = onDownloadStarterPack,
                            modifier = Modifier.testTag("hub_download_starter_pack_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    tint = spec.onAccentText,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Download Starter Pack",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = spec.onAccentText
                                )
                            }
                        }
                    }
                }
            }

            // 2. Hugging Face Dual Auth Card (OAuth 2.0 PKCE + Personal Access Token Paste)
            if (showAuthCard) {
                item(key = "hf_auth_card") {
                    NeuSurface(
                        depth = 1,
                        shape = RoundedCornerShape(22.dp),
                        cornerRadiusDp = 22.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_auth_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (settings.hasHfToken) {
                                    "Signed In to Hugging Face (@${settings.hfUsername.ifBlank { "user" }})"
                                } else {
                                    "Hugging Face Sign-In (OAuth PKCE or Access Token)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = spec.primaryText
                            )
                            Text(
                                text = "Tokens are encrypted with AES-GCM in the Android Keystore and never logged.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = spec.mutedText
                            )

                            // Option A: 1-Tap Personal Access Token Paste
                            NeuSurface(
                                depth = -1,
                                shape = RoundedCornerShape(16.dp),
                                cornerRadiusDp = 16.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BasicTextField(
                                    value = tokenPasteInput,
                                    onValueChange = { tokenPasteInput = it },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = spec.primaryText),
                                    cursorBrush = SolidColor(spec.accent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                        .testTag("hub_token_paste_input"),
                                    decorationBox = { inner ->
                                        if (tokenPasteInput.isEmpty()) {
                                            Text(
                                                text = "Paste hf_... Personal Access Token",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = spec.mutedText
                                            )
                                        }
                                        inner()
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                NeuSurface(
                                    depth = 1,
                                    shape = CircleShape,
                                    cornerRadiusDp = 20.dp,
                                    onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://huggingface.co/settings/tokens")
                                        )
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Open HF Tokens",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = spec.accentText,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    )
                                }

                                NeuSurface(
                                    depth = 1,
                                    accentFill = true,
                                    shape = CircleShape,
                                    cornerRadiusDp = 20.dp,
                                    onClick = {
                                        if (tokenPasteInput.isNotBlank()) {
                                            onSaveHfToken(tokenPasteInput)
                                            tokenPasteInput = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("hub_save_token_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Save Token",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = spec.onAccentText,
                                        modifier = Modifier.padding(vertical = 10.dp)
                                    )
                                }
                            }

                            // Option B: OAuth 2.0 + PKCE (Configurable Client ID)
                            Text(
                                text = "OAuth 2.0 + PKCE (Redirect: com.sky.assistant://oauth-callback)",
                                style = MaterialTheme.typography.labelMedium,
                                color = spec.mutedText
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NeuSurface(
                                    depth = -1,
                                    shape = RoundedCornerShape(14.dp),
                                    cornerRadiusDp = 14.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    BasicTextField(
                                        value = oauthClientIdInput,
                                        onValueChange = { oauthClientIdInput = it },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = spec.primaryText),
                                        cursorBrush = SolidColor(spec.accent),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        decorationBox = { inner ->
                                            if (oauthClientIdInput.isEmpty()) {
                                                Text(
                                                    text = "OAuth Client ID",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = spec.mutedText
                                                )
                                            }
                                            inner()
                                        }
                                    )
                                }
                                NeuSurface(
                                    depth = 1,
                                    shape = CircleShape,
                                    cornerRadiusDp = 20.dp,
                                    onClick = {
                                        val url = onBuildOAuthUrl(oauthClientIdInput)
                                        if (url != null) {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    }
                                ) {
                                    Text(
                                        text = "OAuth Sign-In",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = spec.accentText,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }

                            if (settings.hasHfToken) {
                                Text(
                                    text = "Sign out & remove Keystore token",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.errorText,
                                    modifier = Modifier.clickable { onSignOutHf() }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Gated Repo 401/403 License Banner
            if (gatedRepoBanner != null) {
                item(key = "gated_banner") {
                    val (repoId, licenseUrl) = gatedRepoBanner
                    NeuSurface(
                        depth = 1,
                        shape = RoundedCornerShape(18.dp),
                        cornerRadiusDp = 18.dp,
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(licenseUrl)))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_gated_license_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LockOpen,
                                contentDescription = null,
                                tint = spec.warningFill
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gated Repository: $repoId",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = spec.warningText
                                )
                                Text(
                                    text = "Tap to accept the model license on Hugging Face, then retry.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.primaryText
                                )
                            }
                        }
                    }
                }
            }

            if (!hubStatusMessage.isNullOrBlank()) {
                item(key = "hub_status_msg") {
                    Text(
                        text = hubStatusMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = spec.accentText
                    )
                }
            }

            // 4. Active Downloads with Animated Progress Rings
            if (downloadTasks.isNotEmpty()) {
                item(key = "active_downloads_header") {
                    Text(
                        text = "Downloads",
                        style = MaterialTheme.typography.labelLarge,
                        color = spec.mutedText
                    )
                }
                items(downloadTasks, key = { it.taskId }) { task ->
                    DownloadTaskRow(
                        task = task,
                        onPause = { onPauseDownload(task.taskId) },
                        onResume = {
                            val slot = ModelMetadataInspector.inferRoleSlot(task.fileName, task.repoId)
                            onStartDownload(
                                HubRepoItem(
                                    repoId = task.repoId,
                                    displayName = task.displayName,
                                    author = task.repoId.substringBefore("/"),
                                    downloads = 0,
                                    likes = 0,
                                    isGated = false,
                                    roleSlot = slot,
                                    summary = ""
                                ),
                                HubModelFile(
                                    fileName = task.fileName,
                                    sizeBytes = task.totalBytes,
                                    sha256 = task.expectedSha256,
                                    quantization = ModelMetadataInspector.inferQuantizationFromName(task.fileName),
                                    downloadUrl = task.downloadUrl
                                )
                            )
                        },
                        onCancel = { onCancelDownload(task.taskId) }
                    )
                }
            }

            // 5. Tabs: Reply (GGUF) | Task/Speech (.cact) | Installed Library
            item(key = "hub_tabs") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Reply (GGUF)",
                        "Task / Speech (.cact)",
                        "Library (${installedModels.size})"
                    ).forEachIndexed { idx, title ->
                        val selected = selectedTab == idx
                        NeuSurface(
                            depth = if (selected) -1 else 1,
                            pressed = selected,
                            shape = CircleShape,
                            cornerRadiusDp = 20.dp,
                            onClick = {
                                selectedTab = idx
                                if (idx < 2) {
                                    onSearchHub(searchQuery, idx == 1)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hub_tab_$idx"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) spec.accentText else spec.primaryText,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }

            if (selectedTab < 2) {
                // Search Bar + Direct Repo URL Paste
                item(key = "hub_search_bar") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeuSurface(
                            depth = -1,
                            shape = CircleShape,
                            cornerRadiusDp = 22.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "Search Hugging Face Hub",
                                    tint = spec.mutedText,
                                    modifier = Modifier.size(18.dp)
                                )
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = {
                                        searchQuery = it
                                        onSearchHub(it, selectedTab == 1)
                                    },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = spec.primaryText),
                                    cursorBrush = SolidColor(spec.accent),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("hub_search_input"),
                                    decorationBox = { inner ->
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = if (selectedTab == 0) {
                                                    "Search GGUF models (SmolLM2, Qwen, Llama, Gemma)…"
                                                } else {
                                                    "Search Cactus .cact models (Needle 3, Whistle)…"
                                                },
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = spec.mutedText
                                            )
                                        }
                                        inner()
                                    }
                                )
                            }
                        }

                        // Paste direct repo URL
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NeuSurface(
                                depth = -1,
                                shape = RoundedCornerShape(16.dp),
                                cornerRadiusDp = 16.dp,
                                modifier = Modifier.weight(1f)
                            ) {
                                BasicTextField(
                                    value = repoUrlInput,
                                    onValueChange = { repoUrlInput = it },
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = spec.primaryText),
                                    cursorBrush = SolidColor(spec.accent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    decorationBox = { inner ->
                                        if (repoUrlInput.isEmpty()) {
                                            Text(
                                                text = "Or paste Hugging Face repo URL / ID…",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = spec.mutedText
                                            )
                                        }
                                        inner()
                                    }
                                )
                            }
                            NeuSurface(
                                depth = 1,
                                shape = CircleShape,
                                cornerRadiusDp = 20.dp,
                                onClick = {
                                    if (repoUrlInput.isNotBlank()) {
                                        onFetchRepoUrl(repoUrlInput)
                                        repoUrlInput = ""
                                    }
                                }
                            ) {
                                Text(
                                    text = "Fetch",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = spec.accentText,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                // Filtered Hub Repo Cards with RAM-Fit Compatibility Badges
                val visibleRepos = hubRepos.filter { repo ->
                    if (selectedTab == 1) {
                        repo.roleSlot != com.example.data.local.ModelRoleSlot.REPLY
                    } else {
                        repo.roleSlot == com.example.data.local.ModelRoleSlot.REPLY
                    }
                }

                items(visibleRepos, key = { it.repoId }) { repo ->
                    val file = repo.recommendedFile ?: HubModelFile(
                        fileName = "${repo.displayName.lowercase().replace(" ", "-")}-q4_k_m.gguf",
                        sizeBytes = 850_000_000L,
                        sha256 = null,
                        quantization = "Q4_K_M",
                        downloadUrl = "https://huggingface.co/${repo.repoId}/resolve/main/model.gguf"
                    )
                    val ramFit = remember(file.sizeBytes) {
                        ModelMetadataInspector.evaluateRamFit(context, file.sizeBytes)
                    }

                    NeuSurface(
                        depth = 1,
                        useCachedShadowForList = true,
                        shape = RoundedCornerShape(22.dp),
                        cornerRadiusDp = 22.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = repo.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    Text(
                                        text = repo.repoId,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.mutedText
                                    )
                                }

                                // RAM-fit badge (Fits / Tight / Too Big)
                                val badgeColor = when (ramFit) {
                                    RamFitBadge.FITS -> spec.successText
                                    RamFitBadge.TIGHT -> spec.warningText
                                    RamFitBadge.TOO_BIG -> spec.errorText
                                }
                                NeuSurface(
                                    depth = -1,
                                    useCachedShadowForList = true,
                                    shape = CircleShape,
                                    cornerRadiusDp = 14.dp
                                ) {
                                    Text(
                                        text = "RAM: ${ramFit.label}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            if (repo.summary.isNotBlank()) {
                                Text(
                                    text = repo.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = spec.mutedText
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${file.quantization} · ${formatSizeMb(file.sizeBytes)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = spec.accentText
                                )
                                NeuSurface(
                                    depth = 1,
                                    useCachedShadowForList = true,
                                    accentFill = true,
                                    shape = CircleShape,
                                    cornerRadiusDp = 20.dp,
                                    onClick = { onStartDownload(repo, file) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CloudDownload,
                                            contentDescription = "Download ${repo.displayName}",
                                            tint = spec.onAccentText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Download",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = spec.onAccentText
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Installed Model Library Tab
                items(installedModels, key = { it.modelId }) { model ->
                    NeuSurface(
                        depth = 1,
                        useCachedShadowForList = true,
                        shape = RoundedCornerShape(22.dp),
                        cornerRadiusDp = 22.dp,
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = spec.primaryText
                                    )
                                    Text(
                                        text = "Role: ${model.roleSlot.uppercase()} · Arch: ${model.architecture} · Quant: ${model.quantization}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = spec.accentText
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete model",
                                    tint = spec.mutedText,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { onDeleteInstalledModel(model) }
                                )
                            }

                            Text(
                                text = "Ctx: ${model.contextLength} · Template: ${model.chatTemplate} · Size: ${formatSizeMb(model.sizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = spec.mutedText
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "SHA-256: ${model.sha256.take(16)}…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = spec.mutedText
                                )
                                NeuSurface(
                                    depth = if (model.isActiveDefault) -1 else 1,
                                    useCachedShadowForList = true,
                                    shape = CircleShape,
                                    cornerRadiusDp = 18.dp,
                                    onClick = { onSetDefaultModel(model) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (model.isActiveDefault) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = spec.successFill,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Text(
                                            text = if (model.isActiveDefault) "Active Default" else "Set Active",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (model.isActiveDefault) spec.successText else spec.primaryText
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

@Composable
private fun DownloadTaskRow(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val spec = SkyTheme.spec
    val rawProgress = if (task.totalBytes > 0L) {
        (task.downloadedBytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(rawProgress, label = "dlRingProgress")

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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Animated Neumorphic Progress Ring
            Box(
                modifier = Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    drawCircle(
                        color = spec.lo,
                        style = Stroke(width = stroke)
                    )
                    drawArc(
                        color = spec.accent,
                        startAngle = -90f,
                        sweepAngle = animatedProgress * 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = spec.primaryText
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = spec.primaryText
                )
                Text(
                    text = "${task.status} · ${formatSizeMb(task.downloadedBytes)} / ${formatSizeMb(task.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = spec.mutedText
                )
                if (!task.errorMessage.isNullOrBlank()) {
                    Text(
                        text = task.errorMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = spec.errorText
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.status == "DOWNLOADING") {
                    Icon(
                        imageVector = Icons.Rounded.Pause,
                        contentDescription = "Pause download",
                        tint = spec.primaryText,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onPause() }
                    )
                } else if (task.status == "PAUSED" || task.status == "FAILED") {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Resume download",
                        tint = spec.accentText,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onResume() }
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel download",
                    tint = spec.mutedText,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onCancel() }
                )
            }
        }
    }
}

private fun formatSizeMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
