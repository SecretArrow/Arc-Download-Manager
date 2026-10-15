package com.arc.downloadmanager.engine.hls

/**
 * Minimal pure-Kotlin HLS (m3u8) playlist model + parser. No Android dependencies so it
 * stays unit-testable on the JVM.
 *
 * Supported v1 scope:
 *  - master playlists: the variant with the highest BANDWIDTH attribute is selected and
 *    its URI returned via [HlsPlaylist.nextPlaylistUri];
 *  - media playlists: `#EXTINF:<duration>,` followed by the next non-comment line (the
 *    segment URI) — every other tag is ignored;
 *  - encryption (`#EXT-X-KEY` with METHOD != NONE) is NOT supported: parsing stops and an
 *    empty media playlist is returned so the task fails with a clear unsupported error
 *    instead of producing a corrupt file;
 *  - segment count is capped at [MAX_SEGMENTS] to bound memory on pathological inputs.
 */
data class HlsSegment(
    val uri: String,
    val durationSec: Double,
)

data class HlsPlaylist(
    val segments: List<HlsSegment>,
    val isMaster: Boolean,
    val nextPlaylistUri: String?,
)

object HlsPlaylist {
    /** Hard cap on parsed media segments (a ~2 h VOD at 6 s chunks is ~1200). */
    private const val MAX_SEGMENTS = 5000

    /** Parses an m3u8 body into a [HlsPlaylist] (master or media). */
    fun parse(text: String): HlsPlaylist = if (text.contains("#EXT-X-STREAM-INF")) parseMaster(text) else parseMedia(text)

    /** Absolutizes a relative segment/variant [uri] against the playlist [base] URL. */
    fun resolve(
        base: String,
        uri: String,
    ): String = runCatching { java.net.URI(base).resolve(uri).toString() }.getOrElse { base }

    private fun parseMaster(text: String): HlsPlaylist {
        val lines = text.lines()
        var bestBandwidth = -1L
        var bestUri: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth =
                    Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                // The variant URI is the next non-comment, non-blank line.
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                if (j < lines.size && bandwidth > bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestUri = lines[j].trim()
                }
                i = j + 1
                continue
            }
            i++
        }
        return HlsPlaylist(segments = emptyList(), isMaster = true, nextPlaylistUri = bestUri)
    }

    private fun parseMedia(text: String): HlsPlaylist {
        val segments = ArrayList<HlsSegment>()
        var pendingDuration: Double? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    // "#EXTINF:<duration>,[title]" — the segment URI follows on the next
                    // non-comment line.
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                // Encryption v1 (AES-128 / SAMPLE-AES) is unsupported: bail out with an
                // empty playlist so the caller fails the task instead of writing garbage.
                line.startsWith("#EXT-X-KEY") -> {
                    val method =
                        Regex("METHOD=([A-Za-z0-9-]+)").find(line)?.groupValues?.get(1)?.uppercase() ?: "NONE"
                    if (method != "NONE") {
                        return HlsPlaylist(segments = emptyList(), isMaster = false, nextPlaylistUri = null)
                    }
                }
                line.isEmpty() || line.startsWith("#") -> Unit // any other tag/comment is ignored
                else -> {
                    if (segments.size < MAX_SEGMENTS) {
                        segments.add(HlsSegment(uri = line, durationSec = pendingDuration ?: 0.0))
                    }
                    pendingDuration = null
                }
            }
        }
        return HlsPlaylist(segments = segments, isMaster = false, nextPlaylistUri = null)
    }
}
