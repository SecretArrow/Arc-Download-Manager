package com.arc.downloadmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.arc.downloadmanager.engine.DownloadEngine
import com.arc.downloadmanager.service.DownloadServiceController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ArcApp : Application(), Configuration.Provider {

    @Inject
    lateinit var engine: DownloadEngine

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var serviceController: DownloadServiceController

    override fun onCreate() {
        super.onCreate()
        engine.start()
        serviceController.startObserving()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
