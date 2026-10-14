package com.arc.downloadmanager.engine

import kotlinx.coroutines.flow.StateFlow

/** Connectivity view used by the engine; abstracted for JVM tests. */
interface ConnectivityView {
    val isOnline: StateFlow<Boolean>

    val isOnWifi: StateFlow<Boolean>

    /** Re-reads the live connectivity state and republishes it; a self-healing poll hook. */
    fun refreshNow()
}
