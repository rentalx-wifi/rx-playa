package com.music.bitchord.ui.components

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The identity a reorder page gives each row.
 *
 * Two rows with one key is a LazyColumn crash, and Save that compares rows by
 * `setVideoId` alone never lights for a playlist that has none — both were
 * reported as one issue.
 */
class ReorderKeyTest {

    private fun song(videoId: String, setVideoId: String? = null) =
        Song(videoId = videoId, title = videoId, artist = "Artist", thumbnailUrl = null, setVideoId = setVideoId)

    @Test
    fun `a playlist without setVideoIds still distinguishes its rows`() {
        val keys = listOf(song("a"), song("b"), song("c")).asReorderEntries().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the same song twice is two rows with two keys`() {
        val keys = listOf(song("a"), song("a"), song("a")).asReorderEntries().map { it.key }
        assertEquals(3, keys.toSet().size)
    }

    @Test
    fun `moving a row changes what Save would send`() {
        val original = listOf(song("a"), song("b"), song("c")).asReorderEntries()
        val moved = original.toMutableList().apply { add(0, removeAt(2)) }
        assertNotEquals(original.map { it.key }, moved.map { it.key })
    }

    @Test
    fun `an untouched list is not a change`() {
        val original = listOf(song("a"), song("b")).asReorderEntries()
        assertEquals(original.map { it.key }, original.toMutableList().map { it.key })
    }

    @Test
    fun `a YouTube entry is keyed by its own set id`() {
        val entries = listOf(song("a", setVideoId = "set-1")).asReorderEntries()
        assertEquals("set-1#0", entries.single().key)
    }
}
