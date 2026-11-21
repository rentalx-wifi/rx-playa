package com.music.bitchord

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistQueueDropTest {

    private fun song(
        id: String,
        source: String? = null,
        tier: QueueTier = QueueTier.CONTEXT,
    ) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist $id",
        thumbnailUrl = null,
        playbackSourceType = null,
        playbackSourceId = source,
        queueTier = tier,
    )

    @Test
    fun `rows of the edited playlist leave the queue`() {
        val songs = listOf(
            song("a", "VLPL1"),
            song("b", "VLPL1"),
            song("c", "VLPL1"),
        )
        val drop = QueueCoordinator.playlistQueueDrops(songs, currentIndex = 0, playlistId = "VLPL1", videoIds = setOf("b"))

        assertEquals(listOf(1), drop.indices)
        assertEquals(listOf("b"), drop.videoIds)
        assertNull(drop.playing)
    }

    @Test
    fun `the same recording from another source stays`() {
        val songs = listOf(
            song("a", "VLPL1"),
            song("a", "VLALBUM"),
            song("a", null),
            song("a", "VLPL1", QueueTier.USER_QUEUE),
        )
        val drop = QueueCoordinator.playlistQueueDrops(songs, currentIndex = 3, playlistId = "VLPL1", videoIds = setOf("a"))

        assertEquals(listOf(0), drop.indices)
        assertNull(drop.playing)
    }

    @Test
    fun `the playing row is left to finish`() {
        val songs = listOf(
            song("a", "VLPL1"),
            song("b", "VLPL1"),
        )
        val drop = QueueCoordinator.playlistQueueDrops(songs, currentIndex = 1, playlistId = "VLPL1", videoIds = setOf("b"))

        assertEquals(emptyList<Int>(), drop.indices)
        assertEquals("b", drop.playing)
    }

    @Test
    fun `a deleted playlist takes its whole context and nothing else`() {
        val songs = listOf(
            song("a", "VLPL1"),
            song("b", "VLPL1"),
            song("c", "VLPL2"),
            song("d", "VLPL1", QueueTier.USER_QUEUE),
        )
        val drop = QueueCoordinator.playlistQueueDrops(songs, currentIndex = 2, playlistId = "VLPL1", videoIds = emptySet())

        assertEquals(listOf(0, 1), drop.indices)
        assertEquals(listOf("a", "b"), drop.videoIds)
    }

    @Test
    fun `browse ids match with and without the VL prefix`() {
        val songs = listOf(song("a", "PL1"), song("b", "PL1"))
        val drop = QueueCoordinator.playlistQueueDrops(songs, currentIndex = -1, playlistId = "VLPL1", videoIds = setOf("a"))

        assertEquals(listOf(0), drop.indices)
    }
}
