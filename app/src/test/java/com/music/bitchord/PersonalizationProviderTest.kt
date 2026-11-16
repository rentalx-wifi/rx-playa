package com.music.bitchord

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.personalization.StoredTasteSignals
import com.music.bitchord.data.personalization.StoredTasteSong
import com.music.bitchord.data.personalization.TasteImport
import com.music.bitchord.data.personalization.collectSpotifyPlaylistTaste
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.yandex.YandexMusicApi
import com.music.bitchord.data.yandex.YandexMusicApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

class PersonalizationProviderTest {
    private fun metadata(id: Int) =
        """{"id":"$id","title":"Track $id","artists":[{"name":"Artist"}],"durationMs":180000}"""

    @Test fun `Yandex likes fetch all IDs in ordered batches without a track cap`() = runBlocking {
        MockWebServer().use { server ->
            val likes = (1..205).joinToString(",") {
                """{"id":"$it","albumId":"album","timestamp":"2026-01-02T10:00:00Z"}"""
            }
            server.enqueue(MockResponse().setBody("""{"result":{"library":{"tracks":[$likes]}}}"""))
            (1..205).toList().chunked(100).forEach { batch ->
                server.enqueue(MockResponse().setBody("""{"result":[${batch.joinToString(",", transform = ::metadata)}]}"""))
            }
            val api = YandexMusicApi(OkHttpClient(), server.url("/"))
            val rows = api.likedTracks(UUID.randomUUID().toString(), "user")
            assertEquals((1..205).map(Int::toString), rows.map { it.track.id })
            assertEquals(Instant.parse("2026-01-02T10:00:00Z").toEpochMilli(), rows.first().likedAtMs)
            assertEquals(listOf("Artist"), rows.first().track.artistNames)
            assertEquals(4, server.requestCount)
            assertEquals("/users/user/likes/tracks", server.takeRequest().requestUrl?.encodedPath)
            val requests = (1..3).map { server.takeRequest() }
            assertTrue(requests.all { it.path == "/tracks" && it.method == "POST" })
            val sizes = requests.map { request ->
                val body = URLDecoder.decode(request.body.readUtf8(), "UTF-8")
                body.substringAfter("track-ids=").substringBefore("&").split(',').size
            }
            assertEquals(listOf(100, 100, 5), sizes)
        }
    }

