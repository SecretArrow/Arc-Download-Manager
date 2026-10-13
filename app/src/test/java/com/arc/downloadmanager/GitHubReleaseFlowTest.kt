package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.CookieScopePolicy
import com.arc.downloadmanager.engine.TaskControl
import com.arc.downloadmanager.engine.TaskRunner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the GitHub release-asset download flow: the original URL 302-redirects
 * to a signed CDN link (release-assets.githubusercontent.com) that carries the file name in
 * Content-Disposition and expires after about an hour. A fake DNS maps github.test and cdn.test
 * onto the local MockWebServer so redirect hops genuinely cross hosts.
 */
class GitHubReleaseFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var storage: FakeTaskStorage
    private lateinit var runner: TaskRunner

    private val assetPath = "/SecretArrow/Arc-Download-Manager/releases/download/v1.4.0/app-release.apk"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryDownloadStore()
        storage = FakeTaskStorage(tmp.newFolder("storage"))
        runner =
            TaskRunner(
                store = store,
                baseClient = OkHttpClient(),
                storage = storage,
                networkMonitor = FakeConnectivity(),
                cookieProvider = FixedCookieProvider(),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** OkHttp client whose DNS resolves the fake test hosts onto the local MockWebServer. */
    private fun clientWithFakeHosts(): OkHttpClient =
        OkHttpClient.Builder()
            .dns { hostname ->
                if (hostname == "github.test" || hostname == "cdn.test") {
                    listOf(InetAddress.getByAddress(hostname, server.socketAddress.address))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
            .build()

    private fun awaitStatus(
        id: String,
        vararg statuses: DownloadStatus,
        timeoutMs: Long = 15_000,
    ): DownloadEntity =
        runBlocking {
            withTimeout(timeoutMs) {
                while (true) {
                    store.getById(id)?.let { if (it.status in statuses) return@withTimeout it }
                    Thread.sleep(25)
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException()
            }
        }

    private fun insert(entity: DownloadEntity) = runBlocking { store.insert(entity) }

    private fun entity(
        url: String,
        filename: String = "app-release.apk",
        segments: Int = 0,
    ) = DownloadEntity(
        id = "gh-${System.nanoTime()}",
        url = url,
        filename = filename,
        segmentsPlanned = segments,
    )

    /** Classic GitHub release flow: 302 to a signed asset URL that serves the bytes. */
    private class GitHubDispatcher(
        private val payload: ByteArray,
        private val origin: String,
        private val signedOrigin: String = origin,
        private val signed403WithCookies: Boolean = false,
    ) : Dispatcher() {
        val signedCounter = AtomicInteger(0)
        var sawCookieOnSignedRequest = false
            private set

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: "/"
            return when {
                path.startsWith(assetPath) -> {
                    val n = signedCounter.incrementAndGet()
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", "$signedOrigin/signed/$n/app-release.apk?sig=fresh-$n")
                }
                path.startsWith("/signed/") -> {
                    if (request.getHeader("Cookie") != null) {
                        sawCookieOnSignedRequest = true
                        if (signed403WithCookies) return MockResponse().setResponseCode(403)
                    }
                    val rangeHeader = request.getHeader("Range")
                    if (rangeHeader == null) {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/vnd.android.package-archive")
                            .setHeader("Content-Disposition", "attachment; filename=app-release.apk")
                            .setHeader("Accept-Ranges", "bytes")
                            .setHeader("ETag", "\"0x8DF27910E5ED719\"")
                            .setHeader("Content-Length", payload.size.toString())
                            .setBody(Buffer().write(payload))
                    } else {
                        val match = Regex("bytes=(\\d+)-(\\d+)?").find(rangeHeader) ?: return MockResponse().setResponseCode(416)
                        val start = match.groupValues[1].toLong()
                        val end = (match.groupValues[2].toLongOrNull() ?: (payload.size - 1L)).coerceAtMost(payload.size - 1L)
                        MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                            .setHeader("Accept-Ranges", "bytes")
                            .setHeader("ETag", "\"0x8DF27910E5ED719\"")
                            .setHeader("Content-Length", (end - start + 1).toString())
                            .setBody(Buffer().write(payload.copyOfRange(start.toInt(), (end + 1).toInt())))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `github release redirect flow completes with discovered filename`() {
        val payload = "GITHUB-RELEASE-APK-BYTES".toByteArray()
        val origin = server.url("/").toString().trimEnd('/')
        server.dispatcher = GitHubDispatcher(payload, origin)
        val task = entity("$origin$assetPath", filename = "download")
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)
        assertEquals(payload.size.toLong(), done.downloadedBytes)
        assertEquals("app-release.apk", done.filename)
        assertTrue(done.finalUrl.orEmpty().contains("/signed/"))
        assertEquals("application/vnd.android.package-archive", done.mimeType)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `session cookies are never forwarded to the cross-host asset CDN`() {
        val payload = "NO-COOKIE-LEAK".toByteArray()
        // github.test hosts the page/redirect, cdn.test serves the bytes — different hosts.
        val assetOrigin = "http://github.test:${server.port}"
        val cdnOrigin = "http://cdn.test:${server.port}"
        // A strict CDN: any Cookie header on the signed request is rejected with 403.
        server.dispatcher = GitHubDispatcher(payload, assetOrigin, cdnOrigin, signed403WithCookies = true)
        val runnerWithDns =
            TaskRunner(
                store = store,
                baseClient = clientWithFakeHosts(),
                storage = storage,
                networkMonitor = FakeConnectivity(),
                cookieProvider = FixedCookieProvider(),
            )
        val task =
            entity("$assetOrigin$assetPath").copy(
                cookies = "logged_in=yes; _gh_sess=SECRET-SESSION; __Host-user_session_same_site=TOKEN",
                requiresSession = true,
                referer = "$assetOrigin/SecretArrow/Arc-Download-Manager/releases/tag/v1.4.0",
            )
        insert(task)

        runBlocking { runnerWithDns.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)
        assertEquals(payload.size.toLong(), done.downloadedBytes)
        assertFalse((server.dispatcher as GitHubDispatcher).sawCookieOnSignedRequest)
    }

    @Test
    fun `resume across the redirect chain completes the file`() {
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val origin = server.url("/").toString().trimEnd('/')
        server.dispatcher = GitHubDispatcher(payload, origin)
        val task =
            entity("$origin$assetPath").copy(
                resumable = true,
                acceptRanges = true,
                totalBytes = payload.size.toLong(),
                downloadedBytes = 100L,
            )
        insert(task)
        // Pre-seed the first 100 bytes exactly like a real interrupted download.
        storage.partFile(task.id, 0).writeBytes(payload.copyOfRange(0, 100))

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)
        assertEquals(payload.size.toLong(), done.downloadedBytes)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `expired signed url recovers by re-resolving the original link`() {
        // 8 MiB payload so the planner uses two segments (AUTO_THRESHOLD = 8 MiB).
        val payload = ByteArray(8 * 1024 * 1024) { (it % 253).toByte() }
        val origin = server.url("/").toString().trimEnd('/')
        server.dispatcher =
            object : Dispatcher() {
                val signedCounter = AtomicInteger(0)

                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: "/"
                    return when {
                        path.startsWith(assetPath) -> {
                            val n = signedCounter.incrementAndGet()
                            MockResponse()
                                .setResponseCode(302)
                                .setHeader("Location", "$origin/signed/$n/big.apk?sig=fresh-$n")
                        }
                        path.startsWith("/signed/1/") && request.getHeader("Range") != null ->
                            // The signed URL issued at open time expires before segments start.
                            MockResponse().setResponseCode(403)
                        path.startsWith("/signed/") -> {
                            val rangeHeader = request.getHeader("Range")
                            val match = Regex("bytes=(\\d+)-(\\d+)?").find(rangeHeader ?: "") ?: return MockResponse().setResponseCode(416)
                            val start = match.groupValues[1].toLong()
                            val end = (match.groupValues[2].toLongOrNull() ?: (payload.size - 1L)).coerceAtMost(payload.size - 1L)
                            MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                                .setHeader("Accept-Ranges", "bytes")
                                .setHeader("ETag", "\"0x8DF27910E5ED719\"")
                                .setHeader("Content-Length", (end - start + 1).toString())
                                .setBody(Buffer().write(payload.copyOfRange(start.toInt(), (end + 1).toInt())))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val task = entity("$origin$assetPath", segments = 2)
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED, timeoutMs = 30_000)
        assertEquals(payload.size.toLong(), done.downloadedBytes)
        // finalUrl must have been refreshed to a newly signed link (counter > 1).
        val signedIndex = done.finalUrl.orEmpty().substringAfter("/signed/", "").substringBefore('/').toIntOrNull()
        assertNotNull("expected a refreshed signed URL", signedIndex)
        assertTrue("expected refresh past the expired link", signedIndex!! > 1)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `release page html url fails fast with PAGE_NOT_FILE`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><body>Release page v1.4.0</body></html>")
            }
        val origin = server.url("/").toString().trimEnd('/')
        val task = entity("$origin/SecretArrow/Arc-Download-Manager/releases/tag/v1.4.0", filename = "v1.4.0")
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val failed = awaitStatus(task.id, DownloadStatus.FAILED)
        assertEquals(com.arc.downloadmanager.engine.DownloadError.PAGE_NOT_FILE.name, failed.errorCode)
    }

    @Test
    fun `lenient url validation accepts pasted github links with stray characters`() {
        // java.net.URI rejects spaces/unicode; OkHttp HttpUrl does not.
        assertTrue(CookieScopePolicy.isSupportedUrl("https://github.com/owner/repo/releases/download/v1.4.0/My App (1).apk"))
        assertTrue(CookieScopePolicy.isSupportedUrl(" https://github.com/owner/repo/releases/latest "))
        assertFalse(CookieScopePolicy.isSupportedUrl("ftp://github.com/file.apk"))
        assertFalse(CookieScopePolicy.isSupportedUrl("not a url"))
    }
}
