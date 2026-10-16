@file:OptIn(ExperimentalMaterial3Api::class)

package com.arc.downloadmanager.ui.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.VaultFileEntity
import com.arc.downloadmanager.data.repository.FileCategory
import com.arc.downloadmanager.data.vault.VaultLockState
import com.arc.downloadmanager.ui.components.LockTile
import com.arc.downloadmanager.ui.components.fileIcon
import com.arc.downloadmanager.ui.components.showBiometricPrompt
import com.arc.downloadmanager.ui.theme.categoryAccent
import com.arc.downloadmanager.util.Format
import java.text.DateFormat
import java.util.Date

/**
 * The Encrypted Vault: setup (first run), unlock, and the unlocked file list.
 * One route, three phases — driven by [VaultLockState].
 */
@Composable
fun VaultScreen(viewModel: VaultViewModel = hiltViewModel()) {
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // One-shot toasts (saved / exported / deleted / errors)
    LaunchedEffect(Unit) {
        viewModel.events.collect { toast ->
            val text =
                toast.arg?.let { context.getString(toast.res, it) } ?: context.getString(toast.res)
            snackbar.showSnackbar(text)
        }
    }

    var pendingExportId by remember { mutableStateOf<String?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val id = pendingExportId
            if (uri != null && id != null) viewModel.exportTo(id, uri)
            pendingExportId = null
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = queryDisplayName(context, uri) ?: "file"
                viewModel.importFromPicker(uri, name, context.contentResolver.getType(uri))
            }
        }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            VaultTopBar(
                locked = lockState !is VaultLockState.Unlocked,
                viewModel = viewModel,
            )
        },
        floatingActionButton = {
            if (lockState is VaultLockState.Unlocked) {
                ExtendedFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.vault_add_file)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (lockState) {
                is VaultLockState.NoVault -> SetupView(viewModel)
                is VaultLockState.Locked -> UnlockView(viewModel)
                is VaultLockState.Unlocked -> {
                    Column {
                        if (busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        VaultContent(
                            viewModel = viewModel,
                            onExport = { id ->
                                val file = viewModel.visible.value.firstOrNull { it.id == id }
                                pendingExportId = id
                                exportLauncher.launch(file?.name ?: "file")
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultTopBar(
    locked: Boolean,
    viewModel: VaultViewModel,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.nav_vault)) },
        actions = {
            if (!locked) {
                IconButton(onClick = viewModel::lockNow) {
                    Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.vault_lock_now))
                }
                OverflowMenu(viewModel)
            }
        },
    )
}

@Composable
private fun OverflowMenu(viewModel: VaultViewModel) {
    var open by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
    var autoLock by remember { mutableStateOf(false) }
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val biometricAvailable by viewModel.biometricAvailable.collectAsState()
    val context = LocalContext.current

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_menu_change_password)) },
                onClick = {
                    open = false
                    changePassword = true
                },
                leadingIcon = { Icon(Icons.Filled.LockOpen, contentDescription = null) },
            )
            if (biometricAvailable) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (biometricEnabled) R.string.vault_biometric_disable else R.string.vault_biometric_enable,
                            ),
                        )
                    },
                    onClick = {
                        open = false
                        if (biometricEnabled) {
                            viewModel.disableBiometric()
                        } else {
                            (context as? FragmentActivity)?.let { activity ->
                                val shown =
                                    runCatching {
                                        showBiometricPrompt(
                                            activity,
                                            onSuccess = { viewModel.enableBiometric { } },
                                            onError = { _, _ -> },
                                        )
                                    }.getOrDefault(false)
                                if (!shown) viewModel.enableBiometric { }
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_autolock_title)) },
                onClick = {
                    open = false
                    autoLock = true
                },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            )
        }
    }
    if (changePassword) {
        ChangePasswordDialog(
            viewModel = viewModel,
            onDismiss = { changePassword = false },
        )
    }
    if (autoLock) {
        AutoLockDialog(
            viewModel = viewModel,
            onDismiss = { autoLock = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Phase 1: setup
// ---------------------------------------------------------------------------

@Composable
private fun SetupView(viewModel: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val biometricAvailable by viewModel.biometricAvailable.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        LockTile()
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.vault_setup_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.vault_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.vault_setup_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.vault_password),
            show = showPassword,
            onToggleShow = { showPassword = !showPassword },
        )
        Spacer(Modifier.height(8.dp))
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it },
            label = stringResource(R.string.vault_password_confirm),
            show = showPassword,
            onToggleShow = { showPassword = !showPassword },
        )
        if (errorRes != null) {
            Spacer(Modifier.height(8.dp))
            errorRes?.let { err ->
                Text(
                    stringResource(err),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                errorRes = null
                viewModel.setupVault(password, confirm) { errorRes = it }
            },
            enabled = !busy && password.isNotBlank() && confirm.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.vault_setup_create))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.vault_password_min_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    RecoveryKeyDialog(viewModel)
}

