package dev.alder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersionsByNumber() {
        assertTrue(newerVersion("1.1", "1.0"))
        assertTrue(newerVersion("1.10", "1.9"))
        assertTrue(newerVersion("2", "1.9.9"))
        assertTrue(newerVersion("1.2.1", "1.2"))
        assertTrue(newerVersion("1.3.0", "1.2.9"))
        assertFalse(newerVersion("1.0", "1.0"))
        assertFalse(newerVersion("1.0", "1.0.1"))
        assertFalse(newerVersion("1.0.0", "1.0"))
        assertFalse(newerVersion("1.2", "1.2.0"))
    }
}
