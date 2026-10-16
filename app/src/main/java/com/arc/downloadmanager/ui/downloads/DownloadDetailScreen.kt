@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.downloads

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.torrent.TorrentFileInfo
import com.arc.downloadmanager.ui.components.statusColor
import com.arc.downloadmanager.ui.components.statusLabelRes
import com.arc.downloadmanager.util.Format

@Composable
fun DownloadDetailScreen(
    downloadId: String,
    onBack: () -> Unit,
    onOpenVault: () -> Unit = {},
    viewModel: DownloadDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.detail.collectAsStateWithLifecycle()
    val torrentFiles by viewModel.torrentFiles.collectAsStateWithLifecycle()
    val entity = state.entity
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editSavedToast by remember { mutableStateOf(false) }

    Scaffold(
        // WindowInsets(0): status bar inset is applied by the TopAppBar; the navigation-bar
        // inset is applied explicitly below because this screen has no bottom NavigationBar.
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(entity?.filename ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        if (entity == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {}
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProgressCard(entity, state.segments, state.speedBps)
            ActionRow(entity, viewModel, context, onEdit = { showEditDialog = true }, onOpenVault = onOpenVault)
            InfoSection(entity, state.torrent)
            if (entity.url.startsWith("magnet:")) {
                TorrentFilesCard(files = torrentFiles, onToggle = viewModel::setTorrentFileIncluded)
            }
        }
    }

    if (showDeleteDialog && entity != null) {
        var alsoDeleteFile by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_task_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_task_message, entity.filename))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = alsoDeleteFile,
                            onCheckedChange = { alsoDeleteFile = it },
                        )
                        Text(stringResource(R.string.delete_also_file))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(deleteFile = alsoDeleteFile, onDone = onBack)
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showEditDialog && entity != null) {
        EditDownloadSheet(
            entity = entity,
            onDismiss = { showEditDialog = false },
            onSave = { url, filename ->
                showEditDialog = false
                viewModel.saveEdit(url, filename) { saved -> editSavedToast = !saved }
            },
        )
    }

    if (editSavedToast) {
        AlertDialog(
            onDismissRequest = { editSavedToast = false },
            title = { Text(stringResource(R.string.edit_task_title)) },
            text = { Text(stringResource(R.string.edit_rejected)) },
            confirmButton = {
                TextButton(onClick = { editSavedToast = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun ProgressCard(
    entity: DownloadEntity,
    segments: List<com.arc.downloadmanager.data.db.SegmentEntity>,
    speedBps: Long,
) {
    val ctx = LocalContext.current
    val percent = Format.percent(entity.downloadedBytes, entity.totalBytes)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (percent >= 0) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (entity.status.isActive) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(
                text = "${Format.bytes(entity.downloadedBytes)} / ${
                    if (entity.totalBytes > 0) Format.bytes(entity.totalBytes) else "?"
                }  •  ${stringResource(statusLabelRes(entity.status, entity.errorCode))}",
                style = MaterialTheme.typography.bodyMedium,
                color =
                    statusColor(entity.status).takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entity.status.isActive && speedBps > 0) {
                val eta = Format.eta(ctx, (entity.totalBytes - entity.downloadedBytes).coerceAtLeast(0), speedBps)
                Text(
                    text = listOfNotNull(Format.speed(ctx, speedBps), eta).joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            if (segments.isNotEmpty()) {
                Text(
                    text =
                        stringResource(
                            R.string.segments_active,
                            segments.count { it.status == DownloadStatus.DOWNLOADING },
                            segments.size,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                )
                segments.forEach { segment ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "#${segment.index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        val segPercent = if (segment.length > 0) segment.downloadedBytes.toFloat() / segment.length else 0f
                        LinearProgressIndicator(
                            progress = { segPercent.coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = Format.bytes(segment.downloadedBytes),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    entity: DownloadEntity,
    viewModel: DownloadDetailViewModel,
    context: android.content.Context,
    onEdit: () -> Unit,
    onOpenVault: () -> Unit,
) {
    val active = entity.status.isActive
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active) {
            Button(onClick = viewModel::pause) {
                Icon(Icons.Filled.Pause, contentDescription = null)
                Text(stringResource(R.string.action_pause), Modifier.padding(start = 4.dp))
            }
        } else {
            Button(onClick = viewModel::resume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(stringResource(R.string.action_resume), Modifier.padding(start = 4.dp))
            }
        }
        OutlinedButton(onClick = viewModel::retry) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text(stringResource(R.string.action_retry), Modifier.padding(start = 4.dp))
        }
        OutlinedButton(onClick = viewModel::restart) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entity.status != com.arc.downloadmanager.domain.model.DownloadStatus.COMPLETED) {
            OutlinedButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Text(stringResource(R.string.action_edit), Modifier.padding(start = 4.dp))
            }
        }
        OutlinedButton(
            onClick = { if (entity.archivedAt == null) viewModel.archive() else viewModel.restore() },
        ) {
            Icon(
                if (entity.archivedAt == null) Icons.Filled.Archive else Icons.Filled.Unarchive,
                contentDescription = null,
            )
            Text(
                stringResource(if (entity.archivedAt == null) R.string.action_archive else R.string.action_unarchive),
                Modifier.padding(start = 4.dp),
            )
        }
    }
    if (entity.status == DownloadStatus.COMPLETED) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    runCatching {
                        val uri = android.net.Uri.parse(entity.finalUri)
                        val intent =
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, entity.mimeType ?: "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(intent)
                    }
                },
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.action_open), Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = entity.mimeType ?: "*/*"
                                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(entity.finalUri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                },
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(R.string.action_share), Modifier.padding(start = 4.dp))
            }
        }
        OutlinedButton(
            onClick = {
                viewModel.saveToVault(
                    entity,
                    onNavigate = onOpenVault,
                ) { res, arg ->
                    val msg =
                        if (arg != null) context.getString(res, arg) else context.getString(res)
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null)
            Text(stringResource(R.string.action_save_to_vault), Modifier.padding(start = 4.dp))
        }
    }
    if (entity.status == DownloadStatus.AUTH_REQUIRED) {
        Text(
            text = stringResource(R.string.error_auth),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InfoSection(
    entity: DownloadEntity,
    torrent: com.arc.downloadmanager.engine.torrent.TorrentSnapshot? = null,
) {
    val ctx = LocalContext.current
    val elapsedMs = ((entity.completedAt ?: System.currentTimeMillis()) - entity.createdAt).coerceAtLeast(1)
    val avgSpeed = entity.downloadedBytes * 1000 / elapsedMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(stringResource(R.string.details_source), com.arc.downloadmanager.engine.CookieScopePolicy.hostOf(entity.finalUrl ?: entity.url))
            InfoRow(stringResource(R.string.details_speed_avg), Format.speed(ctx, avgSpeed))
            torrent?.let {
                InfoRow(
                    stringResource(R.string.details_torrent_state),
                    when (it.state) {
                        com.arc.downloadmanager.engine.torrent.TorrentRunState.FETCHING_METADATA ->
                            stringResource(R.string.torrent_state_metadata)
                        com.arc.downloadmanager.engine.torrent.TorrentRunState.CHECKING ->
                            stringResource(R.string.torrent_state_checking)
                        com.arc.downloadmanager.engine.torrent.TorrentRunState.DOWNLOADING ->
                            stringResource(R.string.torrent_state_downloading)
                        com.arc.downloadmanager.engine.torrent.TorrentRunState.FINISHED ->
                            stringResource(R.string.torrent_state_finished)
                        com.arc.downloadmanager.engine.torrent.TorrentRunState.ERROR ->
                            it.errorMessage ?: stringResource(R.string.error_torrent_failed)
                        com.arc.downloadmanager.engine.torrent.TorrentRunState.UNKNOWN ->
                            stringResource(R.string.torrent_state_unknown)
                    },
                )
                InfoRow(stringResource(R.string.details_torrent_peers), stringResource(R.string.torrent_peers_value, it.numPeers, it.numSeeds))
            }
            InfoRow(
                stringResource(R.string.details_resume_supported),
                if (entity.resumable) stringResource(R.string.details_resume_supported) else stringResource(R.string.details_resume_unsupported),
            )
            InfoRow(stringResource(R.string.details_retries), entity.retryCount.toString())
            entity.expectedSha256?.let { InfoRow(stringResource(R.string.details_checksum), entity.checksumSha256 ?: "—") }
            entity.errorCode?.let { code ->
                InfoRow(stringResource(R.string.details_error), friendlyError(code, entity.errorMessage))
            }
            InfoRow(stringResource(R.string.details_added), Format.date(entity.createdAt))
            entity.completedAt?.let { InfoRow(stringResource(R.string.details_completed_at), Format.date(it)) }
            InfoRow(
                stringResource(R.string.settings_default_destination),
                entity.finalUri?.let { com.arc.downloadmanager.engine.CookieScopePolicy.redact(it) } ?: "—",
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** Per-file download selection for magnet tasks (switches Priority.DEFAULT vs Priority.IGNORE). */
@Composable
private fun TorrentFilesCard(
    files: List<TorrentFileInfo>,
    onToggle: (index: Int, include: Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.isEmpty()) {
                Text(
                    text = stringResource(R.string.torrent_waiting_metadata),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val selected = files.count { it.priority != 0 }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.torrent_files),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.torrent_selected_summary, selected, files.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = file.path.substringAfterLast('/').substringAfterLast('\\'),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = Format.bytes(file.sizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = file.priority != 0,
                        onCheckedChange = { include -> onToggle(file.fileIndex, include) },
                    )
                }
            }
            if (selected == 0) {
                Text(
                    text = stringResource(R.string.torrent_at_least_one),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.65f),
        )
    }
}

/** Maps a stable [com.arc.downloadmanager.engine.DownloadError] code to a localized, actionable message. */
@Composable
private fun friendlyError(
    code: String,
    detail: String?,
): String =
    when (code) {
        com.arc.downloadmanager.engine.DownloadError.INVALID_URL.name -> stringResource(R.string.error_invalid_url)
        com.arc.downloadmanager.engine.DownloadError.NETWORK_UNAVAILABLE.name -> stringResource(R.string.error_network)
        com.arc.downloadmanager.engine.DownloadError.AUTH_REQUIRED.name -> stringResource(R.string.error_auth)
        com.arc.downloadmanager.engine.DownloadError.FORBIDDEN.name -> stringResource(R.string.error_forbidden)
        com.arc.downloadmanager.engine.DownloadError.EXPIRED_LINK.name -> stringResource(R.string.error_expired)
        com.arc.downloadmanager.engine.DownloadError.RANGE_NOT_SUPPORTED.name -> stringResource(R.string.error_no_range)
        com.arc.downloadmanager.engine.DownloadError.RESOURCE_CHANGED.name -> stringResource(R.string.error_resource_changed)
        com.arc.downloadmanager.engine.DownloadError.RATE_LIMITED.name -> stringResource(R.string.error_rate_limited)
        com.arc.downloadmanager.engine.DownloadError.INSUFFICIENT_STORAGE.name -> stringResource(R.string.error_storage)
        com.arc.downloadmanager.engine.DownloadError.PERMISSION_DENIED.name -> stringResource(R.string.error_permission)
        com.arc.downloadmanager.engine.DownloadError.FILE_EXISTS.name -> stringResource(R.string.error_exists)
        com.arc.downloadmanager.engine.DownloadError.SEGMENT_MISMATCH.name -> stringResource(R.string.error_segment_mismatch)
        com.arc.downloadmanager.engine.DownloadError.MERGE_FAILED.name -> stringResource(R.string.error_merge)
        com.arc.downloadmanager.engine.DownloadError.CHECKSUM_MISMATCH.name -> stringResource(R.string.error_checksum)
        com.arc.downloadmanager.engine.DownloadError.PAGE_NOT_FILE.name -> stringResource(R.string.error_page_not_file)
        com.arc.downloadmanager.engine.DownloadError.PROTOCOL_UNSUPPORTED.name -> stringResource(R.string.error_protocol_unsupported)
        com.arc.downloadmanager.engine.DownloadError.TORRENT_METADATA_TIMEOUT.name -> stringResource(R.string.error_torrent_metadata_timeout)
        com.arc.downloadmanager.engine.DownloadError.TORRENT_FAILED.name -> stringResource(R.string.error_torrent_failed)
        com.arc.downloadmanager.engine.DownloadError.CANCELED.name -> stringResource(R.string.error_canceled)
        else -> stringResource(R.string.error_unknown, detail ?: code)
    }
