package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How much of the kick and the bass body is in what is playing right now — the
 * number the RX Wave plate moves to.
 *
 * Read off the audio thread by
 * [com.music.bitchord.playback.audio.PrecisionAudioSink], because that is the
 * only place the samples exist: the app has no RECORD_AUDIO permission and no
 * `android.media.audiofx.Visualizer`, and asking for a microphone to animate a
 * plate is not a trade worth making.
 *
 * Only the *audible* sink feeds this. A crossfade keeps two players running,
 * and an idle one would otherwise write its silence over the beat every time it
 * was prepared — which is what made the animation stall at track changes.
 *
 * ## The band
 *
 * A one-pole high pass at [LOW_CUT_HZ] into a one-pole low pass at
 * [HIGH_CUT_HZ], both computed from the format's own sample rate, which is the
 * native equivalent of the web recipe's bin range: 130 Hz is the plate, so the
 * band is the kick and the bass line, without the sub rumble that no phone
 * speaker reproduces and without the mids that made every snare look like a
 * beat. Both filters run at the source rate and only the squaring is decimated,
 * because filtering after the decimation folds everything up to 5 kHz back into
 * the band.
 *
 * ## The level
 *
 * Normalised against a floating peak rather than against a fixed gain: the peak
 * follows a quiet track down and a loud one up, so both use the whole height of
 * the plate. It rises instantly (a peak is a maximum) and falls slowly
 * ([PEAK_TAU_SECONDS]) so a single kick cannot pull the reference down behind
 * itself and flatten the next four bars.
 *
 * No smoothing here on purpose. The meter reports the band as it is — the
 * attack and release the plate needs belong to the plate, and two cascaded
 * envelopes are what made it trail the music.
 *
 * The value is a plain volatile float rather than a flow: a state emission per
 * audio frame would wake the UI thread sixty times a second to redraw the same
 * pixel, and the plate already has a frame callback of its own to read it from.
 */
object AudioEnergy {

    /** The kick's lower edge. Below this is rumble, not rhythm. */
    private const val LOW_CUT_HZ = 38f

    /** Just above the kick and the bass body. */
    private const val HIGH_CUT_HZ = 165f

    /** Under the 60 Hz a phone draws at, so one read per frame is enough. */
    private const val PUBLISH_INTERVAL_NANOS = 8_000_000L

    /** Every fourth frame is summed; the filters themselves are not decimated. */
    private const val DECIMATION = 4

    /** How long the reference peak takes to fall. Long: it is a reference. */
    private const val PEAK_TAU_SECONDS = 1.6f

    /** Below this the band counts as silent rather than as a quiet peak. */
    private const val PEAK_FLOOR = 0.002f

    /**
     * Flattens the top of the range a little, so a track that sits on its
     * limiter does not spend the quiet half of every bar pinned at one — the
     * "convulsions" of a level that clips.
     */
    private const val LEVEL_CURVE = 0.8f

    /**
     * Kick and bass energy, 0..1, relative to the track's own peak: 0 is
     * silence, 1 is the loudest the band has been in the last second or two.
     */
    @Volatile
    var bass: Float = 0f
        private set

    /** When [bass] was last written, on [System.nanoTime]'s clock. */
    @Volatile
    var updatedAtNanos: Long = 0L
        private set

    private var sampleRate = 0
    private var highPassCoefficient = 0f
    private var lowPassCoefficient = 0f
    private var previousInput = 0f
    private var highPassed = 0f
    private var band = 0f
    private var peak = PEAK_FLOOR
    private var lastPublishNanos = 0L
    private var lastBlockNanos = 0L

    /** A new format: both coefficients depend on the sample rate. */
    fun configure(sampleRateHz: Int) {
        if (sampleRateHz <= 0 || sampleRateHz == sampleRate) return
        sampleRate = sampleRateHz
        val rate = sampleRateHz.toFloat()
        val highPassOmega = 2f * Math.PI.toFloat() * LOW_CUT_HZ / rate
        highPassCoefficient = 1f / (1f + highPassOmega)
        val lowPassOmega = 2f * Math.PI.toFloat() * HIGH_CUT_HZ / rate
        lowPassCoefficient = lowPassOmega / (1f + lowPassOmega)
        previousInput = 0f
        highPassed = 0f
        band = 0f
    }

