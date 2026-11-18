package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What an import from another service is allowed to accept.
 *
 * The complaint behind this file was "перенос некоторых песен не соответствует
 * плейлисту на ямуз": the playlist arrived with rows that were not the tracks
 * that were in it. Both allowances below were the way that happened, and both
 * are still the right answer for a source switch during playback — hence two
 * entry points rather than one stricter matcher.
 */
class StrictImportMatchingTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Мукка",
        duration: String = "3:00",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
    )

    @Test
    fun `a shorter title by the same artist is not the track that was imported`() {
        val target = TrackMatcher.Target("Любовь как сон", "Мукка", durationSec = 180)
        val wrong = song("wrong", "Любовь", duration = "3:05")
        val right = song("right", "Любовь как сон", duration = "3:02")

        // The loose matcher reads the shorter core as contained in the longer
        // title and the runtime as close enough, which is exactly how a
        // different song of the same artist got written into the playlist.
        assertEquals("wrong", TrackMatcher.best(listOf(wrong), target)?.videoId)
        assertNull(TrackMatcher.strictBest(listOf(wrong), target))
        assertEquals("right", TrackMatcher.strictBest(listOf(wrong, right), target)?.videoId)
    }

    @Test
    fun `an ordinary with in a title does not shorten it`() {
        val target = TrackMatcher.Target("Stay With Me", "Sam Smith", durationSec = 200)
        val truncated = song("short", "Stay", artist = "Sam Smith", duration = "3:20")
        assertNull(TrackMatcher.strictBest(listOf(truncated), target))

        val plain = song("plain", "Stay With Me", artist = "Sam Smith", duration = "3:21")
        assertEquals("plain", TrackMatcher.strictBest(listOf(plain), target)?.videoId)
    }

    @Test
    fun `another artist no longer passes on an identical runtime`() {
        val target = TrackMatcher.Target("Northern Lights", "Original Artist", durationSec = 180)
        val cover = song("cover", "Northern Lights", artist = "Cover Band", duration = "3:00")
        // The loose matcher lets an exact runtime stand in for a shared credit,
        // which is what put a cover in place of the real row.
        assertEquals("cover", TrackMatcher.best(listOf(cover), target)?.videoId)
        assertNull(TrackMatcher.strictBest(listOf(cover), target))
    }

    @Test
    fun `an import still accepts the same recording when the catalogue spells the take`() {
        val target = TrackMatcher.Target("Xtal", "Aphex Twin", durationSec = 295)
        val release = song("xtal", "Xtal (Remastered 2011)", artist = "Aphex Twin", duration = "4:55")
        assertEquals("xtal", TrackMatcher.strictBest(listOf(release), target)?.videoId)
    }
}
