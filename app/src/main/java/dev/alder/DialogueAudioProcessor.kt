package dev.alder

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lifts dialogue and speech above music and background sound effects.
 *
 * For multi-channel surround streams (5.1 / 7.1, channelCount >= 3):
 * - Center channel (ch 2) holds most dialogue; boosted by +4.5 dB (~1.68x).
 * - LFE / subwoofer (ch 3 on 5.1/7.1) holds explosions and rumbles; attenuated by -6 dB (0.5x).
 * - Left, Right, and Surround channels trimmed by -2 dB (~0.79x).
 *
 * For stereo streams (channelCount == 2):
 * - Decomposed into Mid (L + R) / 2 and Side (L - R) / 2.
 * - Dialogue sits center (Mid boosted x1.45), while wide music/effects sit in Side (trimmed x0.75).
 * - Recomposed into L' and R'.
 *
 * A fast soft-knee saturator prevents hard-clipping crackle when loud speech peaks occur.
 */
class DialogueAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val buffer = replaceOutputBuffer(remaining)

        if (!enabled) {
            buffer.put(inputBuffer)
            buffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val bytesPerFrame = channelCount * 2
        val frames = remaining / bytesPerFrame

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        if (channelCount == 2) {
            for (f in 0 until frames) {
                val l = inputBuffer.short.toFloat()
                val r = inputBuffer.short.toFloat()
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f
                val midPrime = mid * 1.45f
                val sidePrime = side * 0.75f
                val lPrime = midPrime + sidePrime
                val rPrime = midPrime - sidePrime
                buffer.putShort(saturate(lPrime).toShort())
                buffer.putShort(saturate(rPrime).toShort())
            }
        } else {
            // Multichannel (3.0, 5.0, 5.1, 7.1, etc.)
            for (f in 0 until frames) {
                for (ch in 0 until channelCount) {
                    val raw = inputBuffer.short.toFloat()
                    val mult = when (ch) {
                        2 -> 1.68f // Front Center (+4.5 dB)
                        3 -> if (channelCount >= 6) 0.5f else 0.79f // LFE (-6 dB) on 5.1/7.1, or Surround on 5.0
                        else -> 0.79f // L/R/Surrounds (-2 dB)
                    }
                    val processed = saturate(raw * mult)
                    buffer.putShort(processed.toShort())
                }
            }
        }

        // Copy any remainder bytes if buffer was not frame-aligned
        if (inputBuffer.hasRemaining()) {
            buffer.put(inputBuffer)
        }

        buffer.flip()
    }

    companion object {
        private const val THRESHOLD = 24576f // 75% of 32767 (~ -2.7 dBFS)
        private const val SPAN = 8191f // 32767 - 24576

        /**
         * Smooth soft-knee saturation curve.
         * Pure linear below THRESHOLD; smoothly compresses above THRESHOLD approaching 32767.
         */
        internal fun saturate(v: Float): Int {
            val out = when {
                v > THRESHOLD -> {
                    val d = v - THRESHOLD
                    val u = d / SPAN
                    THRESHOLD + SPAN * (u / (1f + u))
                }
                v < -THRESHOLD -> {
                    val d = -v - THRESHOLD
                    val u = d / SPAN
                    -(THRESHOLD + SPAN * (u / (1f + u)))
                }
                else -> v
            }
            return out.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }
}
