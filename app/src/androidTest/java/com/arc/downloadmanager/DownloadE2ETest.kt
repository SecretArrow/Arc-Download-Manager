package com.arc.downloadmanager

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arc.downloadmanager.data.repository.DownloadRepository
import com.arc.downloadmanager.domain.model.DownloadStatus
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end engine test on device: a real HTTP server (MockWebServer in-process) serves a file,
 * the repository enqueues it, the engine downloads it over the loopback interface and finalizes.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DownloadE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: DownloadRepository

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        hiltRule.inject()
        server = MockWebServer()
        server.start()
    }

    @Test
    fun downloadCompletesWithRealEngineOnDevice() = runBlocking {
        val payload = "arc-on-device-e2e-payload".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.size.toString())
                .setBody(okio.Buffer().write(payload)),
        )
        val url = server.url("/e2e.txt").toString()

        val result = repository.add(
            com.arc.downloadmanager.domain.model.DownloadRequest(url = url),
            policyOverride = com.arc.downloadmanager.data.settings.OverwritePolicy.RENAME,
        )
        val id = (result as com.arc.downloadmanager.data.repository.AddResult.Started).id

        withTimeout(30_000) {
            while (true) {
                val entity = repository.get(id)
                if (entity?.status == DownloadStatus.COMPLETED) break
                if (entity?.status == DownloadStatus.FAILED || entity?.status == DownloadStatus.AUTH_REQUIRED) {
                    throw AssertionError("Download failed: ${entity?.errorCode} ${entity?.errorMessage}")
                }
                kotlinx.coroutines.delay(200)
            }
        }
        val done = repository.get(id)!!
        composeRule.waitUntil(15_000) {
            composeRule
                .onAllNodesWithText(done.filename, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(done.filename, substring = true).assertExists()
        server.shutdown()
    }
}
