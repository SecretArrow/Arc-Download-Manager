package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.DownloadEngine
import com.arc.downloadmanager.engine.TaskControl
import com.arc.downloadmanager.engine.TaskRunner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** MockWebServer dispatcher that behaves like a byte-range capable file server. */
class RangeFileDispatcher(private val body: ByteArray) : Dispatcher() {
    val etag = "\"arc-etag-1\""

    override fun dispatch(request: RecordedRequest): MockResponse {
        val rangeHeader = request.getHeader("Range")
        return if (rangeHeader != null) {
            val match = Regex("bytes=(\\d+)-(\\d+)?").find(rangeHeader) ?: return MockResponse().setResponseCode(416)
            val start = match.groupValues[1].toLong()
            val end = (match.groupValues[2].toLongOrNull() ?: (body.size - 1L)).coerceAtMost(body.size - 1L)
            if (start > end || start >= body.size) return MockResponse().setResponseCode(416)
            val buffer = Buffer().write(body.copyOfRange(start.toInt(), (end + 1).toInt()))
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${body.size}")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("ETag", etag)
                .setBody(buffer)
        } else {
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", body.size.toString())
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("ETag", etag)
                .setBody(Buffer().write(body))
        }
    }
}

class TaskRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var storage: FakeTaskStorage
    private lateinit var runner: TaskRunner

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

    private fun entity(
        url: String,
        segments: Int = 0,
    ) = DownloadEntity(
        id = "task-${System.nanoTime()}",
        url = url,
        filename = "file.bin",
        segmentsPlanned = segments,
    )

    private fun awaitStatus(
        id: String,
        vararg statuses: DownloadStatus,
        timeoutMs: Long = 15_000,
    ): DownloadEntity =
        runBlocking {
            withTimeout(timeoutMs) {
                while (true) {
                    store.getById(id)?.let { if (it.status in statuses) return@withTimeout it }
                    Thread.sleep(50)
                }
                @Suppress("UNREACHABLE_CODE")
                throw IllegalStateException()
            }
        }

    @Test
    fun `single stream download completes with correct content`() {
        val payload = "Hello Arc Download Manager!".toByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
            }
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)
        assertEquals(payload.size.toLong(), done.downloadedBytes)
        val finalUri = requireNotNull(done.finalUri)
        val finalFile = File(java.net.URI(finalUri).path)
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `multi segment download merges correctly`() {
        val random = java.util.Random(42)
        val payload = ByteArray(9 * 1024 * 1024)
        random.nextBytes(payload)
        server.dispatcher = RangeFileDispatcher(payload)

        val task = entity(server.url("/big.bin").toString(), segments = 4)
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED, timeoutMs = 60_000)
        assertEquals(payload.size.toLong(), done.totalBytes)

        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue("merged content must match", finalFile.readBytes().contentEquals(payload))

        val segments = runBlocking { store.segmentsFor(task.id) }
        assertEquals(4, segments.size)
        assertTrue(segments.all { it.downloadedBytes == it.length })
    }

    @Test
    fun `resumes from partial data using range requests`() {
        val payload = ByteArray(4 * 1024 * 1024) { ((it * 7) % 251).toByte() }
        server.dispatcher = RangeFileDispatcher(payload)

        val task = entity(server.url("/resume.bin").toString())
        val partial = 1024L * 1024L
        runBlocking {
            store.insert(
                task.copy(
                    resumable = true,
                    downloadedBytes = partial,
                    totalBytes = payload.size.toLong(),
                    acceptRanges = true,
                ),
            )
            // Simulate previously downloaded partial data on disk.
            storage.partFile(task.id, 0).writeBytes(payload.copyOfRange(0, partial.toInt()))
        }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED, timeoutMs = 30_000)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `auth required on 401`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(401).setBody("unauthorized")
            }
        val task = entity(server.url("/protected").toString())
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val failed = awaitStatus(task.id, DownloadStatus.AUTH_REQUIRED)
        assertEquals("AUTH_REQUIRED", failed.errorCode)
    }

    @Test
    fun `falls back to single stream when ranges are ignored`() {
        // Server ignores Range and always returns 200 with full body.
        val payload = "no-range-server".toByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
            }
        val task = entity(server.url("/plain").toString(), segments = 4)
        runBlocking { store.insert(task.copy(downloadedBytes = 3L, resumable = true)) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `checksum mismatch fails the task`() {
        val payload = "payload-for-checksum".toByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
            }
        val task = entity(server.url("/sum").toString())
        runBlocking { store.insert(task.copy(expectedSha256 = "deadbeef")) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val failed = awaitStatus(task.id, DownloadStatus.FAILED)
        assertEquals("CHECKSUM_MISMATCH", failed.errorCode)
    }

    @Test
    fun `follows redirects and completes`() {
        val payload = "redirected-content".toByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/start") {
                        return MockResponse().setResponseCode(302).setHeader("Location", "/final")
                    }
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
                }
            }
        val task = entity(server.url("/start").toString()).copy(referer = server.url("/start").toString())
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)
        assertEquals(payload.size.toLong(), done.downloadedBytes)
    }
}

class DownloadEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryDownloadStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryDownloadStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `processes queue with concurrency limit and finishes all tasks`() {
        val payload = "queue-test-payload".toByteArray()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
            }
        val storage = FakeTaskStorage(tmp.newFolder("storage"))
        val settings = FixedSettingsSource(EngineSettings(maxConcurrentFiles = 2))
        val engine =
            DownloadEngine(
                store = store,
                baseClient = OkHttpClient(),
                storage = storage,
                settingsRepository = settings,
                networkMonitor = FakeConnectivity(),
                cookieProvider = FixedCookieProvider(),
            )
        engine.start()

        val ids =
            (1..4).map { index ->
                "task-$index"
            }
        runBlocking {
            ids.forEach { id ->
                engine.enqueue(
                    DownloadEntity(
                        id = id,
                        url = server.url("/$id").toString(),
                        filename = "$id.bin",
                    ),
                )
            }
            withTimeout(30_000) {
                while (true) {
                    val all = store.getAllNow()
                    if (all.size == 4 && all.all { it.status == DownloadStatus.COMPLETED }) break
                    Thread.sleep(100)
                }
            }
        }
        ids.forEach { id ->
            val done = runBlocking { store.getById(id) }
            assertNotNull(done)
            assertEquals(DownloadStatus.COMPLETED, done!!.status)
            assertEquals(payload.size.toLong(), done.downloadedBytes)
        }
    }

    @Test
    fun `cancel removes task data and marks canceled`() {
        val storage = FakeTaskStorage(tmp.newFolder("storage2"))
        val engine =
            DownloadEngine(
                store = store,
                baseClient = OkHttpClient(),
                storage = storage,
                settingsRepository = FixedSettingsSource(),
                networkMonitor = FakeConnectivity(),
                cookieProvider = FixedCookieProvider(),
            )
        runBlocking {
            val id = "cancel-me"
            store.insert(DownloadEntity(id = id, url = "http://example.invalid/file", filename = "f.bin"))
            engine.cancel(id)
            withTimeout(5_000) {
                while (store.getById(id)?.status != DownloadStatus.CANCELED) Thread.sleep(50)
            }
        }
    }
}
