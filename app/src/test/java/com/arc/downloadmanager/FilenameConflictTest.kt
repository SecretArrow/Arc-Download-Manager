package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.data.settings.OverwritePolicy
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.SpeedLimitHolder
import com.arc.downloadmanager.engine.TaskControl
import com.arc.downloadmanager.engine.TaskRunner
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Filename-collision handling at the destination. The Settings radio is the single source of
 * truth (CollisionPolicy): RENAME (default) keeps every file and silently saves a fresh copy,
 * REPLACE overwrites, SKIP parks the task in CONFLICT until the user picks overwrite /
 * rename-new / rename-old. A restarted task replaces its OWN previous artifact under any
 * policy instead of forking a copy or asking about its own file.
 */
class FilenameConflictTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryDownloadStore
    private lateinit var storage: FakeTaskStorage
    private lateinit var root: File
    private lateinit var runner: TaskRunner

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryDownloadStore()
        root = tmp.newFolder("storage")
        storage = FakeTaskStorage(root)
        runner =
            TaskRunner(
                store = store,
                baseClient = OkHttpClient(),
                storage = storage,
                networkMonitor = FakeConnectivity(),
                cookieProvider = FixedCookieProvider(),
                speedLimitHolder = SpeedLimitHolder(),
            )
    }

    private fun entity(url: String) =
        DownloadEntity(
            id = "conflict-${System.nanoTime()}",
            url = url,
            filename = "file.bin",
        )

    private fun bodyDispatcher(): okhttp3.mockwebserver.Dispatcher =
        object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(Buffer().write("DATA".toByteArray()))
        }

    private fun download(settings: EngineSettings): DownloadEntity {
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }
        runBlocking { runner.run(task.id, settings, TaskControl(), { 16 }, {}) }
        return task
    }

    /** Default settings (RENAME policy): the collision is resolved silently, nothing is asked. */
    private fun parkInConflict(settings: EngineSettings = EngineSettings(overwritePolicy = OverwritePolicy.SKIP)): DownloadEntity {
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }
        storage.conflict = { true }
        runBlocking { runner.run(task.id, settings, TaskControl(), { 16 }, {}) }
        return task
    }

    @Test
    fun `default rename policy resolves the collision silently and keeps the old file`() {
        server.dispatcher = bodyDispatcher()
        storage.conflict = { true }
        File(root, "final_file.bin").writeText("OLD")

        val task = download(EngineSettings())

        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
        assertEquals("OLD", File(root, "final_file.bin").readText())
        assertEquals("DATA", File(root, "final_file (1).bin").readText())
    }

    @Test
    fun `skip policy parks the task when the name is taken`() {
        server.dispatcher = bodyDispatcher()
        val task = parkInConflict()

        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.CONFLICT, after.status)
        assertEquals("FILE_EXISTS", after.errorCode)
        val staged = storage.findStagedFile(task.id)
        assertNotNull(staged)
        assertTrue(staged!!.exists())
    }

    @Test
    fun `replace policy overwrites the taken name without asking`() {
        server.dispatcher = bodyDispatcher()
        storage.conflict = { true }
        File(root, "final_file.bin").writeText("OLD")

        val task = download(EngineSettings(overwritePolicy = OverwritePolicy.REPLACE))

        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
        assertEquals("DATA", File(root, "final_file.bin").readText())
        assertFalse(File(root, "final_file (1).bin").exists())
    }

    @Test
    fun `legacy auto-rename switch keeps the silent fresh-copy behavior`() {
        server.dispatcher = bodyDispatcher()
        storage.conflict = { true }
        File(root, "final_file.bin").writeText("OLD")

        val task = download(EngineSettings(autoRenameDuplicates = true))

        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
        assertEquals("OLD", File(root, "final_file.bin").readText())
        assertEquals("DATA", File(root, "final_file (1).bin").readText())
    }

    @Test
    fun `no collision completes without parking`() {
        server.dispatcher = bodyDispatcher()
        val task = entity(server.url("/file").toString())
        runBlocking { store.insert(task) }
        storage.conflict = { false }

        runBlocking { runner.run(task.id, EngineSettings(overwritePolicy = OverwritePolicy.SKIP), TaskControl(), { 16 }, {}) }

        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
    }

    @Test
    fun `restart replaces its own previous artifact instead of parking`() {
        server.dispatcher = bodyDispatcher()
        storage.conflict = { true }
        // The task completed earlier (own artifact at the destination), then the user hit Restart.
        val task =
            DownloadEntity(
                id = "restart-${System.nanoTime()}",
                url = server.url("/file").toString(),
                filename = "file.bin",
                finalUri = "file://${root.path}/final_file.bin",
                status = DownloadStatus.QUEUED,
            )
        File(root, "final_file.bin").writeText("OLD")
        runBlocking { store.insert(task) }

        runBlocking { runner.run(task.id, EngineSettings(overwritePolicy = OverwritePolicy.SKIP), TaskControl(), { 16 }, {}) }

        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
        assertEquals("DATA", File(root, "final_file.bin").readText())
        assertFalse(File(root, "final_file (1).bin").exists())
    }

    @Test
    fun `overwrite resolution replaces the old file without re-downloading`() {
        server.dispatcher = bodyDispatcher()
        val task = parkInConflict()
        File(root, "final_file.bin").writeText("OLD")

        val published = runBlocking { runner.publishStaged(task.id, EngineSettings(), overwrite = true) }

        assertTrue(published)
        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
        assertEquals("DATA", File(root, "final_file.bin").readText())
    }

    @Test
    fun `rename new resolution keeps the old file and saves a fresh copy`() {
        server.dispatcher = bodyDispatcher()
        val task = parkInConflict()
        File(root, "final_file.bin").writeText("OLD")

        val published = runBlocking { runner.publishStaged(task.id, EngineSettings(), overwrite = false) }

        assertTrue(published)
        val after = runBlocking { store.getById(task.id)!! }
        assertEquals(DownloadStatus.COMPLETED, after.status)
        assertEquals("OLD", File(root, "final_file.bin").readText())
        assertEquals("DATA", File(root, "final_file (1).bin").readText())
    }

    @Test
    fun `rename old resolution moves the existing file aside`() {
        server.dispatcher = bodyDispatcher()
        val task = parkInConflict()
        File(root, "final_file.bin").writeText("OLD")

        val renamed = runBlocking { storage.renameExisting(store.getById(task.id)!!) }

        assertTrue(renamed)
        assertEquals("OLD", File(root, "final_file (1).bin").readText())
        assertFalse(File(root, "final_file.bin").exists())
    }
}
