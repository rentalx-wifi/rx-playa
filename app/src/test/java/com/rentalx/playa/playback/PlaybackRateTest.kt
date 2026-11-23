package com.rentalx.playa.playback

import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a speed becomes playback parameters.
 *
 * The whole difference between "a podcast at 1.25x" and a sped-up edit of a
 * track is the pitch field of [PlaybackParameters], so it is worth pinning to a
 * test rather than to a listening check.
 */
class PlaybackRateTest {

    @Test
    fun `pitch stays at 1 while correction is on`() {
        val parameters = PlaybackRate(speed = 0.8f, pitchFollows = false).parameters
        assertEquals(0.8f, parameters.speed, 0.0001f)
        assertEquals(1f, parameters.pitch, 0.0001f)
    }

    @Test
    fun `pitch follows the speed when it is meant to`() {
        val parameters = PlaybackRate(speed = 0.8f, pitchFollows = true).parameters
        assertEquals(0.8f, parameters.speed, 0.0001f)
        assertEquals(0.8f, parameters.pitch, 0.0001f)
    }

    @Test
    fun `a variant multiplies the speed and keeps either way of following`() {
        val user = PlaybackRate(speed = 1.25f, pitchFollows = false)
        val slowed = PlaybackRate(speed = 0.8f, pitchFollows = true)

        // 1.25 x 0.8 is 1, and the slowed edit's own pitch intent survives.
        val combined = user.combine(slowed)
        assertEquals(1f, combined.speed, 0.0001f)
        assertTrue("the variant asked for the pitch to follow", combined.pitchFollows)
    }

    @Test
    fun `the listener's own pitch preference is enough on its own`() {
        val user = PlaybackRate(speed = 0.75f, pitchFollows = true)
        val plain = PlaybackRate(speed = 1f, pitchFollows = false)
        assertTrue(user.combine(plain).pitchFollows)
    }

    @Test
    fun `a change too small to hear is not a change to the player`() {
        val rate = PlaybackRate(speed = 0.8f, pitchFollows = false)
        assertFalse(rate.differsFrom(PlaybackParameters(0.8f)))
        assertTrue(rate.differsFrom(PlaybackParameters(1f)))
        // Same speed, different pitch: turning correction off is audible.
        assertTrue(rate.copy(pitchFollows = true).differsFrom(PlaybackParameters(0.8f)))
    }
}
