package com.arc.downloadmanager.engine

import kotlinx.coroutines.flow.StateFlow

/** Connectivity view used by the engine; abstracted for JVM tests. */
interface ConnectivityView {
    val isOnline: StateFlow<Boolean>

    val isOnWifi: StateFlow<Boolean>
}
