package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.ui.home.HomeFilter
import com.arc.downloadmanager.ui.home.HomeListFilter
import com.arc.downloadmanager.ui.home.HomeSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure Home list-shaping logic (filter / search / sort). */
class HomeListFilterTest {
    private fun entity(
        id: String,
        filename: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        createdAt: Long = 0L,
        totalBytes: Long = 0L,
        downloadedBytes: Long = 0L,
        url: String = "https://example.test/$filename",
        archivedAt: Long? = null,
    ): DownloadEntity =
        DownloadEntity(
            id = id,
            url = url,
            filename = filename,
            status = status,
            createdAt = createdAt,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            archivedAt = archivedAt,
        )

    private val sample =
        listOf(
            entity("a", "alpha.zip", DownloadStatus.DOWNLOADING, createdAt = 100, totalBytes = 500, downloadedBytes = 250),
            entity("b", "beta.mp4", DownloadStatus.COMPLETED, createdAt = 300, totalBytes = 900),
            entity("c", "gamma.iso", DownloadStatus.FAILED, createdAt = 200, totalBytes = 4000),
            entity("d", "old.apk", DownloadStatus.PAUSED, createdAt = 50, archivedAt = 999),
        )

    @Test
    fun `ALL excludes archived tasks`() {
        val result = HomeListFilter.apply(sample, "", HomeFilter.ALL, HomeSort.DATE_NEWEST)
        assertEquals(listOf("b", "c", "a"), result.map { it.id })
    }

    @Test
    fun `RUNNING matches active and queued candidates but not archived`() {
        val result = HomeListFilter.apply(sample, "", HomeFilter.RUNNING, HomeSort.DATE_NEWEST)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `COMPLETED and FAILED slices are disjoint`() {
        val completed = HomeListFilter.apply(sample, "", HomeFilter.COMPLETED, HomeSort.DATE_NEWEST)
        val failed = HomeListFilter.apply(sample, "", HomeFilter.FAILED, HomeSort.DATE_NEWEST)
        assertEquals(listOf("b"), completed.map { it.id })
        assertEquals(listOf("c"), failed.map { it.id })
    }

    @Test
    fun `ARCHIVED shows only archived tasks`() {
        val result = HomeListFilter.apply(sample, "", HomeFilter.ARCHIVED, HomeSort.DATE_NEWEST)
        assertEquals(listOf("d"), result.map { it.id })
    }

    @Test
    fun `search matches filename and url case-insensitively`() {
        val byName = HomeListFilter.apply(sample, "GAMMA", HomeFilter.ALL, HomeSort.DATE_NEWEST)
        assertEquals(listOf("c"), byName.map { it.id })
        val byUrl =
            HomeListFilter.apply(
                sample,
                "example.test/beta",
                HomeFilter.ALL,
                HomeSort.DATE_NEWEST,
            )
        assertEquals(listOf("b"), byUrl.map { it.id })
    }

    @Test
    fun `sort by name ignores case and sort by size prefers totalBytes`() {
        val byName = HomeListFilter.apply(sample, "", HomeFilter.ALL, HomeSort.NAME)
        assertEquals(listOf("a", "b", "c"), byName.map { it.id })
        val bySize = HomeListFilter.apply(sample, "", HomeFilter.ALL, HomeSort.SIZE)
        assertEquals(listOf("c", "b", "a"), bySize.map { it.id })
    }

    @Test
    fun `sort date oldest reverses newest order`() {
        val newest = HomeListFilter.apply(sample, "", HomeFilter.ALL, HomeSort.DATE_NEWEST)
        val oldest = HomeListFilter.apply(sample, "", HomeFilter.ALL, HomeSort.DATE_OLDEST)
        assertEquals(newest.reversed(), oldest)
    }

    @Test
    fun `unknown-size tasks sort last by size using downloadedBytes fallback`() {
        val unknown =
            entity("u", "unknown.bin", DownloadStatus.DOWNLOADING, createdAt = 400, totalBytes = -1, downloadedBytes = 50)
        val result = HomeListFilter.apply(sample + unknown, "", HomeFilter.ALL, HomeSort.SIZE)
        assertTrue(result.last().id == "u")
    }
}
