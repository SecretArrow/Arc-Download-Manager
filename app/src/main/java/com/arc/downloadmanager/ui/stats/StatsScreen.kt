@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.repository.DayStat
import com.arc.downloadmanager.data.repository.DownloadStats
import com.arc.downloadmanager.data.repository.HostStat
import com.arc.downloadmanager.util.Format
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    Scaffold(
        // WindowInsets(0): status bar inset is applied by the TopAppBar; the navigation-bar
        // inset is applied explicitly below because this screen has no bottom NavigationBar.
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.stats_refresh))
                    }
                },
            )
        },
    ) { padding ->
        when (val value = stats) {
            null ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            else ->
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
                    SummaryRow(value)
                    if (value.totalCompleted == 0) {
                        StatsEmpty()
                    } else {
                        WeekCard(value.last7Days)
                        TopHostsCard(value.topHosts)
                    }
                }
        }
    }
}

/** Completed / Failed / Total downloaded summary cards, mirroring the hero chip language. */
@Composable
private fun SummaryRow(stats: DownloadStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryCard(
            icon = Icons.Filled.CheckCircle,
            tint = Color(0xFF2E7D32),
            value = stats.totalCompleted.toString(),
            label = stringResource(R.string.stats_completed),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            icon = Icons.Filled.Error,
            tint = MaterialTheme.colorScheme.error,
            value = stats.totalFailed.toString(),
            label = stringResource(R.string.stats_failed),
            modifier = Modifier.weight(1f),
        )
        SummaryCard(
            icon = Icons.Filled.Download,
            tint = MaterialTheme.colorScheme.primary,
            value = Format.bytes(stats.totalBytes),
            label = stringResource(R.string.stats_total_downloaded),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCard(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Last 7 days" card: hand-drawn Canvas bar chart plus weekday labels. */
@Composable
private fun WeekCard(days: List<DayStat>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.stats_last_7_days),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Format.bytes(days.sumOf { it.bytes }),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            WeekChart(days)
            WeekLabels(days)
        }
    }
}

/** Seven vertical bars, height proportional to the day's downloaded bytes; today is drawn last. */
@Composable
private fun WeekChart(days: List<DayStat>) {
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(132.dp),
    ) {
        val maxBytes = days.maxOf { it.bytes }.coerceAtLeast(1L)
        val slot = size.width / days.size
        val barWidth = slot * 0.5f
        val trackHeight = 4.dp.toPx()
        // Same primary-fade language as the DownloadRow progress bar, mapped onto the chart.
        val barBrush = Brush.verticalGradient(listOf(primary, primary.copy(alpha = 0.65f)))
        days.forEachIndexed { index, day ->
            val left = slot * index + (slot - barWidth) / 2f
            // Baseline stub so zero days still read as part of the chart.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, size.height - trackHeight),
                size = Size(barWidth, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f),
            )
            if (day.bytes > 0) {
                val fraction = (day.bytes.toFloat() / maxBytes).coerceIn(0.04f, 1f)
                val barHeight = trackHeight + (size.height - trackHeight) * fraction
                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(min(barWidth, barHeight) / 2f, min(barWidth, barHeight) / 2f),
                )
            }
        }
    }
}

/** Weekday labels ("EEE") aligned 1:1 with the chart slots; today is highlighted. */
@Composable
private fun WeekLabels(days: List<DayStat>) {
    val formatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
    ) {
        days.forEachIndexed { index, day ->
            val isToday = index == days.lastIndex
            Text(
                text = LocalDate.ofEpochDay(day.epochDay).format(formatter),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (isToday) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Top hosts by downloaded bytes, each with a count chip and a proportional mini bar. */
@Composable
private fun TopHostsCard(hosts: List<HostStat>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_top_hosts),
                style = MaterialTheme.typography.titleMedium,
            )
            val maxBytes = hosts.maxOf { it.bytes }.coerceAtLeast(1L)
            hosts.forEach { host ->
                HostRow(host = host, fraction = host.bytes.toFloat() / maxBytes)
            }
        }
    }
}

@Composable
private fun HostRow(
    host: HostStat,
    fraction: Float,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = host.host,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = Format.bytes(host.bytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = accent.copy(alpha = 0.13f),
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    text = host.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        ) {
            if (host.bytes > 0) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.7f)))),
                )
            }
        }
    }
}

/** Friendly empty state when nothing has completed yet. */
@Composable
private fun StatsEmpty() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                            ),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.BarChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.stats_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}
