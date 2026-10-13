package com.arc.downloadmanager.engine.transport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Read access to content:// resources, backed by an Android [Context] in production. */
interface ContentStreams {
    /** File size in bytes, or -1 when the provider does not expose one. */
    fun size(uri: Uri): Long

    /** Opens a read stream for [uri], or null when the provider refuses. */
    fun open(uri: Uri): InputStream?
}

/** Production implementation backed by the system [android.content.ContentResolver]. */
@Singleton
class AndroidContentStreams
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ContentStreams {
        override fun size(uri: Uri): Long {
            runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) return cursor.getLong(idx)
                    }
            }
            return runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }

        override fun open(uri: Uri): InputStream? = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }
