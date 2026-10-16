package com.arc.downloadmanager.data.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed wrapper for the biometric copy of the vault DEK.
 *
 * The Keystore key requires recent user authentication (biometric or device
 * credential) — every use must fall inside a short validity window that opens
 * after the user passes [com.arc.downloadmanager.ui.components.showBiometricPrompt].
 * This avoids the fragile CryptoObject paths across API levels while keeping
 * the guarantee that the wrapped DEK can only be unwrapped right after a
 * successful user authentication on this device.
 */
object VaultBiometric {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "arc_vault_bio"
    private const val AUTH_VALIDITY_SECONDS = 60

    sealed class Result {
        data class Ok(val dek: ByteArray) : Result()

        /** Key missing (never enrolled) — caller should offer to (re)create it. */
        object Missing : Result()

        /** Auth window closed / key invalidated — caller should re-prompt. */
        object NeedAuth : Result()
    }

    fun isHardwareCapable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun loadKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** Must be called right after a successful user-auth prompt (validity window open). */
    fun createAndWrap(dek: ByteArray): ByteArray? {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(false)
                .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
                .build(),
        )
        val key = generator.generateKey()
        return wrap(dek, key)
    }

    private fun wrap(
        dek: ByteArray,
        key: SecretKey,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(dek)
        return iv + ct
    }

    /** Must be called right after a successful user-auth prompt (validity window open). */
    fun unwrap(wrapped: ByteArray): Result {
        val key = loadKey() ?: return Result.Missing
        return try {
            val iv = wrapped.copyOfRange(0, 12)
            val ct = wrapped.copyOfRange(12, wrapped.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            Result.Ok(cipher.doFinal(ct))
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            Result.NeedAuth
        } catch (e: KeyPermanentlyInvalidatedException) {
            Result.NeedAuth
        } catch (e: Exception) {
            Result.NeedAuth
        }
    }

    fun removeKey() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }
}
