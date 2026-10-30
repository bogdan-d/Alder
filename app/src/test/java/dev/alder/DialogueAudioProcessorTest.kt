package dev.alder

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("DEPRECATION")
class DialogueAudioProcessorTest {

    @Test
    fun rejectsUnsupportedFormats() {
        val processor = DialogueAudioProcessor()

        // Mono PCM 16-bit -> rejected
        val mono = AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(mono))

        // Stereo Float -> rejected
        val stereoFloat = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(stereoFloat))
    }

    @Test
    fun acceptsStereoAndSurroundPcm16() {
        val processor = DialogueAudioProcessor()

        val stereo = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        val configuredStereo = processor.configure(stereo)
        assertEquals(stereo, configuredStereo)
        processor.flush()
        assertTrue(processor.isActive)

        val surround51 = AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
        val configuredSurround = processor.configure(surround51)
        assertEquals(surround51, configuredSurround)
        processor.flush()
        assertTrue(processor.isActive)
    }

    @Test
    fun passesThroughWhenDisabled() {
        val processor = DialogueAudioProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        processor.enabled = false

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1000.toShort())
        input.putShort(2000.toShort())
        input.putShort((-500).toShort())
        input.putShort((-1500).toShort())
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        assertEquals(1000.toShort(), output.short)
        assertEquals(2000.toShort(), output.short)
        assertEquals((-500).toShort(), output.short)
        assertEquals((-1500).toShort(), output.short)
    }

    @Test
    fun stereoMidSideBoostsCenterDialogueAndTrimsSide() {
        val processor = DialogueAudioProcessor()
        val format = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        processor.enabled = true

        // 1. Pure center sound: L = 1000, R = 1000 -> Mid = 1000, Side = 0
        // Mid' = 1000 * 1.45 = 1450, Side' = 0 -> L' = 1450, R' = 1450
        val inputCenter = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        inputCenter.putShort(1000.toShort())
        inputCenter.putShort(1000.toShort())
        inputCenter.flip()

        processor.queueInput(inputCenter)
        var output = processor.output
        assertEquals(1450.toShort(), output.short)
        assertEquals(1450.toShort(), output.short)

        // 2. Pure wide/side sound: L = 1000, R = -1000 -> Mid = 0, Side = 1000
        // Mid' = 0, Side' = 1000 * 0.75 = 750 -> L' = 750, R' = -750
        val inputSide = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        inputSide.putShort(1000.toShort())
        inputSide.putShort((-1000).toShort())
        inputSide.flip()

        processor.queueInput(inputSide)
        output = processor.output
        assertEquals(750.toShort(), output.short)
        assertEquals((-750).toShort(), output.short)
    }

    @Test
    fun surround51BoostsCenterCutsLfeTrimsSurround() {
        val processor = DialogueAudioProcessor()
        val format = AudioProcessor.AudioFormat(48000, 6, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        processor.enabled = true

        val input = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
        // ch 0: Left = 1000
        input.putShort(1000.toShort())
        // ch 1: Right = 1000
        input.putShort(1000.toShort())
        // ch 2: Center = 1000
        input.putShort(1000.toShort())
        // ch 3: LFE = 1000
        input.putShort(1000.toShort())
        // ch 4: Ls = 1000
        input.putShort(1000.toShort())
        // ch 5: Rs = 1000
        input.putShort(1000.toShort())
        input.flip()

        processor.queueInput(input)
        val output = processor.output

        // Left / Right trimmed by 0.79x -> ~790
        assertEquals(790.toShort(), output.short)
        assertEquals(790.toShort(), output.short)
        // Center boosted by 1.68x -> ~1680
        assertEquals(1680.toShort(), output.short)
        // LFE cut by 0.5x -> 500
        assertEquals(500.toShort(), output.short)
        // Surrounds trimmed by 0.79x -> ~790
        assertEquals(790.toShort(), output.short)
        assertEquals(790.toShort(), output.short)
    }

    @Test
    fun softKneeSaturatorNeverExceedsShortLimits() {
        // Below threshold (24576) is linear
        assertEquals(10000, DialogueAudioProcessor.saturate(10000f))
        assertEquals(-10000, DialogueAudioProcessor.saturate(-10000f))
        assertEquals(24576, DialogueAudioProcessor.saturate(24576f))

        // Above threshold smoothly compresses
        val sat32k = DialogueAudioProcessor.saturate(32767f)
        assertTrue("Expected compressed value below 32767: $sat32k", sat32k in 24577..32767)

        // Extreme peaks never wrap or exceed Short.MAX_VALUE
        val extremePositive = DialogueAudioProcessor.saturate(100000f)
        assertTrue("Expected capped value: $extremePositive", extremePositive <= Short.MAX_VALUE.toInt())

        val extremeNegative = DialogueAudioProcessor.saturate(-100000f)
        assertTrue("Expected capped value: $extremeNegative", extremeNegative >= Short.MIN_VALUE.toInt())
    }
}
