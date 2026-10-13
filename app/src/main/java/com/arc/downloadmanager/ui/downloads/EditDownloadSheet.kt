package com.arc.downloadmanager.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.engine.CookieScopePolicy

/**
 * Dialog to edit a task's URL and display filename. Validation mirrors the engine's
 * [com.arc.downloadmanager.engine.TaskEditPolicy] so the user gets instant feedback.
 */
@Composable
fun EditDownloadSheet(
    entity: DownloadEntity,
    onDismiss: () -> Unit,
    onSave: (url: String, filename: String) -> Unit,
) {
    var url by rememberSaveable(entity.id) { mutableStateOf(entity.url) }
    var filename by rememberSaveable(entity.id) { mutableStateOf(entity.filename) }
    var submitted by remember { mutableStateOf(false) }

    val trimmedUrl = url.trim()
    val trimmedName = filename.trim()
    val urlValid = CookieScopePolicy.isSupportedUrl(trimmedUrl)
    val nameValid = trimmedName.isNotEmpty()
    val showUrlError = submitted && !urlValid
    val showNameError = submitted && !nameValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_task_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text(stringResource(R.string.edit_filename_label)) },
                    singleLine = true,
                    isError = showNameError,
                    supportingText = {
                        if (showNameError) {
                            Text(
                                text = stringResource(R.string.edit_invalid_filename),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.edit_url_label)) },
                    singleLine = true,
                    isError = showUrlError,
                    supportingText = {
                        if (showUrlError) {
                            Text(
                                text = stringResource(R.string.edit_invalid_url),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    submitted = true
                    if (urlValid && nameValid) onSave(trimmedUrl, trimmedName)
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
