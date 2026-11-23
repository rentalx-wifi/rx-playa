package com.music.bitchord.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.abs

/**
 * How fast the player is going, and whether the pitch is dragged along.
 *
 * Media3 differs from a tape deck in exactly one place: [PlaybackParameters]
 * takes the two separately. `pitch = 1` keeps the recording in its own key
 * while the tempo moves — the podcast behaviour, and what this app has always
 * done. `pitch = speed` is plain resampling, which is what "slowed" and
 * "sped up" versions of a track are: the same recording, lower and slower.
 *
 * Kept apart from both settings that feed it — the listener's global speed and
 * a variant's own rate — because the two multiply and either one can ask for
 * the pitch to follow.
 */
internal data class PlaybackRate(
    val speed: Float = 1f,
    val pitchFollows: Boolean = false,
) {
    val parameters: PlaybackParameters
        get() = if (pitchFollows) PlaybackParameters(speed, speed) else PlaybackParameters(speed)

    /** False when this is not worth a call to the player. */
    fun differsFrom(current: PlaybackParameters): Boolean =
        abs(current.speed - speed) > SPEED_EPSILON ||
            abs(current.pitch - if (pitchFollows) speed else 1f) > SPEED_EPSILON

    companion object {
        /**
         * Under what Android's Sonic path treats as a real change, so a slider
         * dragged through the same value does not restart its window.
         */
        const val SPEED_EPSILON = 0.001f
    }
}

/**
 * The rate the listener's own settings ask for, before any track's variant is
 * stacked on top — see [combine].
 */
internal fun userPlaybackRate(): PlaybackRate = PlaybackRate(
    speed = AppSettings.playbackSpeed.value,
    pitchFollows = !AppSettings.pitchCorrection.value,
)

/**
 * Stacks a variant's [rate] and its own pitch intent onto the listener's.
 *
 * The speeds multiply, the ways add: either the listener turned correction off
 * or the variant is a slowed edit that has to sound slowed, and in both cases
 * the pitch follows the samples.
 */
internal fun PlaybackRate.combine(variant: PlaybackRate?): PlaybackRate {
    if (variant == null) return this
    return PlaybackRate(
        speed = this.speed * variant.speed,
        pitchFollows = this.pitchFollows || variant.pitchFollows,
    )
}

/** Applies [rate], unless it is already the player's. */
internal fun ExoPlayer.applyPlaybackRate(rate: PlaybackRate) {
    if (!rate.differsFrom(playbackParameters)) return
    setPlaybackParameters(rate.parameters)
}

/**
 * The rate every player should be at right now.
 *
 * Lives here rather than in a settings flow because two things make it up and
 * one of them belongs to the track that is playing, not to the listener: the
 * listener's speed and pitch preference, with the playing track's variant (a
 * slowed edit, say) stacked on top. The transition code multiplies its own
 * beatmatch rate into it a dozen times over and has no way to ask which track
 * is playing, so the answer is kept where both sides can reach it.
 */
internal object PlaybackRateState {
    @Volatile
    private var variant: PlaybackRate? = null

    /** What the listeners' settings alone ask for. */
    val base: PlaybackRate get() = userPlaybackRate().combine(variant)

    /** The track's own rate, without the listener's speed stacked on. */
    val trackVariant: PlaybackRate? get() = variant

    /** Sets the playing track's variant rate, or null for an ordinary track. */
    fun setVariant(rate: PlaybackRate?) {
        variant = rate
    }
}
