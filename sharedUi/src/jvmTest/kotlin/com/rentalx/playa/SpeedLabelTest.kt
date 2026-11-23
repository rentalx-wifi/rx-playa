package com.rentalx.playa.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a speed reads on the chip and in the drawer. */
class SpeedLabelTest {

    @Test
    fun `a whole speed drops its decimals`() {
        assertEquals("1×", formatSpeed(1f))
        assertEquals("2×", formatSpeed(2f))
    }

    @Test
    fun `the named edits read at one or two decimals`() {
        assertEquals("0.8×", formatSpeed(0.8f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("0.75×", formatSpeed(0.75f))
        assertEquals("0.5×", formatSpeed(0.5f))
    }

    @Test
    fun `a slider step is still one decimal or two, never more`() {
        // 0.5 + 7 * 0.05 in binary floating point is 0.85000004.
        assertEquals("0.85×", formatSpeed(0.85000004f))
    }
}
