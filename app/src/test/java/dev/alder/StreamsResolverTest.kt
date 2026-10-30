package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsResolverTest {

    private fun stream(
        title: String = "Test Stream",
        url: String = "https://example.com/stream.mkv",
        filename: String? = "test.mkv",
        size: Long = 1_000_000_000L,
        bingeGroup: String? = null
    ): Stream = Stream(
        title = title,
        description = "",
        url = url,
        headers = emptyMap(),
        size = size,
        filename = filename,
        bingeGroup = bingeGroup
    )

    @Test
    fun readyStreamsStopsAtNullWhenGapsDisallowed() {
        val s1 = stream("s1")
        val s2 = stream("s2")
        val s3 = stream("s3")
        val answers: Array<List<Stream>?> = arrayOf(listOf(s1), null, listOf(s3))

        val withoutGaps = readyStreams(answers, allowGaps = false)
        assertEquals(listOf(s1), withoutGaps)

        val withGaps = readyStreams(answers, allowGaps = true)
        assertEquals(listOf(s1, s3), withGaps)
    }

    @Test
    fun wantedFiltersCamReleases() {
        val cam = stream("Movie (CAM)")
        assertTrue(cam.cam)

        val nonCam = stream("Movie 1080p BluRay")
        assertFalse(nonCam.cam)
    }

    @Test
    fun cancellableCanBeCanceled() {
        var canceled = false
        val c = object : Cancellable {
            override fun cancel() { canceled = true }
        }
        assertFalse(canceled)
        c.cancel()
        assertTrue(canceled)
    }
}
