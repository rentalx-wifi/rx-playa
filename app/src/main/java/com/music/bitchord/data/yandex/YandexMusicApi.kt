package com.music.bitchord.data.yandex

import com.music.bitchord.data.Http
import com.music.bitchord.data.importing.ExternalPlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Minimal account information needed by the connection screen. */
data class YandexMusicAccount(
    val uid: String,
    val displayName: String,
    val hasPlus: Boolean,
)

data class YandexMusicPlaylist(
    val uid: String,
    val kind: String,
    val title: String,
    val trackCount: Int,
    val coverUrl: String?,
)

data class YandexMusicPlaylistTrack(
    val id: String,
    override val title: String,
    override val artist: String,
    override val album: String?,
    override val durationMs: Long,
    override val coverUrl: String?,
    val artistNames: List<String> = emptyList(),
) : ExternalPlaylistTrack

data class YandexLikedTrack(val track: YandexMusicPlaylistTrack, val likedAtMs: Long?)
data class YandexLikedArtist(val name: String, val likedAtMs: Long?)
data class YandexHistoryTrack(val track: YandexMusicPlaylistTrack, val listenedDayMs: Long?)

sealed interface YandexPlaylistReference {
    data class UserPlaylist(val userId: String, val kind: String) : YandexPlaylistReference
    data class Uuid(val value: String) : YandexPlaylistReference
}

/** Accepts both classic `/users/{owner}/playlists/{kind}` and UUID playlist links. */
fun extractYandexPlaylistReference(input: String): YandexPlaylistReference? {
    val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase().orEmpty()
    if (!host.startsWith("music.yandex.")) return null
    val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty).map {
        runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
    }
    val users = segments.indexOf("users")
    val playlists = segments.indexOf("playlists")
    if (users >= 0 && playlists == users + 2 && playlists + 1 < segments.size) {
        val user = segments[users + 1].takeIf(String::isNotBlank) ?: return null
        val kind = segments[playlists + 1].takeIf(String::isNotBlank) ?: return null
        return YandexPlaylistReference.UserPlaylist(user, kind)
    }
    if (playlists >= 0 && playlists + 1 < segments.size) {
        return segments[playlists + 1].takeIf(String::isNotBlank)?.let { YandexPlaylistReference.Uuid(it) }
    }
    val singular = segments.indexOf("playlist")
    if (singular >= 0 && singular + 1 < segments.size) {
        return segments[singular + 1].takeIf(String::isNotBlank)?.let { YandexPlaylistReference.Uuid(it) }
    }
    return null
}

/** Metadata used by Rich Presence; no stream URL is requested. */
data class YandexMusicTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val albumId: String?,
    val albumTitle: String?,
    val coverUrl: String?,
    val durationMs: Long,
    val available: Boolean,
    val premiumOnly: Boolean,
    val error: String?,
) {
    val artist: String get() = artists.joinToString(", ")

    fun isUsable(): Boolean =
        title.isNotBlank() && artist.isNotBlank() && available && error.isNullOrBlank()

    fun webUrl(): String = if (!albumId.isNullOrBlank()) {
        "https://music.yandex.ru/album/$albumId/track/$id"
    } else {
        "https://music.yandex.ru/track/$id"
    }
}

sealed class YandexMusicApiException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Unauthorized : YandexMusicApiException("Yandex Music rejected the OAuth token")
    class AccessDenied : YandexMusicApiException("Yandex Music denied access to this resource")
    class EmptyResult : YandexMusicApiException("Yandex Music returned an empty result")
    class Http(val status: Int) : YandexMusicApiException("Yandex Music returned HTTP $status")
    class InvalidResponse(cause: Throwable? = null) :
        YandexMusicApiException("Yandex Music returned an invalid response", cause)
}

/**
 * Lightweight client for the two REST calls the integration needs.
 *
 * Playback state itself does not come from the legacy `/queues` endpoint: that
 * endpoint can retain a stale queue after playback has stopped. Ynison supplies
 * the live index, pause state and clock; this client resolves the selected id to
 * catalogue metadata and validates the OAuth token through `/account/status`.
 */
