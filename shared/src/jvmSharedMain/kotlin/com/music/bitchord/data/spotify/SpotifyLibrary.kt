package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import okhttp3.RequestBody.Companion.toRequestBody

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
    val artists: List<String> = emptyList(),
    val likedAtMs: Long? = null,
)

const val SPOTIFY_TRACK_PREFIX = "spotify-track:"

fun SpotifyTrack.toSourceSong(): Song = Song(
    videoId = SPOTIFY_TRACK_PREFIX + id,
    title = title,
    artist = artist,
    thumbnailUrl = imageUrl,
    durationText = durationMs.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60_000, it / 1_000 % 60) },
    albumName = album,
    playbackSource = "Spotify",
    playbackSourceType = PlaybackSourceType.SEARCH,
    playbackSourceId = id,
)

val Song.isSpotifySourceTrack: Boolean get() = videoId.startsWith(SPOTIFY_TRACK_PREFIX)

fun Song.spotifyTrackId(): String? = playbackSourceId?.takeIf(String::isNotBlank)
    ?: videoId.removePrefix(SPOTIFY_TRACK_PREFIX).takeIf { videoId.startsWith(SPOTIFY_TRACK_PREFIX) }
    ?: videoId.removePrefix("taste:spotify:").takeIf { videoId.startsWith("taste:spotify:") }

fun Song.spotifyWebUrl(): String? {
    return spotifyTrackId()?.let { "https://open.spotify.com/track/$it" }
}

object SpotifyLibrary {
    /** The web player's bearer, and its client token when there is one. */
    class Tokens(val bearer: String, val clientToken: String?)

    /**
     * Where the tokens come from: the phone mints them in a WebView, the desktop in JavaFX's.
     * Null tokens mean signed out or expired.
     */
    fun interface Auth {
        suspend fun tokens(): Tokens?
    }

    /** Installed by each application at start-up; until then every call fails as signed out. */
    @Volatile
    var auth: Auth? = null

