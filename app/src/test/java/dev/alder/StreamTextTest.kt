package dev.alder

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StreamTextTest {
    @Test fun sizeAndSeedersComeFromTheAddonText() {
        val torrentio = JSONObject().put("name", "Torrentio\n4k").put("infoHash", "65fc916711647cfe3a5ab0e3718f776414a5914f")
            .put("title", "Night of the Living Dead 1968 2160p BluRay\n👤 52 💾 4.35 GB ⚙️ YTS")
        val s = requireNotNull(Stream.from(torrentio, allowTorrents = true))
        assertEquals(4_350_000_000L, s.size)
        assertEquals(52, s.seeders)
        assertEquals(812_000_000L, sizeIn("1080p · 812 MB · 10 MB/s"))
        assertEquals(0L, sizeIn("no size here"))
        assertNull(seedersIn("Torrentio 4k"))
    }

    @Test fun audioReadsFormatAndChannels() {
        assertEquals("DD+ 5.1", audioSummary("movie.2160p.web-dl.ddp5.1.atmos-x".replace("atmos", "")))
        assertEquals("Atmos 7.1", audioSummary("truehd.7.1.atmos"))
        assertEquals("AAC 2.0", audioSummary("x265.10bit.aac2.0"))
        assertEquals("DTS-HD 5.1", audioSummary("dts-hd.ma.5.1"))
        assertEquals("FLAC Mono", audioSummary("h265.sdr.flac.mono"))
        assertNull(audioSummary("2160p bluray x265"))
    }

    @Test fun debridTagSaysCachedOrNot() {
        assertEquals("RD ⚡", Debrid("Real-Debrid", "RD", true).tag)
        assertEquals("RD ⏳", Debrid("Real-Debrid", "RD", false).tag)
        assertEquals("RD", Debrid("Real-Debrid", "RD", null).tag)
    }
}
