package com.arc.downloadmanager.engine.torrent

import java.io.File

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
            }.sortedBy { it.absolutePath }.toList()
    }

    /** Total bytes of the payload files. */
    fun totalBytes(files: List<File>): Long = files.sumOf { it.length() }

    /** Best-effort MIME type from a file extension (delegates to the shared extension map). */
    fun guessMime(name: String): String? = com.arc.downloadmanager.engine.FilenameDetector.guessMime(name)
}
