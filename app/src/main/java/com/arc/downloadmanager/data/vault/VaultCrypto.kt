package com.arc.downloadmanager.data.vault

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault crypto core (pure JVM, unit-testable).
 *
 * Design: one random 256-bit master key (DEK) encrypts every vault file with
 * AES-256-GCM (fresh IV per file, file id bound as AAD). The DEK itself is
 * never stored in the clear — it lives only as AES-GCM "wrapped" copies:
 *
 *  - password copy  : DEK wrapped under a KEK derived from the password via
 *                     PBKDF2-HMAC-SHA256 (per-envelope random salt)
 *  - recovery copy  : same, but the KEK derives from the one-time recovery key
 *  - biometric copy : wrapped by an AndroidKeyStore key gated behind user auth
 *                     (see [VaultBiometric]; handled outside this class)
 *
 * Changing the password therefore re-wraps 32 bytes instead of re-encrypting
 * the whole vault — old files stay readable.
 *
 * Blob format (per file): MAGIC("ARCV1") | IV(12) | AES-256-GCM ciphertext,
 * tag appended by the cipher, AAD = file id UTF-8.
 */
object VaultCrypto {
    const val MAGIC = "ARCV1"
    const val PBKDF2_ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val KEY_BITS = 256
    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128

    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val CIPHER = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newDek(): ByteArray = randomBytes(KEY_BITS / 8)

    fun newSalt(): ByteArray = randomBytes(SALT_BYTES)

    /** Deterministic KEK from a human secret + salt. */
    fun deriveKek(
        secret: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): SecretKey {
        val spec = PBEKeySpec(secret, salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KDF)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            java.util.Arrays.fill(spec.password, ' ')
        }
    }

    /** AES-GCM wrap: returns IV || ciphertext||tag. */
    fun wrap(
        plain: ByteArray,
        kek: SecretKey,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    /** Inverse of [wrap]; throws AEADBadTagException on wrong key/corruption. */
    fun unwrap(
        wrapped: ByteArray,
        kek: SecretKey,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        require(wrapped.size > IV_BYTES) { "wrapped payload too short" }
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val ct = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Streams [source] into an encrypted blob on [target]. The caller measures
     * the resulting file for the encrypted size. Memory-safe for large files.
     */
    fun encryptFile(
        source: InputStream,
        target: OutputStream,
        dek: ByteArray,
        fileId: String,
    ) {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(fileId.toByteArray(Charsets.UTF_8))
        target.write(MAGIC.toByteArray(Charsets.US_ASCII))
        target.write(iv)
        val out = CipherOutputStream(target, cipher)
        source.copyTo(out, BUFFER_SIZE)
        out.flush()
        out.close()
    }

    /** Streams a vault blob into [target] (plaintext). Verifies magic + GCM tag. */
    fun decryptFile(
        source: InputStream,
        target: OutputStream,
        dek: ByteArray,
        fileId: String,
    ) {
        val magic = ByteArray(MAGIC.length)
        readFully(source, magic)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "not a vault blob" }
        val iv = ByteArray(IV_BYTES)
        readFully(source, iv)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(fileId.toByteArray(Charsets.UTF_8))
        // Manual chunked update loop instead of CipherInputStream: keeps
        // AEADBadTagException unwrapped and memory flat for large files.
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = source.read(buf)
            if (n < 0) break
            if (n > 0) {
                val dec = cipher.update(buf, 0, n)
                if (dec != null && dec.isNotEmpty()) target.write(dec)
            }
        }
        val tail = cipher.doFinal()
        if (tail != null && tail.isNotEmpty()) target.write(tail)
        target.flush()
    }

    private fun readFully(
        inp: InputStream,
        buf: ByteArray,
    ) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            require(n >= 0) { "unexpected end of blob" }
            off += n
        }
    }
}
