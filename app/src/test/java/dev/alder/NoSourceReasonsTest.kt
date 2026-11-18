package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoSourceReasonsTest {
    private val names = listOf("Torrentio", "MediaFusion", "Trailers")

    @Test fun saysWhyEachAddonGaveNothing() {
        val reasons = noSourceReasons(names, arrayOf(null, HttpError(403), null), intArrayOf(12, 0, 0), intArrayOf(0, 0, 2), 0)
        assertEquals(listOf(
            "Couldn't get sources from MediaFusion (key or sign-in refused). Try again, or check the add-on in Stremio.",
            "Torrentio found only torrents. A TV plays torrents through a debrid service, such as Real-Debrid, " +
                "connected in the add-on's settings.",
            "Trailers only has links that open in a browser or on YouTube, which Alder can't play.",
        ), reasons)
    }

    @Test fun torrentsOutweighLinks() {
        val reasons = noSourceReasons(listOf("Torrentio"), arrayOf(null), intArrayOf(3), intArrayOf(1), 0)
        assertEquals(1, reasons.size)
        assertTrue(reasons[0].startsWith("Torrentio found only torrents."))
    }

    @Test fun underAListOnlyFixableReasons() {
        val reasons = hiddenSourceReasons(names, arrayOf(null, HttpError(403), null), intArrayOf(12, 0, 0))
        assertEquals(2, reasons.size)
        assertTrue(reasons[0].startsWith("Couldn't get sources from MediaFusion"))
        assertTrue(reasons[1].startsWith("Torrentio found only torrents."))
        assertEquals(emptyList<String>(), hiddenSourceReasons(names, arrayOfNulls(3), intArrayOf(0, 0, 0)))
    }

    @Test fun nothingAtAll() {
        assertEquals(listOf("Your add-ons have no sources for this title."),
            noSourceReasons(listOf("Torrentio"), arrayOf(null), intArrayOf(0), intArrayOf(0), 0))
    }

    @Test fun setupPageLeavesKeysOut() {
        assertEquals("https://torrentio.strem.fun/configure",
            freshConfigureUrl("https://torrentio.strem.fun/providers=yts,eztv|realdebrid=ABC123"))
        assertEquals("https://aiostreams.example/stremio/configure",
            freshConfigureUrl("https://aiostreams.example/stremio/eyJ1dWlkIjoiMTIzNDU2Nzg5MCJ9eyJ1dWlk"))
        assertEquals("https://host.example/addon/configure", freshConfigureUrl("https://host.example/addon"))
        assertEquals("http://192.168.2.10:7000/configure", freshConfigureUrl("http://192.168.2.10:7000"))
    }

    @Test fun plainFailureReasons() {
        assertEquals("key or sign-in refused", failureReason(HttpError(401)))
        assertEquals("too many requests", failureReason(HttpError(429)))
        assertEquals("having problems", failureReason(HttpError(503)))
        assertEquals("HTTP 400", failureReason(HttpError(400)))
        assertEquals("timed out", failureReason(java.net.SocketTimeoutException()))
        assertTrue(refusedKey(HttpError(403)))
        assertFalse(refusedKey(HttpError(500)))
    }
}
