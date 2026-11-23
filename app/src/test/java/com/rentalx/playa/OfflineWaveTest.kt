package com.rentalx.playa

import com.rentalx.playa.data.model.Song
import com.rentalx.playa.playback.OfflineWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The rules the offline station promises — see [OfflineWave]. */
class OfflineWaveTest {

    private fun song(id: String, artist: String, local: Boolean = true) = Song(
        videoId = id,
        title = "Track $id",
        artist = artist,
        thumbnailUrl = null,
        localUri = if (local) "file:///music/$id.m4a" else null,
    )

    @Test
    fun `nothing downloaded is an empty station`() {
        assertEquals(emptyList<Song>(), OfflineWave.plan(emptyList(), emptySet()))
    }

    @Test
    fun `records without a file are not offered`() {
        val plan = OfflineWave.plan(
            downloaded = listOf(song("a", "A"), song("b", "B", local = false)),
            likedIds = emptySet(),
        )
        assertEquals(listOf("a"), plan.map { it.videoId })
    }

    @Test
    fun `the live queue is left out of the next station`() {
        val plan = OfflineWave.plan(
            downloaded = listOf(song("a", "A"), song("b", "B"), song("c", "C")),
            likedIds = emptySet(),
            excludeIds = setOf("a", "b", "c"),
            random = Random(1),
        )
        // Everything is excluded, and a station of repeats beats a dead pill.
        assertEquals(3, plan.size)
    }

    @Test
    fun `what is not queued comes first`() {
        val plan = OfflineWave.plan(
            downloaded = listOf(song("a", "A"), song("b", "B"), song("c", "C")),
            likedIds = emptySet(),
            excludeIds = setOf("a"),
            random = Random(2),
        )
        assertEquals(setOf("b", "c"), plan.map { it.videoId }.toSet())
    }

    @Test
    fun `liked tracks lead the station`() {
        val downloaded = (1..9).map { song("t$it", "Artist $it") }
        val plan = OfflineWave.plan(
            downloaded = downloaded,
            likedIds = setOf("t2", "t5", "t8"),
            limit = 6,
            random = Random(3),
        )
        // Two likes, then one other: the first four rows are two thirds liked.
        val leading = plan.take(4).count { it.videoId in setOf("t2", "t5", "t8") }
        assertEquals(3, leading)
    }

    @Test
    fun `the same artist does not play twice in a row`() {
        val downloaded = listOf(
            song("a1", "Same"),
            song("a2", "Same"),
            song("b1", "Other"),
            song("b2", "Third"),
        )
        val plan = OfflineWave.plan(downloaded, likedIds = emptySet(), random = Random(4))
        assertEquals(4, plan.size)
        plan.zipWithNext().forEach { (first, second) ->
            assertTrue(
                "Adjacent rows share an artist: ${first.artist} / ${second.artist}",
                first.artist != second.artist,
            )
        }
    }

    @Test
    fun `a library of one artist is still a station`() {
        // Nothing can be spaced when everything shares an artist; the rule is
        // "while another choice exists", not "drop tracks to keep it".
        val downloaded = listOf(song("a1", "Same"), song("a2", "Same"), song("a3", "Same"))
        assertEquals(3, OfflineWave.plan(downloaded, likedIds = emptySet()).size)
    }

    @Test
    fun `the limit is respected`() {
        val downloaded = (1..40).map { song("t$it", "Artist $it") }
        assertEquals(5, OfflineWave.plan(downloaded, emptySet(), limit = 5).size)
    }

    @Test
    fun `no track appears twice`() {
        val downloaded = listOf(
            song("a", "A"),
            // The same recording under another id, which a download of a
            // playlist and of its album can both leave behind.
            song("a", "A"),
            song("b", "B"),
        )
        val plan = OfflineWave.plan(downloaded, likedIds = setOf("a", "b"))
        assertEquals(2, plan.size)
    }
}
