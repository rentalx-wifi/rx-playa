package com.rentalx.playa.data.soundcloud

import com.rentalx.playa.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SoundCloudAccount(val id: String, val name: String, val avatarUrl: String?)

internal data class SoundCloudOfficialStream(
    val url: String,
    val bitrateKbps: Int,
    val mimeType: String,
    val durationMs: Long,
)

/** User-scoped official API. Public mode remains in [SoundCloudApi]. */
object SoundCloudAccountApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun me(): SoundCloudAccount {
        val root = get(api("me")).jsonObject
        val id = root.text("urn") ?: root.text("id") ?: throw IOException("SoundCloud profile has no id")
        val name = root.text("username")?.takeIf(String::isNotBlank) ?: "SoundCloud"
        return SoundCloudAccount(id, name, root.text("avatar_url"))
    }

    suspend fun likedTracks(limit: Int = 2_000): List<SoundCloudImportTrack> = withContext(Dispatchers.IO) {
        val rows = LinkedHashMap<String, SoundCloudImportTrack>()
        var next: HttpUrl? = api("me/likes/tracks").newBuilder()
            .addQueryParameter("linked_partitioning", "true")
            .addQueryParameter("limit", "200")
            .build()
        val seen = HashSet<String>()
        while (next != null && rows.size < limit && seen.add(next.toString())) {
            currentCoroutineContext().ensureActive()
            val root = get(next).jsonObject
            root["collection"]?.jsonArray.orEmpty().forEach { element ->
                (element as? JsonObject)?.toImportTrack()?.let { track ->
                    val key = track.sourceUrn ?: track.sourceUrl ?: return@let
                    rows.putIfAbsent(key, track)
                }
            }
            next = root.text("next_href")?.toHttpUrlOrNullTrusted()
        }
        rows.values.take(limit)
    }

    suspend fun playlists(limit: Int = 500): List<SoundCloudImportPlaylist> = withContext(Dispatchers.IO) {
        val result = ArrayList<SoundCloudImportPlaylist>()
        var next: HttpUrl? = api("me/playlists").newBuilder()
            .addQueryParameter("show_tracks", "true")
            .addQueryParameter("linked_partitioning", "true")
            .addQueryParameter("limit", "100")
            .build()
        val seen = HashSet<String>()
        while (next != null && result.size < limit && seen.add(next.toString())) {
            currentCoroutineContext().ensureActive()
            val root = get(next).jsonObject
            root["collection"]?.jsonArray.orEmpty().forEach { element ->
                val playlist = element as? JsonObject ?: return@forEach
                val tracks = (playlist["tracks"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.toImportTrack() }
                result += SoundCloudImportPlaylist(
                    playlist.text("title").orEmpty().ifBlank { "SoundCloud playlist" },
                    tracks,
                    playlist.text("urn"),
                )
            }
            next = root.text("next_href")?.toHttpUrlOrNullTrusted()
        }
        result.take(limit)
    }

    /** Loads every account-visible row only when a personal playlist is selected. */
    suspend fun hydratePlaylist(playlist: SoundCloudImportPlaylist): SoundCloudImportPlaylist {
        val urn = playlist.sourceUrn?.takeIf { it.startsWith("soundcloud:playlists:") }
            ?: return playlist
        return withContext(Dispatchers.IO) {
            val tracks = LinkedHashMap<String, SoundCloudImportTrack>()
            var next: HttpUrl? = api("playlists").newBuilder()
                .addPathSegment(urn)
                .addPathSegment("tracks")
                .addQueryParameter("linked_partitioning", "true")
                .build()
            val seen = HashSet<String>()
            while (next != null && seen.add(next.toString())) {
                currentCoroutineContext().ensureActive()
                val response = get(next)
                val root = response as? JsonObject
                val rows = if (root != null) root["collection"] as? JsonArray else response as? JsonArray
                rows.orEmpty().forEach { element ->
                    (element as? JsonObject)?.toImportTrack()?.let { track ->
                        tracks.putIfAbsent(track.sourceUrn ?: track.sourceUrl.orEmpty(), track)
                    }
                }
                next = root?.text("next_href")?.toHttpUrlOrNullTrusted()
            }
            playlist.copy(tracks = tracks.values.toList().ifEmpty { playlist.tracks })
        }
    }

    suspend fun setLiked(trackUrn: String, liked: Boolean) {
        require(trackUrn.startsWith("soundcloud:tracks:")) { "Invalid SoundCloud track id" }
        val url = api("likes/tracks").newBuilder().addPathSegment(trackUrn).build()
        execute(url, if (liked) "POST" else "DELETE")
    }

    internal suspend fun officialStream(sourceUrl: String): SoundCloudOfficialStream? {
        if (SoundCloudOAuth.accessToken() == null) return null
        val track = get(api("resolve").newBuilder().addQueryParameter("url", sourceUrl).build()).jsonObject
        val urn = track.text("urn") ?: return null
        if (!urn.startsWith("soundcloud:tracks:")) return null
        val access = track.text("access")
        if (access != null && access !in setOf("playable", "preview")) return null
        val streamsUrl = api("tracks").newBuilder()
            .addPathSegment(urn)
            .addPathSegment("streams")
            .apply { track.text("secret_token")?.let { addQueryParameter("secret_token", it) } }
            .build()
        val streams = get(streamsUrl).jsonObject
        val duration = track.number("duration") ?: 0L
        val candidates = listOf(
            Triple("hls_aac_160_url", 160, "audio/mp4"),
            Triple("hls_mp3_128_url", 128, "audio/mpeg"),
            Triple("preview_mp3_128_url", 128, "audio/mpeg"),
        )
        return candidates.firstNotNullOfOrNull { (key, rate, mime) ->
            streams.text(key)?.takeIf(::isTrustedSoundCloudMediaUrl)?.let {
                SoundCloudOfficialStream(it, rate, mime, duration)
            }
        }
    }

    private suspend fun get(url: HttpUrl) = execute(url, "GET")

    private suspend fun execute(url: HttpUrl, method: String): kotlinx.serialization.json.JsonElement =
        withContext(Dispatchers.IO) {
            fun request(token: String) = Request.Builder().url(url)
                .header("Accept", "application/json; charset=utf-8")
                .header("Authorization", "OAuth $token")
                .method(method, if (method == "GET") null else ByteArray(0).toRequestBody(null))
                .build()
            var token = SoundCloudOAuth.accessToken() ?: throw IOException("SoundCloud is not connected")
            var response = client.newCall(request(token)).execute()
            if (response.code == 401) {
                response.close()
                token = SoundCloudOAuth.refreshAccessToken() ?: throw IOException("SoundCloud session expired")
                response = client.newCall(request(token)).execute()
            }
            response.use {
                if (!it.isSuccessful) throw IOException("SoundCloud account request failed (${it.code})")
                val body = it.body.string()
                if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
            }
        }

    private fun api(path: String): HttpUrl = API_BASE.toHttpUrl().newBuilder().addPathSegments(path).build()

    private fun JsonObject.toImportTrack(): SoundCloudImportTrack? {
        val title = text("title")?.takeIf(String::isNotBlank) ?: return null
        val user = this["user"] as? JsonObject
        val source = text("permalink_url") ?: return null
        return SoundCloudImportTrack(
            title = title,
            artist = text("metadata_artist")?.takeIf(String::isNotBlank)
                ?: user?.text("username")?.takeIf(String::isNotBlank)
                ?: "SoundCloud",
            durationMs = number("duration") ?: 0L,
            coverUrl = (text("artwork_url") ?: user?.text("avatar_url"))
                ?.replace("-large.", "-t500x500."),
            sourceUrl = source,
            sourceUrn = text("urn") ?: text("id")?.let { "soundcloud:tracks:$it" },
        )
    }

    private fun String.toHttpUrlOrNullTrusted(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
        ?.takeIf { it.isHttps && it.host == "api.soundcloud.com" }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

    private fun JsonObject.number(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private const val API_BASE = "https://api.soundcloud.com/"
}
