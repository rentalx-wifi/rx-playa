package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpotifyImportMatchingTest {
    private fun song(
        id: String,
        title: String = "Northern Lights",
        artist: String = "Original Artist",
        duration: String = "3:00",
        album: String = "Original Release",
        coverUrl: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = coverUrl,
        durationText = duration,
        albumName = album,
    )

    private fun track() = SpotifyTrack(
        id = "spotify-track",
        title = "Northern Lights",
        artist = "Original Artist",
        album = "Original Release",
        durationMs = 180_000,
        imageUrl = "https://spotify.example/cover",
    )

    @Test
    fun `matchTrack leaves unrelated artist version and duration candidates unmatched`() = runBlocking {
        val track = track()
        val rejected = listOf(
            song("unrelated", title = "A Different Melody"),
            // Different credits need corroborating duration; this is outside that exception.
            song("wrong-artist", artist = "Different Singer", duration = "3:20"),
            song("live", title = "Northern Lights (Live)"),
            song("too-long", duration = "12:00"),
        )

        for (candidate in rejected) {
            val matched = SpotifyImporter.matchTrack(track) { title, artist ->
                assertEquals(track.title, title)
                assertEquals(track.artist, artist)
                listOf(candidate)
            }
            assertNull(matched, candidate.videoId)
        }
        assertNull(SpotifyImporter.matchTrack(track) { _, _ -> emptyList() })
    }

    @Test
    fun `matchTrack still ranks the correct release and keeps its cover`() = runBlocking {
        val track = track()
        val otherRelease = song("single", album = "Other Release", coverUrl = "https://youtube.example/single")
        val correct = song("correct", coverUrl = "https://youtube.example/correct")

        val matched = SpotifyImporter.matchTrack(track) { _, _ -> listOf(otherRelease, correct) }

        assertSame(correct, matched)
        assertEquals("https://youtube.example/correct", matched?.thumbnailUrl)
    }

    @Test
    fun `playlist resolution reports vetoed tracks in order instead of taking their first hit`() = runBlocking {
        withTimeout(10_000) {
            val tracks = listOf(
                SpotifyImportTrack("Northern Lights", "Original Artist"),
                SpotifyImportTrack("Unrelated Track", "Original Artist"),
                SpotifyImportTrack("Paper Moon", "Original Artist"),
                SpotifyImportTrack("Quiet River", "Original Artist"),
                SpotifyImportTrack("Missing Song", "Original Artist"),
                SpotifyImportTrack("Harbor Song", "Original Artist"),
            )
            val first = song("first", coverUrl = "https://youtube.example/first")
            val last = song("last", title = "Harbor Song", coverUrl = "https://youtube.example/last")
            val candidates = mapOf(
                tracks[0].title to listOf(first),
                tracks[1].title to listOf(song("unrelated", title = "A Different Melody")),
                tracks[2].title to listOf(song("wrong-artist", title = "Paper Moon", artist = "Different Singer")),
                tracks[3].title to listOf(song("live", title = "Quiet River (Live)")),
                tracks[4].title to emptyList(),
                tracks[5].title to listOf(last),
            )
            val lastLookupFinished = CompletableDeferred<Unit>()
            val completionOrder = ConcurrentLinkedQueue<String>()
            val progress = ConcurrentLinkedQueue<Pair<Int, Int>>()
            val (songs, unmatched) = SpotifyImporter.resolveToSongs(
                tracks = tracks,
                language = null,
                candidatesFor = { title, artist ->
                    assertEquals("Original Artist", artist)
                    if (title == tracks.first().title) lastLookupFinished.await()
                    completionOrder.add(title)
                    if (title == tracks.last().title) lastLookupFinished.complete(Unit)
                    candidates.getValue(title)
                },
                onProgress = { completed, total -> progress.add(completed to total) },
            )

            assertEquals(listOf(first, last), songs)
            assertEquals(tracks.subList(1, tracks.lastIndex), unmatched)
            val completedTitles = completionOrder.toList()
            assertTrue(completedTitles.indexOf(tracks.last().title) < completedTitles.indexOf(tracks.first().title))
            assertEquals((1..tracks.size).toList(), progress.map { it.first }.sorted())
            assertEquals(setOf(tracks.size), progress.map { it.second }.toSet())
        }
    }

    @Test
    fun `language preference cannot rescue rejected candidates`() = runBlocking {
        val track = SpotifyImportTrack("Northern Lights", "Original Artist")
        val candidates = listOf(
            song("unrelated", title = "A Different Melody"),
            song("wrong-artist", artist = "Different Singer"),
            song("live", title = "Northern Lights (Live)"),
        )
        val (songs, unmatched) = SpotifyImporter.resolveToSongs(
            tracks = listOf(track),
            language = " English ",
            candidatesFor = { title, artist ->
                assertEquals(track.title, title)
                assertEquals("Original Artist English", artist)
                candidates
            },
            onProgress = { _, _ -> },
        )

        assertTrue(songs.isEmpty())
        assertEquals(listOf(track), unmatched)
    }

    @Test
    fun `soft language preference retains a valid conflicting match instead of an unrelated first hit`() = runBlocking {
        val track = SpotifyImportTrack("Northern Lights", "Original Artist")
        val valid = song("tamil", title = "Northern Lights (Tamil)", coverUrl = "https://youtube.example/tamil")
        val unrelated = song("unrelated", title = "A Different Melody")
        val (songs, unmatched) = SpotifyImporter.resolveToSongs(
            tracks = listOf(track),
            language = "English",
            candidatesFor = { _, _ -> listOf(valid, unrelated) },
            onProgress = { _, _ -> },
        )

        assertEquals(listOf(valid), songs)
        assertTrue(unmatched.isEmpty())
    }

    @Test
    fun `language preference still prioritizes an acceptable nonconflicting song`() = runBlocking {
        val track = SpotifyImportTrack("Northern Lights", "Original Artist")
        val conflicting = song("tamil", title = "Northern Lights (Tamil)")
        val preferred = song("english", title = "Northern Lights (English)")
        val (songs, unmatched) = SpotifyImporter.resolveToSongs(
            tracks = listOf(track),
            language = "English",
            candidatesFor = { _, _ -> listOf(conflicting, preferred) },
            onProgress = { _, _ -> },
        )

        assertEquals(listOf(preferred), songs)
        assertTrue(unmatched.isEmpty())
    }

    @Test
    fun `playlist matching is bounded to four and preserves every track beyond one hundred`() = runBlocking {
        withTimeout(10_000) {
            val tracks = List(137) { SpotifyImportTrack("Song $it", "Original Artist") }
            val active = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val firstWaveStarted = CompletableDeferred<Unit>()
            val releaseLookups = CompletableDeferred<Unit>()
            val matching = async {
                SpotifyImporter.resolveToSongs(
                    tracks = tracks,
                    language = null,
                    candidatesFor = { title, _ ->
                        val running = active.incrementAndGet()
                        try {
                            peak.updateAndGet { maxOf(it, running) }
                            if (running == 4) firstWaveStarted.complete(Unit)
                            releaseLookups.await()
                            listOf(song("matched-${title.removePrefix("Song ")}", title = title))
                        } finally {
                            active.decrementAndGet()
                        }
                    },
                    onProgress = { _, _ -> },
                )
            }

            try {
                firstWaveStarted.await()
                assertEquals(4, active.get())
            } finally {
                releaseLookups.complete(Unit)
            }
            val (songs, unmatched) = matching.await()

            assertEquals(4, peak.get())
            assertEquals(0, active.get())
            assertEquals(tracks.map { it.title }, songs.map { it.title })
            assertEquals(List(tracks.size) { "matched-$it" }, songs.map { it.videoId })
            assertTrue(unmatched.isEmpty())
        }
    }
}
