package com.arc.downloadmanager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.arc.downloadmanager.worker.RecoveryWorker
import java.util.concurrent.TimeUnit

/**
 * Wakes the process after boot so recovery can reconcile interrupted downloads.
 * The foreground service itself is NOT started from boot (Android restricts dataSync
 * services from BOOT_COMPLETED); tasks are requeued and resume when the user opens the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val request =
            OneTimeWorkRequestBuilder<RecoveryWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "boot_recovery",
            androidx.work.ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
