package com.rentalx.playa

import com.rentalx.playa.data.importing.BasicExternalPlaylistTrack
import com.rentalx.playa.data.importing.ExternalPlaylistImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An import that cannot finish is worse than one that reports misses.
 *
 * Every row is awaited before the import returns, so a single catalogue search
 * that never answers used to hold the whole thing at a progress counter that had
 * stopped moving — the "вечный поиск" of the report. A row now has a deadline,
 * after which it is a row that was not found.
 */
class ExternalPlaylistImportTimeoutTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(title: String) = BasicExternalPlaylistTrack(
        title = title,
        artist = "Artist",
        album = null,
        durationMs = 180_000L,
        coverUrl = null,
    )

    @Test
    fun `a search that never answers becomes a track that was not found`() = runTest(dispatcher) {
        val progress = mutableListOf<Int>()
        val (songs, missed) = ExternalPlaylistImporter.resolve(
            tracks = listOf(track("Hangs")),
            candidatesFor = { _, _ -> awaitCancellation() },
            onProgress = { done, _ -> progress += done },
            matchTimeoutMs = 100L,
        )

        assertTrue(songs.isEmpty())
        assertEquals(listOf("Hangs"), missed.map { it.title })
        assertEquals("progress still reaches the total", listOf(1), progress)
    }

    @Test
    fun `a slow row does not hold the ones that answer`() = runTest(dispatcher) {
        val (songs, missed) = ExternalPlaylistImporter.resolve(
            tracks = listOf(track("Hangs"), track("Answers")),
            candidatesFor = { title, _ ->
                if (title == "Hangs") awaitCancellation() else emptyList()
            },
            onProgress = { _, _ -> },
            matchTimeoutMs = 100L,
        )

        assertTrue(songs.isEmpty())
        assertEquals(listOf("Hangs", "Answers"), missed.map { it.title })
    }
}
