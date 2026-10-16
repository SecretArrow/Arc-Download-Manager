package com.arc.downloadmanager

import com.arc.downloadmanager.data.vault.VaultCrypto
import com.arc.downloadmanager.data.vault.VaultRecoveryKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

class VaultCryptoTest {
    private val dek = VaultCrypto.newDek()

    private fun roundtrip(data: ByteArray): ByteArray {
        val enc = ByteArrayOutputStream()
        VaultCrypto.encryptFile(ByteArrayInputStream(data), enc, dek, "file-1")
        val blob = enc.toByteArray()
        // header: magic + IV, then ciphertext+tag — total must exceed plaintext by overhead
        assertEquals(
            (data.size + VaultCrypto.MAGIC.length + VaultCrypto.IV_BYTES + VaultCrypto.GCM_TAG_BITS / 8).toLong(),
            blob.size.toLong(),
        )
        val dec = ByteArrayOutputStream()
        VaultCrypto.decryptFile(ByteArrayInputStream(blob), dec, dek, "file-1")
        return dec.toByteArray()
    }

    @Test
    fun `roundtrip small payload`() {
        assertArrayEquals("hello vault".toByteArray(), roundtrip("hello vault".toByteArray()))
    }

    @Test
    fun `roundtrip large payload is streamable`() {
        val data = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
        assertArrayEquals(data, roundtrip(data))
    }

    @Test
    fun `empty payload roundtrips`() {
        assertArrayEquals(ByteArray(0), roundtrip(ByteArray(0)))
    }

    @Test
    fun `wrong dek is rejected by gcm tag`() {
        val enc = ByteArrayOutputStream()
        VaultCrypto.encryptFile(ByteArrayInputStream("secret".toByteArray()), enc, dek, "file-1")
        val dec = ByteArrayOutputStream()
        assertThrows(AEADBadTagException::class.java) {
            VaultCrypto.decryptFile(ByteArrayInputStream(enc.toByteArray()), dec, VaultCrypto.newDek(), "file-1")
        }
    }

    @Test
    fun `aad mismatch is rejected`() {
        val enc = ByteArrayOutputStream()
        VaultCrypto.encryptFile(ByteArrayInputStream("secret".toByteArray()), enc, dek, "file-1")
        val dec = ByteArrayOutputStream()
        assertThrows(AEADBadTagException::class.java) {
            VaultCrypto.decryptFile(ByteArrayInputStream(enc.toByteArray()), dec, dek, "file-2")
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val enc = ByteArrayOutputStream()
        VaultCrypto.encryptFile(ByteArrayInputStream("secret".toByteArray()), enc, dek, "file-1")
        val blob = enc.toByteArray()
        blob[blob.size - 3] = (blob[blob.size - 3].toInt() xor 0x5A).toByte()
        val dec = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            VaultCrypto.decryptFile(ByteArrayInputStream(blob), dec, dek, "file-1")
        }
    }

