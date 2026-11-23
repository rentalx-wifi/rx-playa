package com.rentalx.playa

import com.rentalx.playa.data.model.ArtistRef
import com.rentalx.playa.data.model.HomeSource
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.personalization.TasteProfile
import com.rentalx.playa.data.personalization.TasteProfiles
import com.rentalx.playa.data.personalization.TasteSignal
import com.rentalx.playa.data.personalization.TasteSignalKind
import com.rentalx.playa.data.personalization.TasteTrack
import org.junit.Assert.*
import org.junit.Test

class PersonalizedTasteProfileTest {
    private val now = 1_750_000_000_000L
    private val day = 86_400_000L
    private fun song(id: String, title: String = "Northern Lights", artist: String = "Artist", duration: String = "3:00") =
        Song(id, title, artist, null, duration)
    private fun like(song: Song, source: HomeSource = HomeSource.YOUTUBE, at: Long? = now, order: Int = 0) =
        TasteSignal("like:${song.videoId}", source, TasteSignalKind.LIKE, song, atMs = at, order = order)
    private fun listen(song: Song, count: Int, at: Long = now, id: String = "plays:${song.videoId}") =
        TasteSignal(id, HomeSource.YOUTUBE, TasteSignalKind.LISTEN, song, atMs = at, count = count)
    private fun merge(vararg signals: TasteSignal) = TasteProfiles.merge(signals.toList(), nowMs = now)
    private fun candidate(song: Song, source: HomeSource = HomeSource.YOUTUBE, matching: Boolean = false) =
        TasteTrack(song, setOf(source), 0.0, false, null, Int.MAX_VALUE, 0, null, matching)

    @Test fun `empty input does not invent preferences`() {
        val profile = merge()
        assertTrue(profile.isEmpty)
        assertTrue(profile.tracks.isEmpty())
        assertTrue(profile.artists.isEmpty())
        assertTrue(profile.recentLikes.isEmpty())
    }

    @Test fun `all six sources merge one recording and retain provenance`() {
        val signals = HomeSource.entries.map { source ->
            val row = song(source.name).let { if (source == HomeSource.LOCAL) it.copy(localUri = "content://media/local/1") else it }
            like(row, source).copy(requiresMatching = source in setOf(HomeSource.YANDEX, HomeSource.SPOTIFY, HomeSource.SOUNDCLOUD))
        }
        val profile = TasteProfiles.merge(signals, nowMs = now)
        assertEquals(1, profile.tracks.size)
        assertEquals(HomeSource.entries.toSet(), profile.tracks.single().sources)
        assertEquals("content://media/local/1", profile.tracks.single().song.localUri)
        assertFalse(profile.tracks.single().requiresMatching)
    }

    @Test fun `repeated snapshot observations do not inflate score or frequency`() {
        val observation = listen(song("one"), 8)
        val once = merge(observation)
        val repeated = merge(observation, observation, observation)
        assertEquals(once, repeated)
        assertEquals(8, repeated.tracks.single().plays)
    }

    @Test fun `latest aggregate replaces an older copy instead of summing both`() {
        val old = listen(song("one"), 2, now - day)
        val updated = old.copy(count = 9, atMs = now)
        assertEquals(9, merge(old, updated).tracks.single().plays)
    }

    @Test fun `case and punctuation differences are deduplicated`() {
        assertEquals(1, merge(like(song("a", "Northern Lights!")),
            like(song("b", "northern lights", "ARTIST"), HomeSource.SPOTIFY)).tracks.size)
    }

    @Test fun `Spotify identity survives a duplicate YouTube recording`() {
        val youtube = like(song("abcdefghijk"), HomeSource.YOUTUBE)
        val spotifySong = song("spotify-track:4cOdK2wGLETKBW3PvgPWqT").copy(
            playbackSource = "Spotify",
            playbackSourceId = "4cOdK2wGLETKBW3PvgPWqT",
        )
        val track = merge(youtube, like(spotifySong, HomeSource.SPOTIFY)).tracks.single()
        assertEquals("spotify-track:4cOdK2wGLETKBW3PvgPWqT", track.song.videoId)
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", track.song.playbackSourceId)
        assertEquals(setOf(HomeSource.YOUTUBE, HomeSource.SPOTIFY), track.sources)
    }

    @Test fun `same title by different artists is not a duplicate`() {
        assertEquals(2, merge(like(song("a", artist = "Artist One")),
            like(song("b", artist = "Artist Two"))).tracks.size)
    }

    @Test fun `live and original recordings remain distinct`() {
        assertEquals(2, merge(like(song("a")), like(song("b", "Northern Lights (Live)"))).tracks.size)
    }

    @Test fun `large duration differences and explicit editions remain distinct`() {
        assertFalse(TasteProfiles.sameRecording(song("a"), song("b", duration = "8:00")))
        assertFalse(TasteProfiles.sameRecording(song("a").copy(isExplicit = false), song("b").copy(isExplicit = true)))
    }

    @Test fun `a playable match replaces external-only metadata without losing its source`() {
        val external = like(song("external"), HomeSource.YANDEX).copy(requiresMatching = true)
        val ready = listen(song("abcdefghijk"), 1)
        val track = merge(external, ready).tracks.single()
        assertEquals("abcdefghijk", track.song.videoId)
        assertEquals(setOf(HomeSource.YANDEX, HomeSource.YOUTUBE), track.sources)
        assertFalse(track.requiresMatching)
    }

