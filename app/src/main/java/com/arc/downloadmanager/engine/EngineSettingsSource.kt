package com.arc.downloadmanager.engine

import com.arc.downloadmanager.data.settings.EngineSettings
import kotlinx.coroutines.flow.Flow

/** Read-side of app settings consumed by the engine (abstracted for JVM tests). */
interface EngineSettingsSource {
    suspend fun snapshot(): EngineSettings

    val settings: Flow<EngineSettings>
}
