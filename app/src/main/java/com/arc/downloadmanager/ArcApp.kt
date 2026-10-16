package com.arc.downloadmanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.arc.downloadmanager.data.vault.VaultSession
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

    @Inject
    lateinit var vaultSession: VaultSession

    override fun onCreate() {
        super.onCreate()
        engine.start()
        serviceController.startObserving()
        vaultSession.attach(ProcessLifecycleOwner.get())
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
