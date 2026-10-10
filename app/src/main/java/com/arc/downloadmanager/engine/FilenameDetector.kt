package com.arc.downloadmanager.engine

import java.net.URLDecoder
import java.util.Locale

/**
 * Derives a sensible filename from a URL, Content-Disposition header and MIME type.
 * Pure JVM logic, unit tested.
 */
object FilenameDetector {
    private val MIME_EXTENSIONS =
        mapOf(
            "application/pdf" to "pdf",
            "application/zip" to "zip",
            "application/x-rar-compressed" to "rar",
            "application/x-7z-compressed" to "7z",
            "application/gzip" to "gz",
            "application/x-tar" to "tar",
            "application/vnd.android.package-archive" to "apk",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.ms-powerpoint" to "ppt",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
            "audio/mpeg" to "mp3",
            "audio/ogg" to "ogg",
            "audio/wav" to "wav",
            "audio/flac" to "flac",
            "audio/mp4" to "m4a",
            "video/mp4" to "mp4",
            "video/x-matroska" to "mkv",
            "video/webm" to "webm",
            "video/avi" to "avi",
            "video/quicktime" to "mov",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/svg+xml" to "svg",
            "text/plain" to "txt",
            "text/csv" to "csv",
            "text/html" to "html",
        )

    /** Extract filename from a Content-Disposition header (RFC 6266, RFC 5987). */
    fun fromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        // filename*=UTF-8''name takes precedence
        val star = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
        if (star != null) {
            val decoded =
                runCatching {
                    val parts = star.trim().split("''", limit = 2)
                    if (parts.size == 2) URLDecoder.decode(parts[1], Charsets.UTF_8.name()) else URLDecoder.decode(star, Charsets.UTF_8.name())
                }.getOrNull()
            if (!decoded.isNullOrBlank()) return sanitize(decoded)
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
        if (!plain.isNullOrBlank()) return sanitize(plain.trim())
        return null
    }

    /** Extracts a filename from a URL path, percent-decoding and cleaning query/fragment. */
    fun fromUrl(url: String): String? {
        val path = url.substringBefore('#').substringBefore('?')
        if (path.isBlank()) return null
        val name = path.trimEnd('/').substringAfterLast('/')
        if (name.isBlank() || name == ".." || name == ".") return null
        val decoded = runCatching { URLDecoder.decode(name, Charsets.UTF_8.name()) }.getOrNull() ?: name
        val clean = sanitize(decoded)
        return clean.ifBlank { null }
    }

    /** Picks the best filename, appending a guessed extension when the mime type is known. */
    fun detect(
        url: String,
        disposition: String?,
        mimeType: String?,
        fallbackSeed: Long = 0L,
    ): String {
        val fromDisposition = fromDisposition(disposition)
        val fromUrl = fromUrl(url)
        val base = fromDisposition ?: fromUrl ?: "download_$fallbackSeed"
        val withExt = ensureExtension(base, mimeType, fromDisposition != null)
        return sanitize(withExt)
    }

    fun sanitize(name: String): String {
        val cleaned =
            name
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.')
        return cleaned.take(220).ifBlank { "download" }
    }

    fun ensureExtension(
        name: String,
        mimeType: String?,
        fromDisposition: Boolean,
    ): String {
        val dot = name.lastIndexOf('.')
        if (dot > 0 && dot < name.length - 1) return name
        val mimeExt = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US)?.let { MIME_EXTENSIONS[it] }
        if (mimeExt != null) return "$name.$mimeExt"
        if (fromDisposition) return name
        return name
    }

    /** Suggests a non-conflicting name by appending " (n)" before the extension. */
    fun uniqueName(
        name: String,
        exists: (String) -> Boolean,
    ): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base ($counter)$ext"
            if (!exists(candidate)) return candidate
            counter++
        }
    }
}
