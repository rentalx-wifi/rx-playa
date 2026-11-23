package com.rentalx.playa

import com.rentalx.playa.data.model.Song
import com.rentalx.playa.playback.QueueBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBuilderTest {

    private fun song(id: String, title: String, artist: String = "Arijit Singh") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun video(id: String, title: String, artist: String = "Arijit Singh") =
        song(id, title, artist).copy(isVideo = true)

    @Test
    fun `the artist a station was seeded on keeps its extra room`() {
        // The queue ends with a different act than the one the station was
        // started from — which is what an extended queue always looks like. The
        // seed is passed in, so the extra room lands on it rather than on
        // whoever happens to sit last.
        val seed = song("seed", "Seed", "Kesariya")
        val other = song("last", "Last", "Somebody Else")
        val candidates = (1..5).map { song("k$it", "Track $it", "Kesariya") } +
            (1..3).map { song("o$it", "Other $it", "Somebody Else") }
        val extra = QueueBuilder.extend(
            existing = listOf(seed, other),
            candidates = candidates,
            limit = 10,
            seedSong = seed,
        )
        assertEquals(4, extra.count { it.artist == "Kesariya" })
        assertEquals(2, extra.count { it.artist == "Somebody Else" })
    }

    @Test
    fun `the music video cut of a mix is left out`() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("aaa", "Dildaara (Stand By Me)", "Vishal-Shekhar"),
                video("bbb", "Lyrical Video: Dildara Song", "Shafqat Amanat Ali"),
                song("ccc", "Sajni"),
            ),
            limit = 10,
        )
        assertEquals(listOf("aaa", "ccc"), extra.map { it.videoId })
    }

    @Test
    fun `a mix of nothing but videos still plays`() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(video("aaa", "One", "A"), video("bbb", "Two", "B")),
            limit = 10,
        )
        assertEquals(listOf("aaa", "bbb"), extra.map { it.videoId })
    }

    @Test
    fun `the video cut of a track is the same recording as its audio`() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("aaa", "Kesariya"),
                song("bbb", "Kesariya (Official Video)"),
            ),
        )
    }

    @Test
    fun `matches across a longer billing of the same credit`() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("aaa", "Kesariya", "Arijit Singh"),
                song("bbb", "Kesariya | Official Video", "Arijit Singh, Pritam"),
            ),
        )
    }

    @Test
    fun `a remix is not the original`() {
        assertFalse(
            QueueBuilder.isSameRecording(
                song("aaa", "Kesariya"),
                song("bbb", "Kesariya (Remix)"),
            ),
        )
    }

    @Test
    fun `same title from a different artist is a different song`() {
        assertFalse(
            QueueBuilder.isSameRecording(
                song("aaa", "Perfect", "Ed Sheeran"),
                song("bbb", "Perfect", "One Direction"),
            ),
        )
    }

    @Test
    fun `a reordered credit is the same recording`() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("aaa", "Kalank (Duet)", "Pritam, Arijit Singh & Shilpa Rao"),
                song("bbb", "Kalank (Duet)", "Shilpa Rao, Arijit Singh, & Pritam"),
            ),
        )
    }

    @Test
    fun `topic channels and casing do not affect the credit`() {
        assertEquals(setOf("arijit singh"), QueueBuilder.artistSet("Arijit Singh - Topic"))
        assertEquals(
            setOf("pritam", "arijit singh"),
            QueueBuilder.artistSet("Pritam feat. Arijit Singh"),
        )
    }

    @Test
    fun `extend drops the seed and anything already queued`() {
        val seed = song("aaa", "Kesariya")
        val extra = QueueBuilder.extend(
            existing = listOf(seed),
            candidates = listOf(seed, song("bbb", "Tum Hi Ho"), song("aaa", "Kesariya")),
            limit = 10,
        )
        assertEquals(listOf("bbb"), extra.map { it.videoId })
    }

    @Test
    fun `extend drops a duplicate that only differs by video cut`() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("aaa", "Channa Mereya"),
                song("bbb", "Channa Mereya (Official Video)"),
                song("ccc", "Ae Dil Hai Mushkil"),
            ),
            limit = 10,
        )
        assertEquals(listOf("aaa", "ccc"), extra.map { it.videoId })
    }

    @Test
    fun `extend excludes disliked provider aliases of the same recording`() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("youtube", "Track (Official Video)", "Artist - Topic"),
                song("other", "Allowed", "Someone Else"),
            ),
            limit = 10,
            excluded = listOf(song("spotify-track:1", "Track", "Artist")),
        )

        assertEquals(listOf("other"), extra.map { it.videoId })
    }

    @Test
    fun `extend honours the limit`() {
        val candidates = (1..10).map { song("v$it", "Song $it", "Artist $it") }
        assertEquals(3, QueueBuilder.extend(emptyList(), candidates, limit = 3).size)
    }

    @Test
    fun `one artist cannot take over the station`() {
        val candidates = (1..6).map { song("v$it", "Song $it", "Badshah") }
        val extra = QueueBuilder.extend(
            existing = listOf(song("seed", "Seed", "Diljit Dosanjh")),
            candidates = candidates,
            limit = 10,
        )
        assertEquals(2, extra.size)
    }

    @Test
    fun `the seed's own artist gets more room than the rest`() {
        val seed = song("seed", "Seed", "Diljit Dosanjh")
        val candidates = (1..8).map { song("v$it", "Song $it", "Diljit Dosanjh") }
        val extra = QueueBuilder.extend(listOf(seed), candidates, limit = 10)
        assertEquals(4, extra.size)
    }

    @Test
    fun `the cap counts a reordered credit as one artist`() {
        val seed = song("seed", "Seed", "Nucleya")
        val candidates = listOf(
            song("v1", "One", "Pritam, Arijit Singh"),
            song("v2", "Two", "Arijit Singh, Pritam"),
            song("v3", "Three", "Pritam & Arijit Singh"),
        )
        val extra = QueueBuilder.extend(listOf(seed), candidates, limit = 10)
        assertEquals(listOf("v1", "v2"), extra.map { it.videoId })
    }
}
