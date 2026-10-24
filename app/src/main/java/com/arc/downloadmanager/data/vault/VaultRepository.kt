package com.arc.downloadmanager.data.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.arc.downloadmanager.data.db.VaultFileDao
import com.arc.downloadmanager.data.db.VaultFileEntity
import com.arc.downloadmanager.data.repository.FileRepository
import com.arc.downloadmanager.engine.FilenameDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of an unlock attempt (password, recovery key, or biometric). */
sealed class VaultUnlockResult {
    object Ok : VaultUnlockResult()

    /** Wrong secret; [attemptsLeft] counts down to the next lockout. */
    data class WrongSecret(val attemptsLeft: Int) : VaultUnlockResult()

    /** Brute-force throttle engaged; retry after [waitMs]. */
    data class LockedOut(val waitMs: Long) : VaultUnlockResult()

    object InvalidRecoveryKey : VaultUnlockResult()

    /** Biometric unlock attempted but no Keystore key exists yet. */
    object BiometricMissing : VaultUnlockResult()

    /** Auth window not open — the UI must (re)show the biometric prompt first. */
    object BiometricNeedAuth : VaultUnlockResult()
}

/** What happened when the user asked to save a file into the vault. */
sealed class VaultImportRequest {
    object NeedsSetup : VaultImportRequest()

    /** Vault exists but is locked: import parked until unlock; UI should navigate there. */
    object NeedsUnlock : VaultImportRequest()

    /** Import accepted (running or queued in the caller). */
    object Accepted : VaultImportRequest()
}

/** Outcome of actually importing one file into the vault. */
sealed class VaultImportResult {
    data class Ok(val fileName: String, val sizeBytes: Long) : VaultImportResult()

    data class Failure(val reason: Throwable) : VaultImportResult()
}

/** A file waiting to be vaulted right after the user unlocks the vault. */
data class PendingVaultImport(
    val sourceUri: Uri,
    val displayName: String,
    val mimeType: String?,
)

/**
 * Facade over the encrypted vault: envelope management (setup / unlock /
 * password change / biometric / recovery) and file operations (import /
 * export / open / delete / rename). Heavy work runs on Dispatchers.IO.
 */
