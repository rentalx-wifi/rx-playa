package com.rentalx.playa

import com.rentalx.playa.data.yandex.YandexMusicApi
import com.rentalx.playa.data.yandex.YandexMusicApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

class YandexPersonalizationTest {
    private fun track(id: Int) = """{"id":"$id","title":"Track $id","artists":[{"name":"Artist"}],"durationMs":180000,"albums":[{"title":"Album"}]}"""
    private fun credential() = UUID.randomUUID().toString()

    @Test fun `all liked ids are hydrated in bounded ordered metadata batches`() = runBlocking {
        MockWebServer().use { server ->
            val entries = (1..201).joinToString(",") {
                """{"id":"$it","albumId":"album","timestamp":"2026-01-02T03:04:05Z"}"""
            }
            server.enqueue(MockResponse().setBody("""{"result":{"library":{"tracks":[$entries]}}}"""))
            (1..201).toList().chunked(100).forEach { batch ->
                server.enqueue(MockResponse().setBody("""{"result":[${batch.joinToString(",", transform = ::track)}]}"""))
            }
            val api = YandexMusicApi(OkHttpClient(), server.url("/"))
            val result = api.likedTracks(credential(), "42")
            assertEquals((1..201).map(Int::toString), result.map { it.track.id })
            assertEquals(Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), result.first().likedAtMs)
            assertEquals(listOf("Artist"), result.first().track.artistNames)
            assertEquals(4, server.requestCount)
            assertEquals("/users/42/likes/tracks?if-modified-since-revision=0", server.takeRequest().path)
            val sizes = mutableListOf<Int>()
            repeat(3) {
                val request = server.takeRequest()
                assertEquals("/tracks", request.path)
                val body = URLDecoder.decode(request.body.readUtf8(), "UTF-8")
                sizes += body.substringAfter("track-ids=").substringBefore('&').split(',').size
            }
            assertEquals(listOf(100, 100, 1), sizes)
        }
    }

    @Test fun `an empty liked library needs no metadata requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"library":{"tracks":[]}}}"""))
            val result = YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks(credential(), "42")
            assertTrue(result.isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `missing library is an error and not a fabricated empty profile`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{}}"""))
            val failure = runCatching {
                YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks(credential(), "42")
            }.exceptionOrNull()
            assertTrue(failure is YandexMusicApiException.InvalidResponse)
        }
    }

    @Test fun `favourite artists accept timestamped and legacy metadata without invented dates`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":[
                {"artist":{"id":"1","name":"First"},"timestamp":"2026-01-02T03:04:05Z"},
                {"id":"2","name":"Second"}
            ]}"""))
            val result = YandexMusicApi(OkHttpClient(), server.url("/")).likedArtists(credential(), "42")
            assertEquals(listOf("First", "Second"), result.map { it.name })
            assertNotNull(result.first().likedAtMs)
            assertNull(result.last().likedAtMs)
            assertEquals("/users/42/likes/artists?with-timestamps=true", server.takeRequest().path)
        }
    }

    @Test fun `history uses actual days and hydrates only missing track models`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"result":{"historyTabs":[
                {"date":"2026-01-02","items":[{"tracks":[
                    {"type":"track","data":{"itemId":{"trackId":"1","albumId":"a"},"fullModel":${track(1)}}},
                    {"type":"track","data":{"itemId":{"trackId":"2","albumId":"b"}}},
                    {"type":"ad","data":{"itemId":{"trackId":"3"}}}
                ]}]}
            ]}}"""))
            server.enqueue(MockResponse().setBody("""{"result":[${track(2)}]}"""))
            val result = YandexMusicApi(OkHttpClient(), server.url("/")).historyTracks(credential())
            assertEquals(listOf("1", "2"), result.map { it.track.id })
            assertEquals(LocalDate.parse("2026-01-02").atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                result.first().listenedDayMs)
            assertEquals("/music-history?fullModelsCount=100", server.takeRequest().path)
            val body = URLDecoder.decode(server.takeRequest().body.readUtf8(), "UTF-8")
            assertTrue(body.contains("track-ids=2:b"))
            assertFalse(body.contains("1:a"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `access failures retain their real error classification`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            val denied = runCatching {
                YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks(credential(), "42")
            }.exceptionOrNull()
            assertTrue(denied is YandexMusicApiException.AccessDenied)
            server.enqueue(MockResponse().setResponseCode(401))
            val expired = runCatching {
                YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks(credential(), "42")
            }.exceptionOrNull()
            assertTrue(expired is YandexMusicApiException.Unauthorized)
        }
    }

    @Test fun `cancellation aborts an outstanding HTTP request without loading further metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val work = async { YandexMusicApi(client, server.url("/")).likedTracks(credential(), "42") }
            try {
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            } finally {
                work.cancelAndJoin()
            }
            assertTrue(work.isCancelled)
            assertEquals(1, server.requestCount)
            client.connectionPool.evictAll()
        }
    }
}