    @Test fun `an actually empty Yandex liked list makes no metadata requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"library":{"tracks":[]}}}"""))
            assertTrue(YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("fixture", "user").isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `malformed liked responses are errors not an empty taste profile`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{}}"""))
            val result = runCatching { YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("fixture", "user") }
            assertTrue(result.exceptionOrNull() is YandexMusicApiException.InvalidResponse)
        }
    }

    @Test fun `Yandex forbidden and unauthorized responses keep distinct meanings`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(401))
            val api = YandexMusicApi(OkHttpClient(), server.url("/"))
            assertTrue(runCatching { api.likedTracks("fixture", "user") }.exceptionOrNull() is YandexMusicApiException.AccessDenied)
            assertTrue(runCatching { api.likedTracks("fixture", "user") }.exceptionOrNull() is YandexMusicApiException.Unauthorized)
        }
    }

    @Test fun `Yandex favourite artists accept timestamped and legacy metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":[
                {"artist":{"name":"Favourite"},"timestamp":"2026-01-02T10:00:00Z"},
                {"name":"Legacy"}
            ]}"""))
            val artists = YandexMusicApi(OkHttpClient(), server.url("/")).likedArtists("fixture", "user")
            assertEquals(listOf("Favourite", "Legacy"), artists.map { it.name })
            assertNotNull(artists.first().likedAtMs)
            assertNull(artists.last().likedAtMs)
        }
    }

    @Test fun `Yandex history uses real days and requests only missing track metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"historyTabs":[{"date":"2026-01-02","items":[{"tracks":[
                {"type":"track","data":{"itemId":{"trackId":"1","albumId":"album"},"fullModel":${metadata(1)}}},
                {"type":"track","data":{"itemId":{"trackId":"2","albumId":"album"}}},
                {"type":"ad","data":{"itemId":{"trackId":"3"}}}
            ]}]}]}}"""))
            server.enqueue(MockResponse().setBody("""{"result":[${metadata(2)}]}"""))
            val history = YandexMusicApi(OkHttpClient(), server.url("/")).historyTracks("fixture")
            assertEquals(listOf("1", "2"), history.map { it.track.id })
            val day = LocalDate.parse("2026-01-02").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            assertEquals(day, history.first().listenedDayMs)
            assertEquals(2, server.requestCount)
            assertEquals("/music-history", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("track-ids=2%3Aalbum&with-positions=true", server.takeRequest().body.readUtf8())
        }
    }

    @Test fun `cancelled Yandex HTTP stops before metadata paging`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val pending = async { YandexMusicApi(client, server.url("/")).likedTracks("fixture", "user") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, server.requestCount)
            client.connectionPool.evictAll()
        }
    }

    @Test fun `server likes and menu reads do not masquerade as new user actions`() {
        LikeState.clear()
        try {
            LikeState.seedLiked(setOf("seeded"))
            LikeState.rememberStated("menu", LikeStatus.LIKE)
            assertTrue(LikeState.userChanges.value.isEmpty())
            LikeState.set("explicit", LikeStatus.LIKE)
            assertEquals(mapOf("explicit" to LikeStatus.LIKE), LikeState.userChanges.value)
            LikeState.clear()
            assertTrue(LikeState.userChanges.value.isEmpty())
        } finally {
            LikeState.clear()
        }
    }

    @Test fun `stored import metadata retains its source but excludes feedback credentials`() {
        val sentinel = UUID.randomUUID().toString()
        val row = Song("abcdefghijk", "Song", "Artist", null, feedbackToken = sentinel)
        val stored = StoredTasteSignals(imports = listOf(TasteImport("local:playlist:1", "Imported",
            HomeSource.SOUNDCLOUD, 10L, songs = listOf(StoredTasteSong.of(row)))))
        val encoded = Json.encodeToString(StoredTasteSignals.serializer(), stored)
        assertFalse(encoded.contains(sentinel))
        val decoded = Json.decodeFromString(StoredTasteSignals.serializer(), encoded)
        assertEquals(HomeSource.SOUNDCLOUD, decoded.imports.single().source)
        assertEquals("abcdefghijk", decoded.imports.single().songs.single().song().videoId)
        assertNull(decoded.imports.single().songs.single().song().feedbackToken)
    }

    @Test fun `Spotify playlist contents become account scoped taste signals`() = runTest {
        val playlists = listOf(
            SpotifyPlaylist(SpotifyLibrary.LIKED_ID, "Liked Songs", null, null),
            SpotifyPlaylist("first", "First", null, null),
            SpotifyPlaylist("second", "Second", null, null),
        )
        val taste = collectSpotifyPlaylistTaste("account", playlists) { id ->
            listOf(SpotifyTrack("shared", "Song", "Artist", "Album", 180_000, null),
                SpotifyTrack(id, "Song $id", "Artist", null, 181_000, null))
        }
        assertFalse(taste.incomplete)
        assertEquals(4, taste.signals.size)
        assertTrue(taste.signals.all { it.account == "account" && it.source == HomeSource.SPOTIFY })
        assertTrue(taste.signals.none { it.id.contains(SpotifyLibrary.LIKED_ID) })
        assertEquals(2, taste.signals.count { it.song?.videoId == "spotify-track:shared" })
        assertTrue(taste.signals.none { it.requiresMatching })
    }

    @Test fun `one inaccessible Spotify playlist keeps the others and marks the snapshot partial`() = runTest {
        val playlists = listOf(
            SpotifyPlaylist("available", "Available", null, null),
            SpotifyPlaylist("blocked", "Blocked", null, null),
        )
        val taste = collectSpotifyPlaylistTaste("account", playlists) { id ->
            if (id == "blocked") throw IOException("unavailable")
            listOf(SpotifyTrack("kept", "Kept", "Artist", null, 180_000, null))
        }
        assertTrue(taste.incomplete)
        assertEquals(listOf("playlist:available:kept"), taste.signals.map { it.id })
    }
}
