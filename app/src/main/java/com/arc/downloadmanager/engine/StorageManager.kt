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
class StorageManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
    ) : TaskStorage {
        val tempRoot: File
            get() = File(context.filesDir, "temp").apply { mkdirs() }

        val stagingRoot: File
            get() = File(context.filesDir, "final").apply { mkdirs() }

        /** Torrent payloads are assembled on shared storage (big files); falls back internally. */
        val torrentRoot: File
            get() {
                val base = context.getExternalFilesDir(null) ?: context.filesDir
                return File(base, "torrents").apply { mkdirs() }
            }

        fun tempDirFor(downloadId: String): File = File(tempRoot, downloadId).apply { mkdirs() }

        override fun torrentDir(downloadId: String): File = File(torrentRoot, downloadId).apply { mkdirs() }

        /** Lives under the per-task temp dir so [cleanup] removes finished HLS segments. */
        override fun hlsDir(downloadId: String): File = File(tempDirFor(downloadId), "hls").apply { mkdirs() }

        override fun partFile(
            downloadId: String,
            index: Int,
        ): File = File(tempDirFor(downloadId), "part_$index")

        override fun stagingFile(
            downloadId: String,
            filename: String,
        ): File = File(stagingRoot, "${downloadId}_$filename")

        fun freeBytes(): Long =
            runCatching {
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

        /** Deletes all temporary artifacts of a download (including its torrent payload dir). */
        override fun cleanup(downloadId: String) {
            runCatching { tempDirFor(downloadId).deleteRecursively() }
            File(stagingRoot, downloadId).delete()
            runCatching { File(torrentRoot, downloadId).deleteRecursively() }
        }

        /** Removes orphaned temp dirs whose downloads no longer exist. [validIds] are live download ids. */
        override fun cleanupOrphans(validIds: Set<String>): Int {
            var removed = 0
            tempRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in validIds) {
                    if (dir.deleteRecursively()) removed++
                }
            }
            torrentRoot.listFiles()?.forEach { dir ->
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
        override fun reconcilePart(
            downloadId: String,
            index: Int,
            recordedBytes: Long,
        ): Long {
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
        ): String =
            withContext(Dispatchers.IO) {
                require(staged.exists()) { "Staged file missing: ${staged.name}" }
                val destination =
                    entity.destinationUri
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

        private fun finalizeToPublicDownloads(
            entity: DownloadEntity,
            staged: File,
        ): String {
            val mime = entity.mimeType ?: "application/octet-stream"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, entity.filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Arc")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val uri =
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
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

        private fun finalizeToSaf(
            entity: DownloadEntity,
            staged: File,
            treeUri: Uri,
        ): String {
            val tree =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IOException("Destination folder is no longer accessible")
            val mime = entity.mimeType ?: "application/octet-stream"
            val existing = tree.findFile(entity.filename)
            val doc =
                if (existing != null && existing.isFile) {
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

        /**
         * Copies a finished multi-file torrent payload into the destination folder. On API 29+
         * each file becomes a MediaStore row under Downloads/Arc/<folderName>/; SAF trees get
         * a subfolder; legacy targets use a plain folder in public Downloads.
         */
        @Throws(IOException::class)
        override suspend fun finalizeFolder(
            files: List<File>,
            folderName: String,
            destinationUri: String?,
        ): List<String> =
            withContext(Dispatchers.IO) {
                require(files.isNotEmpty()) { "No payload files to finalize" }
                if (destinationUri != null) {
                    finalizeFolderToSaf(files, folderName, Uri.parse(destinationUri))
                } else {
                    finalizeFolderToPublicDownloads(files, folderName)
                }
            }

        private fun finalizeFolderToPublicDownloads(
            files: List<File>,
            folderName: String,
        ): List<String> {
            val uris = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                files.forEach { file ->
                    val mime = com.arc.downloadmanager.engine.torrent.TorrentFiles.guessMime(file.name) ?: "application/octet-stream"
                    val values =
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Arc/" + folderName)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    val uri =
                        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("MediaStore rejected the torrent file")
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Cannot open MediaStore output stream")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    uris += uri.toString()
                }
                return uris
            }
            // Legacy path (API 26..28)
            val arcDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Arc")
            val folder = File(arcDir, folderName).apply { mkdirs() }
            files.forEach { file ->
                val target = File(folder, file.name)
                file.copyTo(target, overwrite = true)
                uris += Uri.fromFile(target).toString()
            }
            return uris
        }

        private fun finalizeFolderToSaf(
            files: List<File>,
            folderName: String,
            treeUri: Uri,
        ): List<String> {
            val tree =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IOException("Destination folder is no longer accessible")
            val folder = tree.findFile(folderName) ?: tree.createDirectory(folderName) ?: throw IOException("Cannot create folder in destination")
            val uris = mutableListOf<String>()
            files.forEach { file ->
                val mime = com.arc.downloadmanager.engine.torrent.TorrentFiles.guessMime(file.name) ?: "application/octet-stream"
                val name = com.arc.downloadmanager.engine.FilenameDetector.uniqueName(file.name) { folder.findFile(it) != null }
                val doc = folder.createFile(mime, name) ?: throw IOException("Cannot create file in destination folder")
                context.contentResolver.openOutputStream(doc.uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Cannot write to destination folder")
                uris += doc.uri.toString()
            }
            return uris
        }

        /** Deletes the finalized artifact(s). Accepts \n-joined multi-URI payloads (torrent folders). */
        override suspend fun deleteFinal(finalUri: String?): Boolean =
            withContext(Dispatchers.IO) {
                if (finalUri.isNullOrBlank()) return@withContext false
                var anyDeleted = false
                finalUri.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { single ->
                    anyDeleted = deleteSingleFinal(single) || anyDeleted
                }
                anyDeleted
            }

        private fun deleteSingleFinal(finalUri: String): Boolean =
            runCatching {
                val uri = Uri.parse(finalUri)
                when (uri.scheme) {
                    "content" ->
                        if (uri.authority == "media") {
                            context.contentResolver.delete(uri, null, null) > 0
                        } else {
                            DocumentFile.fromSingleUri(context, uri)?.delete() == true
                        }
                    "file" -> {
                        val file = File(uri.path!!)
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
                    else -> false
                }
            }.getOrDefault(false)
    }
