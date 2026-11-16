package com.music.bitchord

import com.music.bitchord.ui.theme.nothingTransitionEnvelope
import com.music.bitchord.ui.theme.nothingTransitionProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NothingStyleTest {
    @Test
    fun `pulse rests at both ends and peaks halfway`() {
        assertEquals(0f, nothingTransitionEnvelope(0f), 0.0001f)
        assertEquals(1f, nothingTransitionEnvelope(0.5f), 0.0001f)
        assertEquals(0f, nothingTransitionEnvelope(1f), 0.0001f)
    }

    @Test
    fun `pulse and progress clamp values outside animation range`() {
        assertEquals(0f, nothingTransitionEnvelope(-2f), 0.0001f)
        assertEquals(0f, nothingTransitionEnvelope(3f), 0.0001f)
        assertEquals(0f, nothingTransitionProgress(-1f), 0.0001f)
        assertEquals(1f, nothingTransitionProgress(2f), 0.0001f)
    }

    @Test
    fun `transition progress is monotonic and front loaded`() {
        val samples = (0..20).map { nothingTransitionProgress(it / 20f) }
        assertTrue(samples.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(samples[10] > 0.5f)
    }
}
