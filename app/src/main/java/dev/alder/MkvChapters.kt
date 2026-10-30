package dev.alder

import java.net.HttpURLConnection
import java.net.URL

data class MkvChapter(val startMs: Long, val title: String)

/**
 * An intro or recap chapter detected within container metadata.
 */
data class MkvIntroSegment(val label: String, val startMs: Long, val endMs: Long)

/**
 * Lightweight pure-Kotlin Matroska (MKV) EBML parser that extracts chapter landmarks from container headers.
 * Does not depend on Media3/ExoPlayer version or Jellyfin ffmpeg-decoder glue.
 */
object MkvChapters {

    const val ID_EBML_HEADER = 0x1A45DFA3L
    const val ID_SEGMENT = 0x18538067L
    const val ID_SEEK_HEAD = 0x114D9B74L
    const val ID_SEEK = 0x4DBBL
    const val ID_SEEK_ID = 0x53ABL
    const val ID_SEEK_POSITION = 0x53ACL
    const val ID_CHAPTERS = 0x1043A770L
    const val ID_EDITION_ENTRY = 0x45B9L
    const val ID_CHAPTER_ATOM = 0xB6L
    const val ID_CHAPTER_TIME_START = 0x91L
    const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
    const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
    const val ID_CHAPTER_DISPLAY = 0x80L
    const val ID_CHAP_STRING = 0x85L

    private const val BUFFER_SIZE = 65536 // 64 KB

    private val INTRO_PATTERN = Regex("(?i)\\b(intro|opening|op|recap|previously on|prologue)\\b")

    /**
     * Finds intro or recap segments from parsed MKV chapters.
     * The segment spans from the intro chapter start until the next chapter start.
     */
    fun findIntroSegments(chapters: List<MkvChapter>, durationMs: Long = 0L): List<MkvIntroSegment> {
        if (chapters.isEmpty()) return emptyList()
        val sorted = chapters.sortedBy { it.startMs }
        val segments = mutableListOf<MkvIntroSegment>()

        for (i in sorted.indices) {
            val curr = sorted[i]
            val title = curr.title.trim()
            if (!INTRO_PATTERN.containsMatchIn(title)) continue

            val isRecap = title.contains("recap", ignoreCase = true) || title.contains("previously", ignoreCase = true)
            val label = if (isRecap) "Skip recap" else "Skip intro"

            val nextStart = sorted.getOrNull(i + 1)?.startMs
            val endMs = when {
                nextStart != null && nextStart > curr.startMs -> nextStart
                durationMs > curr.startMs -> durationMs
                else -> curr.startMs + 90_000L // 90 s default if final or unknown
            }

            // Only consider reasonable intro lengths (between 5 seconds and 10 minutes)
            val lengthMs = endMs - curr.startMs
            if (lengthMs in 5_000L..600_000L) {
                segments.add(MkvIntroSegment(label, curr.startMs, endMs))
            }
        }
        return segments
    }