class YandexMusicApi internal constructor(
    private val client: OkHttpClient = Http.client,
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
) {
    suspend fun account(token: String): YandexMusicAccount {
        val request = requestBuilder("account/status", token).get().build()
        val result = execute(request).resultObject()
        val account = result.objectAt("account") ?: throw YandexMusicApiException.EmptyResult()
        val uid = account.stringAt("uid")?.takeIf { it.isNotBlank() }
            ?: throw YandexMusicApiException.EmptyResult()
        val displayName = account.stringAt("displayName", "display_name")?.takeIf { it.isNotBlank() }
            ?: account.stringAt("fullName", "full_name")?.takeIf { it.isNotBlank() }
            ?: account.stringAt("login")?.takeIf { it.isNotBlank() }
            ?: result.stringAt("defaultEmail", "default_email")?.takeIf { it.isNotBlank() }
            ?: throw YandexMusicApiException.EmptyResult()
        val hasPlus = result.objectAt("plus")?.booleanAt("hasPlus", "has_plus") ?: false
        return YandexMusicAccount(uid = uid, displayName = displayName, hasPlus = hasPlus)
    }

    /** User-owned playlists are metadata and remain readable without Plus. */
    suspend fun playlists(token: String, userId: String): List<YandexMusicPlaylist> {
        val request = requestBuilder("users/$userId/playlists/list", token).get().build()
        return execute(request).resultArray().mapNotNull { element ->
            val playlist = element as? JsonObject ?: return@mapNotNull null
            parsePlaylist(playlist, fallbackUid = userId)
        }
    }

    /** Full playlist contents, in the order stored by Yandex Music. */
    suspend fun playlistTracks(token: String, playlist: YandexMusicPlaylist): List<YandexMusicPlaylistTrack> {
        val request = requestBuilder(listOf("users", playlist.uid, "playlists", playlist.kind), token).get().build()
        val result = execute(request).resultObject()
        return parsePlaylistTracks(result)
    }

    suspend fun playlistByReference(
        token: String,
        reference: YandexPlaylistReference,
    ): Pair<YandexMusicPlaylist, List<YandexMusicPlaylistTrack>> {
        val request = when (reference) {
            is YandexPlaylistReference.UserPlaylist -> requestBuilder(
                listOf("users", reference.userId, "playlists", reference.kind),
                token,
            )
            is YandexPlaylistReference.Uuid -> requestBuilder(listOf("playlist", reference.value), token)
        }.get().build()
        val result = execute(request).resultObject()
        val fallbackUid = (reference as? YandexPlaylistReference.UserPlaylist)?.userId.orEmpty()
        val playlist = parsePlaylist(result, fallbackUid) ?: YandexMusicPlaylist(
            uid = fallbackUid,
            kind = (reference as? YandexPlaylistReference.UserPlaylist)?.kind.orEmpty(),
            title = result.stringAt("title")?.takeIf(String::isNotBlank)
                ?: throw YandexMusicApiException.EmptyResult(),
            trackCount = result.longAt("trackCount", "track_count")?.toInt() ?: 0,
            coverUrl = result.stringAt("ogImage", "og_image").toArtworkUrl(),
        )
        return playlist to parsePlaylistTracks(result)
    }

    private fun parsePlaylistTracks(result: JsonObject): List<YandexMusicPlaylistTrack> =
        result.arrayAt("tracks").orEmpty().mapNotNull { (it as? JsonObject)?.toImportTrack() }

    /** The complete liked-id list; metadata is fetched in bounded batches, never audio. */
    suspend fun likedTracks(token: String, userId: String): List<YandexLikedTrack> {
        val cached = likedLibraries[userId]
        val url = baseUrl.newBuilder().apply {
            listOf("users", userId, "likes", "tracks").forEach(::addPathSegment)
            // The revision already in hand, so an unchanged library is one round
            // trip instead of a full listing and dozens of metadata batches —
            // which is what every Home opening used to cost.
            addQueryParameter("if-modified-since-revision", (cached?.revision ?: 0L).toString())
        }.build()
        val response = try {
            execute(requestBuilder(listOf("users", userId, "likes", "tracks"), token).url(url).get().build())
        } catch (unchanged: YandexMusicApiException.EmptyResult) {
            // 202 with no body: the revision in the request is the current one.
            // Nothing changed, so the list from last time is the answer.
            return cached?.tracks ?: throw unchanged
        }
        val library = response.resultObject().objectAt("library")
            ?: throw YandexMusicApiException.InvalidResponse()
        val entries = library.arrayAt("tracks") ?: throw YandexMusicApiException.InvalidResponse()
        val likes = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.stringAt("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val composite = entry.stringAt("albumId", "album_id")?.takeIf(String::isNotBlank)?.let { "$id:$it" } ?: id
            Triple(id, composite, entry.stringAt("timestamp").toYandexTimestamp())
        }
        val revision = library.longAt("revision")
        if (likes.isEmpty()) {
            if (revision != null) likedLibraries[userId] = CachedLikes(revision, emptyList())
            return emptyList()
        }
        val metadata = tracksMetadata(token, likes.map { it.second }).associateBy { it.id }
        if (metadata.isEmpty()) throw YandexMusicApiException.EmptyResult()
        val tracks = likes.mapNotNull { (id, _, at) -> metadata[id]?.let { YandexLikedTrack(it, at) } }
        // Kept even when the revision was not stated: the cache then re-asks from
        // zero, which is what every call did before this existed.
        likedLibraries[userId] = CachedLikes(revision, tracks)
        return tracks
    }

    /** A liked library as it stood at [revision], for as long as this process lives. */
    private data class CachedLikes(val revision: Long?, val tracks: List<YandexLikedTrack>)

    suspend fun likedArtists(token: String, userId: String): List<YandexLikedArtist> {
        val url = baseUrl.newBuilder().apply {
            listOf("users", userId, "likes", "artists").forEach(::addPathSegment)
            addQueryParameter("with-timestamps", "true")
        }.build()
        return execute(requestBuilder(listOf("users", userId, "likes", "artists"), token)
            .url(url).get().build()).resultArray().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val artist = entry.objectAt("artist") ?: entry
            val name = artist.stringAt("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            YandexLikedArtist(name, entry.stringAt("timestamp").toYandexTimestamp())
        }
    }

    /** Service-returned history days; a history entry is not a measured completed-play counter. */
    suspend fun historyTracks(token: String): List<YandexHistoryTrack> {
        val url = baseUrl.newBuilder().addPathSegment("music-history")
            .addQueryParameter("fullModelsCount", "100").build()
        val result = execute(requestBuilder("music-history", token).url(url).get().build()).resultObject()
        val tabs = result.arrayAt("historyTabs", "history_tabs") ?: throw YandexMusicApiException.InvalidResponse()
        val entries = mutableListOf<Triple<String, String, Long?>>()
        val metadata = mutableMapOf<String, YandexMusicPlaylistTrack>()
        tabs.forEach { element ->
            val tab = element as? JsonObject ?: return@forEach
            val day = tab.stringAt("date")?.let { raw -> runCatching {
                LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() }
            tab.arrayAt("items").orEmpty().forEach { group ->
                (group as? JsonObject)?.arrayAt("tracks").orEmpty().forEach trackLoop@ { item ->
                    val trackItem = item as? JsonObject ?: return@trackLoop
                    if (!trackItem.stringAt("type").equals("track", ignoreCase = true)) return@trackLoop
                    val data = trackItem.objectAt("data") ?: return@trackLoop
                    val identity = data.objectAt("itemId", "item_id") ?: return@trackLoop
                    val id = identity.stringAt("trackId", "track_id") ?: return@trackLoop
                    val composite = identity.stringAt("albumId", "album_id")?.let { "$id:$it" } ?: id
                    entries += Triple(id, composite, day)
                    data.objectAt("fullModel", "full_model")?.toImportTrack()?.let { metadata[id] = it }
                }
            }
        }
        metadata.putAll(tracksMetadata(token, entries.filter { it.first !in metadata }.map { it.second }).associateBy { it.id })
        return entries.mapNotNull { (id, _, day) -> metadata[id]?.let { YandexHistoryTrack(it, day) } }
    }

    suspend fun tracksMetadata(token: String, ids: List<String>): List<YandexMusicPlaylistTrack> {
        val tracks = mutableListOf<YandexMusicPlaylistTrack>()
        for (batch in ids.distinct().chunked(100)) {
            currentCoroutineContext().ensureActive()
            val body = FormBody.Builder().add("track-ids", batch.joinToString(","))
                .add("with-positions", "true").build()
            tracks += execute(requestBuilder("tracks", token).post(body).build()).resultArray()
                .mapNotNull { (it as? JsonObject)?.toImportTrack() }
        }
        return tracks
    }

    /** Catalogue search is metadata-only and works for free accounts. */
    suspend fun searchTracks(token: String, query: String, limit: Int = 25): List<YandexMusicPlaylistTrack> {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("text", query.trim())
            .addQueryParameter("type", "track")
            .addQueryParameter("page", "0")
            .addQueryParameter("nocorrect", "false")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $token")
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept-Language", "ru")
            .header("Accept", "application/json")
            .get()
            .build()
        val tracks = execute(request).resultObject().objectAt("tracks")?.arrayAt("results").orEmpty()
        return tracks.mapNotNull { (it as? JsonObject)?.toImportTrack() }.take(limit.coerceAtLeast(1))
    }

    /**
     * Catalogue search without an account.
     *
     * The public API answers `/search` and `/playlist/{uuid}` for anonymous
     * callers, which is why the Yandex source no longer has to refuse a listener
     * who has not signed in. Streams still need a token, so callers match these
     * rows to a playable recording the same way every other external provider
     * does.
     */
    suspend fun searchTracksAnonymous(query: String, limit: Int = 25): List<YandexMusicPlaylistTrack> {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("text", query.trim())
            .addQueryParameter("type", "track")
            .addQueryParameter("page", "0")
            .addQueryParameter("nocorrect", "false")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept-Language", "ru")
            .header("Accept", "application/json")
            .get()
            .build()
        val tracks = execute(request).resultObject().objectAt("tracks")?.arrayAt("results").orEmpty()
        return tracks.mapNotNull { (it as? JsonObject)?.toImportTrack() }.take(limit.coerceAtLeast(1))
    }

    /** The public chart, readable without a token; used as an anonymous front page. */
    suspend fun chartTracks(limit: Int = 100): List<YandexMusicPlaylistTrack> {
        val landing = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("landing3").addPathSegment("chart").build())
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept", "application/json")
            .get()
            .build()
        val uuid = execute(landing).resultObject()
            .objectAt("chart")
            ?.stringAt("playlistUuid", "playlist_uuid")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return playlistAnonymous(uuid, limit)
    }

    /** Any public playlist by uuid, again without credentials. */
    suspend fun playlistAnonymous(uuid: String, limit: Int = 100): List<YandexMusicPlaylistTrack> {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment("playlist").addPathSegment(uuid).build())
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept-Language", "ru")
            .header("Accept", "application/json")
            .get()
            .build()
        val tracks = execute(request).resultObject().arrayAt("tracks").orEmpty()
        return tracks.mapNotNull { row ->
            val entry = row as? JsonObject ?: return@mapNotNull null
            (entry.objectAt("track") ?: entry).toImportTrack()
        }.take(limit.coerceAtLeast(1))
    }

    private fun JsonObject.toImportTrack(): YandexMusicPlaylistTrack? {
        val short = this
        val track = short.objectAt("track") ?: short
        val title = track.stringAt("title")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val artists = track.arrayAt("artists").orEmpty().mapNotNull { artist ->
            (artist as? JsonObject)?.stringAt("name")?.trim()?.takeIf(String::isNotEmpty)
        }
        val artist = artists.joinToString(", ").ifBlank {
            track.stringAt("ugcArtistName", "ugc_artist_name").orEmpty().trim()
        }
        if (artist.isBlank()) return null
        val album = track.arrayAt("albums")?.firstOrNull() as? JsonObject
        val cover = track.stringAt("coverUri", "cover_uri")
            ?: album?.stringAt("coverUri", "cover_uri")
            ?: track.stringAt("ogImage", "og_image")
        return YandexMusicPlaylistTrack(
            id = track.stringAt("id", "realId", "real_id") ?: short.stringAt("id").orEmpty(),
            title = title,
            artist = artist,
            album = album?.stringAt("title"),
            durationMs = track.longAt("durationMs", "duration_ms") ?: 0L,
            coverUrl = cover.toArtworkUrl(),
            artistNames = artists,
        )
    }

    /**
     * Where a track's audio lives, signed as the Android client signs it.
     *
     * The endpoint (`get-file-info`) is not the older `download-info`: it
     * carries an HMAC over its own parameters — see [YandexAudio.sign] — and
     * answers `encraw` URLs whose bytes are encrypted end to end. [quality] is
     * one of [YandexAudio.QUALITY_LOSSLESS] and friends.
     *
     * @return every rendition offered, best first, or an empty list when the
     *   account cannot have this track at this tier (a free account asking for
     *   lossless gets nothing, which is an answer, not an error).
     */
    suspend fun fileInfo(token: String, trackId: String, quality: String): List<YandexStream> {
        val timestamp = System.currentTimeMillis() / 1000
        val url = baseUrl.newBuilder()
            .addPathSegment("get-file-info")
            .addQueryParameter("ts", timestamp.toString())
            .addQueryParameter("trackId", trackId)
            .addQueryParameter("quality", quality)
            .addQueryParameter("codecs", YandexAudio.CODECS.joinToString(","))
            .addQueryParameter("transports", "encraw")
            .addQueryParameter("sign", YandexAudio.sign(timestamp, trackId, quality))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $token")
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept-Language", "ru")
            .header("Accept", "application/json")
            .get()
            .build()
        val response = execute(request)
        val info = response.resultObject().objectAt("downloadInfo") ?: return emptyList()
        val codec = info.stringAt("codec").orEmpty()
        val bitrate = info.longAt("bitrate")?.toInt()?.takeIf { it > 0 }
        val key = info.stringAt("key")?.takeIf(String::isNotBlank)
        return info.arrayAt("urls").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it.startsWith("https://") }
            .map { YandexStream(url = it, codec = codec, bitrateKbps = bitrate, keyHex = key) }
    }

    suspend fun track(token: String, trackId: String, albumId: String?): YandexMusicTrack {
        val compositeId = albumId?.takeIf { it.isNotBlank() }?.let { "$trackId:$it" } ?: trackId
        val body = FormBody.Builder()
            .add("track-ids", compositeId)
            .add("with-positions", "true")
            .build()
        val request = requestBuilder("tracks", token).post(body).build()
        val result = execute(request).resultArray()
        val track = result.firstOrNull() as? JsonObject ?: throw YandexMusicApiException.EmptyResult()

        val id = track.stringAt("id", "realId", "real_id") ?: trackId
        val title = track.stringAt("title").orEmpty().trim()
        val artists = track.arrayAt("artists")
            ?.mapNotNull { (it as? JsonObject)?.stringAt("name")?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
            .ifEmpty {
                listOfNotNull(track.stringAt("ugcArtistName", "ugc_artist_name")?.trim()?.takeIf(String::isNotEmpty))
            }
        val album = track.arrayAt("albums")?.firstOrNull() as? JsonObject
        val resolvedAlbumId = album?.stringAt("id") ?: albumId
        val cover = track.stringAt("coverUri", "cover_uri")
            ?: album?.stringAt("coverUri", "cover_uri")
            ?: track.stringAt("ogImage", "og_image")

        return YandexMusicTrack(
            id = id,
            title = title,
            artists = artists,
            albumId = resolvedAlbumId,
            albumTitle = album?.stringAt("title"),
            coverUrl = cover.toArtworkUrl(),
            durationMs = track.longAt("durationMs", "duration_ms") ?: 0L,
            available = track.booleanAt("available") != false,
            premiumOnly = track.booleanAt("availableForPremiumUsers", "available_for_premium_users") == true &&
                track.booleanAt("availableFullWithoutPermission", "available_full_without_permission") != true,
            error = track.stringAt("error"),
        )
    }

    private fun requestBuilder(path: String, token: String): Request.Builder = Request.Builder()
        .url(baseUrl.newBuilder().addPathSegments(path).build())
        .header("Authorization", "OAuth $token")
        .header("X-Yandex-Music-Client", CLIENT_HEADER)
        .header("Accept-Language", "ru")
        .header("Accept", "application/json")

    private fun requestBuilder(segments: List<String>, token: String): Request.Builder {
        val url = baseUrl.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $token")
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept-Language", "ru")
            .header("Accept", "application/json")
    }

    private fun parsePlaylist(value: JsonObject, fallbackUid: String): YandexMusicPlaylist? {
        val kind = value.stringAt("kind")?.takeIf { it.isNotBlank() } ?: return null
        val title = value.stringAt("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cover = value.objectAt("cover")
        val coverUri = cover?.stringAt("uri")
            ?: cover?.arrayAt("itemsUri", "items_uri")
                ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: value.stringAt("ogImage", "og_image")
        return YandexMusicPlaylist(
            uid = value.stringAt("uid") ?: fallbackUid,
            kind = kind,
            title = title,
            trackCount = value.longAt("trackCount", "track_count")?.toInt()?.coerceAtLeast(0) ?: 0,
            coverUrl = coverUri.toArtworkUrl(),
        )
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        try {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val result = runCatching { parse(it) }
                            if (continuation.isActive) continuation.resumeWith(result)
                        }
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: YandexMusicApiException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw YandexMusicApiException.InvalidResponse(e)
        }
    }

    private fun parse(response: Response): JsonObject {
        when (response.code) {
            401 -> throw YandexMusicApiException.Unauthorized()
            403 -> throw YandexMusicApiException.AccessDenied()
        }
        if (!response.isSuccessful) throw YandexMusicApiException.Http(response.code)
        val raw = response.body?.string()?.takeIf { it.isNotBlank() }
            ?: throw YandexMusicApiException.EmptyResult()
        return try {
            JSON.parseToJsonElement(raw).jsonObject
        } catch (e: Throwable) {
            throw YandexMusicApiException.InvalidResponse(e)
        }
    }

    private fun JsonObject.resultObject(): JsonObject =
        (this["result"] as? JsonObject) ?: throw YandexMusicApiException.EmptyResult()

    private fun JsonObject.resultArray(): JsonArray =
        (this["result"] as? JsonArray) ?: throw YandexMusicApiException.EmptyResult()

    private companion object {
        const val BASE_URL = "https://api.music.yandex.net/"
        const val CLIENT_HEADER = "YandexMusicAndroid/24023621"
        val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Liked libraries already read, by account.
         *
         * In memory, and deliberately not on disk: this is a shortcut for repeat
         * openings in one session, not a store. Losing it costs one full listing,
         * which is what every lookup cost before it.
         */
        val likedLibraries = java.util.concurrent.ConcurrentHashMap<String, CachedLikes>()
    }
}

