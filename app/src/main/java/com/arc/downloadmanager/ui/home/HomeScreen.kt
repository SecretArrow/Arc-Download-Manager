@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.engine.CookieScopePolicy
import com.arc.downloadmanager.ui.components.DownloadRow
import com.arc.downloadmanager.ui.downloads.AddDownloadSheet
import com.arc.downloadmanager.ui.theme.ArcBrandGradient
import com.arc.downloadmanager.util.Format

@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenFiles: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingDuplicate by viewModel.pendingDuplicate.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableIntStateOf(1) } // 0 compact, 1 detailed
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        // WindowInsets(0): insets are applied exactly once — status bar by the TopAppBar below,
        // navigation bar by the NavigationBar that hosts this screen — and imePadding is not
        // needed here because text input happens inside ModalBottomSheet, which handles it.
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::pauseAll) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.notif_pause_all))
                    }
                    IconButton(onClick = viewModel::resumeAll) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.notif_resume_all))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_download)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
        ) {
            item {
                HeroCard(
                    ui = ui,
                    storageFree = ui.storageFree,
                    onOpenFiles = onOpenFiles,
                )
            }
            item {
                QuickActions(
                    onAdd = { showAdd = true },
                    onPaste = {
                        clipboard.getText()?.toString()?.let { pasted ->
                            if (CookieScopePolicy.isSupportedUrl(pasted.trim())) {
                                viewModel.add(DownloadRequest(url = pasted.trim()))
                                showAdd = false
                            } else {
                                showAdd = true
                            }
                        }
                    },
                    onBatch = { showAdd = true },
                    onBrowser = onOpenBrowser,
                )
            }
            if (ui.attention.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.home_needs_attention), isError = true)
                }
                items(ui.attention, key = { "attn-${it.id}" }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = true,
                        onClick = { onOpenDetail(entity.id) },
                        onPauseResume = { viewModel.resume(entity.id) },
                        onCancel = { viewModel.cancel(entity.id) },
                        onRetry = { viewModel.retry(entity.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.home_active_downloads),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ViewModeSwitch(viewMode) { viewMode = it }
                }
            }
            if (ui.downloads.isEmpty()) {
                item {
                    EmptyDownloads(
                        onAdd = { showAdd = true },
                        onBrowser = onOpenBrowser,
                    )
                }
            } else {
                items(ui.downloads, key = { it.id }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = viewMode == 0,
                        onClick = { onOpenDetail(entity.id) },
                        onPauseResume = {
                            if (entity.status.isActive) viewModel.pause(entity.id) else viewModel.resume(entity.id)
                        },
                        onCancel = { viewModel.cancel(entity.id) },
                        onRetry = { viewModel.retry(entity.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (ui.recentCompleted.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_recent_files)) }
                items(ui.recentCompleted, key = { "recent-${it.id}" }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = true,
                        onClick = { onOpenDetail(entity.id) },
                        onPauseResume = {},
                        onCancel = {},
                        onRetry = {},
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddDownloadSheet(
            onDismiss = { showAdd = false },
            onAdd = viewModel::add,
            onAddBatch = { requests, callback ->
                viewModel.addBatch(requests) { result ->
                    callback(result.started, result.invalid)
                }
            },
        )
    }

    pendingDuplicate?.let { request ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text(stringResource(R.string.duplicate_title)) },
            text = { Text(stringResource(R.string.duplicate_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resolveDuplicate(request, OverwritePolicy.REPLACE)
                }) { Text(stringResource(R.string.duplicate_replace)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::dismissDuplicate) {
                        Text(stringResource(R.string.duplicate_skip))
                    }
                    TextButton(onClick = {
                        viewModel.resolveDuplicate(request, OverwritePolicy.RENAME)
                    }) { Text(stringResource(R.string.duplicate_new_copy)) }
                }
            },
        )
    }
}

/** Brand-gradient hero: live queue summary, aggregate progress ring and storage. */
@Composable
private fun HeroCard(
    ui: HomeUiState,
    storageFree: Long,
    onOpenFiles: () -> Unit,
) {
    val activeBytes = ui.activeBytes.toFloat()
    val progressTarget = if (ui.activeTotalBytes > 0) (activeBytes / ui.activeTotalBytes).coerceIn(0f, 1f) else 0f
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progressTarget,
        animationSpec =
            androidx.compose.animation.core.SpringSpec(
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
            ),
        label = "heroProgress",
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(ArcBrandGradient))
                .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (ui.activeCount > 0) R.string.hero_downloading else R.string.hero_idle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text =
                            listOfNotNull(
                                stringResource(R.string.hero_active_count, ui.activeCount),
                                stringResource(R.string.hero_queued_count, ui.queuedCount),
                                stringResource(R.string.hero_failed_count, ui.failedCount).takeIf { ui.failedCount > 0 },
                            ).joinToString("  \u00B7  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(56.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.22f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Text(
                        text = if (progressTarget > 0f) "${(progressTarget * 100).toInt()}%" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (ui.activeCount > 0) {
                Text(
                    text = Format.speed(ctx, ui.totalSpeed),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${Format.bytes(ui.activeBytes)} / ${Format.bytes(ui.activeTotalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.home_completed_count, ui.completedCount),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            if (storageFree > 0) {
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenFiles,
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Storage,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_free_space, Format.bytes(storageFree)),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onBatch: () -> Unit,
    onBrowser: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = onPaste,
            label = { Text(stringResource(R.string.action_paste_url)) },
            leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(17.dp)) },
        )
        AssistChip(
            onClick = onBatch,
            label = { Text(stringResource(R.string.action_batch_download)) },
            leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null, modifier = Modifier.size(17.dp)) },
        )
        AssistChip(
            onClick = onBrowser,
            label = { Text(stringResource(R.string.action_open_browser)) },
            leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(17.dp)) },
        )
    }
}

@Composable
private fun EmptyDownloads(
    onAdd: () -> Unit,
    onBrowser: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.empty_state_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.empty_state_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_add_download))
            }
            OutlinedButton(onClick = onBrowser) {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_open_browser))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    isError: Boolean = false,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color =
            if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

@Composable
private fun ViewModeSwitch(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = selected == 0,
            onClick = { onSelect(0) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.view_compact)) }
        SegmentedButton(
            selected = selected == 1,
            onClick = { onSelect(1) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.view_detailed)) }
    }
}
