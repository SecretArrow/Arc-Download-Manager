@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.engine.CookieScopePolicy
import com.arc.downloadmanager.ui.components.DownloadRow
import com.arc.downloadmanager.ui.downloads.AddDownloadSheet
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
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
                AggregateCard(
                    ui = ui,
                    storageUsed = 0L,
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
                    SectionHeader(stringResource(R.string.home_needs_attention))
                }
                items(ui.attention, key = { "attn-${it.id}" }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = settings.compactRows,
                        onClick = { onOpenDetail(entity.id) },
                        onPauseResume = { viewModel.resume(entity.id) },
                        onCancel = { viewModel.cancel(entity.id) },
                        onRetry = { viewModel.retry(entity.id) },
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
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.home_no_downloads),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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

@Composable
private fun AggregateCard(
    ui: HomeUiState,
    storageUsed: Long,
    onOpenFiles: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text =
                    stringResource(
                        R.string.home_queue_count,
                        ui.activeCount,
                        ui.queuedCount,
                        ui.failedCount,
                    ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            if (ui.activeTotalBytes > 0) {
                val progress = (ui.activeBytes.toFloat() / ui.activeTotalBytes).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            } else if (ui.activeCount > 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        if (ui.activeCount > 0) {
                            "${Format.bytes(ui.activeBytes)} / ${Format.bytes(ui.activeTotalBytes)}"
                        } else {
                            "${ui.completedCount} completed"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (ui.totalSpeed > 0) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Text(
                        text = Format.speed(ctx, ui.totalSpeed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
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
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = onPaste, label = { Text(stringResource(R.string.action_paste_url)) })
        AssistChip(onClick = onBatch, label = { Text(stringResource(R.string.action_batch_download)) })
        AssistChip(onClick = onBrowser, label = { Text(stringResource(R.string.action_open_browser)) })
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
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
        ) { Text("Compact") }
        SegmentedButton(
            selected = selected == 1,
            onClick = { onSelect(1) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Detailed") }
    }
}
