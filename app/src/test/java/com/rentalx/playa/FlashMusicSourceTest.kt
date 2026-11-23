package com.rentalx.playa

import com.rentalx.playa.data.sources.FlashMusicSource
import com.rentalx.playa.data.sources.SourceConfig
import com.rentalx.playa.data.sources.SourceHealth
import com.rentalx.playa.data.sources.SourceKind
import com.rentalx.playa.data.sources.StreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [FlashMusicSource] against a real FlashMusic server.
 *
 * Skipped when nothing is listening, so a build on a machine with no server
 * stays green: this checks the wire format the server and the app agreed on,
 * and it is worth running whenever either side changes.
 *
 * Point it elsewhere with `-Dflashmusic.url=http://host:port`.
 */
class FlashMusicSourceTest {

    private val base: String = System.getProperty("flashmusic.url") ?: DEFAULT_URL

    private fun source() = FlashMusicSource(
        SourceConfig(id = CONFIG_ID, kind = SourceKind.FLASHMUSIC, baseUrl = base),
    )

    private fun reachable(): Boolean = runCatching {
        OkHttpClient().newCall(Request.Builder().url("$base/healthz").build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun get(url: String): Pair<Int, String> = OkHttpClient()
        .newCall(Request.Builder().url(url).build())
        .execute().use { it.code to (it.body?.string().orEmpty()) }

    @Test
    fun `health reports the catalogue`() = runBlocking {
        assumeTrue("no FlashMusic server on $base", reachable())
        val health = source().health()
        assertTrue("health = $health", health is SourceHealth.Ok)
    }

    @Test
    fun `search names every row in this source's terms`() = runBlocking {
        assumeTrue("no FlashMusic server on $base", reachable())
        val rows = source().search("demo", limit = 10)
        assertTrue("no rows for 'demo'", rows.isNotEmpty())
        rows.forEach { song ->
            assertTrue("untagged row: ${song.videoId}", song.videoId.startsWith("src:$CONFIG_ID::"))
            assertTrue("blank title in $song", song.title.isNotBlank())
        }
    }

    @Test
    fun `every tier answers with audio`() = runBlocking {
        assumeTrue("no FlashMusic server on $base", reachable())
        val src = source()
        val row = src.search("demo", limit = 1).firstOrNull()
        assumeTrue("catalogue has no tracks", row != null)
        val trackId = row!!.videoId.substringAfter("::")

        val requests = listOf(
            "lossless" to StreamRequest.Lossless,
            "high" to StreamRequest.Best,
            "default" to StreamRequest.Capped(192),
        )
        for ((name, request) in requests) {
            val stream = src.stream(trackId, request)
            assertNotNull("no stream for $name", stream)
            val (code, body) = get(stream!!.url)
            assertEquals("$name: ${stream.url}", 200, code)
            assertTrue("$name answered with no bytes", body.isNotEmpty())
        }
    }
}

private const val CONFIG_ID = "test-flashmusic"
private const val DEFAULT_URL = "http://127.0.0.1:8099"