internal fun JsonObject.elementAt(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { this[it] }

internal fun JsonObject.objectAt(vararg names: String): JsonObject? =
    elementAt(*names) as? JsonObject

internal fun JsonObject.arrayAt(vararg names: String): JsonArray? =
    elementAt(*names) as? JsonArray

internal fun JsonObject.stringAt(vararg names: String): String? =
    (elementAt(*names) as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

internal fun JsonObject.booleanAt(vararg names: String): Boolean? =
    (elementAt(*names) as? JsonPrimitive)?.let { primitive ->
        primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
    }

internal fun JsonObject.longAt(vararg names: String): Long? =
    (elementAt(*names) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.doubleAt(vararg names: String): Double? =
    (elementAt(*names) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun String?.toYandexTimestamp(): Long? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    value.toLongOrNull()?.takeIf { it > 0L }?.let { return if (it < 1_000_000_000_000L) it * 1000L else it }
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

internal fun String?.toArtworkUrl(size: String = "1000x1000"): String? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val expanded = value.replace("%%", size).replace("{size}", size)
    return when {
        expanded.startsWith("https://") -> expanded
        expanded.startsWith("http://") -> "https://${expanded.removePrefix("http://")}"
        expanded.startsWith("//") -> "https:$expanded"
        else -> "https://$expanded"
    }
}
