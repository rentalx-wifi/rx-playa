package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The low end of what is playing right now, for the one thing in the app that
 * moves to the music.
 *
 * Read off the audio thread by [com.music.bitchord.playback.audio.PrecisionAudioSink],
 * because that is the only place the samples exist: the app has no
 * RECORD_AUDIO permission and no `android.media.audiofx.Visualizer`, and asking
 * for a microphone to animate a plate is not a trade worth making.
 *
 * Only the *audible* sink feeds this. A crossfade keeps two players running, and
 * the idle one would otherwise write its silence over the beat every time it was
 * prepared — which is what made the animation stall for a moment at track
 * changes.
 *
 * Cheap by construction, since it runs inside the render loop:
 *
 *  - one channel, every fourth frame;
 *  - a single one-pole low pass, which is two multiplies per sample;
 *  - one publish per [PUBLISH_INTERVAL_NANOS], not per block.
 *
 * No smoothing here on purpose. The meter reports the band as it is, and the one
 * consumer — the wave plate — applies the attack and release its own drawing
 * needs. Two cascaded envelopes were what made the plate trail the kick.
 *
 * The value is a plain volatile float rather than a flow: a state emission per
 * audio frame would wake the UI thread sixty times a second to redraw the same
 * pixel, and the plate already has a frame callback of its own to read it from.
 */
object AudioEnergy {

    /** How low the band is: below this, a kick drum and little else. */
    private const val LOW_CUT_HZ = 130f

    /** Under the 60 Hz a phone draws at, so one read per frame is enough. */
    private const val PUBLISH_INTERVAL_NANOS = 8_000_000L

    /** Every fourth frame is plenty at 44.1 kHz for a band this wide. */
    private const val DECIMATION = 4

    /**
     * How fast the reference peak falls back. Slow enough that a track's own
     * loudness does not decide how far the waves travel — a quiet master and a
     * loud one look the same — and fast enough to follow a chorus.
     */
    private const val PEAK_FALL_PER_SECOND = 0.45f

    /** Below this the band is treated as silent rather than as a quiet peak. */
    private const val PEAK_FLOOR = 0.004f

    /**
     * Bass energy, 0..1, relative to the track's own peak: 0 is silence, 1 is
     * the loudest the low end has been in the last second or so.
     */
    @Volatile
    var bass: Float = 0f
        private set

    /** When [bass] was last written, on [System.nanoTime]'s clock. */
    @Volatile
    var updatedAtNanos: Long = 0L
        private set

    private var sampleRate = 0
    private var alpha = 0f
    private var lowPass = 0f
    private var peak = PEAK_FLOOR
    private var lastPublishNanos = 0L
    private var lastBlockNanos = 0L

    /** A new format: the filter's coefficient depends on the sample rate. */
    fun configure(sampleRateHz: Int) {
        if (sampleRateHz <= 0 || sampleRateHz == sampleRate) return
        sampleRate = sampleRateHz
        // One-pole coefficient for ~130 Hz over the decimated stream: the
        // effective rate is what is being filtered.
        val effective = sampleRateHz / DECIMATION.toFloat()
        val rc = 1f / (2f * Math.PI.toFloat() * LOW_CUT_HZ)
        val dt = 1f / effective
        alpha = dt / (rc + dt)
        lowPass = 0f
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
        var sum = 0f
        var counted = 0
        var frame = 0
        while (frame < frames) {
            val x = samples[frame * channels]
            lowPass += alpha * (x - lowPass)
            sum += lowPass * lowPass
            counted++
            frame += DECIMATION
        }
        if (counted == 0) return
        val rms = sqrt(sum / counted)

        val now = System.nanoTime()
        val sinceLastBlock = if (lastBlockNanos == 0L) {
            0.01f
        } else {
            ((now - lastBlockNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        }
        lastBlockNanos = now
        // A peak that follows the track rather than a fixed gain: a mastered
        // kick and a quiet acoustic recording both fill the band to the top.
        peak = maxOf(rms, peak * exp(-sinceLastBlock * PEAK_FALL_PER_SECOND))
        val level = if (peak <= PEAK_FLOOR) 0f else (rms / peak).coerceIn(0f, 1f)

        if (now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = now
            bass = level
            updatedAtNanos = now
        }
    }

    /** Nothing is playing: the plate must fall back to its idle breathing. */
    fun silence() {
        lowPass = 0f
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
