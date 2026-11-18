package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTypesTest {
    private val episode = Video("kitsu:1:1", "Episode 1", 1, 1, null, null, null)

    @Test fun otherTypesGetAPageEachInAddonOrder() =
        assertEquals(listOf("anime", "channel"), extraPageTypes(listOf("movie", "Anime", "series", "tv", "channel", "anime ", "events", "")))

    @Test fun episodesDecideForOtherTypes() {
        assertTrue(hasEpisodes("series", null))
        assertFalse(hasEpisodes("movie", listOf(episode)))
        assertFalse(hasEpisodes("tv", listOf(episode)))
        assertTrue(hasEpisodes("anime", listOf(episode)))
        assertFalse(hasEpisodes("anime", emptyList()))
        assertFalse(hasEpisodes("anime", null))
    }
}
