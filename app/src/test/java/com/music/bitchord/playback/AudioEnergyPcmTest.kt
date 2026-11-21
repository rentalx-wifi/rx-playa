package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The meter as the bit-perfect sink feeds it: interleaved PCM straight from the
 * decoder, never a float block. It used to read a flat zero on that path, which
 * left the RX Wave plate breathing at nothing on a USB DAC.
 */
class AudioEnergyPcmTest {

    private val sampleRate = 48_000

    private fun sine(frames: Int, hz: Double, amplitude: Double = 0.5): DoubleArray =
        DoubleArray(frames) { frame -> amplitude * sin(2.0 * PI * hz * frame / sampleRate) }

    private fun pcm16(samples: DoubleArray, channels: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val value = (sample * Short.MAX_VALUE).toInt().toShort()
            repeat(channels) { buffer.putShort(value) }
        }
        buffer.flip()
        return buffer
    }

    private fun pcm24(samples: DoubleArray, channels: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * channels * 3).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val value = (sample * 8_388_607.0).toInt()
            repeat(channels) {
                buffer.put(value.toByte())
                buffer.put((value shr 8).toByte())
                buffer.put((value shr 16).toByte())
            }
        }
        buffer.flip()
        return buffer
    }

    private fun pcm32(samples: DoubleArray, channels: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val value = (sample * Int.MAX_VALUE).toLong().toInt()
            repeat(channels) { buffer.putInt(value) }
        }
        buffer.flip()
        return buffer
    }

    /**
     * Feeds [seconds] of [samples] the way the sink writes a buffer at a time.
     *
     * Blocks are a few milliseconds apart because the meter publishes at most
     * once per [AudioEnergy]'s own interval: fed back to back the way a test can,
     * a whole second of audio lands inside one interval and only one of the six
     * blocks would ever be published.
     */
    private fun feed(buffer: ByteBuffer, bytesPerSample: Int, channels: Int, seconds: Double) {
        val frames = (seconds * sampleRate).toInt()
        val perBlock = frames / 6
        val stride = bytesPerSample * channels
        var offset = 0
        repeat(6) {
            AudioEnergy.feedPcm(buffer, offset, bytesPerSample, channels, perBlock)
            offset += perBlock * stride
            Thread.sleep(10)
        }
    }

    @Before
    fun reset() {
        AudioEnergy.silence()
        AudioEnergy.configure(sampleRate)
    }

    @Test
    fun `a kick in 16 bit pcm moves the plate`() {
        feed(pcm16(sine(sampleRate, hz = 60.0), channels = 2), bytesPerSample = 2, channels = 2, seconds = 1.0)

        assertTrue("bass was ${AudioEnergy.currentBass()}", AudioEnergy.currentBass() > 0.2f)
    }

    @Test
    fun `24 and 32 bit pcm read the same band`() {
        feed(pcm24(sine(sampleRate, hz = 60.0), channels = 2), bytesPerSample = 3, channels = 2, seconds = 1.0)
        val at24 = AudioEnergy.currentBass()

        AudioEnergy.silence()
        feed(pcm32(sine(sampleRate, hz = 60.0), channels = 2), bytesPerSample = 4, channels = 2, seconds = 1.0)
        val at32 = AudioEnergy.currentBass()

        assertTrue("24-bit read $at24, 32-bit read $at32", at24 > 0.2f && at32 > 0.2f)
        assertEquals(at24, at32, 0.05f)
    }

    @Test
    fun `mono pcm is read too`() {
        feed(pcm16(sine(sampleRate, hz = 55.0), channels = 1), bytesPerSample = 2, channels = 1, seconds = 1.0)

        assertTrue(AudioEnergy.currentBass() > 0.2f)
    }

    @Test
    fun `silence leaves the plate idle`() {
        feed(pcm16(DoubleArray(sampleRate), channels = 2), bytesPerSample = 2, channels = 2, seconds = 1.0)

        assertEquals(0f, AudioEnergy.currentBass(), 0.0001f)
    }

    @Test
    fun `a block shorter than one frame is ignored`() {
        val buffer = pcm16(sine(16, hz = 60.0), channels = 2)
        AudioEnergy.feedPcm(buffer, offset = 0, bytesPerSample = 2, channels = 2, frames = 0)

        assertEquals(0f, AudioEnergy.currentBass(), 0.0001f)
    }
}
