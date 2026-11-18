package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleExtrasTest {
    @Test fun sendsSizeAndEncodedFileName() =
        assertEquals("videoSize=1234&filename=The%20Show%20S01E02%20%5BWEB%5D%26more.mkv",
            subtitleExtras("The Show S01E02 [WEB]&more.mkv", 1234))

    @Test fun nothingKnownLeavesTheRequestAsItWas() {
        assertEquals("", subtitleExtras(null, 0))
        assertEquals("", subtitleExtras(" ", 0))
    }

    @Test fun sendsOnlyWhatIsKnown() {
        assertEquals("videoSize=99", subtitleExtras(null, 99))
        assertEquals("filename=a.mkv", subtitleExtras("a.mkv", 0))
    }
}
