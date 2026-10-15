package com.arc.downloadmanager.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.repository.FileCategory
import com.arc.downloadmanager.data.repository.FileRepository
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.ui.theme.categoryAccent
import com.arc.downloadmanager.ui.theme.categoryGradient
import com.arc.downloadmanager.util.Format

/** Maps a status (plus optional error code) to a color-consistent icon + text pair (never color alone). */
fun statusLabelRes(
    status: DownloadStatus,
    errorCode: String? = null,
): Int =
    when (status) {
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
        DownloadStatus.WAITING_NETWORK ->
            if (errorCode == com.arc.downloadmanager.engine.DownloadError.WIFI_REQUIRED.name) {
                com.arc.downloadmanager.R.string.status_waiting_wifi
            } else {
                com.arc.downloadmanager.R.string.status_waiting_network
            }
        DownloadStatus.WAITING_STORAGE -> com.arc.downloadmanager.R.string.status_waiting_storage
        DownloadStatus.WAITING_SCHEDULED -> com.arc.downloadmanager.R.string.status_waiting_schedule
        DownloadStatus.AUTH_REQUIRED -> com.arc.downloadmanager.R.string.status_auth_required
    }

fun statusColor(status: DownloadStatus): Color =
    when (status) {
        DownloadStatus.COMPLETED -> Color(0xFF2E7D32)
        DownloadStatus.FAILED, DownloadStatus.AUTH_REQUIRED -> Color(0xFFC62828)
        DownloadStatus.PAUSED, DownloadStatus.CANCELED, DownloadStatus.WAITING_NETWORK, DownloadStatus.WAITING_STORAGE, DownloadStatus.WAITING_SCHEDULED ->
            Color(
                0xFF9E6400,
            )
        else -> Color.Unspecified
    }

/** Pill accent for a status: hard color when defined, otherwise theme-driven. */
@Composable
fun statusAccent(status: DownloadStatus): Color =
    statusColor(status).takeIf { it != Color.Unspecified }
        ?: if (status == DownloadStatus.QUEUED || status == DownloadStatus.CONNECTING) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        }

fun fileIcon(category: FileCategory): ImageVector =
    when (category) {
        FileCategory.IMAGE -> Icons.Filled.Image
        FileCategory.VIDEO -> Icons.Filled.Movie
        FileCategory.AUDIO -> Icons.Filled.AudioFile
        FileCategory.ARCHIVE -> Icons.Filled.Archive
        FileCategory.APP -> Icons.Filled.Android
        else -> Icons.Filled.Description
    }

/**
 * One row in the download list: a rounded tonal card with an animated progress bar.
 * Supports single-selection mode (long-press to enter) and an optional archive action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(
    entity: DownloadEntity,
    compact: Boolean,
    onClick: () -> Unit,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    speedBps: Long = 0,
    selected: Boolean = false,
    inSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onArchive: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val status = entity.status
    val percent = Format.percent(entity.downloadedBytes, entity.totalBytes)
    val active = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.CONNECTING
    val category = remember(entity.filename) { FileRepository.categorize(entity.filename, null) }
    val accent = categoryAccent(category)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 5.dp),
        shape = RoundedCornerShape(18.dp),
        color =
            when {
                selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                compact -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        tonalElevation = if (compact && !selected) 0.dp else 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                    .padding(horizontal = 14.dp, vertical = if (compact) 9.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier =
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(categoryGradient(accent)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (inSelectionMode) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint =
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Icon(
                            imageVector = fileIcon(category),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entity.filename,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            buildString {
                                append(Format.bytes(entity.downloadedBytes))
                                if (entity.totalBytes > 0) {
                                    append(" / ")
                                    append(Format.bytes(entity.totalBytes))
                                    if (percent >= 0) append("  \u00B7  $percent%")
                                }
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!compact && active) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        Text(
                            text =
                                listOfNotNull(
                                    com.arc.downloadmanager.util.Format.speed(ctx, speedBps),
                                    com.arc.downloadmanager.util.Format.eta(ctx, entity.totalBytes - entity.downloadedBytes, speedBps),
                                ).joinToString("  \u00B7  "),
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
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(status, entity.errorCode)
                    Row {
                        if (onArchive != null && !inSelectionMode) {
                            IconButton(onClick = onArchive) {
                                Icon(
                                    if (entity.archivedAt == null) Icons.Filled.Archive else Icons.Filled.Unarchive,
                                    contentDescription =
                                        androidx.compose.ui.res.stringResource(
                                            if (entity.archivedAt == null) {
                                                com.arc.downloadmanager.R.string.action_archive
                                            } else {
                                                com.arc.downloadmanager.R.string.action_unarchive
                                            },
                                        ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        when {
                            active || status == DownloadStatus.PAUSING ->
                                IconButton(onClick = onPauseResume) {
                                    Icon(
                                        Icons.Filled.PauseCircle,
                                        contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_pause),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            status == DownloadStatus.PAUSED || status == DownloadStatus.WAITING_NETWORK ||
                                status == DownloadStatus.WAITING_STORAGE || status == DownloadStatus.WAITING_SCHEDULED ->
                                IconButton(onClick = onPauseResume) {
                                    Icon(
                                        Icons.Filled.PlayCircle,
                                        contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_resume),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            status.isTerminal || status == DownloadStatus.AUTH_REQUIRED ->
                                IconButton(onClick = onRetry) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_retry),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                        }
                        if (!status.isTerminal || status == DownloadStatus.CANCELED) {
                            IconButton(onClick = onCancel) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.arc.downloadmanager.R.string.action_cancel),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (percent >= 0 || active) {
                val target = if (percent >= 0) percent / 100f else 0f
                val animated by animateFloatAsState(
                    targetValue = target.coerceIn(0f, 1f),
                    animationSpec = SpringSpec(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
                    label = "rowProgress",
                )
                val progressColor =
                    when {
                        status == DownloadStatus.AUTH_REQUIRED || status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        status == DownloadStatus.COMPLETED -> accent
                        else -> accent
                    }
                if (percent >= 0) {
                    // Custom gradient bar — accent fading toward its lighter edge.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(animated.coerceIn(0.02f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Brush.horizontalGradient(listOf(progressColor, progressColor.copy(alpha = 0.7f)))),
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        color = progressColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/** Small rounded status chip with a tinted container. */
@Composable
private fun StatusPill(
    status: DownloadStatus,
    errorCode: String?,
) {
    val accent = statusAccent(status)
    Surface(
        color = accent.copy(alpha = 0.13f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(statusLabelRes(status, errorCode)),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}
