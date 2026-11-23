package com.music.bitchord.data

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Related artists" loses the artist themselves, repeats and thin shelves
 * instead of showing whatever YouTube happened to put there (issue #43).
 */
class RelatedArtistsTest {
    private fun artist(id: String, name: String) =
        ShelfItem(title = name, subtitle = "Artist", thumbnailUrl = null, videoId = null, browseId = id)

    private fun album(id: String, name: String) =
        ShelfItem(title = name, subtitle = "Album", thumbnailUrl = null, videoId = null, browseId = id)

    private fun shelf(title: String, vararg items: ShelfItem) = HomeShelf(title = title, items = items.toList())

    @Test
    fun `artist pages keep the shelf without the page's own artist and repeats`() {
        val related = shelf(
            "Fans might also like",
            artist("UC1", "Hekata Station"),
            artist("UC2", "Nyan.mp3"),
            artist("UC1", "Hekata Station"),
            artist("UC3", "Dabbackwood"),
            artist("UC4", "5mewmet"),
        )
        val out = YtMusicRepository.tidyRelatedArtists(listOf(related), "Hekata Station", "UCself")
        assertEquals(1, out.size)
        assertEquals(listOf("UC2", "UC3", "UC4"), out.first().items.map { it.browseId })
    }

    @Test
    fun `a shelf left with two names is dropped entirely`() {
        val related = shelf("Fans might also like", artist("UC1", "A"), artist("UC2", "B"), artist("UC1", "A"))
        val out = YtMusicRepository.tidyRelatedArtists(listOf(related), "Self", "UCself")
        assertTrue(out.isEmpty())
    }

    @Test
    fun `album shelves are left alone`() {
        val releases = shelf("Albums", album("MPREb1", "One"), album("MPREb2", "Two"))
        val out = YtMusicRepository.tidyRelatedArtists(listOf(releases), "Self", "UCself")
        assertEquals(1, out.size)
        assertEquals(2, out.first().items.size)
    }
}