    @Test
    fun `non vault blob is rejected`() {
        val dec = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            VaultCrypto.decryptFile(ByteArrayInputStream("not a blob".toByteArray()), dec, dek, "file-1")
        }
    }

    @Test
    fun `two encryptions of same data differ (random iv)`() {
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        VaultCrypto.encryptFile(ByteArrayInputStream("x".toByteArray()), a, dek, "f")
        VaultCrypto.encryptFile(ByteArrayInputStream("x".toByteArray()), b, dek, "f")
        assertFalse(a.toByteArray().contentEquals(b.toByteArray()))
    }

    @Test
    fun `kec derivation is deterministic and secret sensitive`() {
        val salt = VaultCrypto.newSalt()
        val k1 = VaultCrypto.deriveKek("correct horse".toCharArray(), salt, 1000)
        val k2 = VaultCrypto.deriveKek("correct horse".toCharArray(), salt, 1000)
        val k3 = VaultCrypto.deriveKek("wrong horse".toCharArray(), salt, 1000)
        assertArrayEquals(k1.encoded, k2.encoded)
        assertFalse(k1.encoded.contentEquals(k3.encoded))
    }

    @Test
    fun `wrap and unwrap dek envelope`() {
        val kek = VaultCrypto.deriveKek("password".toCharArray(), VaultCrypto.newSalt(), 1000)
        val wrapped = VaultCrypto.wrap(dek, kek)
        assertArrayEquals(dek, VaultCrypto.unwrap(wrapped, kek))
        assertNotEquals(dek, wrapped.copyOfRange(12, wrapped.size))
    }

    @Test
    fun `unwrap with wrong kek fails`() {
        val kek = VaultCrypto.deriveKek("password".toCharArray(), VaultCrypto.newSalt(), 1000)
        val other = VaultCrypto.deriveKek("different".toCharArray(), VaultCrypto.newSalt(), 1000)
        val wrapped = VaultCrypto.wrap(dek, kek)
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.unwrap(wrapped, other) }
    }

    @Test
    fun `password change rewrap keeps dek usable on files`() {
        // setup: wrap DEK under old password KEK
        val oldSalt = VaultCrypto.newSalt()
        val oldWrap = VaultCrypto.wrap(dek, VaultCrypto.deriveKek("old-password".toCharArray(), oldSalt))
        // "change password": unwrap with old, rewrap with new KEK + fresh salt
        val dekRecovered =
            VaultCrypto.unwrap(oldWrap, VaultCrypto.deriveKek("old-password".toCharArray(), oldSalt))
        val newSalt = VaultCrypto.newSalt()
        val newWrap = VaultCrypto.wrap(dekRecovered, VaultCrypto.deriveKek("new-password".toCharArray(), newSalt))
        // old files (encrypted directly with the DEK) still decrypt
        val enc = ByteArrayOutputStream()
        VaultCrypto.encryptFile(ByteArrayInputStream("still readable".toByteArray()), enc, dekRecovered, "file-9")
        val dec = ByteArrayOutputStream()
        VaultCrypto.decryptFile(
            ByteArrayInputStream(enc.toByteArray()),
            dec,
            VaultCrypto.unwrap(newWrap, VaultCrypto.deriveKek("new-password".toCharArray(), newSalt)),
            "file-9",
        )
        assertEquals("still readable", String(dec.toByteArray()))
        assertTrue(dekRecovered.contentEquals(dek))
    }
}

class VaultRecoveryKeyTest {
    @Test
    fun `generate produces 13 dash groups`() {
        val key = VaultRecoveryKey.generate()
        assertEquals(13, key.split("-").size)
        assertTrue(key.matches(Regex("([A-Z2-7]{4}-){12}[A-Z2-7]{4}")))
    }

    @Test
    fun `decode roundtrips generate`() {
        val key = VaultRecoveryKey.generate()
        val bytes = VaultRecoveryKey.decode(key)
        assertEquals(32, bytes.size)
        assertEquals(key, VaultRecoveryKey.encode(bytes))
    }

    @Test
    fun `normalize tolerates case spacing and dashes`() {
        val key = VaultRecoveryKey.generate()
        val messy = key.lowercase().replace("-", " ").replace(" ", "  - ") + " "
        assertEquals(VaultRecoveryKey.normalize(key), VaultRecoveryKey.normalize(messy))
        assertArrayEquals(VaultRecoveryKey.decode(key), VaultRecoveryKey.decode(messy))
    }

    @Test
    fun `fingerprint distinguishes keys`() {
        val a = VaultRecoveryKey.generate()
        val b = VaultRecoveryKey.generate()
        assertTrue(VaultRecoveryKey.fingerprintsMatch(VaultRecoveryKey.fingerprint(a), VaultRecoveryKey.fingerprint(a)))
        assertFalse(VaultRecoveryKey.fingerprintsMatch(VaultRecoveryKey.fingerprint(a), VaultRecoveryKey.fingerprint(b)))
    }

    @Test
    fun `decode rejects wrong length and alphabet`() {
        assertThrows(IllegalArgumentException::class.java) { VaultRecoveryKey.decode("ABCD") }
        assertThrows(IllegalArgumentException::class.java) { VaultRecoveryKey.decode("1".repeat(52)) }
    }
}
