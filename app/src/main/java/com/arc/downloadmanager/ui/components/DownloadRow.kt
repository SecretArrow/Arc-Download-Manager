package com.arc.downloadmanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.repository.FileCategory
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.util.Format

/** Maps a status to a color-consistent icon + text pair (never color alone). */
fun statusLabelRes(status: DownloadStatus): Int = when (status) {
    DownloadStatus.QUEUED -> com.arc.downloadmanager.R.string.status_queued
    DownloadStatus.CONNECTING -> com.arc.downloadmanager.R.string.status_connecting
    DownloadStatus.DOWNLOADING -> com.arc.downloadmanager.R.string.status_downloading
    DownloadStatus.PAUSING -> com.arc.downloadmanager.R.string.status_pausing
    DownloadStatus.PAUSED -> com.arc.downloadmanager.R.string.status_paused
    DownloadStatus.MERGING -> com.arc.downloadmanager.R.string.status_merging
    DownloadStatus.VERIFYING -> com.arc.downloadmanager.R.string.status_verifying
    DownloadStatus.COMPLETED -> com.arc.downloadmanager.R.string.status_completed
    DownloadStatus.FAILED -> com.arc.downloadmanager.R.string.status_failed
    DownloadStatus.CANCELED -> com.arc.downloadmanager.R.string.status_canceled
    DownloadStatus.WAITING_NETWORK -> com.arc.downloadmanager.R.string.status_waiting_network
    DownloadStatus.WAITING_STORAGE -> com.arc.downloadmanager.R.string.status_waiting_storage
    DownloadStatus.AUTH_REQUIRED -> com.arc.downloadmanager.R.string.status_auth_required
}

fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.COMPLETED -> Color(0xFF2E7D32)
    DownloadStatus.FAILED, DownloadStatus.AUTH_REQUIRED -> Color(0xFFC62828)
    DownloadStatus.PAUSED, DownloadStatus.CANCELED, DownloadStatus.WAITING_NETWORK, DownloadStatus.WAITING_STORAGE -> Color(0xFF9E6400)
    else -> Color.Unspecified
}

fun fileIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.IMAGE -> Icons.Filled.Image
    FileCategory.VIDEO -> Icons.Filled.Movie
    FileCategory.AUDIO -> Icons.Filled.AudioFile
    FileCategory.ARCHIVE -> Icons.Filled.Archive
    FileCategory.APP -> Icons.Filled.Android
    else -> Icons.Filled.Description
}

/** One row in the download list (compact or detailed). */
@Composable
fun DownloadRow(
    entity: DownloadEntity,
    compact: Boolean,
    onClick: () -> Unit,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    speedBps: Long = 0,
) {
    val status = entity.status
    val percent = Format.percent(entity.downloadedBytes, entity.totalBytes)
    val active = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.CONNECTING
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = entity.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = buildString {
                        append(Format.bytes(entity.downloadedBytes))
                        if (entity.totalBytes > 0) {
                            append(" / ")
                            append(Format.bytes(entity.totalBytes))
                            if (percent >= 0) append("  •  $percent%")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!compact && active) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Text(
                        text = listOfNotNull(
                            com.arc.downloadmanager.util.Format.speed(ctx, speedBps),
                            com.arc.downloadmanager.util.Format.eta(ctx, entity.totalBytes - entity.downloadedBytes, speedBps),
                        ).joinToString("  •  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (status == DownloadStatus.FAILED || status == DownloadStatus.AUTH_REQUIRED) {
                    Text(
                        text = entity.errorMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = androidx.compose.ui.res.stringResource(statusLabelRes(status)),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(status).takeIf { it != Color.Unspecified }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    when {
                        active || status == DownloadStatus.PAUSING -> IconButton(onClick = onPauseResume) {
                            Icon(Icons.Filled.PauseCircle, contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_pause))
                        }
                        status == DownloadStatus.PAUSED || status == DownloadStatus.WAITING_NETWORK ||
                            status == DownloadStatus.WAITING_STORAGE -> IconButton(onClick = onPauseResume) {
                            Icon(Icons.Filled.PlayCircle, contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_resume))
                        }
                        status.isTerminal || status == DownloadStatus.AUTH_REQUIRED -> IconButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_retry))
                        }
                    }
                    if (!status.isTerminal || status == DownloadStatus.CANCELED) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_cancel))
                        }
                    }
                }
            }
        }
        if (percent >= 0 || active) {
            if (percent >= 0) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    color = if (status == DownloadStatus.AUTH_REQUIRED || status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}
