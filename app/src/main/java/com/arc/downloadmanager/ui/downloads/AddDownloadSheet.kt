@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.downloads

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arc.downloadmanager.R
import com.arc.downloadmanager.domain.model.Destination
import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.domain.model.Priority
import com.arc.downloadmanager.engine.CookieScopePolicy

/**
 * Add-download bottom sheet with single and batch modes, advanced options in an expandable
 * section, and SAF folder picking for custom destinations.
 */
@Composable
fun AddDownloadSheet(
    onDismiss: () -> Unit,
    onAdd: (DownloadRequest) -> Unit,
    onAddBatch: (List<DownloadRequest>, (Int, Int) -> Unit) -> Unit,
    initialUrl: String = "",
    initialDestination: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var filename by rememberSaveable { mutableStateOf("") }
    var segments by rememberSaveable { mutableIntStateOf(0) }
    var priority by rememberSaveable { mutableIntStateOf(1) }
    var wifiOnly by rememberSaveable { mutableStateOf(false) }
    var checksum by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    var batchText by rememberSaveable { mutableStateOf("") }
    var batchMessage by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                destination = it.toString()
            }
        }
    val fileImporter =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.openInputStream(it)
                        ?.bufferedReader()?.use { reader -> reader.readText() }
                }.getOrNull()?.let { text -> batchText = text }
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    // Keep URL/batch fields above the keyboard while typing.
                    .imePadding()
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.action_add_download)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.action_batch_download)) })
            }

            if (tab == 0) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = false
                    },
                    label = { Text(stringResource(R.string.add_url_hint)) },
                    isError = error,
                    supportingText = { if (error) Text(stringResource(R.string.add_empty_url)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            clipboard.getText()?.toString()?.let { if (it.isNotBlank()) url = it.trim() }
                        }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.action_paste_url))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = destination ?: "",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(R.string.add_destination)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.action_choose_folder))
                    }
                }
                Text(stringResource(R.string.add_segments), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                SegmentsRow(segments) { segments = it }
                Text(stringResource(R.string.add_priority), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                PriorityRow(priority) { priority = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.add_wifi_only), modifier = Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                if (advanced) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = checksum,
                        onValueChange = { checksum = it },
                        label = { Text(stringResource(R.string.add_checksum_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        if (url.isBlank() || !CookieScopePolicy.isSupportedUrl(url)) {
                            error = true
                        } else {
                            onAdd(
                                DownloadRequest(
                                    url = url.trim(),
                                    filename = filename.ifBlank { null },
                                    destination = destination?.let { Destination.CustomFolder(it) } ?: Destination.Default,
                                    segments = segments,
                                    priority = Priority.fromValue(priority),
                                    wifiOnly = wifiOnly.takeIf { it },
                                    checksum = checksum.ifBlank { null },
                                ),
                            )
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_start))
                }
            } else {
                OutlinedTextField(
                    value = batchText,
                    onValueChange = { batchText = it },
                    label = { Text(stringResource(R.string.add_batch_hint)) },
                    minLines = 5,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { fileImporter.launch(arrayOf("text/plain", "text/*")) }) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Text(stringResource(R.string.action_import_file), modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(onClick = {
                        clipboard.getText()?.toString()?.let { if (it.isNotBlank()) batchText = it }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    }
                }
                val urls = batchText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val valid = urls.count { CookieScopePolicy.isSupportedUrl(it) }
                Text("$valid / ${urls.size} valid URLs")
                if (batchMessage.isNotBlank()) Text(batchMessage)
                Button(
                    enabled = valid > 0,
                    onClick = {
                        val requests =
                            urls
                                .filter { CookieScopePolicy.isSupportedUrl(it) }
                                .map { DownloadRequest(url = it, source = com.arc.downloadmanager.domain.model.DownloadSource.BATCH) }
                        val template = context.getString(R.string.add_batch_started, 0, 0)
                        onAddBatch(requests) { started, invalid ->
                            batchMessage =
                                template
                                    .replace("%1\$d", started.toString())
                                    .replace("%2\$d", invalid.toString())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_start_all))
                }
            }
        }
    }
}

@Composable
private fun SegmentsRow(
    selected: Int,
    onChange: (Int) -> Unit,
) {
    val options = listOf(0 to "Auto", 2 to "2", 4 to "4", 8 to "8", 16 to "16")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun PriorityRow(
    selected: Int,
    onChange: (Int) -> Unit,
) {
    val options = listOf(0, 1, 2)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, value ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(
                    when (value) {
                        0 -> "Low"
                        2 -> "High"
                        else -> "Normal"
                    },
                )
            }
        }
    }
}
