package com.arc.downloadmanager.engine

import com.arc.downloadmanager.domain.model.DownloadStatus

/**
 * Pure decision logic for editing a download task (URL and/or display filename).
 * Kept side-effect free so it can be unit tested on the JVM and reused by both
 * the engine guard and the UI to decide whether the edit action is available.
 */
object TaskEditPolicy {
    sealed interface Verdict {
        /** Edit may proceed. [resetProgress] when partial data must be discarded (URL changed). */
        data class Allowed(val resetProgress: Boolean) : Verdict

        data class Rejected(val reason: Reason) : Verdict
    }

    enum class Reason {
        /** A running task cannot be edited; pause it first. */
        ACTIVE,

        /** Completed tasks are bound to their final file; restart instead. */
        COMPLETED,

        /** The proposed URL is empty or unsupported. */
        INVALID_URL,

        /** The proposed filename is blank. */
        INVALID_FILENAME,
    }

    /**
     * @param status current task status
     * @param oldUrl the stored task URL
     * @param newUrl the proposed URL (already trimmed by the caller)
     * @param newFilename the proposed display filename (already trimmed by the caller)
     */
    fun evaluate(
        status: DownloadStatus,
        oldUrl: String,
        newUrl: String,
        newFilename: String,
    ): Verdict {
        if (status.isActive) return Verdict.Rejected(Reason.ACTIVE)
        if (status == DownloadStatus.COMPLETED) return Verdict.Rejected(Reason.COMPLETED)
        if (newUrl.isBlank()) return Verdict.Rejected(Reason.INVALID_URL)
        if (!CookieScopePolicy.isSupportedUrl(newUrl)) return Verdict.Rejected(Reason.INVALID_URL)
        if (newFilename.isBlank()) return Verdict.Rejected(Reason.INVALID_FILENAME)
        return Verdict.Allowed(resetProgress = newUrl != oldUrl)
    }
}
