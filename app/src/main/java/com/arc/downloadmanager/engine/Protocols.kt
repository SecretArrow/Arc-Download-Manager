package com.arc.downloadmanager.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Protocol registry for the download engine. Determines which URL schemes the engine can
 * transfer and exposes small pure helpers shared by the transports, the repository and the UI.
 *
 * Supported transfer protocols:
 *  - HTTP / HTTPS (OkHttp, HTTP/2 on TLS, redirects, cookies, ranges, multi-segment)
 *  - FTP / FTPS (Apache Commons Net, passive mode, REST resume, explicit TLS)
 *  - SFTP (JSch, SSH File Transfer Protocol, resume support)
 *  - content:// (Android content providers / SAF documents)
 *  - magnet: (BitTorrent via libtorrent/jlibtorrent, DHT + tracker peer discovery)
 *
 * Known-but-unsupported schemes (ed2k, rtmp ...) surface a friendly
 * [DownloadError.PROTOCOL_UNSUPPORTED] instead of a generic invalid-URL error.
 */
object Protocols {
    /** Transfer protocols the engine fully supports. */
    val HTTP = "http"
    val HTTPS = "https"
    val FTP = "ftp"
    val FTPS = "ftps"
    val SFTP = "sftp"
    val CONTENT = "content"
    val MAGNET = "magnet"

    /** Schemes that can be validated and queued, including locally resolvable ones. */
    val SUPPORTED_SCHEMES: Set<String> = setOf(HTTP, HTTPS, FTP, FTPS, SFTP, CONTENT, MAGNET)

    /** Schemes users commonly paste that the engine cannot download. */
    val KNOWN_UNSUPPORTED: Map<String, String> =
        mapOf(
            "torrent" to "torrent",
            "ed2k" to "eDonkey",
            "thunder" to "Thunder",
            "ws" to "WebSocket",
            "wss" to "WebSocket",
            "rtmp" to "RTMP",
            "rtsp" to "RTSP",
        )

    /** Default ports used when the URL does not specify one. */
    fun defaultPort(scheme: String): Int =
        when (scheme) {
            HTTP -> 80
            HTTPS -> 443
            FTP, FTPS -> 21
            SFTP -> 22
            else -> -1
        }

    /** Lowercase scheme of [url], or null when absent/unparseable. */
    fun schemeOf(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // content:// URIs are handled by android.net.Uri semantics; a light manual parse is enough.
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return null
        val candidate = trimmed.substring(0, colon).lowercase()
        if (!candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return candidate
    }

    /** True when the engine can transfer [url] end-to-end. */
    fun isSupportedUrl(url: String?): Boolean {
        val scheme = schemeOf(url) ?: return false
        if (scheme !in SUPPORTED_SCHEMES) return false
        if (scheme == CONTENT) return true
        if (scheme == MAGNET) return MagnetUri.isMagnet(url)
        if (scheme == HTTP || scheme == HTTPS) {
            // OkHttp's HttpUrl is deliberately lenient (spaces, unicode, missing encoding),
            // which java.net.URI is not; pasted links frequently contain stray characters.
            return url?.trim()?.toHttpUrlOrNull() != null
        }
        // ftp/ftps/sftp need a host at minimum.
        return parseAuthority(url ?: return false) != null
    }

    /** True when the scheme is served by the OkHttp pipeline (used for redirect hops). */
    fun isHttpUrl(url: String?): Boolean = schemeOf(url) in setOf(HTTP, HTTPS)

    /** Scheme display label, e.g. "SFTP" for sftp:// links. */
    fun labelFor(scheme: String?): String =
        when (scheme?.lowercase()) {
            HTTP -> "HTTP"
            HTTPS -> "HTTPS"
            FTP -> "FTP"
            FTPS -> "FTPS"
            SFTP -> "SFTP"
            CONTENT -> "Content"
            MAGNET -> "Magnet"
            null -> ""
            else -> scheme.uppercase()
        }

    data class RemoteTarget(
        val scheme: String,
        val host: String,
        val port: Int,
        val user: String?,
        val password: String?,
        val path: String,
    )

    /**
     * Parses ftp/ftps/sftp URLs into connection parameters. Credentials come from the URL
     * userinfo (ftp://user:pass@host/path). Returns null when host or scheme are unusable.
     */
    fun parseRemote(url: String): RemoteTarget? {
        val scheme = schemeOf(url) ?: return null
        if (scheme != FTP && scheme != FTPS && scheme != SFTP) return null
        val authority = parseAuthority(url) ?: return null
        val (hostPort, userInfo) = authority
        val (host, portFromAuthority) =
            if (hostPort.startsWith("[")) {
                // IPv6 literal [::1]:22
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                val host = hostPort.substring(1, close)
                val port = hostPort.substringAfterLast(':').toIntOrNull() ?: defaultPort(scheme)
                host to port
            } else {
                val parts = hostPort.split(':')
                val host = parts.getOrNull(0).orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: defaultPort(scheme)
                host to port
            }
        if (host.isBlank()) return null
        var user: String? = null
        var password: String? = null
        if (userInfo != null) {
            val idx = userInfo.indexOf(':')
            user = if (idx >= 0) userInfo.substring(0, idx) else userInfo
            if (idx >= 0) password = userInfo.substring(idx + 1)
            user = percentDecode(user)
            password = percentDecode(password)
            if (user.isNullOrBlank()) {
                user = null
                password = null
            }
        }
        val rawPath = url.substringAfter("//", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val path = "/" + percentDecode(rawPath)
        return RemoteTarget(
            scheme,
            host,
            portFromAuthority,
            user,
            password,
            if (path == "/") "/" else path.trimEnd('/').ifEmpty { "/" },
        )
    }

    /** Splits "host[:port]" from "user:pass@" of an authority section. */
    private fun parseAuthority(url: String): Pair<String, String?>? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank()) return null
        val atIndex = authority.lastIndexOf('@')
        return if (atIndex >= 0) {
            authority.substring(atIndex + 1) to authority.substring(0, atIndex)
        } else {
            authority to null
        }
    }

    private fun percentDecode(value: String?): String? {
        if (value == null) return null
        if (!value.contains('%')) return value
        return runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }
}
