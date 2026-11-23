package com.rentalx.playa.data.discord

import com.rentalx.playa.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Where the Discord card's button sends whoever taps it.
 *
 * A track that is not a YouTube video has no watch page, and building one from
 * its own id produced a link into nothing.
 */
class DiscordWatchUrlTest {

    private fun song(videoId: String, source: String? = null, sourceId: String? = null) = Song(
        videoId = videoId,
        title = "Deemz ma ten sos",
        artist = "Afan",
        thumbnailUrl = null,
        playbackSource = source,
        playbackSourceId = sourceId,
    )

    @Test
    fun `a YouTube video links to itself`() {
        assertEquals(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            DiscordRPC.watchUrl(song("dQw4w9WgXcQ")),
        )
    }

    @Test
    fun `a SoundCloud row with a permalink links to SoundCloud`() {
        assertEquals(
            "https://soundcloud.com/afan/deemz",
            DiscordRPC.watchUrl(
                song("soundcloud:tracks:123", source = "SoundCloud", sourceId = "https://soundcloud.com/afan/deemz"),
            ),
        )
    }

    @Test
    fun `a SoundCloud row without one searches instead of building a dead link`() {
        val url = DiscordRPC.watchUrl(song("soundcloud:tracks:123", source = "SoundCloud"))
        assertFalse("still points at a YouTube watch page", url.contains("watch?v=soundcloud"))
        assertEquals("https://music.youtube.com/search?q=Afan%20Deemz%20ma%20ten%20sos", url)
    }

    @Test
    fun `a track from the listener's own server is not a YouTube video`() {
        val url = DiscordRPC.watchUrl(song("src:abc::f20f00fd0e478f00", source = "FlashMusic"))
        assertFalse(url.contains("watch?v=src"))
        assertEquals("https://music.youtube.com/search?q=Afan%20Deemz%20ma%20ten%20sos", url)
    }
}
