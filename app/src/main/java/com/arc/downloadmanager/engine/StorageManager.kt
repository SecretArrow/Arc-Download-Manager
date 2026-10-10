package com.arc.downloadmanager.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.SettingsRepository
import com.arc.downloadmanager.domain.model.Destination
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages temporary segment files, free-space checks and atomic-ish finalization
 * into the user-visible destination (MediaStore Downloads, SAF folder or legacy Downloads dir).
 */
@Singleton
class StorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : TaskStorage {
    val tempRoot: File
        get() = File(context.filesDir, "temp").apply { mkdirs() }

    val stagingRoot: File
        get() = File(context.filesDir, "final").apply { mkdirs() }

    fun tempDirFor(downloadId: String): File = File(tempRoot, downloadId).apply { mkdirs() }

    override fun partFile(downloadId: String, index: Int): File = File(tempDirFor(downloadId), "part_$index")

    override fun stagingFile(downloadId: String, filename: String): File =
        File(stagingRoot, "${downloadId}_${filename}")

    fun freeBytes(): Long = runCatching {
        val stat = StatFs(tempRoot.absolutePath)
        stat.availableBytes
    }.getOrDefault(-1L)

    override suspend fun hasFreeSpace(bytesNeeded: Long): Boolean {
        val free = freeBytes()
        if (free < 0) return true
        val minFree = settingsRepository.snapshot().minFreeSpaceMB * 1024 * 1024
        val margin = 64L * 1024L * 1024L
        return free >= bytesNeeded + margin.coerceAtMost(minFree) && free - bytesNeeded >= minFree / 2
    }

    /** Deletes all temporary artifacts of a download. */
    override fun cleanup(downloadId: String) {
        runCatching { tempDirFor(downloadId).deleteRecursively() }
        File(stagingRoot, downloadId).delete()
    }

    /** Removes orphaned temp dirs whose downloads no longer exist. [validIds] are live download ids. */
    override fun cleanupOrphans(validIds: Set<String>): Int {
        var removed = 0
        tempRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in validIds) {
                if (dir.deleteRecursively()) removed++
            }
        }
        stagingRoot.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('_')
            if (id !in validIds && (f.name.endsWith(".staging") || id.length == 36)) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    /**
     * Reconciles on-disk temp data with recorded progress after a crash:
     * truncates part files that grew beyond the recorded byte count.
     * Returns the safe per-part byte counts.
     */
    override fun reconcilePart(downloadId: String, index: Int, recordedBytes: Long): Long {
        val file = partFile(downloadId, index)
        if (!file.exists()) return 0L
        return if (file.length() > recordedBytes) {
            runCatching {
                java.io.RandomAccessFile(file, "rw").use { raf -> raf.setLength(recordedBytes) }
            }
            recordedBytes
        } else {
            file.length()
        }
    }

    /**
     * Copies the staged file into the destination and returns the content URI (or file path) of
     * the final artifact. The staged file is removed afterwards.
     */
    @Throws(IOException::class)
    override suspend fun finalize(
        entity: DownloadEntity,
        staged: File,
    ): String = withContext(Dispatchers.IO) {
        require(staged.exists()) { "Staged file missing: ${staged.name}" }
        val destination = entity.destinationUri
            ?.let { Destination.CustomFolder(it) }
            ?: Destination.Default
        try {
            when (destination) {
                is Destination.Default -> finalizeToPublicDownloads(entity, staged)
                is Destination.CustomFolder -> finalizeToSaf(entity, staged, Uri.parse(destination.treeUri))
            }
        } finally {
            staged.delete()
        }
    }

    private fun finalizeToPublicDownloads(entity: DownloadEntity, staged: File): String {
        val mime = entity.mimeType ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entity.filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Arc")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore rejected the download")
            resolver.openOutputStream(uri)?.use { out -> staged.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Cannot open MediaStore output stream")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri.toString()
        }
        // Legacy path (API 26..28)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val arcDir = File(downloadsDir, "Arc").apply { mkdirs() }
        val target = File(arcDir, FilenameDetector.uniqueName(entity.filename) { File(arcDir, it).exists() })
        staged.copyTo(target, overwrite = true)
        return Uri.fromFile(target).toString()
    }

    private fun finalizeToSaf(entity: DownloadEntity, staged: File, treeUri: Uri): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Destination folder is no longer accessible")
        val mime = entity.mimeType ?: "application/octet-stream"
        val existing = tree.findFile(entity.filename)
        val doc = if (existing != null && existing.isFile) {
            existing
        } else {
            val name = FilenameDetector.uniqueName(entity.filename) { tree.findFile(it) != null }
            tree.createFile(mime, name) ?: throw IOException("Cannot create file in destination folder")
        }
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            staged.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Cannot write to destination folder")
        return doc.uri.toString()
    }

    /** Deletes the finalized artifact (MediaStore row, SAF document or plain file). */
    override suspend fun deleteFinal(finalUri: String?): Boolean = withContext(Dispatchers.IO) {
        if (finalUri.isNullOrBlank()) return@withContext false
        runCatching {
            val uri = Uri.parse(finalUri)
            when (uri.scheme) {
                "content" -> if (uri.authority == "media") {
                    context.contentResolver.delete(uri, null, null) > 0
                } else {
                    DocumentFile.fromSingleUri(context, uri)?.delete() == true
                }
                "file" -> File(uri.path!!).delete()
                else -> false
            }
        }.getOrDefault(false)
    }
}
