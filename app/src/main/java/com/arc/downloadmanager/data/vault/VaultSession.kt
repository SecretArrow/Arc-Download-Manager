package com.arc.downloadmanager.data.vault

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Lifecycle phase of the vault. */
sealed class VaultLockState {
    /** No envelope exists yet — first-run setup. */
    object NoVault : VaultLockState()

    /** Envelope exists, DEK not in memory. */
    object Locked : VaultLockState()

    /** DEK in memory; files decryptable until [lock]. */
    data class Unlocked(val unlockedAtMs: Long) : VaultLockState()
}

/**
 * Holds the vault master key (DEK) strictly in memory while unlocked and
 * enforces auto-lock:
 *
 *  - background: when the app leaves the foreground for longer than the
 *    configured auto-lock window (0 = immediately), the vault locks;
 *  - idle: while unlocked, an inactivity ticker re-locks after the same
 *    window with no vault interaction (floored at 2 minutes so the vault is
 *    not yanked away mid-read when the user chose "immediate on background").
 *
 * Locking zeroes the key and deletes temporary decrypted open-copies, so
 * process death / app close can never leave plaintext behind.
 */
@Singleton
class VaultSession
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
        private val envelopeStore: VaultEnvelopeStore,
    ) : DefaultLifecycleObserver {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _state = MutableStateFlow<VaultLockState>(VaultLockState.NoVault)
        val state: StateFlow<VaultLockState> = _state.asStateFlow()

        /** Auto-lock window in minutes; refreshed from the envelope store. */
        @Volatile
        var autoLockMinutes: Int = 1
            private set

        @Volatile
        private var dek: ByteArray? = null

        @Volatile
        private var lastActivityMs: Long = 0L

        @Volatile
        private var backgroundedAtMs: Long = 0L

        private val tempOpenDirs = mutableListOf<File>()

        /** Called once from [com.arc.downloadmanager.ArcApp] to hook process lifecycle. */
        fun attach(owner: LifecycleOwner) {
            owner.lifecycle.addObserver(this)
            // A previous process may have died with decrypted files still in the open cache
            // (in-memory temp tracking dies with the process) — sweep them before anything
            // can observe them.
            runCatching { java.io.File(context.cacheDir, "vault_open").deleteRecursively() }
            scope.launch {
                refreshAutoLock()
                _state.value =
                    if (envelopeStore.currentEnvelope() == null) VaultLockState.NoVault else VaultLockState.Locked
            }
            scope.launch {
                while (true) {
                    delay(15_000)
                    if (_state.value !is VaultLockState.Unlocked) continue
                    val idleLimitMs = (if (autoLockMinutes <= 0) 2 else autoLockMinutes) * 60_000L
                    if (System.currentTimeMillis() - lastActivityMs > idleLimitMs) lock()
                }
            }
        }

        suspend fun refreshAutoLock() {
            autoLockMinutes = envelopeStore.autoLockMinutes.first()
        }

        fun peekDek(): ByteArray? {
            return if (_state.value is VaultLockState.Unlocked) dek else null
        }

        fun markUnlocked(newDek: ByteArray) {
            dek = newDek.copyOf()
            lastActivityMs = System.currentTimeMillis()
            _state.value = VaultLockState.Unlocked(lastActivityMs)
        }

        /** Re-arms the idle timer after any vault interaction. */
        fun touch() {
            lastActivityMs = System.currentTimeMillis()
        }

        fun markNoVault() {
            dek?.fill(0)
            dek = null
            _state.value = VaultLockState.NoVault
            cleanupTempFiles()
        }

        fun lock() {
            dek?.fill(0)
            dek = null
            if (_state.value != VaultLockState.NoVault) _state.value = VaultLockState.Locked
            cleanupTempFiles()
        }

        /**
         * Registers a decrypted temp directory (an "open" copy) so it can be
         * wiped when the vault locks or the app restarts.
         */
        fun trackTempDir(dir: File) {
            synchronized(tempOpenDirs) { tempOpenDirs.add(dir) }
        }

        fun wipeTempDir(dir: File) {
            dir.deleteRecursively()
            synchronized(tempOpenDirs) { tempOpenDirs.remove(dir) }
        }

        private fun cleanupTempFiles() {
            synchronized(tempOpenDirs) {
                tempOpenDirs.forEach { it.deleteRecursively() }
                tempOpenDirs.clear()
            }
        }

        // --- process lifecycle (auto-lock on background) ---

        override fun onStop(owner: LifecycleOwner) {
            backgroundedAtMs = System.currentTimeMillis()
            if (_state.value is VaultLockState.Unlocked && autoLockMinutes <= 0) lock()
        }

        override fun onStart(owner: LifecycleOwner) {
            val bg = backgroundedAtMs
            backgroundedAtMs = 0
            if (bg == 0L) return
            if (_state.value !is VaultLockState.Unlocked) return
            val limitMs = autoLockMinutes.coerceAtLeast(0) * 60_000L
            if (System.currentTimeMillis() - bg > limitMs) lock()
        }
    }
