package com.arc.downloadmanager.engine

import com.arc.downloadmanager.data.db.DownloadEntity
import java.io.File

/**
 * Storage surface used by the engine, abstracted so unit tests can run on the JVM.
 */
interface TaskStorage {
    fun partFile(
        downloadId: String,
        index: Int,
    ): File

    fun stagingFile(
        downloadId: String,
        filename: String,
    ): File

    /** Per-task directory where a BitTorrent payload is assembled in place. */
    fun torrentDir(downloadId: String): File

    fun reconcilePart(
        downloadId: String,
        index: Int,
        recordedBytes: Long,
    ): Long

    fun cleanup(downloadId: String)

    fun cleanupOrphans(validIds: Set<String>): Int

    suspend fun hasFreeSpace(bytesNeeded: Long): Boolean

    suspend fun finalize(
        entity: DownloadEntity,
        staged: File,
    ): String

    /**
     * Copies a finished multi-file torrent payload ([files]) into the destination under
     * [folderName] (e.g. Downloads/Arc/<folderName>/). Returns one final URI per file.
     */
    suspend fun finalizeFolder(
        files: List<File>,
        folderName: String,
        destinationUri: String?,
    ): List<String>

    suspend fun deleteFinal(finalUri: String?): Boolean
}
