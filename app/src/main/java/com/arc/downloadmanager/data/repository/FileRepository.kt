package com.arc.downloadmanager.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class FileCategory { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APP, OTHER }

data class FileItem(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String?,
    val modifiedAt: Long,
    val category: FileCategory,
)

/**
 * File library over the app's public download locations (MediaStore Downloads/Arc on API 29+,
 * the legacy Downloads/Arc folder on older devices and any user-chosen SAF folder).
 */
@Singleton
class FileRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun categorize(
            name: String,
            mime: String?,
        ): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            val m = mime?.substringBefore(';')?.lowercase() ?: ""
            return when {
                m.startsWith("image/") || ext in IMAGE_EXTS -> FileCategory.IMAGE
                m.startsWith("video/") || ext in VIDEO_EXTS -> FileCategory.VIDEO
                m.startsWith("audio/") || ext in AUDIO_EXTS -> FileCategory.AUDIO
                ext in ARCHIVE_EXTS || m.contains("zip") || m.contains("tar") || m.contains("compressed") -> FileCategory.ARCHIVE
                ext == "apk" || m == "application/vnd.android.package-archive" -> FileCategory.APP
                m.startsWith("text/") || ext in DOC_EXTS || m.contains("pdf") || m.contains("document") ||
                    m.contains("sheet") || m.contains("presentation") -> FileCategory.DOCUMENT
                else -> FileCategory.OTHER
            }
        }

        suspend fun listAll(): List<FileItem> =
            withContext(Dispatchers.IO) {
                val items = mutableListOf<FileItem>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    items += listFromMediaStore()
                } else {
                    items += listFromLegacyDir()
                }
                items
            }

        private fun listFromMediaStore(): List<FileItem> {
            val result = mutableListOf<FileItem>()
            val projection =
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                )
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val relativePath = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                        if (!relativePath.startsWith("Download/Arc")) continue
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        result +=
                            FileItem(
                                uri = uri.toString(),
                                name = name,
                                size = cursor.getLong(sizeCol),
                                mime = cursor.getString(mimeCol),
                                modifiedAt = cursor.getLong(dateCol) * 1000,
                                category = categorize(name, cursor.getString(mimeCol)),
                            )
                    }
                }
            }
            return result
        }

        private fun listFromLegacyDir(): List<FileItem> {
            val dir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Arc",
                )
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    FileItem(
                        uri = Uri.fromFile(it).toString(),
                        name = it.name,
                        size = it.length(),
                        mime = null,
                        modifiedAt = it.lastModified(),
                        category = categorize(it.name, null),
                    )
                } ?: emptyList()
        }

        suspend fun listCustomFolder(treeUriString: String): List<FileItem> =
            withContext(Dispatchers.IO) {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return@withContext emptyList()
                tree.listFiles()
                    .filter { it.isFile }
                    .map {
                        FileItem(
                            uri = it.uri.toString(),
                            name = it.name ?: "unknown",
                            size = it.length(),
                            mime = it.type,
                            modifiedAt = it.lastModified(),
                            category = categorize(it.name ?: "", it.type),
                        )
                    }
            }

        suspend fun delete(uriString: String): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    when (uri.scheme) {
                        "content" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == "media") {
                                context.contentResolver.delete(uri, null, null) > 0
                            } else {
                                DocumentFile.fromSingleUri(context, uri)?.delete() == true
                            }
                        "file" -> File(uri.path!!).delete()
                        else -> false
                    }
                }.getOrDefault(false)
            }

        suspend fun rename(
            uriString: String,
            newName: String,
        ): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    when (uri.scheme) {
                        "content" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == "media") {
                                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                                context.contentResolver.update(uri, values, null, null) > 0
                            } else {
                                DocumentFile.fromSingleUri(context, uri)?.renameTo(newName) == true
                            }
                        "file" -> File(uri.path!!).renameTo(File(File(uri.path!!).parent, newName))
                        else -> false
                    }
                }.getOrDefault(false)
            }

        suspend fun totalBytesUsed(): Long = withContext(Dispatchers.IO) { listAll().sumOf { it.size } }

        companion object {
            private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic")
            private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "ts")
            private val AUDIO_EXTS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "wma")
            private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
            private val DOC_EXTS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "md")
        }
    }
