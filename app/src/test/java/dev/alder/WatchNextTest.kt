package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNextTest {
    @Test
    fun parsesIso8601Timestamps() {
        val timeWithoutMillis = WatchNext.parseIsoTimestamp("2026-10-28T05:25:04Z")
        assertEquals(1793165104000L, timeWithoutMillis)

        val timeWithMillis = WatchNext.parseIsoTimestamp("2026-10-28T05:25:04.123Z")
        // Second precision is consistent across both API >= 26 and API < 26 fallback
        assertEquals(1793165104000L, timeWithMillis / 1000 * 1000)
    }

    @Test
    fun fallsBackToCurrentTimeOnInvalidOrMissingTimestamp() {
        val before = System.currentTimeMillis()
        val timeEmpty = WatchNext.parseIsoTimestamp("")
        val timeInvalid = WatchNext.parseIsoTimestamp("not-a-date")
        val after = System.currentTimeMillis()

        assertTrue(timeEmpty in before..after)
        assertTrue(timeInvalid in before..after)
    }
}
