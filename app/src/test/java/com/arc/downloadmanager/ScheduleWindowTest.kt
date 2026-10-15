package com.arc.downloadmanager

import com.arc.downloadmanager.engine.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure window math for scheduled downloads ("night quota"): overnight windows that wrap past
 * midnight, same-day windows, the 24 h degenerate case, the disabled bypass and minute clamping.
 */
class ScheduleWindowTest {
    @Test
    fun `disabled schedule never blocks`() {
        // 12:00 noon is well outside the default 22:00–06:00 window, yet downloads may start.
        assertTrue(ScheduleWindow.activeNow(enabled = false, startMinuteOfDay = 1320, endMinuteOfDay = 360, nowMinuteOfDay = 720))
        assertTrue(ScheduleWindow.activeNow(enabled = false, startMinuteOfDay = 540, endMinuteOfDay = 1020, nowMinuteOfDay = 1439))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        // Window 22:00–06:00 → [1320..1439] and [0..359]; end minute (06:00) itself is outside.
        assertTrue(ScheduleWindow.activeNow(true, 1320, 360, 1320)) // 22:00 start inclusive
        assertTrue(ScheduleWindow.activeNow(true, 1320, 360, 1439)) // 23:59
        assertTrue(ScheduleWindow.activeNow(true, 1320, 360, 0)) // 00:00
        assertTrue(ScheduleWindow.activeNow(true, 1320, 360, 359)) // 05:59
        assertFalse(ScheduleWindow.activeNow(true, 1320, 360, 360)) // 06:00 end exclusive
        assertFalse(ScheduleWindow.activeNow(true, 1320, 360, 719)) // 11:59
        assertFalse(ScheduleWindow.activeNow(true, 1320, 360, 1319)) // 21:59
    }

    @Test
    fun `same-day window is start inclusive and end exclusive`() {
        // Window 09:00–17:00 → [540..1019].
        assertTrue(ScheduleWindow.activeNow(true, 540, 1020, 540)) // 09:00
        assertTrue(ScheduleWindow.activeNow(true, 540, 1020, 1019)) // 16:59
        assertFalse(ScheduleWindow.activeNow(true, 540, 1020, 539)) // 08:59
        assertFalse(ScheduleWindow.activeNow(true, 540, 1020, 1020)) // 17:00
    }

    @Test
    fun `start equals end means a 24 hour window when enabled`() {
        for (now in listOf(0, 539, 540, 720, 1439)) {
            assertTrue("now=$now", ScheduleWindow.activeNow(true, 540, 540, now))
        }
    }

    @Test
    fun `minutes outside 0 to 1439 are clamped`() {
        assertEquals(0, ScheduleWindow.clamp(-1))
        assertEquals(0, ScheduleWindow.clamp(-600))
        assertEquals(720, ScheduleWindow.clamp(720))
        assertEquals(1439, ScheduleWindow.clamp(1439))
        assertEquals(1439, ScheduleWindow.clamp(1440))
        assertEquals(1439, ScheduleWindow.clamp(9999))

        // Clamped inputs must not throw and behave like their clamped equivalents:
        // start 1440→1439, end -1→0 is a wrap window whose exclusive end degenerates the
        // wrapped part to empty, so only minute 23:59 itself is inside.
        assertTrue(ScheduleWindow.activeNow(true, 1440, -1, 1439))
        assertFalse(ScheduleWindow.activeNow(true, 1440, -1, 0)) // 00:00 is the exclusive end
        assertFalse(ScheduleWindow.activeNow(true, 1440, -1, 720))
        // now is clamped too: 1500→1439 stays inside the 22:00–06:00 window.
        assertTrue(ScheduleWindow.activeNow(true, 1320, 360, 1500))
    }

    @Test
    fun `minute formatting is zero padded HH mm`() {
        assertEquals("22:00", ScheduleWindow.formatMinute(1320))
        assertEquals("06:00", ScheduleWindow.formatMinute(360))
        assertEquals("00:00", ScheduleWindow.formatMinute(0))
        assertEquals("09:05", ScheduleWindow.formatMinute(545))
        assertEquals("23:59", ScheduleWindow.formatMinute(1445)) // clamped
    }

    @Test
    fun `minute parsing accepts HH mm and rejects garbage`() {
        assertEquals(1320, ScheduleWindow.parseMinute("22:00"))
        assertEquals(360, ScheduleWindow.parseMinute("06:00"))
        assertEquals(360, ScheduleWindow.parseMinute("6:00"))
        assertEquals(0, ScheduleWindow.parseMinute("0:0"))
        assertNull(ScheduleWindow.parseMinute("24:00"))
        assertNull(ScheduleWindow.parseMinute("12:60"))
        assertNull(ScheduleWindow.parseMinute("2200"))
        assertNull(ScheduleWindow.parseMinute(""))
    }
}
