package com.music.bitchord

import com.music.bitchord.playback.autoplayQueueNeedsRefresh
import com.music.bitchord.playback.isExternalProviderTrackId
import com.music.bitchord.playback.isYouTubeVideoId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayFallbackTest {
    @Test
    fun `only real YouTube ids bypass strict external matching`() {
        assertTrue(isYouTubeVideoId("dQw4w9WgXcQ"))
        assertFalse(isYouTubeVideoId("soundcloud:tracks:123"))
        assertFalse(isYouTubeVideoId("spotify-track:abc"))
        assertFalse(isYouTubeVideoId("vk-audio:-1:2"))
        assertFalse(isYouTubeVideoId("content://media/1"))
        assertTrue(isExternalProviderTrackId("spotify-track:abc"))
        assertTrue(isExternalProviderTrackId("vk-audio:-1:2"))
        assertTrue(isExternalProviderTrackId("soundcloud:tracks:123"))
        assertFalse(isExternalProviderTrackId("dQw4w9WgXcQ"))
    }

    @Test
    fun `enabled autoplay refreshes when current track has no queue after it`() {
        assertTrue(
            autoplayQueueNeedsRefresh(
                enabled = true,
                repeatAll = false,
                currentIndex = 2,
                itemCount = 3,
                loadInProgress = false,
            ),
        )
    }

    @Test
    fun `an existing queue or active load does not start a duplicate refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(true, false, 1, 3, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, 2, 3, true))
    }

    @Test
    fun `disabled autoplay repeat all and a missing current track do not refresh`() {
        assertFalse(autoplayQueueNeedsRefresh(false, false, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, true, 0, 1, false))
        assertFalse(autoplayQueueNeedsRefresh(true, false, -1, 0, false))
    }
}