@Singleton
class VaultRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: VaultFileDao,
        private val envelopeStore: VaultEnvelopeStore,
        private val session: VaultSession,
    ) {
        val files: Flow<List<VaultFileEntity>> = dao.observeAll()

        val lockState: Flow<VaultLockState> = session.state

        @Volatile
        var pendingImport: PendingVaultImport? = null

        suspend fun hasVault(): Boolean = envelopeStore.currentEnvelope() != null

        suspend fun autoLockMinutes(): Int = envelopeStore.autoLockMinutes.first()

        suspend fun setAutoLockMinutes(minutes: Int) {
            envelopeStore.setAutoLockMinutes(minutes)
            session.refreshAutoLock()
        }

        // ------------------------------------------------------------------
        // Envelope lifecycle
        // ------------------------------------------------------------------

        /**
         * Creates the vault: generates the DEK, wraps it under the password
         * and under a fresh recovery key, persists the envelope and unlocks
         * the session. Returns the recovery key to show to the user ONCE.
         */
        suspend fun setup(password: CharArray): String {
            require(password.size >= MIN_PASSWORD_LEN) { "password too short" }
            val dek = VaultCrypto.newDek()
            val salt = VaultCrypto.newSalt()
            val recovery = VaultRecoveryKey.generate()
            val pwWrap = VaultCrypto.wrap(dek, VaultCrypto.deriveKek(password, salt))
            val recWrap = VaultCrypto.wrap(dek, VaultCrypto.deriveKek(VaultRecoveryKey.kekSecret(recovery), salt))
            envelopeStore.save(
                VaultEnvelope(
                    salt = salt,
                    iterations = VaultCrypto.PBKDF2_ITERATIONS,
                    pwWrap = pwWrap,
                    recoveryWrap = recWrap,
                    recoveryFp = VaultRecoveryKey.fingerprint(recovery),
                    bioWrap = null,
                ),
            )
            envelopeStore.saveLimiterState(0, 0L)
            vaultDir().mkdirs()
            File(vaultDir(), NOMEDIA).writeBytes(ByteArray(0))
            session.refreshAutoLock()
            session.markUnlocked(dek)
            return recovery
        }

        suspend fun unlock(password: CharArray): VaultUnlockResult {
            val envelope = envelopeStore.currentEnvelope() ?: return VaultUnlockResult.InvalidRecoveryKey
            val (fails, lockedUntil) = envelopeStore.currentLimiterState()
            val decision = VaultAttemptLimiter.decide(System.currentTimeMillis(), fails, lockedUntil)
            if (!decision.allowed) return VaultUnlockResult.LockedOut(decision.waitMs)
            return try {
                val kek = VaultCrypto.deriveKek(password, envelope.salt, envelope.iterations)
                val dek = VaultCrypto.unwrap(envelope.pwWrap, kek)
                envelopeStore.saveLimiterState(0, 0L)
                session.markUnlocked(dek)
                VaultUnlockResult.Ok
            } catch (e: Exception) {
                registerFail(fails)
            }
        }

        suspend fun unlockWithRecoveryKey(displayKey: String): VaultUnlockResult {
            val envelope = envelopeStore.currentEnvelope() ?: return VaultUnlockResult.InvalidRecoveryKey
            val (fails, lockedUntil) = envelopeStore.currentLimiterState()
            val decision = VaultAttemptLimiter.decide(System.currentTimeMillis(), fails, lockedUntil)
            if (!decision.allowed) return VaultUnlockResult.LockedOut(decision.waitMs)
            if (!VaultRecoveryKey.fingerprintsMatch(VaultRecoveryKey.fingerprint(displayKey), envelope.recoveryFp)) {
                return registerFail(fails)
            }
            return try {
                val kek = VaultCrypto.deriveKek(VaultRecoveryKey.kekSecret(displayKey), envelope.salt, envelope.iterations)
                val dek = VaultCrypto.unwrap(envelope.recoveryWrap, kek)
                envelopeStore.saveLimiterState(0, 0L)
                session.markUnlocked(dek)
                VaultUnlockResult.Ok
            } catch (e: Exception) {
                registerFail(fails)
            }
        }

        /**
         * Unlocks via the AndroidKeyStore-wrapped DEK copy. The UI must show
         * the biometric prompt FIRST so the Keystore auth window is open.
         */
        suspend fun unlockWithBiometric(): VaultUnlockResult {
            val envelope = envelopeStore.currentEnvelope() ?: return VaultUnlockResult.InvalidRecoveryKey
            val wrapped = envelope.bioWrap ?: return VaultUnlockResult.BiometricMissing
            return when (val r = VaultBiometric.unwrap(wrapped)) {
                is VaultBiometric.Result.Ok -> {
                    envelopeStore.saveLimiterState(0, 0L)
                    session.markUnlocked(r.dek)
                    VaultUnlockResult.Ok
                }
                VaultBiometric.Result.Missing -> VaultUnlockResult.BiometricMissing
                VaultBiometric.Result.NeedAuth -> VaultUnlockResult.BiometricNeedAuth
            }
        }

        fun lockNow() = session.lock()

        suspend fun changePassword(
            old: CharArray,
            new: CharArray,
        ): VaultUnlockResult {
            val envelope = envelopeStore.currentEnvelope() ?: return VaultUnlockResult.InvalidRecoveryKey
            require(new.size >= MIN_PASSWORD_LEN) { "password too short" }
            val dek =
                try {
                    VaultCrypto.unwrap(envelope.pwWrap, VaultCrypto.deriveKek(old, envelope.salt, envelope.iterations))
                } catch (e: Exception) {
                    return VaultUnlockResult.WrongSecret(attemptsLeft = 0)
                }
            // Fresh salt for the new KEK. Only the password wrap changes — the
            // DEK is untouched, so existing files (and the recovery/biometric
            // wraps) remain valid.
            val salt = VaultCrypto.newSalt()
            val newWrap = VaultCrypto.wrap(dek, VaultCrypto.deriveKek(new, salt))
            envelopeStore.save(envelope.copy(salt = salt, pwWrap = newWrap))
            return VaultUnlockResult.Ok
        }

        /** Wraps the DEK under a fresh AndroidKeyStore key. UI prompts biometrics first. */
        suspend fun enableBiometric(): Boolean {
            val dek = session.peekDek() ?: return false
            val wrap = VaultBiometric.createAndWrap(dek) ?: return false
            envelopeStore.setBioWrap(wrap)
            return true
        }

        suspend fun disableBiometric() {
            envelopeStore.setBioWrap(null)
            VaultBiometric.removeKey()
        }

        suspend fun biometricEnabled(): Boolean = envelopeStore.currentEnvelope()?.bioWrap != null

        // ------------------------------------------------------------------
        // Files
        // ------------------------------------------------------------------

        fun observeFiles(): Flow<List<VaultFileEntity>> = dao.observeAll()

        suspend fun getById(id: String): VaultFileEntity? = dao.getById(id)

        /**
         * Entry point for the "Save to Vault" buttons on Downloads/Files
         * screens: accepts, or parks the file until the vault is unlocked.
         */
        suspend fun requestImport(
            sourceUri: Uri,
            displayName: String,
            mimeType: String?,
        ): VaultImportRequest =
            when {
                !hasVault() -> VaultImportRequest.NeedsSetup
                session.peekDek() == null -> {
                    pendingImport = PendingVaultImport(sourceUri, displayName, mimeType)
                    VaultImportRequest.NeedsUnlock
                }
                else -> VaultImportRequest.Accepted
            }

        /** Re-wraps the password copy from the in-memory DEK (after recovery unlock). */
        suspend fun resetPasswordWithCurrentDek(new: CharArray): Boolean {
            val dek = session.peekDek() ?: return false
            val envelope = envelopeStore.currentEnvelope() ?: return false
            val salt = VaultCrypto.newSalt()
            val newWrap = VaultCrypto.wrap(dek, VaultCrypto.deriveKek(new, salt))
            envelopeStore.save(envelope.copy(salt = salt, pwWrap = newWrap))
            return true
        }

        /** One-shot "Save to Vault" from outside the vault screen. */
        sealed class ExternalSaveResult {
            object NeedsSetup : ExternalSaveResult()

            object NeedsUnlock : ExternalSaveResult()

            data class Done(val name: String) : ExternalSaveResult()

            object Failed : ExternalSaveResult()
        }

        suspend fun saveFromExternal(
            sourceUri: Uri,
            displayName: String,
            mimeType: String?,
        ): ExternalSaveResult =
            when (requestImport(sourceUri, displayName, mimeType)) {
                VaultImportRequest.NeedsSetup -> ExternalSaveResult.NeedsSetup
                VaultImportRequest.NeedsUnlock -> ExternalSaveResult.NeedsUnlock
                VaultImportRequest.Accepted ->
                    when (val r = importFromUri(sourceUri, displayName, mimeType)) {
                        is VaultImportResult.Ok -> ExternalSaveResult.Done(r.fileName)
                        is VaultImportResult.Failure -> ExternalSaveResult.Failed
                    }
            }

        suspend fun importFromUri(
            sourceUri: Uri,
            displayName: String,
            mimeType: String?,
        ): VaultImportResult {
            val dek = session.peekDek() ?: return VaultImportResult.Failure(IllegalStateException("vault locked"))
            val id = UUID.randomUUID().toString()
            val blob = File(vaultDir(), "$id.avault")
            return try {
                val inp =
                    context.contentResolver.openInputStream(sourceUri)
                        ?: return VaultImportResult.Failure(IllegalStateException("cannot open source"))
                inp.use { stream ->
                    blob.outputStream().use { out ->
                        VaultCrypto.encryptFile(stream, out, dek, id)
                    }
                }
                val encSize = blob.length()
                val plainSize = (encSize - PLAINTEXT_OVERHEAD).coerceAtLeast(0)
                val entity =
                    VaultFileEntity(
                        id = id,
                        name = displayName.ifBlank { "file" },
                        mimeType = mimeType,
                        category = FileRepository.categorize(displayName, mimeType).name,
                        sizeBytes = plainSize,
                        encSizeBytes = encSize,
                        blobPath = blob.name,
                        addedAt = System.currentTimeMillis(),
                    )
                dao.insert(entity)
                session.touch()
                VaultImportResult.Ok(entity.name, entity.sizeBytes)
            } catch (t: Throwable) {
                blob.delete()
                VaultImportResult.Failure(t)
            }
        }

        /** Decrypts into a SAF target chosen by the user (ACTION_CREATE_DOCUMENT). */
        suspend fun exportTo(
            fileId: String,
            target: Uri,
        ): Boolean {
            val dek = session.peekDek() ?: return false
            val entity = dao.getById(fileId) ?: return false
            val blob = File(vaultDir(), entity.blobPath)
            return try {
                val out = context.contentResolver.openOutputStream(target) ?: return false
                out.use { o ->
                    blob.inputStream().use { inp ->
                        VaultCrypto.decryptFile(inp, o, dek, entity.id)
                    }
                }
                dao.touchOpened(fileId, System.currentTimeMillis())
                session.touch()
                true
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * Decrypts to an app-private cache dir and builds a view intent via
         * FileProvider. The temp copy is wiped when the vault locks.
         */
        suspend fun buildOpenIntent(file: VaultFileEntity): Intent? {
            val dek = session.peekDek() ?: return null
            val blob = File(vaultDir(), file.blobPath)
            if (!blob.exists()) return null
            val dir = File(context.cacheDir, "vault_open/${UUID.randomUUID()}").apply { mkdirs() }
            val out = File(dir, file.name)
            return try {
                blob.inputStream().use { inp ->
                    out.outputStream().use { o ->
                        VaultCrypto.decryptFile(inp, o, dek, file.id)
                    }
                }
                session.trackTempDir(dir)
                dao.touchOpened(file.id, System.currentTimeMillis())
                session.touch()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, FilenameDetector.guessMime(file.name) ?: file.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (t: Throwable) {
                session.wipeTempDir(dir)
                null
            }
        }

        suspend fun delete(fileId: String) {
            val entity = dao.getById(fileId)
            if (entity != null) {
                File(vaultDir(), entity.blobPath).delete()
                dao.delete(fileId)
            }
            session.touch()
        }

        suspend fun rename(
            fileId: String,
            newName: String,
        ) {
            val entity = dao.getById(fileId)
            val cleaned = newName.trim().ifBlank { "file" }
            // Keep the extension: viewers/installers dispatch on it when the decrypted
            // temp copy is opened, and a renamed "movie" without ".mp4" confuses them.
            val final =
                if (entity != null &&
                    !cleaned.contains('.') &&
                    entity.name.contains('.')
                ) {
                    cleaned + entity.name.substring(entity.name.lastIndexOf('.'))
                } else {
                    cleaned
                }
            dao.rename(fileId, final)
            session.touch()
        }

        fun vaultDir(): File = File(context.filesDir, "vault")

        private suspend fun registerFail(fails: Int): VaultUnlockResult {
            val lockedUntil = VaultAttemptLimiter.onFail(System.currentTimeMillis(), fails)
            envelopeStore.saveLimiterState(fails + 1, lockedUntil)
            return if (lockedUntil > 0) {
                VaultUnlockResult.LockedOut((lockedUntil - System.currentTimeMillis()).coerceAtLeast(0))
            } else {
                VaultUnlockResult.WrongSecret(VaultAttemptLimiter.FREE_ATTEMPTS - (fails + 1))
            }
        }

        companion object {
            const val MIN_PASSWORD_LEN = 4
            const val NOMEDIA = ".nomedia"

            /** MAGIC("ARCV1") + IV(12) + GCM tag(16). */
            val PLAINTEXT_OVERHEAD: Long =
                VaultCrypto.MAGIC.length + VaultCrypto.IV_BYTES + (VaultCrypto.GCM_TAG_BITS / 8).toLong()
        }
    }
