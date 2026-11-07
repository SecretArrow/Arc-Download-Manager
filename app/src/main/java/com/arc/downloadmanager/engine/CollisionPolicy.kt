package com.arc.downloadmanager.engine

import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.domain.model.DownloadSource

/**
 * Single source of truth for destination filename collisions.
 *
 * The Settings radio (Rename / Skip / Replace) governs EVERY collision surface — enqueue
 * duplicates and the finalize step alike — so the app can never act against the visible
 * setting:
 *  - [OverwritePolicy.RENAME] (default): nothing is ever destroyed or asked — a download
 *    whose name is taken is saved as a fresh copy ("name (1).ext").
 *  - [OverwritePolicy.REPLACE]: the existing same-name artifact is replaced.
 *  - [OverwritePolicy.SKIP]: re-added URLs are ignored with a toast; a finished download
 *    whose destination name is taken parks in CONFLICT and the user decides per task.
 *
 * Pure JVM logic, unit tested.
 */
object CollisionPolicy {
    /** What to do with the staged bytes of a finished download. */
    enum class Finalize {
        /** Save under a fresh non-conflicting name ("name (1).ext"); the old file is kept. */
        RENAME_NEW,

        /** Replace the existing same-name artifact with the new download. */
        OVERWRITE,

        /** Destination name is taken: park the task in CONFLICT and ask the user. */
        ASK,
    }

    /**
     * @param policy the user-visible overwrite policy (Settings radio).
     * @param legacyAutoRename the retired per-switch auto-rename toggle; kept as an OR-alias
     *   so users who enabled it before the merge keep the silent-rename behavior.
     * @param destinationTaken true when the destination already holds an entry with the
     *   task's display name.
     * @param replaceOwnArtifact true when the task is re-publishing its OWN previous
     *   artifact (restart/retry of an already-completed task): redoing a download must
     *   replace the old bytes instead of forking a "(1)" copy or asking about its own file.
     */
    fun finalizeDecision(
        policy: OverwritePolicy,
        legacyAutoRename: Boolean,
        destinationTaken: Boolean,
        replaceOwnArtifact: Boolean = false,
    ): Finalize =
        when {
            replaceOwnArtifact -> Finalize.OVERWRITE
            policy == OverwritePolicy.RENAME || legacyAutoRename -> Finalize.RENAME_NEW
            policy == OverwritePolicy.REPLACE -> Finalize.OVERWRITE
            destinationTaken -> Finalize.ASK
            else -> Finalize.RENAME_NEW
        }

    /** Whether a new download request should be enqueued or reported as a duplicate. */
    enum class Enqueue { ADD, SKIP_DUPLICATE }

    /**
     * [OverwritePolicy.SKIP] ignores re-added URLs (toast), except for browser shares —
     * sharing from another app is an explicit per-download intent. RENAME and REPLACE
     * always proceed; the finalize step applies the same policy to the artifacts.
     */
    fun enqueueDecision(
        policy: OverwritePolicy,
        sameUrlTaskExists: Boolean,
        source: DownloadSource?,
    ): Enqueue =
        if (sameUrlTaskExists && policy == OverwritePolicy.SKIP && source != DownloadSource.BROWSER) {
            Enqueue.SKIP_DUPLICATE
        } else {
            Enqueue.ADD
        }
}
