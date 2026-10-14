@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.BuildConfig
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.data.settings.ThemeMode
import com.arc.downloadmanager.ui.theme.ArcHeroBrush

private const val ABOUT_GITHUB_URL = "https://github.com/SecretArrow/Arc-Download-Manager"

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                viewModel.setDefaultDestination(it.toString())
            }
        }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        ) {
            SettingsSection(stringResource(R.string.settings_downloads), icon = Icons.Filled.Download) {
                NumberRow(
                    label = stringResource(R.string.settings_max_concurrent),
                    value = settings.maxConcurrentFiles,
                    range = 1f..10f,
                    onCommit = viewModel::setMaxConcurrent,
                )
                NumberRow(
                    label = stringResource(R.string.settings_max_segments),
                    value = settings.maxSegmentsPerFile,
                    range = 1f..16f,
                    onCommit = viewModel::setMaxSegments,
                )
                SwitchRow(stringResource(R.string.settings_auto_segments), settings.autoDetectSegments, viewModel::setAutoSegments)
                SwitchRow(stringResource(R.string.settings_wifi_only), settings.wifiOnly, viewModel::setWifiOnly)
                SwitchRow(stringResource(R.string.settings_auto_resume), settings.autoResume, viewModel::setAutoResume)
                SwitchRow(stringResource(R.string.settings_auto_retry), settings.autoRetry, viewModel::setAutoRetry)
                Text(
                    stringResource(R.string.settings_auto_retry_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberRow(
                    label = stringResource(R.string.settings_retries),
                    value = settings.retryAttempts,
                    range = 0f..20f,
                    onCommit = viewModel::setRetries,
                )
                SwitchRow(stringResource(R.string.settings_notify_complete), settings.notifyCompletion, viewModel::setNotifyCompletion)
                Text(stringResource(R.string.settings_overwrite_policy), style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        OverwritePolicy.RENAME to R.string.settings_overwrite_rename,
                        OverwritePolicy.SKIP to R.string.settings_overwrite_skip,
                        OverwritePolicy.REPLACE to R.string.settings_overwrite_replace,
                    ).forEachIndexed { index, (policy, label) ->
                        SegmentedButton(
                            selected = settings.overwritePolicy == policy,
                            onClick = { viewModel.setOverwritePolicy(policy) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(stringResource(label)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_default_destination),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.action_choose_folder))
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_network), icon = Icons.Filled.Wifi) {
                NumberRow(
                    label = stringResource(R.string.settings_bandwidth_limit),
                    value = settings.bandwidthLimitKBps.toInt(),
                    range = 0f..10240f,
                    steps = 20,
                    onCommit = { viewModel.setBandwidthLimit(it.toLong()) },
                )
                NumberRow(
                    label = stringResource(R.string.settings_connect_timeout),
                    value = settings.connectTimeoutSec,
                    range = 5f..120f,
                    onCommit = viewModel::setConnectTimeout,
                )
                NumberRow(
                    label = stringResource(R.string.settings_read_timeout),
                    value = settings.readTimeoutSec,
                    range = 5f..600f,
                    onCommit = viewModel::setReadTimeout,
                )
            }

            SettingsSection(stringResource(R.string.settings_storage), icon = Icons.Filled.Storage) {
                NumberRow(
                    label = stringResource(R.string.settings_min_free_space),
                    value = settings.minFreeSpaceMB.toInt(),
                    range = 50f..4096f,
                    onCommit = { viewModel.setMinFreeSpace(it.toLong()) },
                )
                SwitchRow(stringResource(R.string.settings_cleanup_temp), settings.autoCleanupTemp, viewModel::setAutoCleanup)
                SwitchRow(stringResource(R.string.settings_sha256_after), settings.computeSha256, viewModel::setComputeSha256)
            }

            SettingsSection(stringResource(R.string.settings_appearance), icon = Icons.Filled.Palette) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(
                        ThemeMode.SYSTEM to R.string.settings_theme_system,
                        ThemeMode.LIGHT to R.string.settings_theme_light,
                        ThemeMode.DARK to R.string.settings_theme_dark,
                    ).forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(stringResource(label)) }
                    }
                }
                SwitchRow(stringResource(R.string.settings_dynamic_color), settings.dynamicColor, viewModel::setDynamicColor)
                SwitchRow(stringResource(R.string.settings_compact_rows), settings.compactRows, viewModel::setCompactRows)
            }

            SettingsSection(stringResource(R.string.settings_advanced), icon = Icons.Filled.Tune) {
                TextButton(onClick = viewModel::reset) { Text(stringResource(R.string.settings_reset)) }
            }

            AboutCard(version = BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AboutCard(version: String) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(ArcHeroBrush),
            ) {
                // Decorative translucent circles on the gradient band.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 28.dp, y = (-34).dp)
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)),
                )
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-20).dp, y = 24.dp)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f)),
                )
                // Logo badge overlapping the band edge — white disc + brand-tinted mark,
                // visible in both themes (the raw vector strokes are white).
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 28.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                shape = CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                // Developer credit pill — "Developed by Maragung".
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.about_developed_by, stringResource(R.string.about_developer)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.settings_version, version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ABOUT_GITHUB_URL)),
                            )
                        }
                    },
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.about_open_github))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberRow(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onCommit: (Int) -> Unit,
) {
    var sliderValue by rememberSaveable(value) { mutableStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${sliderValue.toInt()}", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(sliderValue.toInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}
