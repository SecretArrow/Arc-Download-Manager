package com.arc.downloadmanager.engine

import android.net.Uri
import com.arc.downloadmanager.engine.Protocols
import java.net.URI

/**
 * Decides whether session cookies may be forwarded from one URL to another.
 * Cookies are only forwarded to the same host (or a subdomain of the original host)
 * and never across scheme downgrades.
 */
object CookieScopePolicy {
    data class Decision(val allowCookies: Boolean, val allowReferer: Boolean)

    fun evaluate(
        originalUrl: String,
        targetUrl: String,
    ): Decision {
        val from = runCatching { URI(originalUrl) }.getOrNull() ?: return Decision(false, false)
        val to = runCatching { URI(targetUrl) }.getOrNull() ?: return Decision(false, false)
        val fromHost = from.host?.lowercase() ?: return Decision(false, false)
        val toHost = to.host?.lowercase() ?: return Decision(false, false)
        val sameHost = fromHost == toHost || toHost.endsWith(".$fromHost")
        val schemeDowngrade = (from.scheme == "https" && to.scheme == "http")
        return Decision(allowCookies = sameHost && !schemeDowngrade, allowReferer = sameHost)
    }

    /** Converts a raw "k=v; k2=v2" cookie string into a map. */
    fun parseCookies(cookieHeader: String?): Map<String, String> {
        if (cookieHeader.isNullOrBlank()) return emptyMap()
        return cookieHeader.split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (key.isEmpty()) null else key to value
            }
            .toMap()
    }

    /** Serializes cookies back into a header value, dropping cross-origin names for the given target. */
    fun cookieHeaderFor(
        url: String,
        cookies: Map<String, String>,
    ): String? = if (cookies.isEmpty()) null else cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** Extracts the host of a URL for logging/UI purposes, never logging query params. */
    fun hostOf(url: String): String = runCatching { URI(url).host ?: url.substringBefore('/') }.getOrDefault(url)

    /** Strips query/fragment so links can be logged without leaking tokens. */
    fun redact(url: String): String = url.substringBefore('?').substringBefore('#') + if (url.contains('?')) "?…" else ""

    /** True when the string looks like a downloadable URL (http/https/ftp/ftps/sftp/content). */
    fun isSupportedUrl(url: String): Boolean = Protocols.isSupportedUrl(url)

    /** True when the URL is served by the OkHttp pipeline (http/https only, used for redirect hops). */
    fun isHttpUrl(url: String): Boolean = Protocols.isHttpUrl(url)

    fun safeUri(uriString: String): Uri? = runCatching { Uri.parse(uriString) }.getOrNull()
}
