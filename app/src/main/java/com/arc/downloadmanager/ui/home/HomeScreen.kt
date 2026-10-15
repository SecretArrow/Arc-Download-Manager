@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.ui.components.DownloadRow
import com.arc.downloadmanager.ui.downloads.AddDownloadSheet
import com.arc.downloadmanager.ui.theme.ArcHeroBrush
import com.arc.downloadmanager.util.Format
import com.arc.downloadmanager.util.LinkText
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenStats: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingDuplicate by viewModel.pendingDuplicate.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addSheetUrl by rememberSaveable { mutableStateOf("") }
    var viewMode by rememberSaveable { mutableIntStateOf(1) } // 0 compact, 1 detailed
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Single entry point for opening the add sheet: the URL field it is pre-filled with.
    val openAddSheet = { url: String ->
        addSheetUrl = url
        showAdd = true
    }

    Scaffold(
        // WindowInsets(0): insets are applied exactly once — status bar by the TopAppBar below,
        // navigation bar by the NavigationBar that hosts this screen — and imePadding is not
        // needed here because text input happens inside ModalBottomSheet, which handles it.
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            if (ui.inSelectionMode) {
                SelectionTopBar(
                    count = ui.selection.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllVisible,
                    onArchive = {
                        if (ui.filter == HomeFilter.ARCHIVED) viewModel.restoreSelected() else viewModel.archiveSelected()
                    },
                    onDelete = { showDeleteDialog = true },
                    archivedView = ui.filter == HomeFilter.ARCHIVED,
                )
            } else {
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
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.setQuery("")
                        }) {
                            Icon(
                                if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search_hint),
                            )
                        }
                        IconButton(onClick = viewModel::pauseAll) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.notif_pause_all))
                        }
                        IconButton(onClick = viewModel::resumeAll) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.notif_resume_all))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !ui.inSelectionMode,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                ExtendedFloatingActionButton(
                    onClick = { openAddSheet("") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_download)) },
                )
            }
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
                AnimatedVisibility(visible = !ui.inSelectionMode) {
                    Column {
                        HeroCard(
                            ui = ui,
                            storageFree = ui.storageFree,
                            onOpenFiles = onOpenFiles,
                        )
                        AnimatedVisibility(visible = showSearch) {
                            SearchField(
                                value = ui.query,
                                onValueChange = viewModel::setQuery,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        FilterRow(
                            ui = ui,
                            onSelectFilter = viewModel::setFilter,
                            onOpenSortMenu = { showSortMenu = true },
                            sortMenuVisible = showSortMenu,
                            onDismissSortMenu = { showSortMenu = false },
                            onSelectSort = {
                                viewModel.setSort(it)
                                showSortMenu = false
                            },
                        )
                    }
                }
            }
            if (!ui.inSelectionMode) {
                item {
                    QuickActions(
                        onAdd = { openAddSheet("") },
                        onPaste = {
                            // Paste & go: pre-fill the sheet, the confirm button stays the only add path.
                            openAddSheet(LinkText.firstUrl(readClipboardText(context)) ?: "")
                        },
                        onBatch = { openAddSheet("") },
                        onBrowser = onOpenBrowser,
                        onStats = onOpenStats,
                        onClearFinished =
                            if (ui.completedCount > 0) {
                                {
                                    viewModel.clearFinished { count ->
                                        scope.launch {
                                            snackbar.showSnackbar(context.getString(R.string.clear_finished_done, count))
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                    )
                }
            }
            if (ui.filter == HomeFilter.ALL && ui.attention.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.home_needs_attention), isError = true)
                }
                items(ui.attention, key = { "attn-${it.id}" }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = true,
                        onClick = { rowClick(viewModel, entity.id, onOpenDetail) },
                        onPauseResume = { viewModel.resume(entity.id) },
                        onCancel = { viewModel.cancel(entity.id) },
                        onRetry = { viewModel.retry(entity.id) },
                        speedBps = ui.speeds[entity.id] ?: 0L,
                        selected = entity.id in ui.selection,
                        inSelectionMode = ui.inSelectionMode,
                        onLongPress = { viewModel.toggleSelected(entity.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (ui.visibleDownloads.isEmpty()) {
                item {
                    if (ui.filter == HomeFilter.ARCHIVED) {
                        EmptyState(
                            icon = Icons.Filled.Archive,
                            title = stringResource(R.string.archived_empty_title),
                            subtitle = stringResource(R.string.archived_empty_subtitle),
                        )
                    } else if (ui.downloads.isEmpty()) {
                        EmptyDownloads(
                            onAdd = { openAddSheet("") },
                            onBrowser = onOpenBrowser,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.no_results_title),
                            subtitle = stringResource(R.string.no_results_subtitle),
                        )
                    }
                }
            } else {
                item {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text =
                                stringResource(
                                    if (ui.filter == HomeFilter.ARCHIVED) R.string.home_archived_downloads else R.string.home_active_downloads,
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (!ui.inSelectionMode) {
                            ViewModeSwitch(viewMode) { viewMode = it }
                        }
                    }
                }
                items(ui.visibleDownloads, key = { it.id }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = viewMode == 0,
                        onClick = { rowClick(viewModel, entity.id, onOpenDetail) },
                        onPauseResume = {
                            if (entity.status.isActive) viewModel.pause(entity.id) else viewModel.resume(entity.id)
                        },
                        onCancel = { viewModel.cancel(entity.id) },
                        onRetry = { viewModel.retry(entity.id) },
                        speedBps = ui.speeds[entity.id] ?: 0L,
                        selected = entity.id in ui.selection,
                        inSelectionMode = ui.inSelectionMode,
                        onLongPress = { viewModel.toggleSelected(entity.id) },
                        onArchive = {
                            if (ui.filter == HomeFilter.ARCHIVED) viewModel.restore(entity.id) else viewModel.archive(entity.id)
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (!ui.inSelectionMode && ui.filter == HomeFilter.ALL && ui.recentCompleted.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.home_recent_files)) }
                items(ui.recentCompleted, key = { "recent-${it.id}" }) { entity ->
                    DownloadRow(
                        entity = entity,
                        compact = true,
                        onClick = { onOpenDetail(entity.id) },
                        onPauseResume = {},
                        onCancel = {},
                        onRetry = {},
                        selected = false,
                        inSelectionMode = false,
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
            initialUrl = addSheetUrl,
        )
    }

    if (showDeleteDialog) {
        DeleteSelectionDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = { deleteFiles ->
                viewModel.deleteSelected(deleteFiles)
                showDeleteDialog = false
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

/** Plain text from the system clipboard; null on any failure (some OEM clipboard impls throw). */
private fun readClipboardText(context: Context): String? =
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

/** Click handler shared by rows: toggle selection in selection mode, open detail otherwise. */
private fun rowClick(
    viewModel: HomeViewModel,
    id: String,
    onOpenDetail: (String) -> Unit,
) {
    if (viewModel.selection.value.isNotEmpty()) {
        viewModel.toggleSelected(id)
    } else {
        onOpenDetail(id)
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    archivedView: Boolean,
) {
    // statusBarsPadding: this custom bar replaces the TopAppBar in selection mode, so it
    // must apply the status-bar inset itself or its content collides with the notification bar.
    Surface(
        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
            Text(
                text = stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            IconButton(onClick = onArchive) {
                Icon(
                    if (archivedView) Icons.Filled.Unarchive else Icons.Filled.Archive,
                    contentDescription =
                        stringResource(if (archivedView) R.string.action_unarchive else R.string.action_archive),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    )
}

@Composable
private fun FilterRow(
    ui: HomeUiState,
    onSelectFilter: (HomeFilter) -> Unit,
    onOpenSortMenu: () -> Unit,
    sortMenuVisible: Boolean,
    onDismissSortMenu: () -> Unit,
    onSelectSort: (HomeSort) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = ui.filter == HomeFilter.ALL,
            onClick = { onSelectFilter(HomeFilter.ALL) },
            label = { Text(stringResource(R.string.filter_all)) },
        )
        FilterChip(
            selected = ui.filter == HomeFilter.RUNNING,
            onClick = { onSelectFilter(HomeFilter.RUNNING) },
            label = { Text(stringResource(R.string.filter_running, ui.runningCount)) },
        )
        FilterChip(
            selected = ui.filter == HomeFilter.COMPLETED,
            onClick = { onSelectFilter(HomeFilter.COMPLETED) },
            label = { Text(stringResource(R.string.filter_completed, ui.completedCount)) },
        )
        FilterChip(
            selected = ui.filter == HomeFilter.FAILED,
            onClick = { onSelectFilter(HomeFilter.FAILED) },
            label = { Text(stringResource(R.string.filter_failed, ui.failedCount)) },
        )
        FilterChip(
            selected = ui.filter == HomeFilter.ARCHIVED,
            onClick = { onSelectFilter(HomeFilter.ARCHIVED) },
            label = { Text(stringResource(R.string.filter_archived, ui.archivedCount)) },
        )
        Box {
            IconButton(onClick = onOpenSortMenu, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Sort,
                    contentDescription = stringResource(R.string.sort_by),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = sortMenuVisible, onDismissRequest = onDismissSortMenu) {
                SortMenuItem(HomeSort.DATE_NEWEST, ui.sort, onSelectSort)
                SortMenuItem(HomeSort.DATE_OLDEST, ui.sort, onSelectSort)
                SortMenuItem(HomeSort.NAME, ui.sort, onSelectSort)
                SortMenuItem(HomeSort.SIZE, ui.sort, onSelectSort)
            }
        }
    }
}

@Composable
private fun SortMenuItem(
    sort: HomeSort,
    current: HomeSort,
    onSelect: (HomeSort) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(sortLabelRes(sort)),
                fontWeight = if (sort == current) FontWeight.Bold else FontWeight.Normal,
            )
        },
        onClick = { onSelect(sort) },
    )
}

private fun sortLabelRes(sort: HomeSort): Int =
    when (sort) {
        HomeSort.DATE_NEWEST -> R.string.sort_date_newest
        HomeSort.DATE_OLDEST -> R.string.sort_date_oldest
        HomeSort.NAME -> R.string.sort_name
        HomeSort.SIZE -> R.string.sort_size
    }

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
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
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeleteSelectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var alsoDeleteFile by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_selection_title)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_selection_message))
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = alsoDeleteFile,
                        onCheckedChange = { alsoDeleteFile = it },
                    )
                    Text(stringResource(R.string.delete_also_file))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(alsoDeleteFile) }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
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
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec =
            androidx.compose.animation.core.SpringSpec(
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
            ),
        label = "heroProgress",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(ArcHeroBrush)
                .padding(20.dp),
    ) {
        // Decorative translucent circles bleeding past the edges — depth without noise.
        Box(
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 52.dp, y = (-58).dp)
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-44).dp, y = 46.dp)
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (ui.activeCount > 0) R.string.hero_downloading else R.string.hero_idle),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeroChip(stringResource(R.string.hero_active_count, ui.activeCount))
                        HeroChip(stringResource(R.string.hero_queued_count, ui.runningCount))
                        if (ui.failedCount > 0) {
                            HeroChip(stringResource(R.string.hero_failed_count, ui.failedCount))
                        }
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(60.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.22f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Text(
                        text = if (progressTarget > 0f) "${(progressTarget * 100).toInt()}%" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (ui.activeCount > 0) {
                Text(
                    text = Format.speed(androidx.compose.ui.platform.LocalContext.current, ui.totalSpeed),
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

/** Translucent glass pill used inside the hero card. */
@Composable
private fun HeroChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun QuickActions(
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onBatch: () -> Unit,
    onBrowser: () -> Unit,
    onStats: () -> Unit,
    onClearFinished: (() -> Unit)?,
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
            label = { Text(stringResource(R.string.share_paste_download)) },
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
        AssistChip(
            onClick = onStats,
            label = { Text(stringResource(R.string.stats_title)) },
            leadingIcon = { Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(17.dp)) },
        )
        if (onClearFinished != null) {
            AssistChip(
                onClick = onClearFinished,
                label = { Text(stringResource(R.string.action_clear_finished)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(17.dp)) },
            )
        }
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
                Icons.Filled.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
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
