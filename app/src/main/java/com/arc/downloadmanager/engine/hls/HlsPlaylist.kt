package com.arc.downloadmanager.engine.hls

import java.net.URI
import java.util.Locale

/**
 * Decryption info carried by `#EXT-X-KEY`. Only AES-128 (and METHOD=NONE) is supported by
 * [HlsDownloader]; anything else (e.g. SAMPLE-AES) fails with [com.arc.downloadmanager.engine.DownloadError.HLS_UNSUPPORTED].
 */
data class HlsKeyInfo(
    val method: String,
    val keyUrl: String,
    /** Hex IV without the optional `0x` prefix, or null when the playlist omits it. */
    val ivHex: String?,
) {
    companion object {
        /**
         * Per RFC 8216 §5.2, when the IV attribute is absent the IV is the 128-bit big-endian
         * representation of the segment's media sequence number (32 hex chars).
         */
        fun derivedIvHex(mediaSequence: Long): String = String.format(Locale.US, "%032x", mediaSequence)
    }
}

/** Parsed result of an M3U8 document: either a master playlist or a media playlist. */
sealed interface HlsPlaylist {
    /** One `#EXT-X-STREAM-INF` entry of a master playlist. [url] is resolved (absolute). */
    data class Variant(
        val url: String,
        val bandwidth: Long,
        val resolution: String?,
        val codecs: String?,
    )

    data class Master(
        val variants: List<Variant>,
    ) : HlsPlaylist

    /** One media segment. [sequence] is the media sequence number (MEDIA-SEQUENCE + index). */
    data class Segment(
        val url: String,
        val durationSec: Double,
        val sequence: Long,
    )

    data class Media(
        val segments: List<Segment>,
        val targetDurationSec: Long?,
        val totalDurationSec: Double,
        /**
         * Active decryption key (last `#EXT-X-KEY` wins). Limitation: key rotation across a
         * playlist is not modeled — the whole download uses this single key.
         */
        val encryption: HlsKeyInfo?,
        /** fMP4 init segment from `#EXT-X-MAP` (absolute), or null for MPEG-TS playlists. */
        val initSegmentUrl: String?,
        /** True when `#EXT-X-ENDLIST` is absent (live/event stream). */
        val isLive: Boolean,
    ) : HlsPlaylist
}

/**
 * Parses [text] as an HLS playlist and resolves every variant/segment/key/map URL against
 * [playlistUrl] (java.net.URI.resolve semantics: relative AND absolute references both work).
 *
 * Tolerated input quirks: UTF-8 BOM, CRLF line endings, comments, unknown `#EXT-X-*` tags,
 * quoted attribute values containing commas. `#EXT-X-BYTERANGE` is deliberately SKIPPED:
 * each segment is downloaded whole (byte-range sub-segments are not stitched).
 *
 * @throws IllegalArgumentException when the input is blank or not an M3U8 document.
 */
fun parse(
    text: String,
    playlistUrl: String,
): HlsPlaylist {
    val body = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    require(body.startsWith("#EXTM3U")) { "Not an HLS playlist: missing #EXTM3U header" }
    val base = baseUri(playlistUrl)

    var mediaSequence = 0L
    var targetDuration: Long? = null
    var endListSeen = false
    var pendingVariantAttrs: Map<String, String>? = null
    var pendingDuration: Double? = null
    var encryption: HlsKeyInfo? = null
    var initSegmentUrl: String? = null
    val variants = mutableListOf<HlsPlaylist.Variant>()
    val segments = mutableListOf<HlsPlaylist.Segment>()

    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#") -> {
                when {
                    line.startsWith("#EXT-X-STREAM-INF:") -> {
                        val attrs = parseAttributeList(line.substringAfter(':'))
                        val uriAttr = attrs["URI"]
                        if (uriAttr != null) {
                            // Comma-joined URI="..." form: the variant is complete on this line.
                            variants += buildVariant(attrs, uriAttr, base)
                        } else {
                            // Otherwise the variant URL is the next non-comment line.
                            pendingVariantAttrs = attrs
                        }
                    }
                    line.startsWith("#EXT-X-KEY:") -> {
                        val attrs = parseAttributeList(line.substringAfter(':'))
                        val method = attrs["METHOD"]?.trim()?.uppercase(Locale.US)
                        encryption =
                            if (method == null || method == "NONE") {
                                null
                            } else {
                                val uri = attrs["URI"]?.trim().orEmpty()
                                HlsKeyInfo(
                                    method = method,
                                    keyUrl = if (uri.isEmpty()) "" else resolveUrl(base, uri),
                                    ivHex = attrs["IV"]?.trim()?.removePrefix("0x")?.removePrefix("0X"),
                                )
                            }
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val uri = parseAttributeList(line.substringAfter(':'))["URI"]?.trim().orEmpty()
                        // BYTERANGE on the map is ignored on purpose: the whole init segment is fetched.
                        if (uri.isNotEmpty()) initSegmentUrl = resolveUrl(base, uri)
                    }
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    line.startsWith("#EXT-X-TARGETDURATION:") ->
                        targetDuration = line.substringAfter(':').trim().toLongOrNull()
                    line.startsWith("#EXT-X-ENDLIST") -> endListSeen = true
                    line.startsWith("#EXTINF:") ->
                        // "#EXTINF:<duration>,[title]" — anything after the first comma is a title.
                        pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    else -> Unit // unknown #EXT tags (#EXT-X-BYTERANGE, #EXT-X-DISCONTINUITY, ...) and comments
                }
            }
            pendingVariantAttrs != null -> {
                variants += buildVariant(pendingVariantAttrs, line, base)
                pendingVariantAttrs = null
            }
            pendingDuration != null -> {
                segments += HlsPlaylist.Segment(resolveUrl(base, line), pendingDuration, mediaSequence + segments.size)
                pendingDuration = null
            }
            // A stray URL line without EXTINF/STREAM-INF context is ignored.
            else -> Unit
        }
    }

    return if (variants.isNotEmpty()) {
        HlsPlaylist.Master(variants)
    } else {
        HlsPlaylist.Media(
            segments = segments,
            targetDurationSec = targetDuration,
            totalDurationSec = segments.sumOf { it.durationSec },
            encryption = encryption,
            initSegmentUrl = initSegmentUrl,
            isLive = !endListSeen,
        )
    }
}

