package com.arc.downloadmanager.data.vault

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "arc_vault")

/**
 * Persisted vault envelope: the password-wrapped / recovery-wrapped / optional
 * biometric-wrapped copies of the master DEK, plus brute-force throttle state.
 * Nothing here can decrypt anything on its own — every wrapped copy needs a
 * human secret or an authenticated Keystore.
 */
data class VaultEnvelope(
    val salt: ByteArray,
    val iterations: Int,
    val pwWrap: ByteArray,
    val recoveryWrap: ByteArray,
    val recoveryFp: ByteArray,
    val bioWrap: ByteArray?,
)

@Singleton
class VaultEnvelopeStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private object Keys {
            val SALT = stringPreferencesKey("env_salt")
            val ITER = intPreferencesKey("env_iterations")
            val PW_WRAP = stringPreferencesKey("env_pw_wrap")
            val RECOVERY_WRAP = stringPreferencesKey("env_recovery_wrap")
            val RECOVERY_FP = stringPreferencesKey("env_recovery_fp")
            val BIO_WRAP = stringPreferencesKey("env_bio_wrap")
            val FAIL_COUNT = intPreferencesKey("limit_fail_count")
            val LOCKED_UNTIL = longPreferencesKey("limit_locked_until")
            val AUTO_LOCK_MIN = intPreferencesKey("auto_lock_min")
        }

        val envelope: Flow<VaultEnvelope?> =
            context.vaultDataStore.data.map { p ->
                val salt = p[Keys.SALT] ?: return@map null
                val pw = p[Keys.PW_WRAP] ?: return@map null
                val rec = p[Keys.RECOVERY_WRAP] ?: return@map null
                val fp = p[Keys.RECOVERY_FP] ?: return@map null
                VaultEnvelope(
                    salt = b64(salt),
                    iterations = p[Keys.ITER] ?: VaultCrypto.PBKDF2_ITERATIONS,
                    pwWrap = b64(pw),
                    recoveryWrap = b64(rec),
                    recoveryFp = b64(fp),
                    bioWrap = p[Keys.BIO_WRAP]?.let { b64(it) },
                )
            }

        suspend fun currentEnvelope(): VaultEnvelope? = envelope.first()

        suspend fun save(envelope: VaultEnvelope) {
            context.vaultDataStore.edit { p ->
                p[Keys.SALT] = b64(envelope.salt)
                p[Keys.ITER] = envelope.iterations
                p[Keys.PW_WRAP] = b64(envelope.pwWrap)
                p[Keys.RECOVERY_WRAP] = b64(envelope.recoveryWrap)
                p[Keys.RECOVERY_FP] = b64(envelope.recoveryFp)
                if (envelope.bioWrap == null) p.remove(Keys.BIO_WRAP) else p[Keys.BIO_WRAP] = b64(envelope.bioWrap)
            }
        }

        suspend fun setBioWrap(wrap: ByteArray?) {
            context.vaultDataStore.edit { p ->
                if (wrap == null) p.remove(Keys.BIO_WRAP) else p[Keys.BIO_WRAP] = b64(wrap)
            }
        }

        suspend fun clearAll() {
            context.vaultDataStore.edit { it.clear() }
        }

        // --- brute-force limiter state ---

        val limiterState: Flow<Pair<Int, Long>> =
            context.vaultDataStore.data.map { p -> (p[Keys.FAIL_COUNT] ?: 0) to (p[Keys.LOCKED_UNTIL] ?: 0L) }

        suspend fun currentLimiterState(): Pair<Int, Long> = limiterState.first()

        suspend fun saveLimiterState(
            failCount: Int,
            lockedUntil: Long,
        ) {
            context.vaultDataStore.edit { p ->
                p[Keys.FAIL_COUNT] = failCount
                p[Keys.LOCKED_UNTIL] = lockedUntil
            }
        }

        // --- auto-lock setting ---

        val autoLockMinutes: Flow<Int> = context.vaultDataStore.data.map { p -> p[Keys.AUTO_LOCK_MIN] ?: 1 }

        suspend fun setAutoLockMinutes(minutes: Int) {
            context.vaultDataStore.edit { it[Keys.AUTO_LOCK_MIN] = minutes.coerceIn(0, 60) }
        }

        private fun b64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

        private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    }
