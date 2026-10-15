package com.arc.downloadmanager

import com.arc.downloadmanager.data.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateChecker.isNewer("2.2.0", "2.2.0"))
        assertFalse(UpdateChecker.isNewer("v2.2.0", "v2.2.0"))
    }

    @Test
    fun `higher version is newer and lower is not`() {
        assertTrue(UpdateChecker.isNewer("2.3.0", "2.2.0"))
        assertFalse(UpdateChecker.isNewer("2.2.0", "2.3.0"))
    }

    @Test
    fun `components compare numerically not lexicographically`() {
        // "10" < "9" as text, but 10 > 9 as numbers — the classic version-bump trap.
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.9.0"))
        assertFalse(UpdateChecker.isNewer("2.9.0", "2.10.0"))
    }

    @Test
    fun `leading v prefix is stripped from both sides`() {
        assertTrue(UpdateChecker.isNewer("v2.3.0", "2.2.0"))
        assertTrue(UpdateChecker.isNewer("2.3.0", "v2.2.0"))
        assertTrue(UpdateChecker.isNewer("v3.0.0", "v2.9.9"))
    }

    @Test
    fun `missing components are padded with zero`() {
        assertFalse(UpdateChecker.isNewer("2.2", "2.2.0"))
        assertTrue(UpdateChecker.isNewer("2.2.1", "2.2"))
        assertFalse(UpdateChecker.isNewer("2.2", "2.2.1"))
        assertTrue(UpdateChecker.isNewer("3", "2.9.9"))
    }
}
