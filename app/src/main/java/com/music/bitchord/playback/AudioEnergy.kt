package com.music.bitchord.playback

import com.music.bitchord.playback.audio.AudioBlock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The low end of what is playing right now, for the one thing in the app that
 * moves to the music.
 *
 * Read off the audio thread by [com.music.bitchord.playback.audio.DspChain],
 * because that is the only place the samples exist: the app has no
 * RECORD_AUDIO permission and no `android.media.audiofx.Visualizer`, and
 * asking for a microphone to animate a pill is not a trade worth making. The
 * DSP chain runs in front of every PCM buffer, so its processed output is
 * exactly what the listener hears.
 *
 * Cheap by construction, since it runs inside the render loop:
 *
 *  - one channel, every fourth frame;
 *  - a single one-pole low pass, which is two multiplies per sample;
 *  - one publish per [PUBLISH_INTERVAL_NANOS], not per block.
 *
 * The value is a plain volatile float rather than a flow: a state emission per
 * audio frame would wake the UI thread sixty times a second to redraw the same
 * pixel, and the pill already has a frame callback of its own to read it from.
 */
object AudioEnergy {

    /** How low the band is: below this, a kick drum and little else. */
    private const val LOW_CUT_HZ = 130f

    /** Kept under the 60 Hz a phone draws at, so one read per frame is enough. */
    private const val PUBLISH_INTERVAL_NANOS = 16_000_000L

    /** Every fourth frame is plenty at 44.1 kHz for a band this wide. */
    private const val DECIMATION = 4

    /**
     * Bass energy, 0..1, smoothed with a fast attack and a slow release — the
     * shape a drummer would call a beat rather than a flicker.
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
    private var smoothed = 0f
    private var lastPublishNanos = 0L

    /** A new format: the filter's coefficient depends on the sample rate. */
    fun configure(sampleRateHz: Int) {
        if (sampleRateHz <= 0 || sampleRateHz == sampleRate) return
        sampleRate = sampleRateHz
        // One-pole coefficient for ~130 Hz at this rate, over the decimated
        // stream: the effective rate is what is being filtered.
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
        // A digital master rarely has its bass above -12 dBFS, so the raw RMS
        // reads as almost nothing; the curve lifts what is there and the clamp
        // keeps a loud track from pinning the animation.
        val target = (rms * 4f).coerceIn(0f, 1f)
        smoothed = if (target > smoothed) {
            smoothed + (target - smoothed) * 0.55f
        } else {
            smoothed * 0.86f
        }
        val now = System.nanoTime()
        if (now - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = now
            if (abs(smoothed) < 0.002f) smoothed = 0f
            bass = smoothed
            updatedAtNanos = now
        }
    }

    /** Nothing is playing: the pill must fall back to its idle breathing. */
    fun silence() {
        smoothed = 0f
        lowPass = 0f
        bass = 0f
        updatedAtNanos = System.nanoTime()
    }

    /**
     * [bass], or zero when the last block is older than [freshNanos] — a paused
     * player stops feeding, and a value frozen mid-beat would leave the pill
     * thumping at nothing.
     */
    fun currentBass(freshNanos: Long = 400_000_000L): Float {
        val at = updatedAtNanos
        if (at == 0L || System.nanoTime() - at > freshNanos) return 0f
        return bass
    }
}
