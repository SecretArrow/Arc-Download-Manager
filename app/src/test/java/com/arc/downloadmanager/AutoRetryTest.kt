package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.TaskControl
import com.arc.downloadmanager.engine.TaskRunner
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Task-level auto-retry policy: recoverable failures are re-queued (the engine then holds them
 * for a fixed 30 s cool-down before the next attempt) until [EngineSettings.retryAttempts] is
 * exhausted — only then the task is marked FAILED. With auto-retry OFF the first failure is
 * final immediately, and permanent errors never retry regardless of the switch.
 */
class AutoRetryTest {
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
        retryCount: Int = 0,
    ) = DownloadEntity(
        id = "retry-${System.nanoTime()}",
        url = url,
        filename = "file.bin",
        retryCount = retryCount,
    )

    private fun statusDispatcher(
        code: Int,
        body: String = "boom",
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setResponseCode(code).setBody(Buffer().write(body.toByteArray()))
    }

    @Test
    fun `recoverable failure is requeued for auto retry`() {
        server.dispatcher = statusDispatcher(500)
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }

        val after = store.getById(task.id)!!
        assertEquals(DownloadStatus.QUEUED, after.status)
        assertEquals(1, after.retryCount)
        assertEquals("SERVER_ERROR", after.errorCode)
    }

    @Test
    fun `auto retry disabled fails immediately`() {
        server.dispatcher = statusDispatcher(500)
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }

        runBlocking {
            runner.run(task.id, EngineSettings(autoRetry = false), TaskControl(), { 16 }, {})
        }

        val after = store.getById(task.id)!!
        assertEquals(DownloadStatus.FAILED, after.status)
        assertEquals(0, after.retryCount)
    }

    @Test
    fun `exhausted attempts marks task failed`() {
        server.dispatcher = statusDispatcher(500)
        val task = entity(server.url("/file").toString(), retryCount = 5)
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(retryAttempts = 5), TaskControl(), { 16 }, {}) }

        val after = store.getById(task.id)!!
        assertEquals(DownloadStatus.FAILED, after.status)
    }

    @Test
    fun `auth required is never auto retried`() {
        server.dispatcher = statusDispatcher(401)
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }

        val after = store.getById(task.id)!!
        assertEquals(DownloadStatus.AUTH_REQUIRED, after.status)
        assertEquals(0, after.retryCount)
    }

    @Test
    fun `permanent page link error is not retried`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html")
                        .setBody(Buffer().write("<html>release page</html>".toByteArray()))
            }
        val task = entity(server.url("/releases/tag/v2.0.0").toString())
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }

        val after = store.getById(task.id)!!
        assertEquals(DownloadStatus.FAILED, after.status)
        assertEquals("PAGE_NOT_FILE", after.errorCode)
    }
}
