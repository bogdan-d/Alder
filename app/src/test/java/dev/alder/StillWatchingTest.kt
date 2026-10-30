package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class StillWatchingTest {
    @Test fun asksBeforeTheFourthEpisodeWithNoKeyPressed() {
        assertEquals(false, askStillWatching(1))
        assertEquals(false, askStillWatching(2))
        assertEquals(true, askStillWatching(3))
    }

    @Test fun sleepRowNamesTheChoice() {
        assertEquals("Off", sleepLabel(stopAfter = false, sleepAt = 0, now = 1_000))
        assertEquals("After this episode", sleepLabel(stopAfter = true, sleepAt = 0, now = 1_000))
        assertEquals("In 60 min", sleepLabel(stopAfter = false, sleepAt = 1_000 + SLEEP_TIMER, now = 1_000))
        assertEquals("In 1 min", sleepLabel(stopAfter = false, sleepAt = 1_001, now = 1_000))
        assertEquals("Off", sleepLabel(stopAfter = false, sleepAt = 1_000, now = 1_000)) // ran out
    }
}
