package com.arc.downloadmanager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.DownloadEngine
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.util.SpeedTracker
import com.arc.downloadmanager.widget.ArcWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service keeping downloads alive while the app is backgrounded.
 * Shows aggregate progress and pause/resume/cancel actions.
 *
 * Android 15+ enforces a six-hour runtime budget per 24 hours for the dataSync
 * foreground-service type (see [QuotaGuard]). This service handles the two failure
 * modes without crashing:
 *  1. [onTimeout] fires — all tasks are paused, a "tap to resume" notification is
 *     posted, and the service stops within the few-seconds deadline.
 *  2. [android.app.ForegroundServiceStartNotAllowedException] is thrown because the
 *     budget is already exhausted — the start is swallowed, tasks stay paused, and the
 *     user is asked to open the app (foreground resets the timer).
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject
    lateinit var engine: DownloadEngine

    @Inject
    lateinit var store: DownloadStore

    @Inject
    lateinit var notifications: NotificationHelper

    @Inject
    lateinit var settingsRepository: com.arc.downloadmanager.data.settings.SettingsRepository

    @Inject
    lateinit var quotaGuard: QuotaGuard

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speedTracker = SpeedTracker()
    private val announcedTerminal = mutableSetOf<String>()
    private var startedInForeground = false
    private var observing = false
    private var notifyCompletion = true

    /** Snapshot of tasks that are active or runnable, refreshed on every store emission. */
    @Volatile
    private var lastRunnableIds: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                startInForegroundIfNeeded()
                engine.pauseAll()
            }
            ACTION_RESUME_ALL -> {
                if (quotaGuard.isExhausted()) {
                    // The 6 h dataSync budget is gone: do not queue tasks into a service
                    // that cannot run. Opening the app resets the timer, so point there.
                    notifications.postQuotaPaused()
                } else {
                    startInForegroundIfNeeded()
                    engine.resumeAll()
                }
            }
            ACTION_CANCEL_ALL -> {
                startInForegroundIfNeeded()
                engine.cancelAll()
            }
            else -> startInForegroundIfNeeded()
        }
        observeOnce()
        return START_STICKY
    }

    private fun startInForegroundIfNeeded() {
        if (startedInForeground) return
        val notification = notifications.aggregateNotification(0, 0, 0, 0, emptyList(), anyActive = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use the SAME id as the live aggregate notification: the platform-required
                // foreground notification IS the aggregate one, so postAggregate() updates it
                // in place and the user only ever sees a single, live-updating notification
                // (previously a separate id froze at 0 B/s — the duplicate notification).
                startForeground(NotificationHelper.NOTIF_AGGREGATE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NotificationHelper.NOTIF_AGGREGATE_ID, notification)
            }
            startedInForeground = true
        } catch (rejected: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+, subclass of ISE):
            // "Time limit already exhausted for foreground service type dataSync".
            onForegroundStartRejected()
        } catch (rejected: SecurityException) {
            onForegroundStartRejected()
        }
    }

    /**
     * Quota-exhausted / start-denied path: pause everything so no task waits on a service
     * that will not run, tell the user how to resume, and leave the foreground state.
     */
    private fun onForegroundStartRejected() {
        startedInForeground = false
        quotaGuard.markExhausted()
        quotaGuard.rememberQuotaPaused(lastRunnableIds)
        engine.pauseAll()
        notifications.cancelAggregate()
        notifications.postQuotaPaused()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Called by the platform on Android 15+ when a dataSync service hits the six-hour
     * budget. The service stops being foreground immediately and must call
     * [stopSelf] within a few seconds — everything here is quick, synchronous work.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ) {
            handleTimeout()
        }
    }

    /** shortService variant (API 34); Arc does not use shortService but stays defensive. */
    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        quotaGuard.markExhausted()
        quotaGuard.rememberQuotaPaused(lastRunnableIds)
        engine.pauseAll()
        notifications.cancelAggregate()
        notifications.postQuotaPaused()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun observeOnce() {
        if (observing) return
        observing = true
        scope.launch {
            settingsRepository.settings.collect { settings -> notifyCompletion = settings.notifyCompletion }
        }
        scope.launch {
            store.observeAll().collectLatest { all ->
                lastRunnableIds = all.filter { it.status.isActive || it.status.isRunningCandidate }.map { it.id }.toSet()
                val active = all.filter { it.status.isActive }
                val running = active.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING }
                val downloaded = running.sumOf { it.downloadedBytes }
                val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
                val speed = running.sumOf { speedTracker.record(it.id, it.downloadedBytes) }
                if (active.isEmpty()) {
                    ArcWidgetProvider.push(applicationContext, all)
                    notifications.cancelAggregate()
                    stopSelf()
                } else {
                    startInForegroundIfNeeded()
                    ArcWidgetProvider.push(applicationContext, all)
                    notifications.postAggregate(
                        notifications.aggregateNotification(
                            activeCount = active.size,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            speedBps = speed,
                            activeTasks = active,
                            anyActive = running.isNotEmpty(),
                        ),
                    )
                }
                all.forEach { entity -> announceTransition(entity) }
            }
        }
    }

    private fun announceTransition(entity: DownloadEntity) {
        when (entity.status) {
            DownloadStatus.COMPLETED -> {
                if (announcedTerminal.add(entity.id) && notifyCompletion) notifications.postCompleted(entity)
            }
            DownloadStatus.FAILED -> {
                if (announcedTerminal.add(entity.id)) notifications.postFailed(entity)
            }
            else -> announcedTerminal.remove(entity.id)
        }
    }

    override fun onDestroy() {
        observing = false
        speedTracker.reset()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.arc.downloadmanager.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.arc.downloadmanager.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.arc.downloadmanager.CANCEL_ALL"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}

/**
 * Observes the download queue and starts/stops the foreground service as tasks come and go.
 * All service starts are guarded against the Android 15+ dataSync time-limit rejection.
 */
class DownloadServiceController
    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
        private val store: DownloadStore,
        private val quotaGuard: QuotaGuard,
        private val notifications: NotificationHelper,
        private val engine: DownloadEngine,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @Volatile
        private var lastActiveCount = 0

        fun startObserving() {
            if (started) return
            started = true
            scope.launch {
                store.observeActiveCount().collectLatest { count ->
                    lastActiveCount = count
                    if (count > 0) {
                        safeStart()
                    }
                }
            }
        }

        private fun safeStart() {
            if (quotaGuard.skipStarts) return
            try {
                DownloadService.start(context)
            } catch (rejected: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException on API 31+.
                onForegroundStartRejected()
            } catch (rejected: SecurityException) {
                onForegroundStartRejected()
            }
        }

        private fun onForegroundStartRejected() {
            quotaGuard.markExhausted()
            notifications.postQuotaPaused()
        }

        /**
         * Called from MainActivity.onStart. Bringing the app to the foreground resets the
         * six-hour dataSync budget, so: clear the exhausted state, auto-resume downloads
         * that were paused by the timeout, and restart the foreground service if needed.
         */
        fun onAppForeground() {
            quotaGuard.clearOnForeground()
            val quotaPaused = quotaGuard.consumeQuotaPausedIds()
            quotaPaused.forEach { id -> engine.resume(id) }
            if (lastActiveCount > 0 || quotaPaused.isNotEmpty()) safeStart()
        }

        companion object {
            @Volatile
            private var started = false
        }
    }
