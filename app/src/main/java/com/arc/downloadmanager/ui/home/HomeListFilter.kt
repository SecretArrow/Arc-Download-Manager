package com.arc.downloadmanager.ui.home

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus

/** Which slice of the task list the Home screen shows. */
enum class HomeFilter {
    ALL,
    RUNNING,
    COMPLETED,
    FAILED,
    ARCHIVED,
}

/** Ordering of the visible task list. */
enum class HomeSort {
    DATE_NEWEST,
    DATE_OLDEST,
    NAME,
    SIZE,
}

/**
 * Pure list-shaping logic for the Home screen: filter, search and sort.
 * Kept side-effect free so it can be unit tested on the JVM.
 */
object HomeListFilter {
    fun apply(
        downloads: List<DownloadEntity>,
        query: String,
        filter: HomeFilter,
        sort: HomeSort,
    ): List<DownloadEntity> {
        val q = query.trim().lowercase()
        val filtered =
            downloads
                .asSequence()
                .filter { matchesFilter(it, filter) }
                .filter { entity ->
                    if (q.isEmpty()) {
                        true
                    } else {
                        entity.filename.lowercase().contains(q) || entity.url.lowercase().contains(q)
                    }
                }
        return when (sort) {
            HomeSort.DATE_NEWEST -> filtered.sortedByDescending { it.createdAt }
            HomeSort.DATE_OLDEST -> filtered.sortedBy { it.createdAt }
            HomeSort.NAME -> filtered.sortedBy { it.filename.lowercase() }
            HomeSort.SIZE -> filtered.sortedByDescending { maxOf(it.totalBytes, it.downloadedBytes) }
        }.toList()
    }

    fun matchesFilter(
        entity: DownloadEntity,
        filter: HomeFilter,
    ): Boolean =
        when (filter) {
            HomeFilter.ALL -> entity.archivedAt == null
            HomeFilter.RUNNING ->
                entity.archivedAt == null && (entity.status.isActive || entity.status.isRunningCandidate)
            HomeFilter.COMPLETED ->
                entity.archivedAt == null && entity.status == DownloadStatus.COMPLETED
            HomeFilter.FAILED ->
                entity.archivedAt == null &&
                    (entity.status == DownloadStatus.FAILED || entity.status == DownloadStatus.AUTH_REQUIRED)
            HomeFilter.ARCHIVED -> entity.archivedAt != null
        }
}
