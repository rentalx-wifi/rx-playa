package com.music.bitchord.data.discord

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window over Discord presence updates: a new card at once, the same card
 * again only once the window ends. Yandex pushes state frames continuously, and
 * sending a card per frame is what got it rate-limited.
 */
class DiscordPresenceThrottleTest {

    private val window = 15_000L

    @Test
    fun `the first card of a session goes out at once`() {
        assertTrue(shouldPushPresence(nowMs = 1_000L, lastPushAtMs = 0L, key = "a", lastKey = null, windowMs = window))
    }

    @Test
    fun `the same card inside the window waits`() {
        assertFalse(shouldPushPresence(nowMs = 5_000L, lastPushAtMs = 1_000L, key = "a", lastKey = "a", windowMs = window))
        assertFalse(shouldPushPresence(nowMs = 15_999L, lastPushAtMs = 1_000L, key = "a", lastKey = "a", windowMs = window))
    }

    @Test
    fun `the same card after the window goes out`() {
        assertTrue(shouldPushPresence(nowMs = 16_000L, lastPushAtMs = 1_000L, key = "a", lastKey = "a", windowMs = window))
    }

    @Test
    fun `a new track never waits`() {
        assertTrue(shouldPushPresence(nowMs = 1_500L, lastPushAtMs = 1_000L, key = "b", lastKey = "a", windowMs = window))
    }
}
