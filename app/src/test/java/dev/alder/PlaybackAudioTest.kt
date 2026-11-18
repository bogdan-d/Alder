package dev.alder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioTest {
    @Test fun supportsExactLosslessEncodingAndRejectsDtsCore() {
        val trueHd = format(MimeTypes.AUDIO_TRUEHD)
        val dtsHd = format(MimeTypes.AUDIO_DTS_HD)
        assertTrue(canPassAudio(trueHd, false, C.ENCODING_DOLBY_TRUEHD))
        assertTrue(canPassAudio(dtsHd, false, C.ENCODING_DTS_HD))
        assertFalse(canPassAudio(dtsHd, false, C.ENCODING_DTS))
        assertFalse(canPassAudio(trueHd, false, null))
        assertFalse(canPassAudio(trueHd, true, C.ENCODING_DOLBY_TRUEHD))
        assertTrue(canPassAudio(format(MimeTypes.AUDIO_RAW), true, C.ENCODING_PCM_16BIT))
        assertNull(losslessEncoding(format(MimeTypes.AUDIO_DTS_EXPRESS)))
    }

    @Test fun losslessPreferenceRespectsCommentaryAndFailedTracks() {
        val lossless = format(MimeTypes.AUDIO_TRUEHD)
        val default = format(MimeTypes.AUDIO_AC3).buildUpon().setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val commentary = lossless.buildUpon().setLabel("Commentary").build()
        val formats = listOf(commentary, default, lossless)
        val supportsLossless: (Format) -> Boolean = { losslessEncoding(it) != null }

        assertEquals(lossless, bestAudioFormat(formats, "en", emptySet(), supportsLossless))
        assertEquals(default, bestAudioFormat(formats, "en", emptySet()) { false })
        assertEquals(default, bestAudioFormat(formats, "en", setOf(lossless), supportsLossless))
    }

    @Test fun onlyAudioOutputErrorsTriggerPcmRecovery() {
        assertTrue(audioOutputFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertTrue(audioOutputFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertFalse(audioOutputFailure(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(audioOutputFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(shouldRetryWithPcm(false, true, MimeTypes.AUDIO_TRUEHD))
        assertTrue(shouldRetryWithPcm(false, true, null))
        assertFalse(shouldRetryWithPcm(false, true, MimeTypes.AUDIO_RAW))
        assertFalse(shouldRetryWithPcm(true, true, MimeTypes.AUDIO_TRUEHD))
    }

    private fun format(mime: String) = Format.Builder().setSampleMimeType(mime).setChannelCount(6).build()
}
