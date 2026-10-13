package com.arc.downloadmanager.engine

import java.net.URLDecoder
import java.util.Locale

/**
 * Pure parser for BitTorrent magnet URIs (magnet:?xt=urn:btih:...). JVM-testable.
 *
 * Supports v1 info-hashes given as 40 hex chars or 32 base32 chars, plus display-name
 * extraction. v2-only (btmh) magnets are recognized but only surface their display name;
 * the engine relies on libtorrent to handle them.
 */
object MagnetUri {
    private const val PREFIX = "magnet:?"

    /** Parsed fields of a magnet link. */
    data class Magnet(
        val uri: String,
        /** v1 info-hash as lowercase hex, or null when the magnet is v2-only or malformed. */
        val infoHashHex: String?,
        /** True when the magnet carries a v2 (btmh) hash. */
        val hasV2Hash: Boolean,
        /** Human readable name from the dn= parameter (URL-decoded), may be null. */
        val displayName: String?,
    )

    /** True when [url] looks like a magnet URI with at least one xt hash parameter. */
    fun isMagnet(url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (!trimmed.startsWith(PREFIX)) return false
        return parse(trimmed)?.hasHash == true
    }

    private val Magnet.hasHash: Boolean
        get() = !infoHashHex.isNullOrBlank() || hasV2Hash

    /**
     * Parses a magnet URI. Returns null when the URI is malformed or carries no xt hash.
     */
    fun parse(url: String?): Magnet? {
        val trimmed = url?.trim().orEmpty()
        if (!trimmed.startsWith(PREFIX)) return null
        val query = trimmed.substring(PREFIX.length).substringBefore('#')
        if (query.isBlank()) return null
        var infoHashHex: String? = null
        var hasV2 = false
        var displayName: String? = null
        query.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val key = pair.substringBefore('=').lowercase(Locale.US)
            val rawValue = pair.substringAfter('=', "")
            when (key) {
                "xt" -> {
                    val xt = percentDecode(rawValue).orEmpty()
                    when {
                        xt.startsWith("urn:btih:", true) -> {
                            val candidate = xt.substring("urn:btih:".length)
                            // Known encodings (hex/base32) are normalized; anything else is kept
                            // verbatim - libtorrent remains the authority on hash validity.
                            infoHashHex =
                                normalizeV1(candidate)
                                    ?: candidate.trim().lowercase(Locale.US).takeIf { it.isNotBlank() }
                                    ?: infoHashHex
                        }
                        xt.startsWith("urn:btmh:", true) -> hasV2 = true
                    }
                }
                "dn" -> displayName = percentDecode(rawValue)?.takeIf { it.isNotBlank() }
            }
        }
        if (infoHashHex == null && !hasV2) return null
        return Magnet(uri = trimmed, infoHashHex = infoHashHex, hasV2Hash = hasV2, displayName = displayName)
    }

    /** Convenience accessor for the display name (dn=), URL-decoded. */
    fun displayName(url: String): String? = parse(url)?.displayName

    /** v1 info-hash as lowercase hex, or null. */
    fun infoHashHex(url: String): String? = parse(url)?.infoHashHex

    /**
     * Normalizes a v1 info-hash token: 40 hex chars pass through (lowercased), 32 base32
     * chars are decoded to 20 bytes and re-encoded as hex. Anything else is rejected.
     */
    fun normalizeV1(token: String): String? {
        val trimmed = token.trim()
        return when {
            trimmed.length == 40 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
                trimmed.lowercase(Locale.US)
            trimmed.length == 32 && trimmed.all { it.isLetterOrDigit() } -> base32ToHex(trimmed.uppercase(Locale.US))
            else -> null
        }
    }

    /** RFC 4648 base32 (A-Z, 2-7) to lowercase hex. Returns null on invalid input. */
    private fun base32ToHex(input: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var bits = 0
        var value = 0
        val out = StringBuilder()
        for (c in input) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) return null
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                out.append(((value ushr (bits - 8)) and 0xFF).toString(16).padStart(2, '0'))
                bits -= 8
            }
        }
        // A valid v1 hash decodes to exactly 20 bytes (160 bits); base32 encodes that in 32 chars.
        return if (out.length == 40) out.toString() else null
    }

    private fun percentDecode(value: String): String? =
        runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()
}
