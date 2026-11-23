package com.rentalx.playa

import com.rentalx.playa.data.importing.YouTubeMusicPlaylistImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which links count as a YouTube Music playlist, and which do not.
 *
 * The parser is the whole of what an import needs from the user, so it has to
 * accept what an address bar actually gives and refuse everything else before
 * a request is spent on it. The bare-id form is there for people pasting the
 * id out of a share sheet; the video-only link is refused on purpose, because
 * a single video is not a playlist and importing it silently would look like
 * the import had dropped every other track.
 */
class YouTubePlaylistLinkTest {

    @Test
    fun `playlist pages give their browse id`() {
        assertEquals(
            "VLPLabc123def456",
            YouTubeMusicPlaylistImport.browseIdOf("https://music.youtube.com/playlist?list=PLabc123def456"),
        )
        assertEquals(
            "VLOLAK5uy_abcdefgh",
            YouTubeMusicPlaylistImport.browseIdOf("https://www.youtube.com/playlist?list=OLAK5uy_abcdefgh"),
        )
    }

    @Test
    fun `a watch link carries the playlist it is playing from`() {
        assertEquals(
            "VLPLxyz789abc12",
            YouTubeMusicPlaylistImport.browseIdOf(
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLxyz789abc12&index=4",
            ),
        )
    }

    @Test
    fun `a bare id is accepted with or without its prefix`() {
        assertEquals("VLPLbareid123456", YouTubeMusicPlaylistImport.browseIdOf("PLbareid123456"))
        assertEquals("VLPLbareid123456", YouTubeMusicPlaylistImport.browseIdOf("VLPLbareid123456"))
    }

    @Test
    fun `anything that is not a playlist is refused`() {
        assertNull(YouTubeMusicPlaylistImport.browseIdOf(""))
        assertNull(YouTubeMusicPlaylistImport.browseIdOf("https://youtu.be/dQw4w9WgXcQ"))
        assertNull(YouTubeMusicPlaylistImport.browseIdOf("https://open.spotify.com/playlist/37i9dQZF1DXc"))
        assertNull(YouTubeMusicPlaylistImport.browseIdOf("моя любимая песня"))
    }
}
