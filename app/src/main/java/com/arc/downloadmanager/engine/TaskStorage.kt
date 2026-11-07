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

    /** Per-task directory where HLS media segments are staged before the merge. */
    fun hlsDir(downloadId: String): File

    fun reconcilePart(
        downloadId: String,
        index: Int,
        recordedBytes: Long,
    ): Long

    fun cleanup(downloadId: String)

    fun cleanupOrphans(validIds: Set<String>): Int

    suspend fun hasFreeSpace(bytesNeeded: Long): Boolean

    /**
     * Copies the staged file into the destination and returns the content URI (or file path) of
     * the final artifact. The staged file is removed afterwards.
     *
     * When [overwrite] is true an existing same-name entry at the destination is replaced;
     * otherwise a fresh non-conflicting name ("name (1).ext") is chosen if the name is taken.
     */
    suspend fun finalize(
        entity: DownloadEntity,
        staged: File,
        overwrite: Boolean = false,
    ): String

    /** True when the destination already holds an entry with the task's display name. */
    suspend fun destinationConflict(entity: DownloadEntity): Boolean

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

    /**
     * Renames the existing same-name entry at the destination to a fresh non-conflicting name
     * ("name (1).ext") so the staged download can be published under the original name.
     * Returns false when the entry cannot be renamed.
     */
    suspend fun renameExisting(entity: DownloadEntity): Boolean

    /**
     * Locates the staged payload of a parked (e.g. CONFLICT) task. The staged file name embeds
     * the display filename, which the user may have edited after staging — match by task id.
     */
    fun findStagedFile(downloadId: String): File?
}
