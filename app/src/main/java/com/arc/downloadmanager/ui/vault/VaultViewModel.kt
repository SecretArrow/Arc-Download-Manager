package com.arc.downloadmanager.ui.vault

import android.content.Context
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arc.downloadmanager.data.vault.PendingVaultImport
import com.arc.downloadmanager.data.vault.VaultEnvelopeStore
import com.arc.downloadmanager.data.vault.VaultImportRequest
import com.arc.downloadmanager.data.vault.VaultImportResult
import com.arc.downloadmanager.data.vault.VaultLockState
import com.arc.downloadmanager.data.vault.VaultRepository
import com.arc.downloadmanager.data.vault.VaultSession
import com.arc.downloadmanager.data.vault.VaultUnlockResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Sort options for the vault file list. */
enum class VaultSort(val labelRes: Int) {
    DATE(com.arc.downloadmanager.R.string.vault_sort_date),
    NAME(com.arc.downloadmanager.R.string.vault_sort_name),
    SIZE(com.arc.downloadmanager.R.string.vault_sort_size),
}

/** One-shot snackbar events. */
data class VaultToast(val res: Int, val arg: String? = null)

@HiltViewModel
class VaultViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val repository: VaultRepository,
        private val session: VaultSession,
        private val envelopeStore: VaultEnvelopeStore,
    ) : ViewModel() {
        val lockState: StateFlow<VaultLockState> =
            repository.lockState.stateIn(viewModelScope, SharingStarted.Eagerly, VaultLockState.Locked)

        /** All files as stored (repository order); filtering/sorting applied in [visible]. */
        private val query = MutableStateFlow("")
        private val sort = MutableStateFlow(VaultSort.DATE)
        private val sortAscending = MutableStateFlow(false)

        val visible: StateFlow<List<com.arc.downloadmanager.data.db.VaultFileEntity>> =
            combine(repository.files, query, sort, sortAscending) { files, q, s, asc ->
                val filtered =
                    files.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
                val cmp =
                    when (s) {
                        VaultSort.NAME -> compareBy<com.arc.downloadmanager.data.db.VaultFileEntity> { it.name.lowercase() }
                        VaultSort.SIZE -> compareBy { it.sizeBytes }
                        VaultSort.DATE -> compareBy { it.addedAt }
                    }
                if (asc) filtered.sortedWith(cmp) else filtered.sortedWith(cmp.reversed())
            }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val queryText: StateFlow<String> = query.asStateFlow()
        val sortMode: StateFlow<VaultSort> = sort.asStateFlow()
        val sortAsc: StateFlow<Boolean> = sortAscending.asStateFlow()

        private val _events = MutableSharedFlow<VaultToast>(extraBufferCapacity = 8)
        val events = _events.asSharedFlow()

        // ---- unlock screen state ----
        data class UnlockUi(
            val busy: Boolean = false,
            val attemptsLeft: Int? = null,
            val lockedUntilMs: Long = 0L,
            val remainingWaitSec: Int = 0,
            val errorRes: Int? = null,
        )

        private val _unlockUi = MutableStateFlow(UnlockUi())
        val unlockUi: StateFlow<UnlockUi> = _unlockUi.asStateFlow()

        // ---- setup flow ----
        val biometricEnabled = MutableStateFlow(false)
        val biometricAvailable = MutableStateFlow(false)
        val autoLockMinutes = MutableStateFlow(1)

        /** Recovery key surfaced exactly once at setup, until acknowledged. */
        private val _recoveryKey = MutableStateFlow<String?>(null)
        val recoveryKey: StateFlow<String?> = _recoveryKey.asStateFlow()

        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        private var lockoutTicker: Job? = null
        private var recoveryPending = false
        private val pendingAfterUnlockHandled = AtomicBoolean(false)

        init {
            viewModelScope.launch {
                biometricEnabled.value = runCatching { repository.biometricEnabled() }.getOrDefault(false)
                biometricAvailable.value = runCatching { probeBiometric() }.getOrDefault(false)
                autoLockMinutes.value = repository.autoLockMinutes()
            }
            // After unlocking, flush any import that was parked while locked.
            viewModelScope.launch {
                lockState.collect { st ->
                    if (st is VaultLockState.Unlocked) {
                        session.touch()
                        processPendingImport()
                    }
                }
            }
            // Auto-lock setting can change mid-session from another screen.
            envelopeStore.autoLockMinutes.onEach { autoLockMinutes.value = it }.launchIn(viewModelScope)
        }

        private suspend fun probeBiometric(): Boolean {
            val mgr = BiometricManager.from(appContext)
            val res = mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            return res == BiometricManager.BIOMETRIC_SUCCESS
        }

        // ------------------------------------------------------------------
        // Setup
        // ------------------------------------------------------------------

        fun setupVault(
            password: String,
            confirm: String,
            onError: (Int) -> Unit,
        ) {
            if (password.length < VaultRepository.MIN_PASSWORD_LEN) {
                onError(com.arc.downloadmanager.R.string.vault_password_min)
                return
            }
            if (password != confirm) {
                onError(com.arc.downloadmanager.R.string.vault_password_mismatch)
                return
            }
            _busy.value = true
            viewModelScope.launch {
                try {
                    val key = repository.setup(password.toCharArray())
                    recoveryPending = true
                    _recoveryKey.value = key
                } catch (t: Throwable) {
                    onError(com.arc.downloadmanager.R.string.vault_error_generic)
                } finally {
                    _busy.value = false
                }
            }
        }

        fun acknowledgeRecoveryKey() {
            _recoveryKey.value = null
            recoveryPending = false
        }

        // ------------------------------------------------------------------
        // Unlock
        // ------------------------------------------------------------------

        fun unlock(password: String) {
            if (_unlockUi.value.busy) return
            _unlockUi.value = UnlockUi(busy = true)
            viewModelScope.launch {
                when (val r = repository.unlock(password.toCharArray())) {
                    is VaultUnlockResult.Ok -> _unlockUi.value = UnlockUi()
                    is VaultUnlockResult.WrongSecret ->
                        _unlockUi.value =
                            UnlockUi(attemptsLeft = r.attemptsLeft, errorRes = com.arc.downloadmanager.R.string.vault_wrong_password)
                    is VaultUnlockResult.LockedOut -> startLockout(r.waitMs)
                    else -> _unlockUi.value = UnlockUi(errorRes = com.arc.downloadmanager.R.string.vault_error_generic)
                }
            }
        }

        /** Called right after the biometric prompt succeeds (auth window open). */
        fun unlockAfterBiometricPrompt() {
            if (_unlockUi.value.busy) return
            _unlockUi.value = UnlockUi(busy = true)
            viewModelScope.launch {
                when (val r = repository.unlockWithBiometric()) {
                    is VaultUnlockResult.Ok -> _unlockUi.value = UnlockUi()
                    is VaultUnlockResult.LockedOut -> startLockout(r.waitMs)
                    else -> _unlockUi.value = UnlockUi(errorRes = com.arc.downloadmanager.R.string.vault_error_generic)
                }
            }
        }

        fun unlockWithRecoveryKey(key: String) {
            if (_unlockUi.value.busy) return
            _unlockUi.value = UnlockUi(busy = true)
            viewModelScope.launch {
                when (val r = repository.unlockWithRecoveryKey(key)) {
                    is VaultUnlockResult.Ok -> {
                        _unlockUi.value = UnlockUi()
                        // They just proved they lost the password: invite a reset.
                        _recoveryKey.value = null
                        passwordResetAfterRecovery.value = true
                    }
                    is VaultUnlockResult.WrongSecret ->
                        _unlockUi.value =
                            UnlockUi(attemptsLeft = r.attemptsLeft, errorRes = com.arc.downloadmanager.R.string.vault_recovery_invalid)
                    is VaultUnlockResult.LockedOut -> startLockout(r.waitMs)
                    else -> _unlockUi.value = UnlockUi(errorRes = com.arc.downloadmanager.R.string.vault_recovery_invalid)
                }
            }
        }

        /** True right after a recovery-key unlock: UI offers to set a new password. */
        val passwordResetAfterRecovery = MutableStateFlow(false)

        fun completePasswordReset(
            newPassword: String,
            onError: (Int) -> Unit,
        ) {
            if (newPassword.length < VaultRepository.MIN_PASSWORD_LEN) {
                onError(com.arc.downloadmanager.R.string.vault_password_min)
                return
            }
            viewModelScope.launch {
                val dekInSession = session.peekDek()
                if (dekInSession == null) {
                    onError(com.arc.downloadmanager.R.string.vault_error_generic)
                    return@launch
                }
                repository.resetPasswordWithCurrentDek(newPassword.toCharArray())
                passwordResetAfterRecovery.value = false
                _events.emit(VaultToast(com.arc.downloadmanager.R.string.vault_change_success))
            }
        }

        fun dismissPasswordReset() {
            passwordResetAfterRecovery.value = false
        }

        private fun startLockout(waitMs: Long) {
            val until = System.currentTimeMillis() + waitMs
            _unlockUi.value = UnlockUi(lockedUntilMs = until, remainingWaitSec = ((waitMs + 999) / 1000).toInt())
            lockoutTicker?.cancel()
            lockoutTicker =
                viewModelScope.launch {
                    while (true) {
                        delay(1_000)
                        val left = until - System.currentTimeMillis()
                        if (left <= 0) {
                            _unlockUi.value = UnlockUi()
                            break
                        }
                        _unlockUi.value = _unlockUi.value.copy(remainingWaitSec = ((left + 999) / 1000).toInt())
                    }
                }
        }

        // ------------------------------------------------------------------
        // Unlocked: files
        // ------------------------------------------------------------------

        fun search(q: String) {
            query.value = q
            session.touch()
        }

        fun setSort(mode: VaultSort) {
            if (sort.value == mode) sortAscending.value = !sortAscending.value else sort.value = mode
            session.touch()
        }

        fun touch() = session.touch()

        /** Entry point used by Downloads/Files screens; parks the import when locked. */
        fun requestImport(
            uri: Uri,
            name: String,
            mime: String?,
            onNavigate: (VaultImportRequest) -> Unit,
        ) {
            viewModelScope.launch {
                val request = repository.requestImport(uri, name, mime)
                onNavigate(request)
                if (request is com.arc.downloadmanager.data.vault.VaultImportRequest.Accepted) {
                    runPendingImportNow()
                }
            }
        }

        private fun processPendingImport() {
            val pending = repository.pendingImport ?: return
            if (!pendingAfterUnlockHandled.compareAndSet(false, true)) return
            repository.pendingImport = null
            viewModelScope.launch {
                importInternal(pending)
                pendingAfterUnlockHandled.set(false)
            }
        }

        private suspend fun runPendingImportNow() {
            val pending = repository.pendingImport ?: return
            repository.pendingImport = null
            importInternal(pending)
        }

        /** Imports straight from a picker result while the vault is open. */
        fun importFromPicker(
            uri: Uri,
            name: String,
            mime: String?,
        ) {
            _busy.value = true
            viewModelScope.launch {
                importInternal(PendingVaultImport(uri, name, mime))
                _busy.value = false
            }
        }

        private suspend fun importInternal(pending: PendingVaultImport) {
            session.touch()
            when (val r = repository.importFromUri(pending.sourceUri, pending.displayName, pending.mimeType)) {
                is VaultImportResult.Ok -> _events.emit(VaultToast(com.arc.downloadmanager.R.string.vault_saved_to_vault, r.fileName))
                is VaultImportResult.Failure -> _events.emit(VaultToast(com.arc.downloadmanager.R.string.vault_import_failed))
            }
        }

        fun openFile(
            id: String,
            onIntent: (android.content.Intent?) -> Unit,
        ) {
            viewModelScope.launch {
                val entity = repository.getById(id)
                val intent = entity?.let { repository.buildOpenIntent(it) }
                if (intent == null) _events.emit(VaultToast(com.arc.downloadmanager.R.string.vault_open_failed))
                onIntent(intent)
            }
        }

        fun deleteFile(id: String) {
            viewModelScope.launch {
                repository.delete(id)
                _events.emit(VaultToast(com.arc.downloadmanager.R.string.vault_deleted))
            }
        }

        fun renameFile(
            id: String,
            name: String,
        ) {
            viewModelScope.launch { repository.rename(id, name) }
        }

        fun exportTo(
            id: String,
            target: Uri,
        ) {
            _busy.value = true
            viewModelScope.launch {
                val ok = repository.exportTo(id, target)
                _busy.value = false
                _events.emit(
                    VaultToast(if (ok) com.arc.downloadmanager.R.string.vault_export_done else com.arc.downloadmanager.R.string.vault_error_generic),
                )
            }
        }

        // ------------------------------------------------------------------
        // Unlocked: settings actions
        // ------------------------------------------------------------------

        fun lockNow() = repository.lockNow()

        fun changePassword(
            old: String,
            new: String,
            confirm: String,
            onError: (Int) -> Unit,
            onSuccess: () -> Unit,
        ) {
            if (new.length < VaultRepository.MIN_PASSWORD_LEN) {
                onError(com.arc.downloadmanager.R.string.vault_password_min)
                return
            }
            if (new != confirm) {
                onError(com.arc.downloadmanager.R.string.vault_password_mismatch)
                return
            }
            viewModelScope.launch {
                when (val r = repository.changePassword(old.toCharArray(), new.toCharArray())) {
                    is VaultUnlockResult.Ok -> {
                        _events.emit(VaultToast(com.arc.downloadmanager.R.string.vault_change_success))
                        onSuccess()
                    }
                    else -> onError(com.arc.downloadmanager.R.string.vault_wrong_password)
                }
            }
        }

        /** UI must have shown the biometric prompt just before calling this. */
        fun enableBiometric(onDone: (Boolean) -> Unit) {
            viewModelScope.launch {
                val ok = runCatching { repository.enableBiometric() }.getOrDefault(false)
                if (ok) biometricEnabled.value = true
                onDone(ok)
            }
        }

        fun disableBiometric() {
            viewModelScope.launch {
                runCatching { repository.disableBiometric() }
                biometricEnabled.value = false
            }
        }

        fun setAutoLockMinutes(minutes: Int) {
            viewModelScope.launch { repository.setAutoLockMinutes(minutes) }
        }

        fun summaryLabel(
            totalFiles: Int,
            totalBytes: Long,
        ): String = appContext.getString(com.arc.downloadmanager.R.string.vault_total_n_files, totalFiles, totalBytes)

        /** True while a brute-force lockout is counting down. */
        fun isLockedOut(): Boolean = _unlockUi.value.lockedUntilMs > System.currentTimeMillis()
    }
