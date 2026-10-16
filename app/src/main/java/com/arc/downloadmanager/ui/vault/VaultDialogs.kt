package com.arc.downloadmanager.ui.vault

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arc.downloadmanager.R

/** Shown once right after setup: the one-time recovery key. */
@Composable
fun RecoveryKeyDialog(viewModel: VaultViewModel) {
    val key by viewModel.recoveryKey.collectAsState()
    if (key == null) return
    var checked by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { /* must be acknowledged */ },
        title = { Text(stringResource(R.string.vault_recovery_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vault_recovery_intro))
                Text(
                    key ?: "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                )
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Arc recovery key", key))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.vault_recovery_copy), Modifier.padding(start = 4.dp))
                }
                Text(
                    stringResource(R.string.vault_setup_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text(stringResource(R.string.vault_recovery_confirm_check), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = viewModel::acknowledgeRecoveryKey, enabled = checked) {
                Text(stringResource(R.string.vault_recovery_done))
            }
        },
    )
}

/** Enter an existing recovery key when the password is forgotten. */
@Composable
fun RecoveryKeyEntryDialog(
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    val ui by viewModel.unlockUi.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_recovery_enter_title)) },
        text = {
            Column {
                Text(stringResource(R.string.vault_recovery_enter_hint))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.vault_recovery_key_label)) },
                    singleLine = true,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.unlockWithRecoveryKey(key)
                    if (ui.lockedUntilMs == 0L && ui.errorRes == null) onDismiss()
                },
                enabled = key.isNotBlank(),
            ) { Text(stringResource(R.string.vault_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** After unlocking with the recovery key: the old password is unknown. */
@Composable
fun PasswordResetDialog(viewModel: VaultViewModel) {
    val showReset by viewModel.passwordResetAfterRecovery.collectAsState()
    if (!showReset) return
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = viewModel::dismissPasswordReset,
        title = { Text(stringResource(R.string.vault_reset_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vault_reset_password_intro))
                OutlinedTextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = { Text(stringResource(R.string.vault_change_new)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.vault_password_confirm)) },
                    singleLine = true,
                )
                errorRes?.let { err ->
                    Text(stringResource(err), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                errorRes = null
                viewModel.completePasswordReset(newPw) { errorRes = it }
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPasswordReset) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ChangePasswordDialog(
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text(stringResource(R.string.vault_change_old)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text(stringResource(R.string.vault_change_new)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.vault_password_confirm)) },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.vault_change_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorRes?.let { err ->
                    Text(stringResource(err), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                errorRes = null
                viewModel.changePassword(
                    old,
                    new,
                    confirm,
                    onError = { errorRes = it },
                    onSuccess = onDismiss,
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun AutoLockDialog(
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
) {
    val current by viewModel.autoLockMinutes.collectAsState()
    val options =
        listOf(
            0 to R.string.vault_autolock_immediate,
            1 to R.string.vault_autolock_1min,
            5 to R.string.vault_autolock_5min,
            15 to R.string.vault_autolock_15min,
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_autolock_title)) },
        text = {
            Column {
                options.forEach { (minutes, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = current == minutes,
                            onClick = {
                                viewModel.setAutoLockMinutes(minutes)
                                onDismiss()
                            },
                        )
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
