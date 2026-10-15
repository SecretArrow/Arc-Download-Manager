package com.arc.downloadmanager

import com.arc.downloadmanager.data.db.DownloadEntity
import com.arc.downloadmanager.data.settings.EngineSettings
import com.arc.downloadmanager.domain.model.DownloadStatus
import com.arc.downloadmanager.engine.SpeedLimitHolder
import com.arc.downloadmanager.engine.TaskControl
import com.arc.downloadmanager.engine.TaskRunner
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end HLS/M3U8 tests through the real TaskRunner: an .m3u8 task must be routed to the
 * HLS pipeline, download (and AES-128-decrypt) its segments, merge them in order and finalize
 * with the container-correct filename.
 */
class HlsDownloadTest {
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
                speedLimitHolder = SpeedLimitHolder(),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun awaitStatus(
        id: String,
        vararg statuses: DownloadStatus,
        timeoutMs: Long = 30_000,
    ): DownloadEntity =
        runBlocking {
            try {
                withTimeout(timeoutMs) {
                    while (true) {
                        store.getById(id)?.let { if (it.status in statuses) return@withTimeout it }
                        delay(25)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    throw IllegalStateException()
                }
            } catch (e: java.util.concurrent.TimeoutException) {
                throw AssertionError("Timed out waiting for $statuses", e)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                val last = store.getById(id)
                throw AssertionError(
                    "Timed out waiting for $statuses; last=${last?.status} " +
                        "err=${last?.errorCode}:${last?.errorMessage} bytes=${last?.downloadedBytes}/${last?.totalBytes}",
                    e,
                )
            }
        }

    private fun insert(entity: DownloadEntity) = runBlocking { store.insert(entity) }

    /** 128-bit test key and CBC/PKCS#7 encryptor mirroring the downloader's decryptor. */
    private fun aesKey(): ByteArray = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

    private fun encrypt(
        plain: ByteArray,
        key: ByteArray,
        ivHex: String,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivHex.hexToByteArray()))
        return cipher.doFinal(plain)
    }

    private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Serves playlists (mpegurl), the AES key and segment ciphertexts; counts key/segment hits. */
    private class HlsDispatcher(
        private val playlists: Map<String, String>,
        private val key: ByteArray?,
        private val segments: Map<String, ByteArray>,
        private val keyPath: String = "/key",
    ) : Dispatcher() {
        val keyRequests = AtomicInteger(0)
        val segmentRequests = ConcurrentHashMap<String, AtomicInteger>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: "/"
            playlists.entries.firstOrNull { path.startsWith(it.key) }?.let { entry ->
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody(entry.value)
            }
            if (path.startsWith(keyPath)) {
                keyRequests.incrementAndGet()
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(requireNotNull(key)))
            }
            segments.entries.firstOrNull { path.startsWith(it.key) }?.let { entry ->
                segmentRequests.getOrPut(entry.key) { AtomicInteger(0) }.incrementAndGet()
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "video/mp2t")
                    .setBody(Buffer().write(entry.value))
            }
            return MockResponse().setResponseCode(404)
        }
    }

    @Test
    fun `hls aes-128 vod stream decrypts merges and completes`() {
        val key = aesKey()
        val ivHex = "9c7db8778570d05c3177c349fd9236aa"
        val plains =
            listOf(
                "ARC-HLS-SEGMENT-0-".toByteArray() + ByteArray(100) { 'a'.code.toByte() },
                "ARC-HLS-SEGMENT-1-".toByteArray() + ByteArray(250) { 'b'.code.toByte() },
                "ARC-HLS-SEGMENT-2-".toByteArray() + ByteArray(77) { 'c'.code.toByte() },
            )
        val ciphertexts = plains.map { encrypt(it, key, ivHex) }
        val playlist =
            buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:3")
                appendLine("#EXT-X-TARGETDURATION:4")
                appendLine("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x$ivHex")
                ciphertexts.indices.forEach { index ->
                    appendLine("#EXTINF:4.0,")
                    appendLine("seg$index.ts")
                }
                append("#EXT-X-ENDLIST")
            }
        val dispatcher =
            HlsDispatcher(
                playlists = mapOf("/v/stream.m3u8" to playlist),
                key = key,
                keyPath = "/v/key.bin",
                segments = ciphertexts.mapIndexed { index, bytes -> "/v/seg$index.ts" to bytes }.toMap(),
            )
        server.dispatcher = dispatcher
        val origin = server.url("/").toString().trimEnd('/')
        val task = DownloadEntity(id = "hls-aes", url = "$origin/v/stream.m3u8", filename = "stream.m3u8")
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)

        assertEquals(plains.sumOf { it.size.toLong() }, done.downloadedBytes)
        assertEquals(done.downloadedBytes, done.totalBytes)
        // .m3u8 filename swapped for the container extension; MPEG-TS mime type.
        assertEquals("stream.ts", done.filename)
        assertEquals("video/mp2t", done.mimeType)
        assertEquals(3, done.segmentsPlanned)
        // The key is fetched exactly once for the whole stream.
        assertEquals(1, dispatcher.keyRequests.get())
        val expected = plains.reduce { acc, bytes -> acc + bytes }
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue("merged output must equal the decrypted segment concatenation", finalFile.readBytes().contentEquals(expected))
    }

    @Test
    fun `master playlist routes to the highest bandwidth variant`() {
        val plain = "HIGH-VARIANT-CHUNK-PAYLOAD".toByteArray()
        val master =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            high.m3u8
            """.trimIndent()
        val highMedia =
            """
            #EXTM3U
            #EXTINF:2.0,
            chunk.ts
            #EXT-X-ENDLIST
            """.trimIndent()
        server.dispatcher =
            HlsDispatcher(
                playlists =
                    mapOf(
                        "/master.m3u8" to master,
                        "/high.m3u8" to highMedia,
                        "/low.m3u8" to "#EXTM3U\n#EXTINF:2.0,\nnever.ts\n#EXT-X-ENDLIST",
                    ),
                key = null,
                segments = mapOf("/chunk.ts" to plain, "/never.ts" to "LOW".toByteArray()),
            )
        val origin = server.url("/").toString().trimEnd('/')
        val task = DownloadEntity(id = "hls-master", url = "$origin/master.m3u8", filename = "video.m3u8")
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)

        assertEquals("video.ts", done.filename)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(plain))
    }

    @Test
    fun `content-type sniffing routes extension-less playlist urls to hls`() {
        val plain = "SNIFFED-PLAYLIST-SEGMENT".toByteArray()
        val playlist =
            """
            #EXTM3U
            #EXTINF:3.0,
            chunk.ts
            #EXT-X-ENDLIST
            """.trimIndent()
        // URL has no .m3u8 extension: only the mpegurl content-type identifies it as HLS.
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: "/"
                    return when {
                        path.startsWith("/playlist") ->
                            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-mpegurl").setBody(playlist)
                        path.startsWith("/chunk.ts") ->
                            MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(plain))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val origin = server.url("/").toString().trimEnd('/')
        val task = DownloadEntity(id = "hls-sniff", url = "$origin/playlist", filename = "playlist")
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)

        assertEquals("playlist.ts", done.filename)
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(plain))
    }

    @Test
    fun `resume skips already downloaded segments`() {
        val plains =
            listOf(
                "RESUME-SEGMENT-0-".toByteArray() + ByteArray(120) { 'x'.code.toByte() },
                "RESUME-SEGMENT-1-".toByteArray() + ByteArray(90) { 'y'.code.toByte() },
            )
        val playlist =
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
            #EXT-X-ENDLIST
            """.trimIndent()
        val dispatcher =
            HlsDispatcher(
                playlists = mapOf("/v/stream.m3u8" to playlist),
                key = null,
                segments =
                    mapOf(
                        "/v/seg0.ts" to plains[0],
                        "/v/seg1.ts" to plains[1],
                    ),
            )
        server.dispatcher = dispatcher
        val origin = server.url("/").toString().trimEnd('/')
        val task = DownloadEntity(id = "hls-resume", url = "$origin/v/stream.m3u8", filename = "stream.m3u8")
        insert(task)
        // Simulate an interrupted previous run: segment 0 already finished on disk.
        File(storage.hlsDir(task.id), "000000.part").writeBytes(plains[0])

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val done = awaitStatus(task.id, DownloadStatus.COMPLETED)

        assertEquals(2, done.segmentsPlanned)
        assertEquals(plains.sumOf { it.size.toLong() }, done.downloadedBytes)
        assertEquals(0, (dispatcher.segmentRequests["/v/seg0.ts"] ?: AtomicInteger(0)).get())
        assertEquals(1, (dispatcher.segmentRequests["/v/seg1.ts"] ?: AtomicInteger(0)).get())
        val expected = plains[0] + plains[1]
        val finalFile = File(java.net.URI(requireNotNull(done.finalUri)).path)
        assertTrue(finalFile.readBytes().contentEquals(expected))
    }

    @Test
    fun `live playlist fails with HLS_LIVE`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            seg.ts
            """.trimIndent() // no #EXT-X-ENDLIST → live/event stream
        server.dispatcher =
            HlsDispatcher(playlists = mapOf("/live.m3u8" to playlist), key = null, segments = emptyMap())
        val origin = server.url("/").toString().trimEnd('/')
        val task = DownloadEntity(id = "hls-live", url = "$origin/live.m3u8", filename = "live.m3u8")
        insert(task)

        runBlocking { runner.run(task.id, EngineSettings(), TaskControl(), { 16 }, {}) }
        val failed = awaitStatus(task.id, DownloadStatus.FAILED)

        assertEquals("HLS_LIVE", failed.errorCode)
    }
}
