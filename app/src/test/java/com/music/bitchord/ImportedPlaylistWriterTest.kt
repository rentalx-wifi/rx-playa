package com.music.bitchord

import com.music.bitchord.data.importing.writeImportedPlaylist
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ImportedPlaylistWriterTest {
    private fun songs(count: Int) = List(count) { index ->
        Song(
            videoId = "video-$index",
            title = "Track $index",
            artist = "Artist $index",
            thumbnailUrl = "https://covers.example/$index",
            durationText = "3:00",
            albumName = "Release $index",
        )
    }

    @Test
    fun `first failed add stops writing and saves every resolved song`() = runTest {
        val songs = songs(151)
        val ids = songs.map { it.videoId }
        val writes = mutableListOf<List<String>>()
        var savedSongs: List<Song>? = null
        var saves = 0

        val result = writeImportedPlaylist(
            videoIds = ids,
            songs = songs,
            createPlaylist = { initial ->
                assertEquals(ids.take(50), initial)
                Result.success("created")
            },
            addToPlaylist = { playlistId, chunk ->
                assertEquals("created", playlistId)
                writes += chunk
                Result.failure(IllegalStateException("cloud refused the chunk"))
            },
            saveLocally = { completeSongs ->
                saves++
                savedSongs = completeSongs
                "local:playlist:fallback"
            },
        )

        assertEquals(listOf(ids.subList(50, 100)), writes)
        assertEquals("created", result.cloudPlaylistId)
        assertEquals(50, result.acceptedCloudTrackCount)
        assertEquals(1, saves)
        assertSame(songs, savedSongs)
        assertEquals(songs, savedSongs)
        assertTrue(result.savedLocally)
        assertEquals("local:playlist:fallback", result.browseId)
    }

    @Test
    fun `later failed add reports only successful batches even without removal ids`() = runTest {
        val songs = songs(201)
        val ids = songs.map { it.videoId }
        val writes = mutableListOf<List<String>>()
        var savedSongs: List<Song>? = null

        val result = writeImportedPlaylist(
            videoIds = ids,
            songs = songs,
            createPlaylist = { Result.success("created") },
            addToPlaylist = { _, chunk ->
                writes += chunk
                if (writes.size == 1) Result.success(emptyMap())
                else Result.failure(IllegalStateException("offline"))
            },
            saveLocally = {
                savedSongs = it
                "local:playlist:fallback"
            },
        )

        assertEquals(listOf(ids.subList(50, 100), ids.subList(100, 150)), writes)
        assertEquals(100, result.acceptedCloudTrackCount)
        assertEquals("created", result.cloudPlaylistId)
        assertSame(songs, savedSongs)
        assertTrue(result.savedLocally)
    }

    @Test
    fun `failed create has no acknowledged cloud tracks and still saves the full list`() = runTest {
        val songs = songs(101)
        var savedSongs: List<Song>? = null

        val result = writeImportedPlaylist(
            videoIds = songs.map { it.videoId },
            songs = songs,
            createPlaylist = { Result.failure(IllegalStateException("create refused")) },
            addToPlaylist = { _, _ -> error("must not append without a playlist") },
            saveLocally = {
                savedSongs = it
                "local:playlist:fallback"
            },
        )

        assertNull(result.cloudPlaylistId)
        assertEquals(0, result.acceptedCloudTrackCount)
        assertSame(songs, savedSongs)
        assertEquals("local:playlist:fallback", result.browseId)
        assertTrue(result.savedLocally)
    }

    @Test
    fun `a thrown cloud error also preserves the complete list locally`() = runTest {
        val songs = songs(151)
        var writes = 0
        var savedSongs: List<Song>? = null

        val result = writeImportedPlaylist(
            videoIds = songs.map { it.videoId },
            songs = songs,
            createPlaylist = { Result.success("created") },
            addToPlaylist = { _, _ ->
                writes++
                throw IllegalStateException("connection lost")
            },
            saveLocally = {
                savedSongs = it
                "local:playlist:fallback"
            },
        )

        assertEquals(1, writes)
        assertEquals(50, result.acceptedCloudTrackCount)
        assertSame(songs, savedSongs)
        assertTrue(result.savedLocally)
    }

    @Test
    fun `successful imports keep order and duplicates beyond one hundred tracks`() = runTest {
        for (count in listOf(0, 1, 50, 51, 100, 101, 137, 251)) {
            val songs = songs(count)
            val ids = songs.mapIndexed { index, song ->
                if (index == 100) songs.first().videoId else song.videoId
            }
            val batches = mutableListOf<List<String>>()
            val result = writeImportedPlaylist(
                videoIds = ids,
                songs = songs,
                createPlaylist = {
                    batches += it
                    Result.success("created")
                },
                addToPlaylist = { playlistId, chunk ->
                    assertEquals("created", playlistId)
                    batches += chunk
                    Result.success(emptyMap())
                },
                saveLocally = { error("successful upload must not fall back") },
            )

            val expectedBatches = if (ids.isEmpty()) listOf(emptyList()) else ids.chunked(50)
            assertEquals(expectedBatches, batches)
            assertEquals(ids, batches.flatten())
            assertEquals(count, result.acceptedCloudTrackCount)
            assertEquals("created", result.cloudPlaylistId)
            assertEquals("VLcreated", result.browseId)
            assertNull(result.localBrowseId)
            assertFalse(result.savedLocally)
        }
    }

    @Test
    fun `cancellation thrown or returned by either cloud operation propagates`() = runTest {
        val songs = songs(151)
        for (cancelCreate in listOf(true, false)) {
            for (returnedAsFailure in listOf(true, false)) {
                val cancellation = CancellationException("import cancelled")
                var saved = false
                var writes = 0
                var caught: CancellationException? = null
                try {
                    writeImportedPlaylist(
                        videoIds = songs.map { it.videoId },
                        songs = songs,
                        createPlaylist = {
                            if (!cancelCreate) Result.success("created")
                            else if (returnedAsFailure) Result.failure(cancellation)
                            else throw cancellation
                        },
                        addToPlaylist = { _, _ ->
                            writes++
                            if (returnedAsFailure) Result.failure(cancellation)
                            else throw cancellation
                        },
                        saveLocally = {
                            saved = true
                            "local:playlist:fallback"
                        },
                    )
                    fail("Cancellation must propagate")
                } catch (failure: CancellationException) {
                    caught = failure
                }

                assertSame(cancellation, caught)
                assertEquals(if (cancelCreate) 0 else 1, writes)
                assertFalse(saved)
            }
        }
    }

    @Test
    fun `a cancelled job cannot become local success through a normal failed Result`() = runTest {
        val songs = songs(151)
        var saved = false
        var completed = false
        var writes = 0
        val job = launch {
            writeImportedPlaylist(
                videoIds = songs.map { it.videoId },
                songs = songs,
                createPlaylist = { Result.success("created") },
                addToPlaylist = { _, _ ->
                    writes++
                    currentCoroutineContext().cancel()
                    Result.failure(IllegalStateException("uploader ignored cancellation"))
                },
                saveLocally = {
                    saved = true
                    "local:playlist:fallback"
                },
            )
            completed = true
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, writes)
        assertFalse(saved)
        assertFalse(completed)
    }
}
