package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reorder page over a playlist kept on this device: the store holds the
 * list itself, so the order written is the order the playlist plays in.
 */
class LocalPlaylistOrderTest {

    private fun song(id: String) = Song(videoId = id, title = "Song $id", artist = "Artist", thumbnailUrl = null)

    private fun order(id: String): List<String> =
        LocalPlaylistStore.getPlaylist(id)?.songs?.map { it.videoId }.orEmpty()

    @Test
    fun `the order handed back is the one the store keeps`() {
        val playlist = LocalPlaylistStore.savePlaylist("Order", listOf(song("a"), song("b"), song("c")))

        assertTrue(LocalPlaylistStore.reorderSongs(playlist.id, listOf(song("c"), song("a"), song("b"))))

        assertEquals(listOf("c", "a", "b"), order(playlist.id))
    }

    @Test
    fun `a card's browse id names the same playlist as the store's own id`() {
        val playlist = LocalPlaylistStore.savePlaylist("Ids", listOf(song("a"), song("b")))

        assertTrue(LocalPlaylistStore.reorderSongs(playlist.browseId, listOf(song("b"), song("a"))))

        assertEquals(listOf("b", "a"), order(playlist.id))
    }

    @Test
    fun `a duplicate keeps its place as a row of its own`() {
        val playlist = LocalPlaylistStore.savePlaylist(
            "Twice",
            listOf(song("a"), song("b"), song("a")),
        )

        assertTrue(LocalPlaylistStore.reorderSongs(playlist.id, listOf(song("a"), song("a"), song("b"))))

        assertEquals(listOf("a", "a", "b"), order(playlist.id))
    }

    @Test
    fun `a playlist that is no longer there is not reordered`() {
        assertFalse(LocalPlaylistStore.reorderSongs("sp_local_gone", listOf(song("a"))))
    }
}
