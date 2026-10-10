package com.arc.downloadmanager

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun bottomNavigationShowsAllDestinations() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.nav_home)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.nav_browser)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.nav_files)).assertExists()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.nav_settings)).assertExists()
    }
}
