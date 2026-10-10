package com.arc.downloadmanager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.DownloadEngine
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.util.SpeedTracker
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speedTracker = SpeedTracker()
    private val announcedTerminal = mutableSetOf<String>()
    private var startedInForeground = false
    private var observing = false
    private var notifyCompletion = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_RESUME_ALL -> engine.resumeAll()
            ACTION_CANCEL_ALL -> engine.cancelAll()
            else -> startInForegroundIfNeeded()
        }
        observeOnce()
        return START_STICKY
    }

    private fun startInForegroundIfNeeded() {
        if (startedInForeground) return
        val notification = notifications.aggregateNotification(0, 0, 0, 0, emptyList(), anyActive = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startedInForeground = true
    }

    private fun observeOnce() {
        if (observing) return
        observing = true
        scope.launch {
            settingsRepository.settings.collect { settings -> notifyCompletion = settings.notifyCompletion }
        }
        scope.launch {
            store.observeAll().collectLatest { all ->
                val active = all.filter { it.status.isActive }
                val running = active.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONNECTING }
                val downloaded = running.sumOf { it.downloadedBytes }
                val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
                val speed = running.sumOf { speedTracker.record(it.id, it.downloadedBytes) }
                if (active.isEmpty()) {
                    notifications.cancelAggregate()
                    stopSelf()
                } else {
                    startInForegroundIfNeeded()
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
        const val NOTIFICATION_ID = 2001

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
 */
class DownloadServiceController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val store: DownloadStore,
) {
    fun startObserving() {
        if (started) return
        started = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            store.observeActiveCount().collectLatest { count ->
                if (count > 0) {
                    DownloadService.start(context)
                }
            }
        }
    }

    companion object {
        @Volatile
        private var started = false
    }
}