    /**
     * Take one block's worth of samples. Called on the audio thread; allocates
     * nothing and never blocks.
     */
    fun feed(block: AudioBlock) {
        val channels = block.channelCount
        val frames = block.frameCount
        if (channels <= 0 || frames <= 0) return
        val samples = block.samples
        accumulate(frames) { frame -> samples[frame * channels] }
    }

    /**
     * The same meter, for a sink whose samples are interleaved PCM rather than
     * floats.
     *
     * Bit-perfect output hands the device exactly what the decoder produced and
     * never builds a float block at all — that is the point of the path — so the
     * first channel is read straight out of the bytes that were just written.
     * Reading is absolute: the buffer's own position is left where the writer
     * put it.
     *
     * [bytesPerSample] is 2, 3 or 4 (16-, 24- and 32-bit PCM); anything else is
     * a stream this meter has no reading for and is ignored.
     */
    fun feedPcm(buffer: ByteBuffer, offset: Int, bytesPerSample: Int, channels: Int, frames: Int) {
        if (bytesPerSample !in 2..4 || channels <= 0 || frames <= 0) return
        val stride = bytesPerSample * channels
        val usable = minOf(frames, (buffer.limit() - offset).coerceAtLeast(0) / stride)
        if (usable <= 0) return
        accumulate(usable) { frame -> sampleAt(buffer, offset + frame * stride, bytesPerSample) }
    }

    /** One sample of the first channel, unscaled to -1..1. */
    private fun sampleAt(buffer: ByteBuffer, index: Int, bytesPerSample: Int): Float =
        when (bytesPerSample) {
            2 -> buffer.getShort(index).toFloat() / 32_768f
            3 -> {
                val low = buffer.get(index).toInt() and 0xFF
                val middle = buffer.get(index + 1).toInt() and 0xFF
                // The top byte is sign-extended by Byte.toInt(), which is
                // exactly what the 24-bit value needs.
                val high = buffer.get(index + 2).toInt() shl 16
                (high or (middle shl 8) or low).toFloat() / 8_388_608f
            }
            else -> buffer.getInt(index).toFloat() / 2_147_483_648f
        }

    /**
     * The filter and the level, over [frames] samples of the first channel.
     * Inline so neither caller allocates a lambda on the audio thread.
     */
    private inline fun accumulate(frames: Int, sampleAt: (Int) -> Float) {
        var sum = 0f
        var counted = 0
        var frame = 0
        while (frame < frames) {
            val x = sampleAt(frame)
            // A one-pole high pass at the kick's lower edge, then a one-pole low
            // pass at its upper one. Two multiplies each, per sample.
            highPassed = highPassCoefficient * (highPassed + x - previousInput)
            previousInput = x
            band += lowPassCoefficient * (highPassed - band)
            if (frame % DECIMATION == 0) {
                sum += band * band
                counted++
            }
            frame++
        }
        publish(sum, counted)
    }

    /** Turns a block's summed band energy into [bass]. */
    private fun publish(sum: Float, counted: Int) {
        if (counted == 0) return
        val rms = sqrt(sum / counted)

        val now = System.nanoTime()
        val sinceLastBlock = if (lastBlockNanos == 0L) {
            0.01f
        } else {
            ((now - lastBlockNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        }
        lastBlockNanos = now

        // A reference that follows the track: instant up, slow down.
        peak = maxOf(rms, peak * exp(-sinceLastBlock / PEAK_TAU_SECONDS))
        val level = if (peak <= PEAK_FLOOR) {
            0f
        } else {
            (rms / peak).coerceIn(0f, 1f).pow(LEVEL_CURVE)
        }

        if (now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = now
            bass = level
            updatedAtNanos = now
        }
    }

    /** Nothing is playing: the plate must fall back to its idle breathing. */
    fun silence() {
        previousInput = 0f
        highPassed = 0f
        band = 0f
        peak = PEAK_FLOOR
        bass = 0f
        updatedAtNanos = System.nanoTime()
    }

    /**
     * [bass], or zero when the last block is older than [freshNanos] — a paused
     * player stops feeding, and a value frozen mid-beat would leave the plate
     * thumping at nothing.
     */
    fun currentBass(freshNanos: Long = 400_000_000L): Float {
        val at = updatedAtNanos
        if (at == 0L || System.nanoTime() - at > freshNanos) return 0f
        return bass
    }
}
