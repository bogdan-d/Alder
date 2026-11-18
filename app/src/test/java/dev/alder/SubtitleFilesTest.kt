package dev.alder

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleFilesTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nZażółć gęślą jaźń\n"

    @Test fun aZipGivesTheSubtitleInside() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("readme.txt")); z.write("from SubDL".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Film.2020.pl.srt")); z.write(srt.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals(srt, String(SubtitleFiles.unpack(zip)))
    }

    @Test fun oldCodePagesBecomeUtf8() {
        assertEquals(srt, SubtitleFiles.decode(srt.toByteArray(charset("windows-1250")), "pol"))
        val arabic = "مرحبا"
        assertEquals(arabic, SubtitleFiles.decode(arabic.toByteArray(charset("windows-1256")), "ara"))
    }

    @Test fun utf8StaysAndLosesItsMark() {
        assertEquals(srt, SubtitleFiles.decode(("﻿" + srt).toByteArray(), "pol"))
        assertEquals(srt, SubtitleFiles.decode(srt.toByteArray(), "eng"))
    }

    @Test fun theFormatComesFromTheText() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, SubtitleFiles.mimeOf(srt))
        assertEquals(MimeTypes.TEXT_VTT, SubtitleFiles.mimeOf("WEBVTT\n\n00:01.000 --> 00:02.000\nHi"))
        assertEquals(MimeTypes.TEXT_SSA, SubtitleFiles.mimeOf("[Script Info]\nTitle: x\n[Events]\n"))
    }
}
