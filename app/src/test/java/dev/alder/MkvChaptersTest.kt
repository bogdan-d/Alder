package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MkvChaptersTest {

    private fun vint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        when {
            value < 0x7F -> out.write((0x80 or value.toInt()))
            value < 0x3FFF -> {
                out.write((0x40 or (value shr 8).toInt()))
                out.write((value and 0xFF).toInt())
            }
            value < 0x1FFFFF -> {
                out.write((0x20 or (value shr 16).toInt()))
                out.write(((value shr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            value < 0x0FFFFFFF -> {
                out.write((0x10 or (value shr 24).toInt()))
                out.write(((value shr 16) and 0xFF).toInt())
                out.write(((value shr 8) and 0xFF).toInt())
                out.write((value and 0xFF).toInt())
            }
            else -> {
                out.write(0x01)
                for (i in 6 downTo 0) out.write(((value shr (i * 8)) and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    private fun element(id: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val idBytes = when {
            id > 0xFFFFFF -> byteArrayOf((id shr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
            id > 0xFFFF -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
            id > 0xFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
            else -> byteArrayOf(id.toByte())
        }
        out.write(idBytes)
        out.write(vint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun ulong(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var started = false
        for (i in 7 downTo 0) {
            val b = ((v shr (i * 8)) and 0xFF).toInt()
            if (b != 0 || started || i == 0) {
                started = true
                out.write(b)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun testParseDirectChapters() {
        val atom1 = element(0xB6,
            element(0x91, ulong(0L)) +
            element(0x80, element(0x85, "Prologue".toByteArray(Charsets.UTF_8)))
        )
        val atom2 = element(0xB6,
            element(0x91, ulong(120_000_000_000L)) + // 120 s in ns
            element(0x80, element(0x85, "The Departure".toByteArray(Charsets.UTF_8)))
        )
        val edition = element(0x45B9, atom1 + atom2)
        val chapters = element(0x1043A770, edition)

        val ebmlHeader = element(0x1A45DFA3, byteArrayOf(1, 2, 3))
        // Segment length can be known or unknown (0x01FFFFFFFFFFFFFFL)
        val segmentPayload = chapters
        val segment = element(0x18538067, segmentPayload)

        val fileBuffer = ebmlHeader + segment
        val result = MkvChapters.parseChaptersFromBuffer(fileBuffer)

        assertEquals(2, result.size)
        assertEquals(0L, result[0].startMs)
        assertEquals("Prologue", result[0].title)
        assertEquals(120_000L, result[1].startMs)
        assertEquals("The Departure", result[1].title)
    }

    @Test
    fun testHiddenChaptersIgnored() {
        val atomVisible = element(0xB6,
            element(0x91, ulong(0L)) +
            element(0x80, element(0x85, "Chapter 1".toByteArray(Charsets.UTF_8)))
        )
        val atomHidden = element(0xB6,
            element(0x91, ulong(60_000_000_000L)) +
            element(0x98, byteArrayOf(1)) + // ChapterFlagHidden = 1
            element(0x80, element(0x85, "Hidden Chapter".toByteArray(Charsets.UTF_8)))
        )
        val chapters = element(0x1043A770, element(0x45B9, atomVisible + atomHidden))
        val fileBuffer = element(0x1A45DFA3, byteArrayOf(1)) + element(0x18538067, chapters)

        val result = MkvChapters.parseChaptersFromBuffer(fileBuffer)
        assertEquals(1, result.size)
        assertEquals("Chapter 1", result[0].title)
    }

    @Test
    fun testSeekHeadFindsChaptersOffset() {
        val seekEntry = element(0x4DBB,
            element(0x53AB, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70)) + // Chapters ID
            element(0x53AC, ulong(5000L)) // SeekPosition = 5000
        )
        val seekHead = element(0x114D9B74, seekEntry)
        val ebmlHeader = element(0x1A45DFA3, byteArrayOf(1))
        val segment = element(0x18538067, seekHead)
        val fileBuffer = ebmlHeader + segment

        val offset = MkvChapters.findChaptersSeekPosition(fileBuffer)
        assertEquals(5000L, offset)
    }

    @Test
    fun testLargeMultiGigabyteSegmentDoesNotOverflowInt() {
        val seekEntry = element(0x4DBB,
            element(0x53AB, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70)) + // Chapters ID
            element(0x53AC, ulong(123456L))
        )
        val seekHead = element(0x114D9B74, seekEntry)
        val ebmlHeader = element(0x1A45DFA3, byteArrayOf(1))
        val out = ByteArrayOutputStream()
        out.write(ebmlHeader)
        out.write(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67)) // ID_SEGMENT
        out.write(vint(5_000_000_000L)) // 5 GB Segment size (> Int.MAX_VALUE)
        out.write(seekHead)
        val buffer = out.toByteArray()

        assertEquals(123456L, MkvChapters.findChaptersSeekPosition(buffer))
    }

    @Test
    fun testEmptyOrInvalidBufferSafe() {
        assertTrue(MkvChapters.parseChaptersFromBuffer(ByteArray(0)).isEmpty())
        assertTrue(MkvChapters.parseChaptersFromBuffer(byteArrayOf(1, 2, 3, 4)).isEmpty())
    }

    @Test
    fun testFindIntroSegmentsFromChapters() {
        val chapters = listOf(
            MkvChapter(0L, "Prologue"),
            MkvChapter(90_000L, "Opening / Intro"),
            MkvChapter(180_000L, "Episode Part 1"),
            MkvChapter(1_200_000L, "Ending"),
            MkvChapter(1_320_000L, "Preview")
        )

        val intros = MkvChapters.findIntroSegments(chapters, 1_400_000L)
        // Expect Prologue and Opening / Intro to be recognized
        assertEquals(2, intros.size)
        assertEquals("Skip intro", intros[0].label)
        assertEquals(0L, intros[0].startMs)
        assertEquals(90_000L, intros[0].endMs)

        assertEquals("Skip intro", intros[1].label)
        assertEquals(90_000L, intros[1].startMs)
        assertEquals(180_000L, intros[1].endMs)
    }

    @Test
    fun testFindRecapSegmentFromChapters() {
        val chapters = listOf(
            MkvChapter(0L, "Previously on Breaking Bad"),
            MkvChapter(45_000L, "Scene 1")
        )
        val intros = MkvChapters.findIntroSegments(chapters, 3_000_000L)
        assertEquals(1, intros.size)
        assertEquals("Skip recap", intros[0].label)
        assertEquals(0L, intros[0].startMs)
        assertEquals(45_000L, intros[0].endMs)
    }
}
