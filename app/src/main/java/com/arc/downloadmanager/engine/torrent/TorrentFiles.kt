package com.arc.downloadmanager.engine.torrent

import java.io.File
import java.util.Locale

/** Pure helpers around finished torrent payloads. JVM-testable (no Android types). */
object TorrentFiles {
    /** Collects payload files recursively, skipping libtorrent bookkeeping artifacts
     *  (hidden dirs like .pad, .parts pre-allocation, resume files). */
    fun collect(saveDir: File): List<File> {
        if (!saveDir.isDirectory) return emptyList()
        val base = saveDir.absolutePath
        return saveDir
            .walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                // Any path segment starting with "." is bookkeeping (e.g. /.pad/123, .parts).
                val relative = file.absolutePath.removePrefix(base)
                relative.split(File.separator).none { it.startsWith(".") }
            }.sortedBy { it.absolutePath }
    }

    /** Total bytes of the payload files. */
    fun totalBytes(files: List<File>): Long = files.sumOf { it.length() }

    /** Best-effort MIME type from a file extension (kept pure so JVM tests can run it). */
    fun guessMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/avi"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "iso" -> "application/x-iso9660-image"
            "apk" -> "application/vnd.android.package-archive"
            "epub" -> "application/epub+zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "txt", "nfo" -> "text/plain"
            "srt" -> "application/x-subrip"
            else -> null
        }
    }
}