    @Test fun `recent listening outranks equally frequent old listening`() {
        val profile = merge(listen(song("old", "Old song"), 5, now - 120 * day),
            listen(song("new", "New song"), 5))
        assertEquals("new", profile.tracks.first().song.videoId)
    }

    @Test fun `frequency matters when recency is equal`() {
        val profile = merge(listen(song("once", "Once"), 1), listen(song("often", "Often"), 12))
        assertEquals("often", profile.tracks.first().song.videoId)
    }

    @Test fun `one new play cannot rejuvenate years of old listening`() {
        val revived = song("revived", "Revived")
        val profile = merge(listen(revived, 10_000, now - 730 * day, "old-month"),
            listen(revived, 1, now, "new-month"), listen(song("recent", "Recent favourite"), 8))
        assertEquals("recent", profile.tracks.first().song.videoId)
        assertEquals(10_001, profile.tracks.last().plays)
    }

    @Test fun `history entries with unknown counts do not become measured plays`() {
        val profile = merge(TasteSignal("history", HomeSource.YANDEX, TasteSignalKind.HISTORY,
            song("history"), count = 0, order = 2, requiresMatching = true))
        assertEquals(0, profile.tracks.single().plays)
        assertNull(profile.tracks.single().lastPlayedAtMs)
    }

    @Test fun `recent likes retain actual dates and unknown service order`() {
        val known = like(song("known", "Known"), at = now - day)
        val unknownFirst = like(song("u1", "Unknown first"), at = null, order = 0)
        val unknownSecond = like(song("u2", "Unknown second"), at = null, order = 1)
        val profile = merge(unknownSecond, known, unknownFirst)
        assertEquals(listOf("known", "u1", "u2"), profile.recentLikes.map { it.song.videoId })
        assertNull(profile.recentLikes[1].likedAtMs)
        val later = TasteProfiles.merge(listOf(known), nowMs = now + 10 * day)
        assertEquals(now - day, later.recentLikes.single().likedAtMs)
    }

    @Test fun `explicit favourite artists work without any track history`() {
        val profile = merge(TasteSignal("artist", HomeSource.YANDEX, TasteSignalKind.ARTIST,
            artist = ArtistRef("Favourite Artist")))
        assertFalse(profile.isEmpty)
        assertTrue(profile.tracks.isEmpty())
        assertEquals("Favourite Artist", profile.artists.single().artist.name)
    }

    @Test fun `separate real artist credits are combined across sources`() {
        val collaboration = song("a").copy(artists = listOf(ArtistRef("Lead"), ArtistRef("Guest")))
        val profile = merge(like(collaboration), like(song("b", "Solo", "Lead"), HomeSource.SPOTIFY))
        assertEquals("Lead", profile.artists.first().artist.name)
        assertEquals(setOf(HomeSource.YOUTUBE, HomeSource.SPOTIFY), profile.artists.first().sources)
        assertEquals(2, profile.artists.size)
    }

    @Test fun `recommendations use favourite artist affinity`() {
        val profile = merge(like(song("liked", "Liked", "Favourite")))
        val ranked = TasteProfiles.rankCandidates(profile, listOf(candidate(song("other", "Other", "Stranger")),
            candidate(song("wanted", "New release", "Favourite"))))
        assertEquals("wanted", ranked.first().song.videoId)
    }

    @Test fun `search duplicates retain the score and like of the profile recording`() {
        val row = song("abcdefghijk")
        val favourite = merge(like(row)).tracks.single()
        val ranked = TasteProfiles.rankCandidates(TasteProfile(), listOf(candidate(row), favourite))
        assertEquals(1, ranked.size)
        assertEquals(favourite.score, ranked.single().score, 0.00001)
        assertTrue(ranked.single().liked)
        assertEquals(now, ranked.single().likedAtMs)
    }

    @Test fun `dislikes suppress catalogue duplicates and their recommendations`() {
        val refused = song("one")
        val profile = merge(like(song("other"), HomeSource.SPOTIFY),
            TasteSignal("dislike", HomeSource.YOUTUBE, TasteSignalKind.DISLIKE, refused))
        assertTrue(profile.tracks.isEmpty())
        assertTrue(TasteProfiles.rankCandidates(profile, listOf(candidate(song("third")))).isEmpty())
    }

    @Test fun `disconnect removes account data but not owned imported metadata`() {
        val remote = like(song("remote", "Remote"), HomeSource.YANDEX).copy(account = "account-a")
        val imported = TasteSignal("owned-import", HomeSource.YANDEX, TasteSignalKind.PLAYLIST, song("local", "Owned"))
        val before = TasteProfiles.merge(listOf(remote, imported), mapOf(HomeSource.YANDEX to "account-a"), now)
        assertEquals(2, before.tracks.size)
        val after = merge(remote, imported)
        assertEquals(listOf("local"), after.tracks.map { it.song.videoId })
    }

    @Test fun `account switch never combines the old and new users likes`() {
        val old = like(song("old", "Old"), HomeSource.SPOTIFY).copy(account = "old-account")
        val current = like(song("current", "Current"), HomeSource.SPOTIFY).copy(account = "new-account")
        val profile = TasteProfiles.merge(listOf(old, current), mapOf(HomeSource.SPOTIFY to "new-account"), now)
        assertEquals(listOf("current"), profile.tracks.map { it.song.videoId })
    }
}