private fun buildVariant(
    attrs: Map<String, String>,
    url: String,
    base: URI,
): HlsPlaylist.Variant =
    HlsPlaylist.Variant(
        url = resolveUrl(base, url.trim()),
        bandwidth = attrs["BANDWIDTH"]?.trim()?.toLongOrNull() ?: 0L,
        resolution = attrs["RESOLUTION"]?.trim(),
        codecs = attrs["CODECS"]?.trim(),
    )

/**
 * Splits an HLS attribute list on commas that are OUTSIDE double quotes
 * (e.g. CODECS="avc1.64001f,mp4a.40.2" survives as one attribute) and returns KEY -> VALUE
 * pairs with surrounding quotes stripped and keys uppercased.
 */
internal fun parseAttributeList(raw: String): Map<String, String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    for (ch in raw) {
        when {
            ch == '"' -> {
                inQuotes = !inQuotes
                current.append(ch)
            }
            ch == ',' && !inQuotes -> {
                parts.add(current.toString())
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotBlank()) parts.add(current.toString())

    val attrs = mutableMapOf<String, String>()
    for (part in parts) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val key = part.substring(0, eq).trim().uppercase(Locale.US)
        val value = part.substring(eq + 1).trim().removeSurrounding("\"")
        if (key.isNotEmpty()) attrs[key] = value
    }
    return attrs
}

private fun baseUri(playlistUrl: String): URI {
    val cleaned = playlistUrl.trim().replace(" ", "%20")
    return try {
        URI(cleaned)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid playlist URL: $playlistUrl")
    }
}

/** Resolves [ref] against [base]; absolute http(s) references survive URI failures untouched. */
private fun resolveUrl(
    base: URI,
    ref: String,
): String =
    try {
        base.resolve(ref).toString()
    } catch (e: Exception) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            ref
        } else {
            throw IllegalArgumentException("Cannot resolve HLS reference: $ref")
        }
    }

/**
 * Cheap format sniffing used by the engine routing: decides whether a URL/response is an HLS
 * playlist BEFORE the generic "web page, not a file" rejection runs.
 */
object HlsSniffer {
    private val HLS_CONTENT_TYPES =
        setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
        )

    /** True for the mpegurl family of content types (parameters ignored). */
    fun isHlsContentType(contentType: String?): Boolean = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US) in HLS_CONTENT_TYPES

    /** True when the URL path (query/fragment ignored) ends with the .m3u8 extension. */
    fun isHlsUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.trimEnd('/').substringAfterLast('/')
        return name.substringAfterLast('.', "").equals("m3u8", ignoreCase = true)
    }

    /** True when the first bytes of a body look like an HLS playlist (BOM/whitespace tolerated). */
    fun looksLikePlaylist(firstBytes: ByteArray): Boolean {
        if (firstBytes.isEmpty()) return false
        val head = firstBytes.toString(Charsets.UTF_8).trimStart('\uFEFF').trimStart()
        return head.startsWith("#EXTM3U")
    }
}
