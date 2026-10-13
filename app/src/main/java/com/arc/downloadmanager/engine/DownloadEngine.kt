package com.arc.downloadmanager.engine

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.engine.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central download orchestrator: owns scheduling, concurrency limits, pause/cancel control
 * channels, adaptive parallelism and recovery. Persists everything through [DownloadStore].
 */
@Singleton
class DownloadEngine
    @Inject
    constructor(
        private val store: DownloadStore,
        baseClient: OkHttpClient,
        private val storage: TaskStorage,
        private val settingsRepository: EngineSettingsSource,
        private val networkMonitor: ConnectivityView,
        private val cookieProvider: CookieProvider,
        torrentEngine: TorrentEngine,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val taskRunner =
            TaskRunner(
                store = store,
                baseClient = baseClient,
                storage = storage,
                networkMonitor = networkMonitor,
                cookieProvider = cookieProvider,
                torrentEngine = torrentEngine,
            )

        private val running = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
        private val controls = ConcurrentHashMap<String, TaskControl>()
        private val scheduleMutex = Mutex()
        private val retryNotBefore = ConcurrentHashMap<String, Long>()
        private val throttledHostsUntil = ConcurrentHashMap<String, Long>()

        private val started = MutableStateFlow(false)

        /** Emits the engine's liveness so the UI/service can react. */
        val isStarted: Flow<Boolean> = started

        fun start() {
            if (!started.compareAndSet(expect = false, update = true)) return
            scope.launch {
                // Recover tasks interrupted by process death.
                recoverInterruptedTasks()
                schedule()
            }
            scope.launch {
                // Ticker: retries queued tasks after backoff, waits out rate limits and network loss.
                while (true) {
                    delay(TICKER_INTERVAL_MS)
                    if (!networkMonitor.isOnline.value) continue
                    wakeWaitingTasks()
                    schedule()
                }
            }
            scope.launch {
                networkMonitor.isOnline.collect { online ->
                    if (online) {
                        wakeWaitingTasks()
                        schedule()
                    }
                }
            }
        }

        /** Public API ---------------------------------------------------------------- */

        suspend fun enqueue(entity: DownloadEntity): String {
            store.insert(entity)
            schedule()
            return entity.id
        }

        fun pause(id: String) {
            val control = controls[id]
            if (control != null) {
                control.pauseRequested = true
                scope.launch { store.updateStatus(id, DownloadStatus.PAUSING) }
            } else {
                scope.launch {
                    val entity = store.getById(id) ?: return@launch
                    if (entity.status.isRunningCandidate || entity.status.isActive) {
                        store.updateStatus(id, DownloadStatus.PAUSED)
                    }
                }
            }
        }

        fun resume(id: String) {
            scope.launch {
                val entity = store.getById(id) ?: return@launch
                val resumable =
                    entity.status in
                        listOf(
                            DownloadStatus.PAUSED,
                            DownloadStatus.FAILED,
                            DownloadStatus.AUTH_REQUIRED,
                            DownloadStatus.CANCELED,
                            DownloadStatus.WAITING_NETWORK,
                            DownloadStatus.WAITING_STORAGE,
                        )
                if (resumable) {
                    store.updateStatus(id, DownloadStatus.QUEUED, null, null)
                    schedule()
                }
            }
        }

        fun cancel(id: String) {
            val control = controls[id]
            if (control != null) control.cancelRequested = true
            scope.launch {
                val entity = store.getById(id) ?: return@launch
                if (entity.status.isTerminal && entity.status != DownloadStatus.CANCELED) return@launch
                storage.cleanup(id)
                store.deleteSegments(id)
                store.updateStatus(id, DownloadStatus.CANCELED, DownloadError.CANCELED.name, null)
            }
        }

        fun retry(id: String) {
            scope.launch {
                store.getById(id) ?: return@launch
                store.resetForRetry(id)
                retryNotBefore.remove(id)
                schedule()
            }
        }

        /** Restarts a task from scratch, discarding all partial data. */
        fun restart(id: String) {
            scope.launch {
                val entity = store.getById(id) ?: return@launch
                if (running.containsKey(id)) controls[id]?.cancelRequested = true
                storage.cleanup(id)
                store.deleteSegments(id)
                store.update(
                    entity.copy(
                        downloadedBytes = 0L,
                        status = DownloadStatus.QUEUED,
                        errorCode = null,
                        errorMessage = null,
                        retryCount = 0,
                        resumable = false,
                        finalUri = null,
                        completedAt = null,
                    ),
                )
                store.updateProgress(id, 0L, entity.totalBytes)
                schedule()
            }
        }

        fun pauseAll() {
            scope.launch {
                store.running().forEach { pause(it.id) }
                store.pending().forEach { store.updateStatus(it.id, DownloadStatus.PAUSED) }
            }
        }

        /**
         * Archives (or restores) tasks in bulk. Archiving an active task pauses it first;
         * archived tasks never auto-resume and stay hidden from the queue until restored.
         */
        fun archive(
            ids: List<String>,
            archived: Boolean,
        ) {
            if (ids.isEmpty()) return
            scope.launch {
                if (archived) {
                    ids.forEach { id ->
                        val control = controls[id]
                        if (control != null) {
                            control.pauseRequested = true
                            store.updateStatus(id, DownloadStatus.PAUSED)
                        }
                    }
                }
                store.setArchived(ids, if (archived) System.currentTimeMillis() else null)
            }
        }

        /**
         * Replaces mutable task metadata (URL and/or filename). Rejected while the task is
         * active or completed; when the URL changes, all partial data is discarded so the
         * next start is consistent with the new source.
         */
        suspend fun setTaskMeta(
            id: String,
            url: String,
            filename: String,
        ): Boolean {
            // A winding-down coroutine may still write part files; edits are only safe once
            // the runner has fully stopped (statuses above already imply no live control).
            if (running.containsKey(id)) return false
            val entity = store.getById(id) ?: return false
            when (
                val verdict =
                    TaskEditPolicy.evaluate(
                        status = entity.status,
                        oldUrl = entity.url,
                        newUrl = url.trim(),
                        newFilename = filename.trim(),
                    )
            ) {
                is TaskEditPolicy.Verdict.Rejected -> return false
                is TaskEditPolicy.Verdict.Allowed -> {
                    val newUrl = url.trim()
                    val newFilename = FilenameDetector.sanitize(filename.trim())
                    store.updateMeta(id, newUrl, newFilename)
                    if (verdict.resetProgress) {
                        storage.cleanup(id)
                        store.deleteSegments(id)
                        val current = store.getById(id) ?: return true
                        store.update(
                            current.copy(
                                downloadedBytes = 0L,
                                resumable = false,
                                acceptRanges = false,
                                eTag = null,
                                lastModified = null,
                                finalUrl = null,
                                errorCode = null,
                                errorMessage = null,
                            ),
                        )
                        store.updateProgress(id, 0L, current.totalBytes)
                    }
                    return true
                }
            }
        }

        /** Bulk delete of any tasks, optionally removing their finalized files too. */
        suspend fun deleteMany(
            ids: List<String>,
            deleteFiles: Boolean,
        ) {
            ids.forEach { id -> delete(id, deleteFiles) }
        }

        /** Removes all visible finished (COMPLETED/CANCELED) tasks; returns the removed count. */
        suspend fun clearFinished(): Int {
            val doomed =
                store.getAllNow().filter {
                    (it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.CANCELED) && it.archivedAt == null
                }
            doomed.forEach { entity ->
                storage.cleanup(entity.id)
                store.deleteSegments(entity.id)
                store.delete(entity.id)
            }
            return doomed.size
        }

        /** Cancels every active and pending task. */
        fun cancelAll() {
            scope.launch {
                store.getAllNow()
                    .filter { !it.status.isTerminal }
                    .forEach { cancel(it.id) }
            }
        }

        fun resumeAll() {
            scope.launch {
                store.getAllNow()
                    .filter {
                        it.archivedAt == null &&
                            (
                                it.status == DownloadStatus.PAUSED ||
                                    it.status == DownloadStatus.FAILED ||
                                    it.status == DownloadStatus.AUTH_REQUIRED ||
                                    it.status == DownloadStatus.CANCELED ||
                                    it.status == DownloadStatus.WAITING_NETWORK ||
                                    it.status == DownloadStatus.WAITING_STORAGE
                            )
                    }
                    .forEach { resume(it.id) }
            }
        }

        suspend fun delete(
            id: String,
            deleteFile: Boolean,
        ) {
            cancel(id)
            val entity = store.getById(id)
            if (deleteFile && entity?.finalUri != null) {
                storage.deleteFinal(entity.finalUri)
            }
            store.delete(id)
        }

        fun schedule() {
            scope.launch { pump() }
        }

        /** Runs recovery (reconcile temp data, requeue interrupted tasks) then schedules. */
        fun recover() {
            scope.launch {
                recoverInterruptedTasks()
                schedule()
            }
        }

        /** Adaptive parallelism: reduces connections for hosts that throttled us recently. */
        fun segmentCapFor(host: String): Int {
            val until = throttledHostsUntil[host] ?: return 16
            return if (System.currentTimeMillis() < until) MIN_THROTTLED_SEGMENTS else 16
        }

        fun noteThrottled(
            host: String,
            retryAfterMs: Long,
        ) {
            throttledHostsUntil[host] = System.currentTimeMillis() + retryAfterMs
        }

        fun isRunning(id: String): Boolean = running.containsKey(id)

        /** Internals ------------------------------------------------------------------ */

        private suspend fun pump() {
            val settings = settingsRepository.snapshot()
            scheduleMutex.withLock {
                val capacity = settings.maxConcurrentFiles - running.size
                if (capacity <= 0) return
                val candidates =
                    store.pending()
                        .filter { !running.containsKey(it.id) }
                        .sortedWith(compareByDescending<DownloadEntity> { it.priority.value }.thenBy { it.createdAt })
                for (task in candidates) {
                    if (running.size >= settings.maxConcurrentFiles) break
                    val notBefore = retryNotBefore[task.id] ?: 0L
                    if (System.currentTimeMillis() < notBefore) continue
                    if (!canStartNow(task, settings)) continue
                    launchTask(task.id, settings)
                }
            }
        }

        private suspend fun canStartNow(
            task: DownloadEntity,
            settings: EngineSettings,
        ): Boolean {
            val online = networkMonitor.isOnline.value
            if (!online) {
                if (task.status != DownloadStatus.WAITING_NETWORK) {
                    scope.launch { store.updateStatus(task.id, DownloadStatus.WAITING_NETWORK) }
                }
                return false
            }
            val wantsWifi = task.wifiOnly || settings.wifiOnly
            if (wantsWifi && !networkMonitor.isOnWifi.value) {
                if (task.status != DownloadStatus.WAITING_NETWORK) {
                    scope.launch { store.updateStatus(task.id, DownloadStatus.WAITING_NETWORK) }
                }
                return false
            }
            val needed = if (task.totalBytes > 0) task.totalBytes - task.downloadedBytes else 128L * 1024L * 1024L
            if (!storage.hasFreeSpace(needed.coerceAtLeast(0))) {
                if (task.status != DownloadStatus.WAITING_STORAGE) {
                    scope.launch { store.updateStatus(task.id, DownloadStatus.WAITING_STORAGE) }
                }
                return false
            }
            return true
        }

        private fun launchTask(
            id: String,
            settings: EngineSettings,
        ) {
            val control = TaskControl()
            controls[id] = control
            running[id] =
                scope.launch {
                    try {
                        taskRunner.run(
                            id = id,
                            settings = settings,
                            control = control,
                            segmentCapFor = ::segmentCapFor,
                            onEnded = { endedId ->
                                scope.launch {
                                    noteHostThrottleFromStore(endedId)
                                    pump()
                                }
                            },
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        store.updateStatus(id, DownloadStatus.FAILED, DownloadError.UNKNOWN.name, e.message)
                    } finally {
                        controls.remove(id)
                        running.remove(id)
                        scope.launch { pump() }
                    }
                }
        }

        private suspend fun noteHostThrottleFromStore(id: String) {
            val entity = store.getById(id) ?: return
            if (entity.errorCode == DownloadError.RATE_LIMITED.name) {
                val host = CookieScopePolicy.hostOf(entity.finalUrl ?: entity.url)
                noteThrottled(host, DEFAULT_THROTTLE_MS)
                retryNotBefore[id] = System.currentTimeMillis() + DEFAULT_THROTTLE_MS
            }
        }

        private suspend fun recoverInterruptedTasks() {
            store.interrupted().forEach { entity ->
                // MERGING/VERIFYING tasks may be mid-finalization; restart them as queued.
                store.updateStatus(entity.id, DownloadStatus.QUEUED, null, null)
            }
            // Reconcile temp files: remove orphaned dirs, truncate oversized parts on next run.
            val all = store.getAllNow()
            storage.cleanupOrphans(all.map { it.id }.toSet())
        }

        private suspend fun wakeWaitingTasks() {
            val now = System.currentTimeMillis()
            store.getAllNow().forEach { entity ->
                if (entity.archivedAt != null) return@forEach
                if (entity.status == DownloadStatus.WAITING_NETWORK || entity.status == DownloadStatus.WAITING_STORAGE) {
                    val notBefore = retryNotBefore[entity.id] ?: 0L
                    if (now >= notBefore) {
                        store.updateStatus(entity.id, DownloadStatus.QUEUED, null, null)
                    }
                }
            }
        }

        companion object {
            private const val TICKER_INTERVAL_MS = 15_000L
            private const val DEFAULT_THROTTLE_MS = 30_000L
            private const val MIN_THROTTLED_SEGMENTS = 2
        }
    }
