package com.arc.downloadmanager.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arc.downloadmanager.engine.DownloadEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Reconciles temporary segment files with database records and requeues interrupted tasks
 * after process death, boot, or connectivity loss.
 */
@HiltWorker
class RecoveryWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val engine: DownloadEngine,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            engine.recover()
            return Result.success()
        }
    }
