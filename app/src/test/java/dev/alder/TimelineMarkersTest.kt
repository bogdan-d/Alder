package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineMarkersTest {

    @Test
    fun testMergeDeduplicatesWithIntroDbPriority() {
        val mkvChapters = listOf(
            MkvChapter(0L, "Chapter 1"),
            MkvChapter(75_000L, "Chapter 2"), // Within 5 s of Intro at 72 s
            MkvChapter(600_000L, "The Heist"),
            MkvChapter(1_200_000L, "Chapter 4") // Within 5 s of Outro at 1_202 s
        )
        val introSegments = listOf(
            IntroSegment("Skip intro", 72_000L, 160_000L)
        )
        val outroAt = 1_202_000L
        val durationMs = 1_300_000L

        val markers = TimelineMarkers.merge(mkvChapters, introSegments, outroAt, durationMs)

        // Expected markers:
        // 0:00 -> Chapter 1
        // 1:12 -> Intro (72_000) - Chapter 2 at 75_000 was dropped
        // 2:40 -> Episode (160_000) - end of Intro
        // 10:00 -> The Heist (600_000)
        // 20:02 -> Credits (1_202_000) - Chapter 4 at 1_200_000 was dropped
        assertEquals(5, markers.size)
        assertEquals("Chapter 1", markers[0].title)
        assertEquals(MarkerType.CHAPTER, markers[0].type)

        assertEquals("Intro", markers[1].title)
        assertEquals(72_000L, markers[1].timeMs)
        assertEquals(160_000L, markers[1].endMs)
        assertEquals(MarkerType.INTRO, markers[1].type)

        assertEquals("Episode", markers[2].title)
        assertEquals(160_000L, markers[2].timeMs)

        assertEquals("The Heist", markers[3].title)
        assertEquals(600_000L, markers[3].timeMs)

        assertEquals("Credits", markers[4].title)
        assertEquals(1_202_000L, markers[4].timeMs)
        assertEquals(MarkerType.CREDITS, markers[4].type)
    }

    @Test
    fun testEmptyWhenNoChaptersOrSegments() {
        val markers = TimelineMarkers.merge(emptyList(), emptyList(), 0L, 500_000L)
        assertEquals(0, markers.size)
    }

    @Test
    fun testPrependsStartWhenFirstMarkerNotAtZero() {
        val mkvChapters = listOf(
            MkvChapter(120_000L, "Scene 1")
        )
        val markers = TimelineMarkers.merge(mkvChapters, emptyList(), 0L, 500_000L)
        assertEquals(2, markers.size)
        assertEquals(0L, markers[0].timeMs)
        assertEquals("Start", markers[0].title)
        assertEquals("Scene 1", markers[1].title)
    }

    @Test
    fun testFindCurrentMarker() {
        val markers = listOf(
            Marker(0L, "Start", MarkerType.CHAPTER),
            Marker(60_000L, "Intro", MarkerType.INTRO, 120_000L),
            Marker(120_000L, "Part 1", MarkerType.CHAPTER)
        )

        assertEquals("Start", TimelineMarkers.findCurrent(markers, 30_000L)?.title)
        assertEquals("Intro", TimelineMarkers.findCurrent(markers, 60_000L)?.title)
        assertEquals("Intro", TimelineMarkers.findCurrent(markers, 90_000L)?.title)
        assertEquals("Part 1", TimelineMarkers.findCurrent(markers, 120_000L)?.title)
        assertEquals("Part 1", TimelineMarkers.findCurrent(markers, 500_000L)?.title)
    }

    @Test
    fun testFindNextAndPrevSnap() {
        val markers = listOf(
            Marker(0L, "Start", MarkerType.CHAPTER),
            Marker(100_000L, "Chapter 2", MarkerType.CHAPTER),
            Marker(200_000L, "Chapter 3", MarkerType.CHAPTER)
        )

        // findNext
        assertEquals(100_000L, TimelineMarkers.findNext(markers, 0L)?.timeMs)
        assertEquals(100_000L, TimelineMarkers.findNext(markers, 50_000L)?.timeMs)
        assertEquals(200_000L, TimelineMarkers.findNext(markers, 100_000L)?.timeMs)
        assertNull(TimelineMarkers.findNext(markers, 200_000L))

        // findPrev: if > 1.5s into current chapter, snaps to current chapter start
        assertEquals(100_000L, TimelineMarkers.findPrev(markers, 150_000L)?.timeMs)
        // If within 1.5s of current chapter start, snaps to previous chapter
        assertEquals(0L, TimelineMarkers.findPrev(markers, 100_500L)?.timeMs)
        assertEquals(0L, TimelineMarkers.findPrev(markers, 0L)?.timeMs)
    }
}
