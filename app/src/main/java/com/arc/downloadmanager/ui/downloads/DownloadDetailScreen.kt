@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.downloads

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.ui.components.statusColor
import com.arc.downloadmanager.ui.components.statusLabelRes
import com.arc.downloadmanager.util.Format

@Composable
fun DownloadDetailScreen(
    downloadId: String,
    onBack: () -> Unit,
    viewModel: DownloadDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.detail.collectAsStateWithLifecycle()
    val entity = state.entity
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entity?.filename ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(onDone = onBack) }) {
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
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProgressCard(entity, state.segments)
            ActionRow(entity, viewModel, context)
            InfoSection(entity)
        }
    }
}

@Composable
private fun ProgressCard(
    entity: DownloadEntity,
    segments: List<com.arc.downloadmanager.data.db.SegmentEntity>,
) {
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
                }  •  ${stringResource(statusLabelRes(entity.status))}",
                style = MaterialTheme.typography.bodyMedium,
                color =
                    statusColor(entity.status).takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun InfoSection(entity: DownloadEntity) {
    val ctx = LocalContext.current
    val elapsedMs = ((entity.completedAt ?: System.currentTimeMillis()) - entity.createdAt).coerceAtLeast(1)
    val avgSpeed = entity.downloadedBytes * 1000 / elapsedMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(stringResource(R.string.details_source), com.arc.downloadmanager.engine.CookieScopePolicy.hostOf(entity.finalUrl ?: entity.url))
            InfoRow(stringResource(R.string.details_speed_avg), Format.speed(ctx, avgSpeed))
            InfoRow(
                stringResource(R.string.details_resume_supported),
                if (entity.resumable) stringResource(R.string.details_resume_supported) else stringResource(R.string.details_resume_unsupported),
            )
            InfoRow(stringResource(R.string.details_retries), entity.retryCount.toString())
            entity.expectedSha256?.let { InfoRow(stringResource(R.string.details_checksum), entity.checksumSha256 ?: "—") }
            entity.errorCode?.let { code ->
                InfoRow(stringResource(R.string.details_error), "$code: ${entity.errorMessage ?: ""}")
            }
            InfoRow("Added", Format.date(entity.createdAt))
            entity.completedAt?.let { InfoRow("Completed", Format.date(it)) }
            InfoRow(
                stringResource(R.string.settings_default_destination),
                entity.finalUri?.let { com.arc.downloadmanager.engine.CookieScopePolicy.redact(it) } ?: "—",
            )
        }
    }
    Spacer(Modifier.height(4.dp))
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
