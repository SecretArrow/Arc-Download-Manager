package com.arc.downloadmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arc.downloadmanager.MainActivity
import com.arc.downloadmanager.R
import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.util.Format
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and posts download notifications. No sensitive data (URLs, cookies) is ever included. */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    private fun createChannels() {
        val downloads = NotificationChannel(
            CHANNEL_DOWNLOADS,
            context.getString(R.string.notif_channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_downloads_desc)
            setShowBadge(false)
        }
        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notif_channel_status),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(downloads)
        notificationManager.createNotificationChannel(status)
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun aggregateNotification(
        activeCount: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
        activeTasks: List<DownloadEntity>,
        anyActive: Boolean,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val progress = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0
        val title = context.getString(
            R.string.notif_title_active,
            activeCount,
            Format.speed(context, speedBps),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setProgress(100, progress, totalBytes <= 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        val inbox = NotificationCompat.InboxStyle()
        activeTasks.take(3).forEach { task ->
            val pct = if (task.totalBytes > 0) " (${task.downloadedBytes * 100 / task.totalBytes}%)" else ""
            inbox.addLine("${task.filename}$pct")
        }
        if (activeTasks.size > 3) inbox.addLine("+${activeTasks.size - 3} more")
        builder.setStyle(inbox)

        if (anyActive) {
            builder.addAction(buildAction(DownloadService.ACTION_PAUSE_ALL, context.getString(R.string.notif_pause_all)))
        } else {
            builder.addAction(buildAction(DownloadService.ACTION_RESUME_ALL, context.getString(R.string.notif_resume_all)))
        }
        builder.addAction(buildAction(DownloadService.ACTION_CANCEL_ALL, context.getString(R.string.notif_cancel_all)))
        return builder.build()
    }

    private fun buildAction(action: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = when (action) {
            DownloadService.ACTION_PAUSE_ALL -> android.R.drawable.ic_media_pause
            DownloadService.ACTION_RESUME_ALL -> android.R.drawable.ic_media_play
            else -> android.R.drawable.ic_menu_close_clear_cancel
        }
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }

    fun postAggregate(notification: Notification) {
        if (!canNotify()) return
        runCatching { notificationManager.notify(NOTIF_AGGREGATE_ID, notification) }
    }

    fun cancelAggregate() {
        notificationManager.cancel(NOTIF_AGGREGATE_ID)
    }

    fun postCompleted(entity: DownloadEntity) {
        postStatus(
            id = entity.id.hashCode(),
            title = context.getString(R.string.notif_completed),
            text = entity.filename,
            success = true,
        )
    }

    fun postFailed(entity: DownloadEntity) {
        postStatus(
            id = entity.id.hashCode(),
            title = context.getString(R.string.notif_failed),
            text = entity.filename,
            success = false,
        )
    }

    private fun postStatus(id: Int, title: String, text: String, success: Boolean) {
        if (!canNotify()) return
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { notificationManager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_STATUS = "status"
        const val NOTIF_AGGREGATE_ID = 1001
    }
}
