package com.music.bitchord

import com.music.bitchord.auth.YandexOAuthResult
import com.music.bitchord.auth.parseYandexOAuthRedirect
import com.music.bitchord.auth.youtubeMusicWebUserAgent
import com.music.bitchord.data.yandex.YandexMusicApi
import com.music.bitchord.data.yandex.YandexPlaylistReference
import com.music.bitchord.data.yandex.extractYandexPlaylistReference
import com.music.bitchord.data.yandex.YnisonSuppression
import com.music.bitchord.data.yandex.parseYnisonPlaybackState
import com.music.bitchord.data.soundcloud.SoundCloudPlaylistImporter
import com.music.bitchord.data.soundcloud.soundCloudClientId
import com.music.bitchord.data.soundcloud.soundCloudScriptUrls
import com.music.bitchord.data.soundcloud.SoundCloudImportTrack
import com.music.bitchord.data.soundcloud.SoundCloudPlayback
import com.music.bitchord.data.soundcloud.isTrustedSoundCloudApiUrl
import com.music.bitchord.data.soundcloud.isTrustedSoundCloudMediaUrl
import com.music.bitchord.data.soundcloud.isTrustedSoundCloudSearchContinuation
import com.music.bitchord.data.soundcloud.isSoundCloudSearchResult
import com.music.bitchord.data.soundcloud.parseSoundCloudPlaybackMetadata
import com.music.bitchord.data.soundcloud.searchContinuationOffset
import com.music.bitchord.data.soundcloud.soundCloudBitrateKbps
import com.music.bitchord.data.soundcloud.soundCloudTranscodingRank
import com.music.bitchord.data.soundcloud.synthesizeSoundCloudManifest
import com.music.bitchord.data.soundcloud.toSearchSong
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.isSpotifySourceTrack
import com.music.bitchord.data.spotify.spotifyWebUrl
import com.music.bitchord.data.spotify.toSourceSong
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexMusicIntegrationTest {
    @Test
    fun `playlist links accept classic and uuid Yandex formats only`() {
        assertEquals(
            YandexPlaylistReference.UserPlaylist("listener", "42"),
            extractYandexPlaylistReference("https://music.yandex.ru/users/listener/playlists/42?utm_source=share"),
        )
        assertEquals(
            YandexPlaylistReference.Uuid("abcd-1234"),
            extractYandexPlaylistReference("https://music.yandex.ru/playlists/abcd-1234"),
        )
        assertNull(extractYandexPlaylistReference("https://evil.example/users/a/playlists/1"))
    }

    @Test
    fun `YouTube login uses desktop Chromium and blocks mobile app redirect cause`() {
        val userAgent = youtubeMusicWebUserAgent(
            "Mozilla/5.0 (Linux; Android 16; Pixel; wv) AppleWebKit/537.36 Chrome/142.0.1.2 Mobile Safari/537.36",
        )
        assertTrue(userAgent.contains("X11; Linux x86_64"))
        assertTrue(userAgent.contains("Chrome/142.0.1.2"))
        assertFalse(userAgent.contains(" wv"))
        assertFalse(userAgent.contains(" Mobile"))
    }

    @Test
    fun `SoundCloud importer accepts only SoundCloud web links`() {
        assertTrue(SoundCloudPlaylistImporter.isPlaylistLink("https://soundcloud.com/artist/sets/list"))
        assertTrue(SoundCloudPlaylistImporter.isPlaylistLink("https://on.soundcloud.com/abc"))
        assertFalse(SoundCloudPlaylistImporter.isPlaylistLink("https://soundcloud.example/artist/sets/list"))
    }

    @Test
    fun `SoundCloud bootstrap finds official asset scripts and public client id`() {
        val html = """
            <html><body>
              <script src="https://a-v2.sndcdn.com/assets/app-a.js"></script>
              <script src="//a-v2.sndcdn.com/assets/app-b.js"></script>
              <script src="https://evil.example/assets/app-c.js"></script>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://a-v2.sndcdn.com/assets/app-a.js",
                "https://a-v2.sndcdn.com/assets/app-b.js",
            ),
            soundCloudScriptUrls(html),
        )
        assertEquals(
            "abcdefghijklmnopqrstuvwxyz123456",
            soundCloudClientId("x,client_id:\"abcdefghijklmnopqrstuvwxyz123456\",y"),
        )
        assertNull(soundCloudClientId("const client_id = null"))
    }

    @Test
    fun `SoundCloud metadata becomes a direct source row with a trusted page URL`() {
        val page = "https://soundcloud.com/artist/track"
        val song = SoundCloudImportTrack(
            title = "Track",
            artist = "Artist",
            durationMs = 123_000,
            coverUrl = "https://i1.sndcdn.com/art.jpg",
            sourceUrl = page,
        ).toSearchSong(0)
        assertTrue(song.isSoundCloudSearchResult)
        assertEquals(page, song.playbackSourceId)
        assertEquals("2:03", song.durationText)
        assertTrue(SoundCloudPlayback.isTrustedSoundCloudUrl(page))
        assertFalse(SoundCloudPlayback.isTrustedSoundCloudUrl("https://soundcloud.com.evil.example/a/b"))
    }

    @Test
    fun `SoundCloud playback accepts 2026 AAC HLS including 96k fallback`() {
        val metadata = parseSoundCloudPlaybackMetadata(
            Json.parseToJsonElement(
                """
                {
                  "kind":"track", "streamable":true, "policy":"ALLOW", "duration":180000,
                  "track_authorization":"public-token",
                  "media":{"transcodings":[
                    {"preset":"aac_96k","url":"https://api-media.soundcloud.com/media/96/stream/hls",
                     "format":{"protocol":"hls","mime_type":"audio/mp4; codecs=\"mp4a.40.2\""}},
                    {"preset":"aac_160k","url":"https://api-media.soundcloud.com/media/160/stream/hls",
                     "format":{"protocol":"hls","mime_type":"audio/mp4; codecs=\"mp4a.40.2\""}},
                    {"preset":"encrypted","url":"https://api-media.soundcloud.com/private",
                     "format":{"protocol":"encrypted_hls","mime_type":"audio/mp4"}},
                    {"preset":"evil","url":"https://soundcloud.com.evil.example/stream",
                     "format":{"protocol":"hls","mime_type":"audio/mp4"}}
                  ]}
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals("public-token", metadata.trackAuthorization)
        assertEquals(180_000L, metadata.durationMs)
        assertEquals(listOf("aac_160k", "aac_96k"), metadata.transcodings
            .sortedBy(::soundCloudTranscodingRank)
            .map { it.preset })
        assertEquals(160, soundCloudBitrateKbps("aac_160k"))
        assertEquals(96, soundCloudBitrateKbps("aac_96k"))
    }

    @Test
    fun `SoundCloud stream hosts reject lookalikes`() {
        assertTrue(isTrustedSoundCloudApiUrl("https://api-media.soundcloud.com/media/id/stream/hls"))
        assertTrue(isTrustedSoundCloudMediaUrl("https://playback.media-streaming.soundcloud.cloud/id/aac_96k/list.m3u8"))
        assertTrue(isTrustedSoundCloudMediaUrl("https://cf-hls-media.sndcdn.com/list.m3u8"))
        assertFalse(isTrustedSoundCloudApiUrl("https://api-media.soundcloud.com.evil.example/stream"))
        assertFalse(isTrustedSoundCloudMediaUrl("https://soundcloud.cloud.evil.example/stream"))
    }

    @Test
    fun `SoundCloud stalled manifest can be rebuilt from its first signed fragment`() {
        val query = "?expires=123&Policy=public&Signature=sig&Key-Pair-Id=key"
        val manifest = synthesizeSoundCloudManifest(
            initUrl = "https://playback.media-streaming.soundcloud.cloud/path/init.mp4$query",
            firstSegmentUrl = "https://playback.media-streaming.soundcloud.cloud/path/data000.m4s$query",
            durationMs = 25_500L,
        )
        assertTrue(manifest.startsWith("#EXTM3U\n"))
        assertTrue(manifest.contains("data000.m4s$query"))
        assertTrue(manifest.contains("data001.m4s$query"))
        assertTrue(manifest.contains("data002.m4s$query"))
        assertFalse(manifest.contains("data003.m4s$query"))
        assertTrue(manifest.contains("#EXTINF:5.500,"))
        assertTrue(manifest.endsWith("#EXT-X-ENDLIST\n"))
    }

    @Test
    fun `SoundCloud search continuation keeps server cursor and rejects lookalikes`() {
        val next = "https://api-v2.soundcloud.com/search/tracks?query_urn=abc&limit=50&offset=50&q=test"
        assertTrue(isTrustedSoundCloudSearchContinuation(next))
        assertEquals(50, searchContinuationOffset(next))
        assertFalse(isTrustedSoundCloudSearchContinuation("https://api-v2.soundcloud.com.evil.example/search/tracks?offset=50"))
        assertFalse(isTrustedSoundCloudSearchContinuation("https://api-v2.soundcloud.com/resolve?offset=50"))
    }

    @Test
    fun `SoundCloud search identity is stable across pagination positions`() {
        val track = SoundCloudImportTrack(
            title = "Track",
            artist = "Artist",
            durationMs = 120_000,
            coverUrl = null,
            sourceUrl = "https://soundcloud.com/artist/track",
        )
        assertEquals(track.toSearchSong(0).videoId, track.toSearchSong(49).videoId)
    }

    @Test
    fun `SoundCloud official urn becomes the rating identity`() {
        val track = SoundCloudImportTrack(
            title = "Track",
            artist = "Artist",
            durationMs = 120_000,
            coverUrl = null,
            sourceUrl = "https://soundcloud.com/artist/track",
            sourceUrn = "soundcloud:tracks:12345",
        )
        assertEquals("soundcloud:tracks:12345", track.toSearchSong(0).videoId)
    }

    @Test
    fun `SoundCloud manifest keeps measured duration and nonzero first segment`() {
        val query = "?Policy=wildcard"
        val manifest = synthesizeSoundCloudManifest(
            initUrl = "https://playback.media-streaming.soundcloud.cloud/path/init.mp4$query",
            firstSegmentUrl = "https://playback.media-streaming.soundcloud.cloud/path/data007.m4s$query",
            durationMs = 20_100L,
            segmentDurationMs = 10_008L,
        )
        assertTrue(manifest.contains("data007.m4s$query"))
        assertTrue(manifest.contains("data008.m4s$query"))
        assertTrue(manifest.contains("data009.m4s$query"))
        assertFalse(manifest.contains("data010.m4s$query"))
        assertTrue(manifest.contains("#EXTINF:10.008,"))
        assertTrue(manifest.contains("#EXTINF:0.084,"))
    }

    @Test
    fun `Spotify rows remain Spotify actions instead of automatic YouTube replacements`() {
        val song = SpotifyTrack(
            id = "spotify-id",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000,
            imageUrl = null,
        ).toSourceSong()
        assertTrue(song.isSpotifySourceTrack)
        assertEquals("https://open.spotify.com/track/spotify-id", song.spotifyWebUrl())
        assertEquals("Spotify", song.playbackSource)
    }

    @Test
    fun `oauth token is read only from trusted redirect fragment`() {
        val result = parseYandexOAuthRedirect(
            "https://music.yandex.ru/#access_token=y0_test%2Dtoken&token_type=bearer",
        )
        assertEquals("y0_test-token", (result as YandexOAuthResult.Token).value)
        assertTrue(
            parseYandexOAuthRedirect(
                "https://oauth.yandex.ru/verification_code#access_token=oauth-host-token",
            ) is YandexOAuthResult.Token,
        )
        assertNull(parseYandexOAuthRedirect("https://evil.example/#access_token=stolen"))
        assertNull(parseYandexOAuthRedirect("https://music.yandex.ru.evil.example/#access_token=stolen"))
    }

    @Test
    fun `ynison state exposes live track and timing`() {
        val state = parseYnisonPlaybackState(
            """
            {
              "active_device_id_optional":"phone",
              "player_state":{
                "status":{
                  "progress_ms":"42000","duration_ms":"180000","paused":false,
                  "playback_speed":1.0,"version":{"timestamp_ms":"100000"}
                },
                "player_queue":{
                  "current_playable_index":0,
                  "playable_list":[{
                    "playable_id":"123","album_id_optional":"456","playable_type":"TRACK",
                    "title":"Fallback","cover_url_optional":"avatars.yandex.net/image/%%"
                  }]
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(YnisonSuppression.NONE, state.suppression)
        assertEquals("123", state.playable?.trackId)
        assertEquals("456", state.playable?.albumId)
        assertEquals(47_000L, state.playable?.positionAt(105_000L))
        assertEquals("https://avatars.yandex.net/image/1000x1000", state.playable?.fallbackCoverUrl)
    }

    @Test
    fun `ynison advertisement suppresses the queued song`() {
        val state = parseYnisonPlaybackState(
            """
            {
              "activeDeviceIdOptional":"phone",
              "playerState":{
                "status":{"paused":false},
                "playerQueueInjectOptional":{"playable":{"playableId":"ad-1","playableType":"AD"}},
                "playerQueue":{"currentPlayableIndex":0,"playableList":[
                  {"playableId":"123","playableType":"TRACK"}
                ]}
              }
            }
            """.trimIndent(),
        )
        assertEquals(YnisonSuppression.ADVERTISEMENT, state.suppression)
        assertNull(state.playable)
    }

    @Test
    fun `rest client validates free account and resolves metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"result":{"account":{"uid":42,"displayName":"Listener"},"plus":{"hasPlus":false}}}""",
            ))
            server.enqueue(MockResponse().setBody(
                """{"result":[{"id":"123","title":"Track","available":true,"availableForPremiumUsers":true,"availableFullWithoutPermission":false,"durationMs":180000,"coverUri":"avatars.yandex.net/c/%%","artists":[{"name":"Artist"}],"albums":[{"id":"456","title":"Album"}]}]}""",
            ))
            server.enqueue(MockResponse().setBody(
                """{"result":[{"uid":42,"kind":7,"title":"My playlist","trackCount":1,"cover":{"uri":"avatars.yandex.net/p/%%"}}]}""",
            ))
            server.enqueue(MockResponse().setBody(
                """{"result":{"tracks":[{"id":"123","albumId":"456","track":{"id":"123","title":"Track","durationMs":180000,"coverUri":"avatars.yandex.net/c/%%","artists":[{"name":"Artist"}],"albums":[{"id":"456","title":"Album"}]}}]}}""",
            ))
            val api = YandexMusicApi(OkHttpClient(), server.url("/"))

            val account = api.account("secret")
            val track = api.track("secret", "123", "456")
            val playlists = api.playlists("secret", account.uid)
            val playlistTracks = api.playlistTracks("secret", playlists.single())

            assertEquals("42", account.uid)
            assertEquals("Listener", account.displayName)
            assertFalse(account.hasPlus)
            assertTrue(track.premiumOnly)
            assertTrue(track.isUsable())
            assertEquals("Artist", track.artist)
            assertEquals("Album", track.albumTitle)
            assertEquals("https://avatars.yandex.net/c/1000x1000", track.coverUrl)
            assertEquals("My playlist", playlists.single().title)
            assertEquals("Track", playlistTracks.single().title)
            assertEquals("OAuth secret", server.takeRequest().headers["Authorization"])
            assertEquals("track-ids=123%3A456&with-positions=true", server.takeRequest().body.readUtf8())
            assertEquals("/users/42/playlists/list", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/users/42/playlists/7", server.takeRequest().requestUrl?.encodedPath)
        }
    }
}