// ---------------------------------------------------------------------------
// Phase 2: locked
// ---------------------------------------------------------------------------

@Composable
private fun UnlockView(viewModel: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var useRecovery by remember { mutableStateOf(false) }
    val ui by viewModel.unlockUi.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val biometricAvailable by viewModel.biometricAvailable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lockedOut = ui.lockedUntilMs > 0

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        LockTile()
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.vault_locked_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.vault_password),
            show = showPassword,
            onToggleShow = { showPassword = !showPassword },
            enabled = !lockedOut && !ui.busy,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                viewModel.unlock(password)
                password = ""
            },
            enabled = !lockedOut && !ui.busy && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.vault_unlock))
        }
        if (biometricEnabled && biometricAvailable) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    (context as? FragmentActivity)?.let { activity ->
                        runCatching {
                            showBiometricPrompt(
                                activity,
                                onSuccess = { viewModel.unlockAfterBiometricPrompt() },
                                onError = { _, _ -> },
                            )
                        }
                    }
                },
                enabled = !lockedOut && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Text(stringResource(R.string.vault_unlock_biometric), Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { useRecovery = true },
            enabled = !lockedOut && !ui.busy,
        ) {
            Text(stringResource(R.string.vault_use_recovery))
        }
        ui.attemptsLeft?.let { left ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vault_attempts_left, left),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (lockedOut) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vault_too_many_attempts, ui.remainingWaitSec),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        ui.errorRes?.let { res ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(res),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (useRecovery) {
        RecoveryKeyEntryDialog(
            viewModel = viewModel,
            onDismiss = { useRecovery = false },
        )
    }
    PasswordResetDialog(viewModel)
}

// ---------------------------------------------------------------------------
// Phase 3: unlocked
// ---------------------------------------------------------------------------

@Composable
private fun VaultContent(
    viewModel: VaultViewModel,
    onExport: (String) -> Unit,
) {
    val files by viewModel.visible.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val sortAsc by viewModel.sortAsc.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<VaultFileEntity?>(null) }
    var renameTarget by remember { mutableStateOf<VaultFileEntity?>(null) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::search,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.vault_search_hint)) },
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
            items(VaultSort.entries) { mode ->
                FilterChip(
                    selected = sortMode == mode,
                    onClick = { viewModel.setSort(mode) },
                    label = {
                        Text(
                            stringResource(mode.labelRes) +
                                if (sortMode == mode) {
                                    if (sortAsc) " ↑" else " ↓"
                                } else {
                                    ""
                                },
                        )
                    },
                )
            }
        }
        if (files.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.vault_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(files, key = { it.id }) { file ->
                    VaultFileRow(
                        file = file,
                        onOpen = {
                            viewModel.openFile(file.id) { intent ->
                                if (intent != null) {
                                    runCatching { context.startActivity(intent) }
                                }
                            }
                        },
                        onExport = { onExport(file.id) },
                        onDelete = { deleteTarget = file },
                        onRename = { renameTarget = file },
                    )
                }
            }
        }
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.vault_delete_confirm_title),
            text = stringResource(R.string.vault_delete_confirm_text, target.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.deleteFile(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onConfirm = {
                viewModel.renameFile(target.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun VaultFileRow(
    file: VaultFileEntity,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val category = runCatching { FileCategory.valueOf(file.category) }.getOrDefault(FileCategory.OTHER)
    val accent = categoryAccent(category)

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
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
                            com.arc.downloadmanager.ui.theme.categoryGradient(accent),
                            MaterialTheme.shapes.small,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fileIcon(category),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Format.bytes(file.sizeBytes) + " • " +
                        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.addedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vault_item_open)) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        },
                        leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vault_item_export)) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                        leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_rename)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                        },
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
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    show: Boolean,
    onToggleShow: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleShow) {
                Icon(
                    if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Reads a display name for a document uri; falls back to the last segment. */
fun queryDisplayName(
    context: Context,
    uri: Uri,
): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
    }.getOrNull()
