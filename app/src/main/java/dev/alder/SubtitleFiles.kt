package dev.alder

import androidx.media3.common.MimeTypes
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * An add-on's subtitle file made ready for ExoPlayer, as Stremio's streaming server does it for its own player: SubDL
 * sends .zip archives and many files are in the old Windows code page of their language, and ExoPlayer reads neither
 * (a zip showed nothing, Arabic or Polish showed as broken letters). The file is unpacked, turned into UTF-8 and kept
 * in [dir]; its format comes from the text, not the link (OpenSubtitles' links have no file extension).
 */
internal object SubtitleFiles {
    class Ready(val file: File, val mimeType: String)

    /** Blocks on the network: off the main thread. */
    fun prepare(dir: File, sub: Subtitle): Ready {
        val name = "sub-" + Integer.toHexString(sub.url.hashCode())
        File(dir, "$name.txt").takeIf { it.exists() }?.let { return Ready(it, mimeOf(it.readText())) }
        val text = decode(unpack(Http.bytes(sub.url, strict = true)), sub.lang)
        val file = File(dir, "$name.txt").apply { parentFile?.mkdirs(); writeText(text) }
        return Ready(file, mimeOf(text))
    }

    /** The subtitle inside a .zip or .gz, or the bytes as they are. */
    internal fun unpack(bytes: ByteArray): ByteArray = when {
        bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { e ->
                    !e.isDirectory && e.name.substringAfterLast('.').lowercase() in setOf("srt", "vtt", "ass", "ssa")
                }?.let { zip.readBytes() } ?: error("no subtitle in the archive")
            }
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> bytes
    }

    /** UTF-8 (or UTF-16 with its mark) when it reads cleanly, else the Windows code page its language used. */
    internal fun decode(bytes: ByteArray, lang: String): String {
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() || bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
            return String(bytes, Charsets.UTF_16)
        val utf8 = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) { null }
        return (utf8 ?: String(bytes, legacy(lang))).removePrefix("﻿")
    }

    // ponytail: by language, not by reading the bytes (no detector library); right for the files add-ons serve.
    private fun legacy(lang: String): Charset = Charset.forName(when (Languages.code(lang)?.let(Languages::base)) {
        "ar", "fa", "ur" -> "windows-1256"
        "ru", "uk", "bg", "sr", "mk", "be" -> "windows-1251"
        "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs", "sq" -> "windows-1250"
        "el" -> "windows-1253"
        "tr", "az" -> "windows-1254"
        "he", "yi" -> "windows-1255"
        "lt", "lv", "et" -> "windows-1257"
        "vi" -> "windows-1258"
        "th" -> "TIS-620"
        "zh" -> "GBK"
        "ja" -> "Shift_JIS"
        "ko" -> "EUC-KR"
        else -> "windows-1252"
    })

    internal fun mimeOf(text: String): String = text.trimStart().let {
        when {
            it.startsWith("WEBVTT") -> MimeTypes.TEXT_VTT
            it.startsWith("[Script Info]", ignoreCase = true) || "\n[Events]" in it -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}
