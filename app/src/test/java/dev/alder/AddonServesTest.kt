package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which add-ons are asked for a title's streams, as stremio-core's is_resource_supported decides it. */
class AddonServesTest {
    private fun addon(manifest: String) =
        Addon(JSONObject("""{"transportUrl":"https://example.com/manifest.json","manifest":$manifest}"""))

    @Test fun emptyPrefixesMeanEveryId() {
        val a = addon("""{"id":"x","types":["movie","series"],"idPrefixes":[],"resources":["stream"]}""")
        assertTrue(a.serves("stream", "movie", "tt0111161"))
        assertTrue(a.serves("stream", "series", "kitsu:1:2"))
    }

    @Test fun aFullResourceWithoutPrefixesTakesEveryId() {
        // The manifest's own prefixes are for its other resources (here its catalog).
        val a = addon("""{"id":"x","types":["movie"],"idPrefixes":["mf"],
            "resources":[{"name":"stream","types":["movie"]},"catalog"]}""")
        assertTrue(a.serves("stream", "movie", "tt0111161"))
    }

    @Test fun prefixesAndTypesStillLimit() {
        // Torrentio's shape.
        val a = addon("""{"id":"x","types":["movie","series","anime","other"],
            "resources":[{"name":"stream","types":["movie","series","anime"],"idPrefixes":["tt","kitsu"]}]}""")
        assertTrue(a.serves("stream", "series", "tt0903747:1:1"))
        assertFalse(a.serves("stream", "movie", "tmdb:278"))
        assertFalse(a.serves("stream", "tv", "tt0903747"))
        assertFalse(a.serves("subtitles", "movie", "tt0111161"))
    }

    @Test fun shortResourcesUseTheManifest() {
        val a = addon("""{"id":"x","types":["movie","series"],"idPrefixes":["tt"],"resources":["stream"]}""")
        assertTrue(a.serves("stream", "movie", "tt0111161"))
        assertFalse(a.serves("stream", "movie", "kitsu:1"))
    }

    @Test fun shortVideosAreVideosAndActionsAreNot() {
        fun s(size: Long) = Stream("t", "", "https://example.com/a.mkv", emptyMap(), size, null, null)
        assertTrue(s(40L * 1024 * 1024).isVideo) // a 7-minute cartoon
        assertTrue(s(0).isVideo)
        assertFalse(s(27L * 1024).isVideo) // MyTrakt's "Mark Watched"
    }
}
