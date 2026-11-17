package com.music.bitchord

import com.music.bitchord.data.soundcloud.SoundCloudPlaylistImporter
import com.music.bitchord.data.soundcloud.isTrustedSoundCloudUserContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SoundCloudPublicProfileTest {
    @Test
    fun `public profile continuation stays on the same HTTPS user collection`() {
        assertTrue(isTrustedSoundCloudUserContinuation("https://api-v2.soundcloud.com/users/12/likes?offset=100", "12", "likes"))
        for (url in listOf(
            "http://api-v2.soundcloud.com/users/12/likes?offset=100",
            "https://api-v2.soundcloud.com.other.example/users/12/likes",
            "https://api-v2.soundcloud.com/users/13/likes",
            "https://api-v2.soundcloud.com/users/12/playlists",
            "https://api-v2.soundcloud.com:444/users/12/likes",
            "https://name@api-v2.soundcloud.com/users/12/likes",
        )) {
            assertFalse(url, isTrustedSoundCloudUserContinuation(url, "12", "likes"))
        }
    }

    @Test
    fun `public playlists follow every page without a one hundred row cap`() = runBlocking {
        val first = (0 until 100).joinToString(",") { "{\"id\":$it}" }
        val pages = listOf(
            Json.parseToJsonElement("""{"collection":[$first],"next_href":"https://api-v2.soundcloud.com/users/12/playlists?offset=100"}""").jsonObject,
            Json.parseToJsonElement("""{"collection":[{"id":100}],"next_href":null}""").jsonObject,
        )
        val requests = ArrayList<String?>()
        val rows = SoundCloudPlaylistImporter.publicUserRows("12", "playlists") { next ->
            requests += next
            pages[requests.lastIndex]
        }
        assertEquals(101, rows.size)
        assertEquals(2, requests.size)
        assertEquals(null, requests.first())
        assertEquals("100", rows.last()["id"].toString())
    }

    @Test(expected = IOException::class)
    fun `a foreign continuation fails instead of publishing a truncated profile`() = runBlocking {
        SoundCloudPlaylistImporter.publicUserRows("12", "likes") {
            Json.parseToJsonElement("""{"collection":[{"track":{}}],"next_href":"https://other.example/page"}""").jsonObject
        }
        Unit
    }

    @Test(expected = IOException::class)
    fun `a repeated page fails instead of looping or returning a partial import`() = runBlocking {
        SoundCloudPlaylistImporter.publicUserRows("12", "likes") {
            Json.parseToJsonElement("""{"collection":[],"next_href":"https://api-v2.soundcloud.com/users/12/likes?offset=100"}""").jsonObject
        }
        Unit
    }

    @Test(expected = CancellationException::class)
    fun `cancellation never becomes an empty successful collection`() = runBlocking {
        SoundCloudPlaylistImporter.publicUserRows("12", "playlists") { throw CancellationException("test cancellation") }
        Unit
    }

    @Test
    fun `profile links reject credentials and nonstandard ports`() {
        assertTrue(SoundCloudPlaylistImporter.isPlaylistLink("https://soundcloud.com/artist"))
        assertFalse(SoundCloudPlaylistImporter.isPlaylistLink("https://name@soundcloud.com/artist"))
        assertFalse(SoundCloudPlaylistImporter.isPlaylistLink("https://soundcloud.com:444/artist"))
    }
}
