package com.arc.downloadmanager.widget

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arc.downloadmanager.R
import com.arc.downloadmanager.engine.DownloadEngine
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.service.DownloadService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile: shows whether downloads are running and toggles pause/resume-all.
 * Tapping a tile counts as user interaction, so starting the foreground service on resume is allowed.
 */
@AndroidEntryPoint
class ArcTileService : TileService() {
    @Inject
    lateinit var engine: DownloadEngine

    @Inject
    lateinit var store: DownloadStore

    @Inject
    lateinit var quotaGuard: com.arc.downloadmanager.service.QuotaGuard

    @Inject
    lateinit var notifications: com.arc.downloadmanager.service.NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        scope.launch { render() }
    }

    override fun onClick() {
        scope.launch {
            val anyActive = runCatching { store.observeAll().first().any { it.status.isActive } }.getOrDefault(false)
            if (anyActive) {
                engine.pauseAll()
            } else if (quotaGuard.isExhausted()) {
                // The dataSync 6 h budget is gone; resuming now would queue tasks into a
                // service that cannot start. Foreground resets the timer — ask for that.
                notifications.postQuotaPaused()
            } else {
                engine.resumeAll()
                try {
                    DownloadService.start(applicationContext)
                } catch (_: IllegalStateException) {
                    // Quota rejected the start (subclass ForegroundServiceStartNotAllowedException).
                    quotaGuard.markExhausted()
                    notifications.postQuotaPaused()
                } catch (_: SecurityException) {
                    quotaGuard.markExhausted()
                    notifications.postQuotaPaused()
                }
            }
            render()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun render() {
        val tile = qsTile ?: return
        val all = runCatching { store.observeAll().first() }.getOrDefault(emptyList())
        val active = all.count { it.status.isActive }
        tile.state = if (active > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active > 0) R.string.tile_pause_all else R.string.tile_resume_all)
        }
        tile.updateTile()
    }
}
