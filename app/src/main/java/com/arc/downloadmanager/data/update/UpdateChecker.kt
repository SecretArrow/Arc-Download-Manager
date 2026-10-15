package com.arc.downloadmanager.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Latest GitHub release, as surfaced by the releases/latest API. */
data class UpdateInfo(
    val tag: String,
    val url: String,
    val name: String?,
)

/**
 * Checks GitHub Releases for a newer version of the app. Best-effort: any failure
 * (offline, rate-limited, unexpected payload) yields null instead of an error.
 */
@Singleton
class UpdateChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: OkHttpClient,
    ) {
        /** Fetches the latest release from GitHub; null on any failure. */
        suspend fun latest(): UpdateInfo? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val request =
                        Request.Builder()
                            .url(RELEASES_LATEST_URL)
                            .header("Accept", "application/vnd.github+json")
                            .header("User-Agent", USER_AGENT)
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val json = JSONObject(response.body?.string() ?: return@runCatching null)
                        UpdateInfo(
                            tag = json.getString("tag_name"),
                            url = json.getString("html_url"),
                            name = json.optString("name").takeIf { it.isNotBlank() },
                        )
                    }
                }.getOrNull()
            }

        /** [latest] filtered to releases whose tag is actually newer than the installed version. */
        suspend fun check(): UpdateInfo? = latest()?.takeIf { isNewer(it.tag, currentVersion()) }

        private fun currentVersion(): String =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()

        companion object {
            private const val RELEASES_LATEST_URL =
                "https://api.github.com/repos/SecretArrow/Arc-Download-Manager/releases/latest"
            private const val USER_AGENT = "Arc-Download-Manager"

            /** Component-wise numeric version comparison; missing components count as 0. */
            fun isNewer(
                remote: String,
                local: String,
            ): Boolean {
                val remoteParts = parts(remote)
                val localParts = parts(local)
                for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
                    val r = remoteParts.getOrElse(i) { 0 }
                    val l = localParts.getOrElse(i) { 0 }
                    if (r != l) return r > l
                }
                return false
            }

            private fun parts(version: String): List<Int> = version.trim().removePrefix("v").removePrefix("V").split('.').map { it.toIntOrNull() ?: 0 }
        }
    }
