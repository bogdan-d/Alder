package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingTest {
    @Test
    fun shelfAndPlaybackAgreeOnSavedProgress() {
        val movie = entry("movie", "movie", 29_000, 7_560_000)
        val credits = entry("credits", "movie", 90_000, 100_000)
        val shortEpisode = entry("short", "series", 75_000, 100_000)
        val pending = entry("pending", "series", 1, 100_000)
        val unknownDuration = entry("unknown", "series", 31_000, 0)

        assertEquals(listOf(movie, shortEpisode, pending, unknownDuration),
            Library.continueWatching(listOf(movie, credits, shortEpisode, pending, unknownDuration)))
        assertEquals(29_000L, Library.resumeAt(movie, "movie"))
        assertEquals(0L, Library.resumeAt(credits, "credits"))
        assertEquals(75_000L, Library.resumeAt(shortEpisode, "short:1:2"))
        assertEquals(0L, Library.resumeAt(shortEpisode, "short:1:1"))
        assertEquals(0L, Library.resumeAt(pending, "pending:1:2"))
        assertEquals(31_000L, Library.resumeAt(unknownDuration, "unknown:1:2"))

        Library.stopped(credits, null)
        assertEquals(0L, credits.state.optLong("timeOffset"))
    }

    @Test
    fun releaseCheckOnlyAppliesToPendingEpisodes() {
        val pending = entry("pending", "series", 1, 100_000)
        val playing = entry("playing", "series", 12_000, 100_000)
        val checked = mutableListOf<String>()

        val visible = Library.continueWatching(listOf(pending, playing)) { _, videoId ->
            checked += videoId
            true
        }

        assertEquals(listOf("pending:1:2"), checked)
        assertEquals(listOf(playing), visible)
    }

    @Test
    fun episodeLabelsUseNumbersFromStructuredIds() {
        assertEquals(1 to 3, episodeNumbers("tt123:1:3"))
        assertEquals(0 to 2, episodeNumbers("tt123:0:2"))
        assertNull(episodeNumbers("addon:e:eyJzZXJpZXMi"))
        assertNull(episodeNumbers("tt123:1:0"))
        assertNull(episodeNumber(-1, 4))
    }

    private fun entry(id: String, type: String, offset: Long, duration: Long) =
        Library.newItem(id, type, id, null).apply {
            state.put("lastWatched", "2026-11-12T12:00:00.000Z")
                .put("timeOffset", offset).put("duration", duration)
            if (type == "series") state.put("video_id", "$id:1:2")
        }
}
