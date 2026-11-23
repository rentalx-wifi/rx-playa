package com.rentalx.playa

import com.rentalx.playa.data.importing.BasicExternalPlaylistTrack
import com.rentalx.playa.data.importing.ExternalPlaylistImporter
import com.rentalx.playa.data.importing.extractPublicYouTubeVideoId
import com.rentalx.playa.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaylistImportMatchingTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(title: String, coverUrl: String? = null) = BasicExternalPlaylistTrack(
        title = title,
        artist = "Original Artist",
        album = "Original Release",
        durationMs = 180_000L,
        coverUrl = coverUrl,
    )

    private fun song(
        id: String,
        title: String,
        artist: String = "Original Artist",
        duration: String = "3:00",
        coverUrl: String? = null,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = coverUrl,
        durationText = duration,
        artistId = "artist-id",
        albumName = "Original Release",
    )

    @Test
    fun `rejected candidates stay unmatched while correct songs retain covers and input order`() = runBlocking {
        withTimeout(10_000) {
            val tracks = listOf(
                track("Northern Lights", "https://external.example/first"),
                track("Unrelated Track"),
                track("Paper Moon"),
                track("Quiet River"),
                track("Long Road"),
                track("Missing Song"),
                track("Harbor Song", "https://external.example/last"),
            )
            val first = song("first", tracks.first().title)
            val last = song("last", tracks.last().title, coverUrl = "https://youtube.example/last")
            val candidates = mapOf(
                tracks[0].title to listOf(song("remix", "Northern Lights (Remix)"), first),
                tracks[1].title to listOf(song("unrelated", "A Different Melody")),
                tracks[2].title to listOf(song("wrong-artist", "Paper Moon", "Different Singer", "3:20")),
                tracks[3].title to listOf(song("live", "Quiet River (Live)")),
                tracks[4].title to listOf(song("too-long", "Long Road", duration = "12:00")),
                tracks[5].title to emptyList(),
                tracks[6].title to listOf(last),
            )
            val lastLookupFinished = CompletableDeferred<Unit>()
            val completionOrder = ConcurrentLinkedQueue<String>()
            val progress = ConcurrentLinkedQueue<Pair<Int, Int>>()
            val (songs, unmatched) = ExternalPlaylistImporter.resolve(
                tracks = tracks,
                candidatesFor = { title, artist ->
                    assertEquals("Original Artist", artist)
                    if (title == tracks.first().title) lastLookupFinished.await()
                    completionOrder.add(title)
                    if (title == tracks.last().title) lastLookupFinished.complete(Unit)
                    candidates.getValue(title)
                },
                onProgress = { completed, total -> progress.add(completed to total) },
            )

            assertEquals(listOf(first.copy(thumbnailUrl = tracks.first().coverUrl), last), songs)
            assertEquals(tracks.subList(1, tracks.lastIndex), unmatched)
            val completedTitles = completionOrder.toList()
            assertTrue(completedTitles.indexOf(tracks.last().title) < completedTitles.indexOf(tracks.first().title))
            assertEquals((1..tracks.size).toList(), progress.map { it.first }.sorted())
            assertEquals(setOf(tracks.size), progress.map { it.second }.toSet())
        }
    }

    @Test
    fun `matching more than one hundred tracks keeps order and bounds candidate lookups to four`() = runBlocking {
        withTimeout(10_000) {
            val tracks = List(137) { track("Song $it") }
            val active = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val firstWaveStarted = CompletableDeferred<Unit>()
            val releaseLookups = CompletableDeferred<Unit>()
            val matching = async {
                ExternalPlaylistImporter.resolve(
                    tracks = tracks,
                    candidatesFor = { title, _ ->
                        val running = active.incrementAndGet()
                        try {
                            peak.updateAndGet { maxOf(it, running) }
                            if (running == 4) firstWaveStarted.complete(Unit)
                            releaseLookups.await()
                            listOf(song("matched-${title.removePrefix("Song ")}", title))
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

    @Test
    fun `public YouTube fallback runs only after music catalogue has no acceptable match`() = runBlocking {
        val wanted = track("Northern Lights")
        val fallback = song("dQw4w9WgXcQ", wanted.title)
        var fallbackCalls = 0

        val (songs, unmatched) = ExternalPlaylistImporter.resolve(
            tracks = listOf(wanted),
            candidatesFor = { _, _ -> listOf(song("wrong", "Different Song")) },
            onProgress = { _, _ -> },
            fallbackCandidatesFor = { _, _ ->
                fallbackCalls++
                listOf(fallback)
            },
        )

        assertEquals(listOf(fallback), songs)
        assertTrue(unmatched.isEmpty())
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `public YouTube id parser accepts video links but not lookalike hosts`() {
        assertEquals("dQw4w9WgXcQ", extractPublicYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractPublicYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=1"))
        assertEquals("dQw4w9WgXcQ", extractPublicYouTubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(null, extractPublicYouTubeVideoId("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
    }
}
