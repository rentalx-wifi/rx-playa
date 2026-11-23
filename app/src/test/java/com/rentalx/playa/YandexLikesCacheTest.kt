package com.rentalx.playa

import com.rentalx.playa.data.yandex.YandexMusicApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * The revision the likes endpoint is asked with, and what a 202 means.
 *
 * Every Home opening used to re-list the whole liked library and re-hydrate its
 * metadata in batches of a hundred: on a library of several thousand that is
 * dozens of POSTs and megabytes, per opening, for a list that had not changed.
 * The endpoint takes the revision the caller already has and answers 202 with no
 * body when that is still current — so the list kept from last time is the
 * answer, at the cost of one GET.
 */
class YandexLikesCacheTest {

    private fun track(id: String) =
        """{"id":"$id","title":"Track $id","artists":[{"name":"Artist"}],"durationMs":180000,"albums":[{"title":"Album"}]}"""

    /** A fresh account per test: the cache is shared for the life of the process. */
    private fun account() = UUID.randomUUID().toString()

    @Test
    fun `an unchanged library costs one request and no metadata batch`() = runBlocking {
        MockWebServer().use { server ->
            val uid = account()
            server.enqueue(MockResponse().setBody("""{"result":{"library":{"revision":7,"tracks":[{"id":"1","albumId":"a"}]}}}"""))
            server.enqueue(MockResponse().setBody("""{"result":[${track("1")}]}"""))

            val first = YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("token", uid)
            assertEquals(listOf("1"), first.map { it.track.id })

            server.enqueue(MockResponse().setResponseCode(202).setBody(""))
            val second = YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("token", uid)

            assertEquals(listOf("1"), second.map { it.track.id })
            val paths = (0 until server.requestCount).map { server.takeRequest().path }
            assertEquals(
                listOf(
                    "/users/$uid/likes/tracks?if-modified-since-revision=0",
                    "/tracks",
                    "/users/$uid/likes/tracks?if-modified-since-revision=7",
                ),
                paths,
            )
        }
    }

    @Test
    fun `a changed library is fetched once and asked for again by its new revision`() = runBlocking {
        MockWebServer().use { server ->
            val uid = account()
            server.enqueue(MockResponse().setBody("""{"result":{"library":{"revision":7,"tracks":[{"id":"1","albumId":"a"}]}}}"""))
            server.enqueue(MockResponse().setBody("""{"result":[${track("1")}]}"""))
            YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("token", uid)

            server.enqueue(
                MockResponse().setBody(
                    """{"result":{"library":{"revision":9,"tracks":[{"id":"1","albumId":"a"},{"id":"2","albumId":"a"}]}}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("""{"result":[${track("1")},${track("2")}]}"""))
            val second = YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("token", uid)
            assertEquals(listOf("1", "2"), second.map { it.track.id })

            server.enqueue(MockResponse().setResponseCode(202).setBody(""))
            YandexMusicApi(OkHttpClient(), server.url("/")).likedTracks("token", uid)

            val paths = (0 until server.requestCount).map { server.takeRequest().path }
            assertEquals(
                listOf(
                    "/users/$uid/likes/tracks?if-modified-since-revision=0",
                    "/tracks",
                    "/users/$uid/likes/tracks?if-modified-since-revision=7",
                    "/tracks",
                    "/users/$uid/likes/tracks?if-modified-since-revision=9",
                ),
                paths,
            )
        }
    }
}