    /** App-specific bearer cache invalidation after a server-side rejection. */
    @Volatile
    var invalidateAuth: (() -> Unit)? = null

    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    /** The phone's canvas client sent this one; kept so its requests look unchanged. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile private var restPreferredUntilMs = 0L

    /** Every playlist in the library, however many pages it takes. */
    suspend fun playlists(): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        authenticated { headers ->
            val collected = requestWithFallback(
                primary = { collectSpotifyPages { offset -> playlistPage(headers, offset) } },
                fallback = { collectSpotifyPages { offset -> restPlaylistPage(headers, offset) } },
            )
            // Liked Songs is not a playlist to Spotify's library query, so it is
            // always offered first rather than depending on it turning up there.
            listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + collected
        }
    }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        authenticated { headers ->
            requestWithFallback(
                primary = {
                    collectSpotifyPages(onPage) { offset ->
                        if (playlistId == LIKED_ID) likedPage(headers, offset) else trackPage(headers, playlistId, offset)
                    }
                },
                fallback = {
                    collectSpotifyPages(onPage) { offset ->
                        if (playlistId == LIKED_ID) restLikedPage(headers, offset)
                        else restTrackPage(headers, playlistId, offset)
                    }
                },
            )
        }
    }

    /** Spotify catalogue first; the listener's complete Liked Songs are the reliable fallback. */
    suspend fun searchTracks(query: String, limit: Int = 25): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext emptyList()
        val wanted = limit.coerceIn(1, 50)
        authenticated { headers ->
            val catalogue = try {
                searchPage(clean, wanted, headers, marketFromToken = true).ifEmpty {
                    searchPage(clean, wanted, headers, marketFromToken = false)
                }
            } catch (failure: SpotifyRequestException) {
                if (failure.shouldRefreshAuth) throw failure else emptyList()
            } catch (_: IOException) {
                emptyList()
            }
            if (catalogue.isNotEmpty()) catalogue else searchLikedSongs(clean, wanted, headers)
        }
    }

    private suspend fun searchPage(
        query: String,
        limit: Int,
        headers: Map<String, String>,
        marketFromToken: Boolean,
    ): List<SpotifyTrack> {
        val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", limit.toString())
            .apply { if (marketFromToken) addQueryParameter("market", "from_token") }
            .build()
        val root = restGet(url, headers)
        return parseSearchTracks(root)
    }

    private suspend fun searchLikedSongs(
        query: String,
        limit: Int,
        headers: Map<String, String>,
    ): List<SpotifyTrack> {
        val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (matches.size < limit) {
            currentCoroutineContext().ensureActive()
            val (rows, total, raw) = requestWithFallback(
                primary = { likedPage(headers, offset) },
                fallback = { restLikedPage(headers, offset) },
            )
            matches += rows.filter { track ->
                val text = listOf(track.title, track.artist, track.album.orEmpty()).joinToString(" ").lowercase()
                terms.all(text::contains)
            }
            if (raw == 0 || offset + raw >= total) break
            offset += raw
        }
        return matches.distinctBy(SpotifyTrack::id).take(limit)
    }

    private suspend fun playlistPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", 50)
            put("offset", offset)
            put("flatten", true)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", false)
        }
        return parsePlaylistPage(gql("libraryV3", LIBRARY, variables, headers))
    }

    private suspend fun restPlaylistPage(
        headers: Map<String, String>,
        offset: Int,
    ): Triple<List<SpotifyPlaylist>, Int, Int> {
        val url = "https://api.spotify.com/v1/me/playlists".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "50")
            .addQueryParameter("offset", offset.toString())
            .build()
        return parseRestPlaylistPage(restGet(url, headers))
    }

    private suspend fun trackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", 100)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", PLAYLIST, variables, headers))
    }

    private suspend fun restTrackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "100")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("market", "from_token")
            .build()
        return parseRestTrackPage(restGet(url, headers))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext LIKED_COVER
        authenticated { headers ->
            requestWithFallback(
                primary = {
                    val variables = buildJsonObject {
                        put("uri", "spotify:playlist:$playlistId")
                        put("offset", 0)
                        put("limit", 1)
                        put("enableWatchFeedEntrypoint", false)
                    }
                    val root = gql("fetchPlaylist", PLAYLIST, variables, headers)
                    largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
                        ?: throw IOException("Spotify playlist cover was empty")
                },
                fallback = {
                    val url = "https://api.spotify.com/v1/playlists".toHttpUrl().newBuilder()
                        .addPathSegment(playlistId)
                        .addQueryParameter("fields", "images")
                        .build()
                    restGet(url, headers).arr("images").restImageUrl()
                }
            )
        }
    }

    private suspend fun likedPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", 100)
        }
        return parseLikedPage(gql("fetchLibraryTracks", LIKED, variables, headers))
    }

    private suspend fun restLikedPage(
        headers: Map<String, String>,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val url = "https://api.spotify.com/v1/me/tracks".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "50")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("market", "from_token")
            .build()
        return parseRestTrackPage(restGet(url, headers), liked = true)
    }

    private suspend fun <T> authenticated(block: suspend (Map<String, String>) -> T): T {
        val first = authHeaders()
        return try {
            block(first)
        } catch (rejected: SpotifyRequestException) {
            if (!rejected.shouldRefreshAuth) throw rejected
            invalidateAuth?.invoke()
            currentCoroutineContext().ensureActive()
            block(authHeaders())
        }
    }

    private suspend fun authHeaders(): Map<String, String> {
        val tokens = auth?.tokens()
            ?: throw IllegalStateException("Spotify sign-in expired")
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
        }
    }

    private suspend fun gql(operation: String, hash: String, variables: JsonObject, headers: Map<String, String>): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val request = Request.Builder()
            .url(GQL)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val text = spotifyResponse(request)
        val root = json.parseToJsonElement(text).jsonObject
        val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return root
    }

    private suspend fun restGet(url: okhttp3.HttpUrl, headers: Map<String, String>): JsonObject {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return json.parseToJsonElement(spotifyResponse(request)).jsonObject
    }

    private suspend fun <T> requestWithFallback(
        primary: suspend () -> T,
        fallback: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        if (System.currentTimeMillis() < restPreferredUntilMs) {
            return try {
                fallback()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                restPreferredUntilMs = 0L
                currentCoroutineContext().ensureActive()
                primary()
            }
        }
        return try {
            primary()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            restPreferredUntilMs = System.currentTimeMillis() + REST_PREFERENCE_MS
            currentCoroutineContext().ensureActive()
            fallback()
        }
    }

    private const val REST_PREFERENCE_MS = 15 * 60_000L
}

internal class SpotifyRequestException(val status: Int) :
    IOException("Spotify request failed ($status)") {
    val shouldRefreshAuth: Boolean get() = status == 401 || status == 403 || status == 429
}

