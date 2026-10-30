package dev.alder

import kotlin.math.abs

enum class MarkerType { CHAPTER, INTRO, RECAP, CREDITS }

data class IntroSegment(val label: String, val startMs: Long, val endMs: Long)

data class Marker(
    val timeMs: Long,
    val title: String,
    val type: MarkerType,
    val endMs: Long? = null
)

object TimelineMarkers {

    /**
     * Combines MKV container chapters with IntroDB segments (intro, recap, outro).
     * If an MKV chapter starts within 5 seconds of an IntroDB segment, IntroDB takes precedence.
     */
    fun merge(
        mkvChapters: List<MkvChapter>,
        introSegments: List<IntroSegment>,
        outroAt: Long,
        durationMs: Long
    ): List<Marker> {
        val result = mutableListOf<Marker>()

        val introMarkers = mutableListOf<Marker>()
        for (seg in introSegments) {
            val type = if (seg.label.contains("recap", ignoreCase = true)) MarkerType.RECAP else MarkerType.INTRO
            val title = if (type == MarkerType.RECAP) "Recap" else "Intro"
            introMarkers.add(Marker(seg.startMs, title, type, seg.endMs))
        }
        if (outroAt > 0) {
            val end = if (durationMs > outroAt) durationMs else null
            introMarkers.add(Marker(outroAt, "Credits", MarkerType.CREDITS, end))
        }

        result.addAll(introMarkers)

        for (chap in mkvChapters) {
            val collides = introMarkers.any { abs(it.timeMs - chap.startMs) <= 5000L }
            if (!collides) {
                result.add(Marker(chap.startMs, chap.title, MarkerType.CHAPTER))
            }
        }

        if (result.isEmpty()) return emptyList()

        for (seg in introSegments) {
            val end = seg.endMs
            if (end > seg.startMs && (durationMs <= 0L || end < durationMs - 10_000L)) {
                val hasPostMarker = result.any { abs(it.timeMs - end) <= 5000L }
                if (!hasPostMarker) {
                    result.add(Marker(end, "Episode", MarkerType.CHAPTER))
                }
            }
        }

        result.sortBy { it.timeMs }

        if (result.first().timeMs > 10_000L) {
            result.add(0, Marker(0L, "Start", MarkerType.CHAPTER))
        }

        return result
    }

    /** Finds the marker currently spanning [timeMs]. */
    fun findCurrent(markers: List<Marker>, timeMs: Long): Marker? {
        if (markers.isEmpty()) return null
        return markers.lastOrNull { timeMs >= it.timeMs } ?: markers.first()
    }

    /** Finds the next marker after [timeMs]. */
    fun findNext(markers: List<Marker>, timeMs: Long, thresholdMs: Long = 1000L): Marker? {
        return markers.firstOrNull { it.timeMs > timeMs + thresholdMs }
    }

    /**
     * Finds the previous marker before [timeMs].
     * If the current position is more than [thresholdMs] into the active chapter, restarts it.
     * Otherwise, steps to the preceding chapter.
     */
    fun findPrev(markers: List<Marker>, timeMs: Long, thresholdMs: Long = 1500L): Marker? {
        if (markers.isEmpty()) return null
        val current = findCurrent(markers, timeMs) ?: return markers.first()
        if (timeMs - current.timeMs > thresholdMs) {
            return current
        }
        val index = markers.indexOf(current)
        return if (index > 0) markers[index - 1] else markers.first()
    }
}
