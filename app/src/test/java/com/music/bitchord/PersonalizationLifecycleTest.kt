package com.music.bitchord

import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalization.PersonalizationSnapshots
import com.music.bitchord.data.personalization.ProviderTasteSnapshot
import com.music.bitchord.data.personalization.TasteSignal
import com.music.bitchord.data.personalization.TasteSignalKind
import com.music.bitchord.data.personalization.collectTasteSongPages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PersonalizationLifecycleTest {
    private val source = HomeSource.YANDEX
    private fun song(id: String) = Song(id, id, "Artist", null)
    private fun snapshot(id: String, owner: String) = ProviderTasteSnapshot(listOf(
        TasteSignal(id, source, TasteSignalKind.LIKE, song(id), account = owner),
    ))

    @Test fun `disconnect immediately removes old snapshots and refuses their callbacks`() {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a"))
        val request = state.begin(source)!!
        assertTrue(state.publish(request, snapshot("old", "a")))
        state.select(emptyMap())
        assertTrue(state.snapshot().isEmpty())
        assertFalse(state.publish(request, snapshot("late", "a")))
        assertNull(state.begin(source))
    }

    @Test fun `new request supersedes success and failure of an old refresh`() {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a"))
        val old = state.begin(source)!!
        val current = state.begin(source)!!
        assertTrue(state.publish(current, snapshot("current", "a")))
        assertFalse(state.publish(old, snapshot("old", "a")))
        assertFalse(state.isCurrent(old))
        assertEquals("current", state.snapshot().getValue(source).signals.single().id)
    }

    @Test fun `switching away and back to the same account still rejects an old generation`() {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a"))
        val old = state.begin(source)!!
        state.select(mapOf(source to "b"))
        state.select(mapOf(source to "a"))
        val current = state.begin(source)!!
        assertFalse(state.publish(old, snapshot("old", "a")))
        assertTrue(state.publish(current, snapshot("new", "a")))
    }

    @Test fun `disconnecting one source preserves other connected source snapshots`() {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a", HomeSource.SPOTIFY to "s"))
        val yandex = state.begin(source)!!
        val spotify = state.begin(HomeSource.SPOTIFY)!!
        state.publish(yandex, snapshot("y", "a"))
        state.publish(spotify, ProviderTasteSnapshot())
        state.select(mapOf(HomeSource.SPOTIFY to "s"))
        assertEquals(setOf(HomeSource.SPOTIFY), state.snapshot().keys)
        assertTrue(state.isCurrent(spotify))
    }

    @Test fun `unchanged account selection does not invalidate a valid request`() {
        val state = PersonalizationSnapshots()
        val accounts = mapOf(source to "a")
        state.select(accounts)
        val request = state.begin(source)!!
        assertTrue(state.select(accounts).isEmpty())
        assertTrue(state.publish(request, snapshot("same", "a")))
    }

    @Test fun `refresh keeps the last snapshot visible until its replacement arrives`() {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a"))
        state.publish(state.begin(source)!!, snapshot("liked", "a"))
        val refresh = state.begin(source)!!
        assertEquals("liked", state.snapshot().getValue(source).signals.single().id)
        state.publish(refresh, ProviderTasteSnapshot())
        assertTrue(state.snapshot().getValue(source).signals.isEmpty())
    }

    @Test fun `a late asynchronous provider cannot resurrect disconnected data`() = runTest {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a"))
        val request = state.begin(source)!!
        val response = CompletableDeferred<ProviderTasteSnapshot>()
        val callback = async { state.publish(request, response.await()) }
        state.select(emptyMap())
        response.complete(snapshot("late", "a"))
        assertFalse(callback.await())
        assertTrue(state.snapshot().isEmpty())
    }

    @Test fun `partial provider results remain explicitly marked incomplete`() {
        val state = PersonalizationSnapshots()
        state.select(mapOf(source to "a"))
        state.publish(state.begin(source)!!, snapshot("available", "a").copy(incomplete = true))
        assertTrue(state.snapshot().getValue(source).incomplete)
        assertEquals(1, state.snapshot().getValue(source).signals.size)
    }

    @Test fun `liked metadata pagination exceeds ten pages and retains order`() = runTest {
        val visited = mutableListOf<String>()
        val songs = collectTasteSongPages(listOf(song("0")), "1") { cursor ->
            visited += cursor
            val page = cursor.toInt()
            listOf(song("$page"), song("0")) to if (page < 12) "${page + 1}" else null
        }
        assertEquals((1..12).map(Int::toString), visited)
        assertEquals((0..12).map(Int::toString), songs.map { it.videoId })
    }

    @Test fun `a repeated cursor fails instead of returning an incomplete playlist`() = runTest {
        var requests = 0
        val result = runCatching {
            collectTasteSongPages(listOf(song("0")), "again") {
                requests++
                listOf(song("1")) to "again"
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, requests)
    }

    @Test fun `a network failure cannot become a successful prefix`() = runTest {
        val result = runCatching {
            collectTasteSongPages(listOf(song("0")), "next") { throw IOException("offline") }
        }
        assertTrue(result.isFailure)
    }

    @Test fun `cancellation stops paging before another request or publication`() = runTest {
        val reached = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Pair<List<Song>, String?>>()
        var requests = 0
        var published = false
        val job = launch {
            collectTasteSongPages(listOf(song("0")), "next") {
                requests++
                reached.complete(Unit)
                response.await()
            }
            published = true
        }
        reached.await()
        job.cancelAndJoin()
        response.complete(listOf(song("1")) to "more")
        assertEquals(1, requests)
        assertFalse(published)
    }
}
