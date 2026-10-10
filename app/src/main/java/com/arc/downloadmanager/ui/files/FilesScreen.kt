@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.files

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.arc.downloadmanager.data.repository.FileCategory
import com.arc.downloadmanager.data.repository.FileItem
import com.arc.downloadmanager.ui.components.fileIcon
import com.arc.downloadmanager.util.Format

@Composable
fun FilesScreen(viewModel: FilesViewModel = hiltViewModel()) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_files)) },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::search,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.files_search_hint)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val categories =
                    listOf(
                        FileCategory.IMAGE to R.string.files_category_images,
                        FileCategory.VIDEO to R.string.files_category_videos,
                        FileCategory.AUDIO to R.string.files_category_audio,
                        FileCategory.DOCUMENT to R.string.files_category_documents,
                        FileCategory.ARCHIVE to R.string.files_category_archives,
                        FileCategory.APP to R.string.files_category_apps,
                        FileCategory.OTHER to R.string.files_category_other,
                    )
                item {
                    FilterChip(
                        selected = category == null,
                        onClick = { viewModel.selectCategory(null) },
                        label = { Text(stringResource(R.string.files_category_all)) },
                    )
                }
                items(categories) { (cat, label) ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { viewModel.selectCategory(cat) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            val visible =
                files
                    .filter { category == FileCategory.OTHER || it.category == category }
                    .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            if (visible.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.files_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    items(visible, key = { it.uri }) { item ->
                        FileRow(
                            item = item,
                            onOpen = {
                                runCatching { context.startActivity(viewModel.openIntent(item)) }
                            },
                            onShare = {
                                runCatching { context.startActivity(Intent.createChooser(viewModel.shareIntent(item), null)) }
                            },
                            onDelete = { viewModel.delete(item) },
                            onRename = { newName -> viewModel.rename(item, newName) { } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    item: FileItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(item.name) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = fileIcon(item.category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Format.bytes(item.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menuOpen = false
                            renaming = true
                        },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    )
                }
            }
        }
    }
    if (renaming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    renaming = false
                    onRename(newName)
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
