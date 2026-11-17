package com.music.bitchord

import com.music.bitchord.playback.contentRangeTotal
import com.music.bitchord.playback.isSoundCloudMediaHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedSoundCloudTransportTest {
    @Test
    fun `SoundCloud AAC and legacy media hosts use bounded ranges`() {
        assertTrue(isSoundCloudMediaHost("playback.media-streaming.soundcloud.cloud"))
        assertTrue(isSoundCloudMediaHost("cf-hls-media.sndcdn.com"))
        assertFalse(isSoundCloudMediaHost("api-v2.soundcloud.com"))
        assertFalse(isSoundCloudMediaHost("soundcloud.cloud.evil.example"))
    }

    @Test
    fun `Content Range exposes complete HLS object length`() {
        assertEquals(
            202_465L,
            contentRangeTotal(mapOf("content-range" to listOf("bytes 0-14335/202465"))),
        )
        assertNull(contentRangeTotal(mapOf("Content-Range" to listOf("bytes */202465"))))
        assertNull(contentRangeTotal(emptyMap()))
    }
}