internal suspend fun spotifyResponse(request: Request): String = suspendCancellableCoroutine { continuation ->
    val call = Http.client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val result = runCatching {
                    if (!it.isSuccessful) throw SpotifyRequestException(it.code)
                    it.body?.string().orEmpty()
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }
    })
}

internal fun parseSearchTracks(root: JsonObject): List<SpotifyTrack> =
    root.obj("tracks")?.arr("items").orEmpty().mapNotNull { element ->
        val track = element.jsonObject
        val id = track.str("id") ?: return@mapNotNull null
        val title = track.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val artistNames = track.arr("artists").orEmpty().mapNotNull { it.jsonObject.str("name") }
            .filter { it.isNotBlank() }
        val artist = artistNames.joinToString(", ")
        if (artist.isBlank()) return@mapNotNull null
        val album = track.obj("album")
        val image = album?.arr("images")?.mapNotNull { image ->
            val obj = image.jsonObject
            obj.str("url")?.let { it to (obj.int("width") ?: 0) }
        }?.maxByOrNull { it.second }?.first
        SpotifyTrack(
            id = id,
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = track.int("duration_ms") ?: 0,
            imageUrl = image,
            artists = artistNames,
        )
    }

/** Source positions, not retained rows, advance the cursor; a truncated page is not an empty library. */
internal suspend fun <T> collectSpotifyPages(
    onPage: (List<T>) -> Unit = {},
    startOffset: Int = 0,
    load: suspend (Int) -> Triple<List<T>, Int, Int>,
): List<T> {
    val collected = mutableListOf<T>()
    var offset = startOffset
    while (true) {
        currentCoroutineContext().ensureActive()
        val (items, total, raw) = load(offset)
        currentCoroutineContext().ensureActive()
        if (raw == 0 && offset < total) throw IOException("Spotify returned an incomplete page")
        collected += items
        offset += raw
        onPage(collected.toList())
        if (offset >= total || raw == 0) return collected
    }
}

private fun JsonObject.likedTimestamp(): Long? {
    val field = this["addedAt"] ?: this["added_at"] ?: this["timestamp"]
    val value = when (field) {
        is JsonPrimitive -> field.contentOrNull
        is JsonObject -> field.str("isoString") ?: field.str("timestamp")
        else -> null
    } ?: return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

internal fun parseRestPlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val rawItems = root.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val playlist = element as? JsonObject ?: return@mapNotNull null
        val id = playlist.str("id") ?: return@mapNotNull null
        val name = playlist.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SpotifyPlaylist(
            id = id,
            name = name,
            owner = playlist.obj("owner")?.str("display_name"),
            imageUrl = playlist.arr("images").restImageUrl(),
        )
    }
    return Triple(items, root.int("total") ?: rawItems.size, rawItems.size)
}

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artistNames = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }
        val artist = artistNames.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
            artists = artistNames,
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        val artistNames = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artistNames.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
            artists = artistNames,
            likedAtMs = element.jsonObject.likedTimestamp(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

internal fun parseRestTrackPage(
    root: JsonObject,
    liked: Boolean = false,
): Triple<List<SpotifyTrack>, Int, Int> {
    val rawItems = root.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element as? JsonObject ?: return@mapNotNull null
        val track = wrapper.obj("track") ?: return@mapNotNull null
        val id = track.str("id") ?: return@mapNotNull null
        val title = track.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val artistNames = track.arr("artists").orEmpty().mapNotNull { artist ->
            (artist as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() }
        }
        if (artistNames.isEmpty()) return@mapNotNull null
        val album = track.obj("album")
        SpotifyTrack(
            id = id,
            title = title,
            artist = artistNames.joinToString(", "),
            album = album?.str("name"),
            durationMs = track.int("duration_ms") ?: 0,
            imageUrl = album?.arr("images").restImageUrl(),
            artists = artistNames,
            likedAtMs = if (liked) wrapper.likedTimestamp() else null,
        )
    }
    return Triple(items, root.int("total") ?: rawItems.size, rawItems.size)
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
        ?.mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonArray?.restImageUrl(): String? = orEmpty().mapNotNull { image ->
    val value = image as? JsonObject ?: return@mapNotNull null
    value.str("url")?.let { it to (value.int("width") ?: 0) }
}.maxByOrNull { it.second }?.first

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
