package com.arc.downloadmanager.ui.components

import com.arc.downloadmanager.domain.model.DownloadStatus

/** Canonical identifiers for task actions that get in-flight button feedback. */
object TaskActions {
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val RETRY = "retry"
    const val CANCEL = "cancel"
    const val RESTART = "restart"
    const val PAUSE_ALL = "pause_all"
    const val RESUME_ALL = "resume_all"
}

/**
 * A task action recorded the moment its button was clicked, used to show a
 * spinner on that button and lock it until the engine reflects the change.
 */
data class PendingAction(
    val action: String,
    /** Status the task was in when the button was clicked. */
    val statusWhenClicked: DownloadStatus,
    val clickedAtMs: Long,
)

/**
 * Pure decision logic for in-flight action feedback. A recorded action stays
 * active while the task still sits in the status it was clicked from; it
 * resolves as soon as the status moves (proof the engine handled the request)
 * or after a timeout, so a rejected no-op can never lock a button forever.
 */
object PendingActionPolicy {
    /** Generous upper bound: engine state writes land within milliseconds. */
    const val DEFAULT_TIMEOUT_MS = 4_000L

    fun isActive(
        pending: PendingAction,
        currentStatus: DownloadStatus,
        nowMs: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = currentStatus == pending.statusWhenClicked && nowMs - pending.clickedAtMs < timeoutMs

    /** Prunes resolved/expired entries; returns the still-active map for the UI. */
    fun activeOnly(
        pending: Map<String, PendingAction>,
        statuses: Map<String, DownloadStatus>,
        nowMs: Long,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Map<String, PendingAction> =
        pending.filter { (id, action) ->
            val status = statuses[id]
            status != null && isActive(action, status, nowMs, timeoutMs)
        }
}
