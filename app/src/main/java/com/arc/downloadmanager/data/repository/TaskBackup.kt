package com.arc.downloadmanager.data.repository

import com.arc.downloadmanager.domain.model.DownloadRequest
import com.arc.downloadmanager.domain.model.DownloadSource
import com.arc.downloadmanager.domain.model.Priority
import com.arc.downloadmanager.engine.store.DownloadStore
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an import run. */
data class ImportResult(val added: Int, val skipped: Int)

/** JSON document plus how many tasks were written into it. */
data class ExportResult(val json: String, val count: Int)

/**
 * Export/import of the non-terminal task list as a portable JSON document.
 * Cookies are NEVER exported — they carry browser sessions and must not leave the device.
 */
@Singleton
class TaskBackup
    @Inject
    constructor(
        private val downloadRepository: DownloadRepository,
        private val store: DownloadStore,
    ) {
        /** Builds the backup document: { "app": "arc", "version": 1, "tasks": [...] }. */
        suspend fun export(): ExportResult {
            val tasks = store.getAllNow().filter { !it.status.isTerminal }
            val arr = JSONArray()
            tasks.forEach { task ->
                arr.put(
                    JSONObject().apply {
                        put("url", task.url)
                        put("filename", task.filename)
                        put("priority", task.priority.value)
                        put("wifiOnly", task.wifiOnly)
                        task.referer?.let { put("referer", it) }
                        task.userAgent?.let { put("userAgent", it) }
                    },
                )
            }
            val json = JSONObject().put("app", "arc").put("version", 1).put("tasks", arr).toString(2)
            return ExportResult(json, tasks.size)
        }

        suspend fun exportJson(): String = export().json

        /** Re-imports a backup document; entries whose URL already exists count as skipped. */
        suspend fun importJson(text: String): ImportResult {
            val entries =
                runCatching {
                    JSONObject(text).optJSONArray("tasks") ?: JSONArray()
                }.getOrDefault(JSONArray())
            var added = 0
            var skipped = 0
            for (i in 0 until entries.length()) {
                val obj = runCatching { entries.getJSONObject(i) }.getOrNull()
                if (obj == null) {
                    skipped++
                    continue
                }
                val url = obj.optString("url").trim()
                if (url.isEmpty()) {
                    skipped++
                    continue
                }
                runCatching {
                    if (store.getByUrl(url).isNotEmpty()) {
                        skipped++
                    } else {
                        val request =
                            DownloadRequest(
                                url = url,
                                filename = obj.optString("filename").takeIf { it.isNotBlank() },
                                priority = Priority.fromValue(obj.optInt("priority", Priority.NORMAL.value)),
                                wifiOnly = obj.optBoolean("wifiOnly").takeIf { it },
                                referer = obj.optString("referer").takeIf { it.isNotBlank() },
                                userAgent = obj.optString("userAgent").takeIf { it.isNotBlank() },
                                source = DownloadSource.MANUAL,
                            )
                        when (downloadRepository.add(request)) {
                            is AddResult.Started -> added++
                            else -> skipped++
                        }
                    }
                }.onFailure { skipped++ }
            }
            return ImportResult(added, skipped)
        }
    }