    /**
     * Fetches and parses chapters from a video URL using a two-step HTTP range request:
     * 1. Reads the first 64 KB. If chapters sit in the header, parses immediately.
     * 2. If chapters sit at a seek offset, makes a targeted 64 KB request to that exact offset.
     */
    fun fetch(url: String, headers: Map<String, String> = emptyMap()): List<MkvChapter> = runCatching {
        // Skip obvious non-MKV / HLS streams
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".mp4")) return emptyList()

        val initialBytes = range(url, 0L, BUFFER_SIZE, headers) ?: return emptyList()
        val direct = parseChaptersFromBuffer(initialBytes)
        if (direct.isNotEmpty()) return direct

        val seekPos = findChaptersSeekPosition(initialBytes) ?: return emptyList()
        val segStart = findSegmentDataStart(initialBytes) ?: return emptyList()
        val targetOffset = segStart + seekPos

        val chapterBytes = range(url, targetOffset, BUFFER_SIZE, headers) ?: return emptyList()
        parseChaptersFromBuffer(chapterBytes)
    }.getOrDefault(emptyList())

    internal fun parseChaptersFromBuffer(bytes: ByteArray): List<MkvChapter> {
        if (bytes.size < 4) return emptyList()
        val reader = EbmlReader(bytes)
        val chapters = mutableListOf<MkvChapter>()

        fun clampEnd(dataStart: Int, size: Long): Int =
            (dataStart.toLong() + size).coerceIn(dataStart.toLong(), bytes.size.toLong()).toInt()

        fun scanEdition(start: Int, end: Int) {
            val r = EbmlReader(bytes, start, end)
            var index = 0
            while (r.hasMore()) {
                val (id, size, dataStart) = r.readElement() ?: break
                val dataEnd = clampEnd(dataStart, size)
                if (id == ID_CHAPTER_ATOM) {
                    val atom = parseAtom(bytes, dataStart, dataEnd, index)
                    if (atom != null) {
                        chapters.add(atom)
                        index++
                    }
                }
                r.skip(size)
            }
        }

        fun scanChapters(start: Int, end: Int) {
            val r = EbmlReader(bytes, start, end)
            while (r.hasMore()) {
                val (id, size, dataStart) = r.readElement() ?: break
                val dataEnd = clampEnd(dataStart, size)
                if (id == ID_EDITION_ENTRY) {
                    scanEdition(dataStart, dataEnd)
                }
                r.skip(size)
            }
        }

        // Fast path: buffer starts directly with ID_CHAPTERS
        val firstId = reader.peekId()
        if (firstId == ID_CHAPTERS) {
            val (_, size, dataStart) = reader.readElement() ?: return emptyList()
            scanChapters(dataStart, clampEnd(dataStart, size))
            return chapters
        }

        // Search through top-level elements (inside or outside Segment)
        while (reader.hasMore()) {
            val (id, size, dataStart) = reader.readElement() ?: break
            val dataEnd = clampEnd(dataStart, size)
            when (id) {
                ID_SEGMENT -> {
                    val segReader = EbmlReader(bytes, dataStart, dataEnd)
                    while (segReader.hasMore()) {
                        val (segElemId, segElemSize, segDataStart) = segReader.readElement() ?: break
                        val segDataEnd = clampEnd(segDataStart, segElemSize)
                        if (segElemId == ID_CHAPTERS) {
                            scanChapters(segDataStart, segDataEnd)
                        }
                        segReader.skip(segElemSize)
                    }
                }
                ID_CHAPTERS -> scanChapters(dataStart, dataEnd)
            }
            reader.skip(size)
        }
        return chapters
    }

    internal fun findChaptersSeekPosition(bytes: ByteArray): Long? {
        fun clampEnd(dataStart: Int, size: Long): Int =
            (dataStart.toLong() + size).coerceIn(dataStart.toLong(), bytes.size.toLong()).toInt()
        val reader = EbmlReader(bytes)
        while (reader.hasMore()) {
            val (id, size, dataStart) = reader.readElement() ?: break
            val dataEnd = clampEnd(dataStart, size)
            if (id == ID_SEGMENT) {
                val segReader = EbmlReader(bytes, dataStart, dataEnd)
                while (segReader.hasMore()) {
                    val (segId, segSize, sStart) = segReader.readElement() ?: break
                    val sEnd = clampEnd(sStart, segSize)
                    if (segId == ID_SEEK_HEAD) {
                        val pos = parseSeekHeadForChapters(bytes, sStart, sEnd)
                        if (pos != null) return pos
                    }
                    segReader.skip(segSize)
                }
            } else if (id == ID_SEEK_HEAD) {
                val pos = parseSeekHeadForChapters(bytes, dataStart, dataEnd)
                if (pos != null) return pos
            }
            reader.skip(size)
        }
        return null
    }

    internal fun findSegmentDataStart(bytes: ByteArray): Long? {
        val reader = EbmlReader(bytes)
        while (reader.hasMore()) {
            val (id, _, dataStart) = reader.readElement() ?: break
            if (id == ID_SEGMENT) return dataStart.toLong()
            reader.skip(0)
        }
        return null
    }

    private fun parseSeekHeadForChapters(bytes: ByteArray, start: Int, end: Int): Long? {
        val reader = EbmlReader(bytes, start, end)
        while (reader.hasMore()) {
            val (id, size, dataStart) = reader.readElement() ?: break
            val dataEnd = (dataStart + size).toInt().coerceAtMost(bytes.size)
            if (id == ID_SEEK) {
                var seekId: Long? = null
                var seekPos: Long? = null
                val seekReader = EbmlReader(bytes, dataStart, dataEnd)
                while (seekReader.hasMore()) {
                    val (sChildId, sChildSize, sChildStart) = seekReader.readElement() ?: break
                    when (sChildId) {
                        ID_SEEK_ID -> seekId = readUint(bytes, sChildStart, sChildSize.toInt())
                        ID_SEEK_POSITION -> seekPos = readUint(bytes, sChildStart, sChildSize.toInt())
                    }
                    seekReader.skip(sChildSize)
                }
                if (seekId == ID_CHAPTERS && seekPos != null) return seekPos
            }
            reader.skip(size)
        }
        return null
    }

    private fun parseAtom(bytes: ByteArray, start: Int, end: Int, index: Int): MkvChapter? {
        val reader = EbmlReader(bytes, start, end)
        var startNs: Long? = null
        var hidden = false
        var enabled = true
        var title: String? = null

        while (reader.hasMore()) {
            val (id, size, dataStart) = reader.readElement() ?: break
            val dataEnd = (dataStart + size).toInt().coerceAtMost(bytes.size)
            when (id) {
                ID_CHAPTER_TIME_START -> startNs = readUint(bytes, dataStart, size.toInt())
                ID_CHAPTER_FLAG_HIDDEN -> hidden = (readUint(bytes, dataStart, size.toInt()) ?: 0L) == 1L
                ID_CHAPTER_FLAG_ENABLED -> enabled = (readUint(bytes, dataStart, size.toInt()) ?: 1L) == 1L
                ID_CHAPTER_DISPLAY -> {
                    val dispReader = EbmlReader(bytes, dataStart, dataEnd)
                    while (dispReader.hasMore()) {
                        val (dId, dSize, dStart) = dispReader.readElement() ?: break
                        if (dId == ID_CHAP_STRING) {
                            title = String(bytes, dStart, dSize.toInt().coerceAtMost(bytes.size - dStart), Charsets.UTF_8).trim()
                        }
                        dispReader.skip(dSize)
                    }
                }
            }
            reader.skip(size)
        }

        if (hidden || !enabled || startNs == null) return null
        val finalTitle = if (!title.isNullOrBlank()) title else "Chapter ${index + 1}"
        return MkvChapter(startNs / 1_000_000L, finalTitle)
    }

    private fun readUint(bytes: ByteArray, start: Int, len: Int): Long? {
        if (start < 0 || start + len > bytes.size || len <= 0 || len > 8) return null
        var result = 0L
        for (i in 0 until len) {
            result = (result shl 8) or (bytes[start + i].toLong() and 0xFF)
        }
        return result
    }

    private fun range(urlStr: String, start: Long, length: Int, headers: Map<String, String>): ByteArray? {
        var currentUrl = urlStr
        for (redirect in 0..3) {
            val end = start + length - 1
            val c = URL(currentUrl).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", headers["User-Agent"] ?: Http.userAgent)
            for ((k, v) in headers) if (!k.equals("User-Agent", ignoreCase = true)) c.setRequestProperty(k, v)
            c.setRequestProperty("Range", "bytes=$start-$end")

            val code = c.responseCode
            if (code in 301..308) {
                currentUrl = c.getHeaderField("Location") ?: return null
                c.disconnect()
                continue
            }
            if (code !in 200..206) {
                c.disconnect()
                return null
            }
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(length)
                val buf = ByteArray(8192)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buf, 0, minOf(buf.size, remaining))
                    if (read < 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
                out.toByteArray()
            }
        }
        return null
    }

    internal class EbmlReader(private val bytes: ByteArray, private val start: Int = 0, private val end: Int = bytes.size) {
        private var offset = start

        fun hasMore(): Boolean = offset < end

        fun peekId(): Long? {
            if (!hasMore()) return null
            val saved = offset
            val id = readId()
            offset = saved
            return id
        }

        fun skip(bytesCount: Long) {
            offset = (offset.toLong() + bytesCount).coerceIn(offset.toLong(), end.toLong()).toInt()
        }

        data class ElementHeader(val id: Long, val size: Long, val dataStart: Int)

        fun readElement(): ElementHeader? {
            val id = readId() ?: return null
            val size = readVintSize() ?: return null
            val dataStart = offset
            return ElementHeader(id, size, dataStart)
        }

        private fun readId(): Long? {
            if (offset >= end) return null
            val b0 = bytes[offset].toInt() and 0xFF
            val len = when {
                (b0 and 0x80) != 0 -> 1
                (b0 and 0x40) != 0 -> 2
                (b0 and 0x20) != 0 -> 3
                (b0 and 0x10) != 0 -> 4
                else -> return null
            }
            if (offset + len > end) return null
            var id = 0L
            for (i in 0 until len) {
                id = (id shl 8) or (bytes[offset++].toLong() and 0xFF)
            }
            return id
        }

        private fun readVintSize(): Long? {
            if (offset >= end) return null
            val b0 = bytes[offset].toInt() and 0xFF
            val len: Int
            val mask: Int
            when {
                (b0 and 0x80) != 0 -> { len = 1; mask = 0x7F }
                (b0 and 0x40) != 0 -> { len = 2; mask = 0x3F }
                (b0 and 0x20) != 0 -> { len = 3; mask = 0x1F }
                (b0 and 0x10) != 0 -> { len = 4; mask = 0x0F }
                (b0 and 0x08) != 0 -> { len = 5; mask = 0x07 }
                (b0 and 0x04) != 0 -> { len = 6; mask = 0x03 }
                (b0 and 0x02) != 0 -> { len = 7; mask = 0x01 }
                (b0 and 0x01) != 0 -> { len = 8; mask = 0x00 }
                else -> return null
            }
            if (offset + len > end) return null
            var size = (bytes[offset++].toLong() and mask.toLong())
            for (i in 1 until len) {
                size = (size shl 8) or (bytes[offset++].toLong() and 0xFF)
            }
            // All 1s indicates unknown size
            val isUnknown = when (len) {
                1 -> size == 0x7FL
                2 -> size == 0x3FFFL
                3 -> size == 0x1FFFFFL
                4 -> size == 0x0FFFFFFFL
                else -> size == 0x00FFFFFFFFFFFFFFL
            }
            return if (isUnknown) (end - offset).toLong() else size
        }
    }
}
