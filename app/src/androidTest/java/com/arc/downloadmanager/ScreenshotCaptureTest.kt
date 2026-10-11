package com.arc.downloadmanager

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Navigates the main destinations and captures full-screen PNG screenshots (including dialogs)
 * to the app's internal files dir. The E2E workflow pulls these via `adb run-as`, uploads them
 * as artifacts and refreshes the `screenshots/` folder referenced by the README.
 * Navigation failures never fail the test — a missing shot is better than a red pipeline.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun captureMainScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val dir = File(context.filesDir, "screenshots")
        dir.deleteRecursively()
        dir.mkdirs()

        fun snap(name: String) {
            runCatching {
                device.takeScreenshot(File(dir, "$name.png"), 1.0f, 100)
            }
        }

        fun tap(label: String) {
            runCatching {
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText(label).performClick()
                composeRule.waitForIdle()
                Thread.sleep(800)
            }
        }

        composeRule.waitForIdle()
        Thread.sleep(1_000)
        snap("home")

        tap(context.getString(R.string.nav_browser))
        snap("browser")

        tap(context.getString(R.string.nav_files))
        snap("files")

        tap(context.getString(R.string.nav_settings))
        snap("settings")

        tap(context.getString(R.string.nav_home))
        tap(context.getString(R.string.action_add_download))
        snap("add")
    }
}
