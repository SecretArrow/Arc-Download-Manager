package com.arc.downloadmanager.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Converts a stored final URI into one that is safe to hand to other apps.
 *
 * Downloads finalized on the legacy path (API 26-28) are stored as `file://` URIs;
 * handing a `file://` URI to another app via an intent throws FileUriExposedException
 * on API 24+ (silently swallowed by the UI's runCatching, leaving Open/Share dead on
 * Android 8/9). Rewriting those to FileProvider `content://` URIs fixes it; MediaStore
 * and SAF URIs are already shareable and pass through untouched.
 */
object ShareUris {
    fun accessible(
        context: Context,
        uri: Uri,
    ): Uri {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        val file = File(path)
        if (!file.exists()) return uri
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrDefault(uri)
    }

    /** A completed download's finalUri may hold multiple URIs joined with newlines (multi-file torrents). */
    fun split(raw: String?): List<Uri> =
        raw
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            ?: emptyList()
}
