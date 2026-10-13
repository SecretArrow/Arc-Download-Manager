package com.arc.downloadmanager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.arc.downloadmanager.MainActivity
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.store.DownloadStore
import com.arc.downloadmanager.service.DownloadService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home-screen widget: aggregate queue status plus pause/resume/add quick controls.
 * State is pushed live from [DownloadService] and re-read from the database on widget updates.
 */
@AndroidEntryPoint
class ArcWidgetProvider : AppWidgetProvider() {
    @Inject
    lateinit var store: DownloadStore

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                push(context, store.observeAll().first().filter { it.archivedAt == null })
            } catch (_: Exception) {
                // Widget refresh is best-effort; never crash the host process.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun push(
            context: Context,
            all: List<DownloadEntity>,
        ) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ArcWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val active = all.count { it.status.isActive }
            val queued = all.count { it.status == DownloadStatus.QUEUED }
            val failed = all.count { it.status == DownloadStatus.FAILED }
            val views =
                RemoteViews(context.packageName, R.layout.widget_downloads).apply {
                    setTextViewText(
                        R.id.widget_status,
                        if (all.isEmpty()) {
                            context.getString(R.string.widget_status_idle)
                        } else {
                            context.getString(R.string.home_queue_count, active, queued, failed)
                        },
                    )
                    setViewVisibility(R.id.widget_pause, if (active > 0) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.widget_play, if (active > 0) View.GONE else View.VISIBLE)
                    setOnClickPendingIntent(R.id.widget_pause, servicePending(context, DownloadService.ACTION_PAUSE_ALL))
                    setOnClickPendingIntent(R.id.widget_play, servicePending(context, DownloadService.ACTION_RESUME_ALL))
                    setOnClickPendingIntent(
                        R.id.widget_add,
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            manager.updateAppWidget(ids, views)
        }

        private fun servicePending(
            context: Context,
            action: String,
        ): PendingIntent =
            PendingIntent.getService(
                context,
                action.hashCode(),
                Intent(context, DownloadService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
