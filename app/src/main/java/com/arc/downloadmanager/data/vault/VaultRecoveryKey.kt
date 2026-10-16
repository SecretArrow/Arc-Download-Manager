package com.arc.downloadmanager.data.vault

import java.security.MessageDigest

/**
 * One-time recovery key for the vault: 32 random bytes rendered as Base32
 * (RFC 4648, Crockford-ambiguous letters excluded from the alphabet we use),
 * grouped for readability: "XXXX-XXXX-XXXX-...".
 *
 * We store only SHA-256 of the normalized key + a DEK copy wrapped under a
 * KEK derived from it. Losing BOTH the password and this key means the vault
 * is unrecoverable by design — there is no escrow anywhere.
 */
object VaultRecoveryKey {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Generates a fresh display key: 13 groups of 4 chars separated by '-'. */
    fun generate(): String {
        val bytes = VaultCrypto.randomBytes(32)
        return encode(bytes)
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        // group in 4s with '-'
        val raw = sb.toString()
        return raw.chunked(4).joinToString("-")
    }

    /** Decodes a user-entered key; accepts any casing/spacing/dash style. */
    fun decode(display: String): ByteArray {
        val clean = normalize(display)
        require(clean.length == 52) { "recovery key must have 52 characters" }
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>(32)
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid character in recovery key" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** Uppercase, strips separators/whitespace. */
    fun normalize(display: String): String = display.uppercase().replace(Regex("[^A-Z2-7]"), "")

    /** Canonical fingerprint stored in prefs; compared on recovery attempts. */
    fun fingerprint(display: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(normalize(display).toByteArray(Charsets.US_ASCII))

    fun fingerprintsMatch(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)

    /** Derives the KEK source secret from a user-entered display key. */
    fun kekSecret(display: String): CharArray = normalize(display).toCharArray()
}
