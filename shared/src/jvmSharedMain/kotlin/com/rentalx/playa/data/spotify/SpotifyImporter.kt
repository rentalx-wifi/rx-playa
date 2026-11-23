package com.rentalx.playa.data.spotify

import com.rentalx.playa.data.YtMusicRepository
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicInteger

/** Single track metadata extracted from a Spotify playlist. */
data class SpotifyImportTrack(
    val title: String,
    val artist: String,
)

/** The result of fetching and resolving a Spotify playlist's tracks. */
data class SpotifyImportResult(
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val totalTracks: Int,
    val resolvedVideoIds: List<String>,
    val unmatchedTracks: List<SpotifyImportTrack>,
)

object SpotifyImporter {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** A Spotify playlist id is 22 base-62 characters. */
    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")

    private fun isSpotifyHost(host: String) =
        host == "spotify.com" || host.endsWith(".spotify.com")

    /** Parses a raw user string or link into a Spotify playlist ID if valid. */
    fun extractPlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("spotify:playlist:")) {
            val id = trimmed.removePrefix("spotify:playlist:").substringBefore("?").substringBefore("/")
            return id.takeIf { PLAYLIST_ID.matches(it) }
        }

        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (!isSpotifyHost(uri.host?.lowercase().orEmpty())) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val playlistIdx = segments.indexOf("playlist")
        if (playlistIdx == -1 || playlistIdx + 1 >= segments.size) return null
        return segments[playlistIdx + 1].takeIf { PLAYLIST_ID.matches(it) }
    }

    /**
     * Fetches public Spotify playlist details from Spotify's embed endpoint.
     */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<SpotifyImportTrack>> =
        withContext(Dispatchers.IO) {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val html = spotifyResponse(request).takeIf { it.isNotBlank() }
                ?: error("Spotify returned an empty playlist page")
            currentCoroutineContext().ensureActive()

            // Extract <script id="__NEXT_DATA__" type="application/json">...</script>
            val scriptRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val match = scriptRegex.find(html)
                ?: error("Could not parse Spotify playlist metadata. Make sure the playlist is public.")

            val jsonString = match.groupValues[1]
            val root = json.parseToJsonElement(jsonString).jsonObject

            val props = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
            val stateData = props?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject
                ?: props?.get("entity")?.jsonObject
                ?: error("Spotify playlist entity missing or private")

            val playlistTitle = stateData["name"]?.jsonPrimitive?.content
                ?: stateData["title"]?.jsonPrimitive?.content
                ?: "Imported Spotify Playlist"

            val trackListJson = stateData["trackList"]?.jsonArray
                ?: stateData["tracks"]?.jsonArray
                ?: JsonArray(emptyList())

            val tracks = mutableListOf<SpotifyImportTrack>()
            for (element in trackListJson) {
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content
                    ?: obj["name"]?.jsonPrimitive?.content
                    ?: continue
                val subtitle = obj["subtitle"]?.jsonPrimitive?.content
                    ?: obj["artists"]?.jsonArray?.joinToString(", ") {
                        it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                    }
                    ?: ""
                if (title.isNotBlank()) {
                    tracks.add(SpotifyImportTrack(title = title.trim(), artist = subtitle.trim()))
                }
            }

            if (tracks.isEmpty()) {
                error("No tracks found in public Spotify playlist.")
            }

            // The embed page stops at its first 100 tracks. Never present that
            // prefix as a complete import: page through the web query or fail
            // explicitly so the user can retry without silently losing songs.
            if (tracks.size >= EMBED_TRACK_LIMIT) {
                val token = props?.get("state")?.jsonObject
                    ?.get("settings")?.jsonObject
                    ?.get("session")?.jsonObject
                    ?.get("accessToken")?.jsonPrimitive?.content
                    ?: error("Spotify did not provide a pagination token")
                tracks += fetchRemainingTracks(playlistId, token, EMBED_TRACK_LIMIT)
            }

            Pair(playlistTitle, tracks)
        }

    /** The best candidate by title, artist, length and album, or null when none is the same recording. */
    suspend fun matchTrack(track: SpotifyTrack): Song? =
        matchTrack(track, YtMusicRepository::songCandidates)

    internal suspend fun matchTrack(
        track: SpotifyTrack,
        candidatesFor: suspend (title: String, artist: String) -> List<Song>,
    ): Song? {
        val candidates = candidatesFor(track.title, track.artist)
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist,
                durationSec = track.durationMs.takeIf { it > 0 }?.div(1000),
                album = track.album,
            ),
        )
    }

    /** Tracks the embed page lists before it cuts off. */
    private const val EMBED_TRACK_LIMIT = 100
    private const val PAGE_SIZE = 100
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val FETCH_PLAYLIST_HASH = "19ff1327c29e99c208c86d7a9d8f1929cfdf3d3202a0ff4253c821f1901aa94d"

    /** Tracks from [start] to the end of the playlist, a page at a time. */
    private suspend fun fetchRemainingTracks(
        playlistId: String,
        token: String,
        start: Int,
    ): List<SpotifyImportTrack> = collectSpotifyPages(startOffset = start) { offset ->
        currentCoroutineContext().ensureActive()
        val variables = """{"uri":"spotify:playlist:$playlistId","offset":$offset,"limit":$PAGE_SIZE,"enableWatchFeedEntrypoint":false}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$FETCH_PLAYLIST_HASH"}}"""
        val url = PATHFINDER_URL.toHttpUrl().newBuilder()
            .addQueryParameter("operationName", "fetchPlaylist")
            .addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")
            .header("App-platform", "WebPlayer")
            .header("Accept", "application/json")
            .build()
        val body = spotifyResponse(request)
        currentCoroutineContext().ensureActive()
        val content = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("playlistV2")?.jsonObject?.get("content")?.jsonObject
            ?: error("Spotify playlist page was empty")
        val items = content["items"]?.jsonArray ?: error("Spotify playlist page had no items")
        val rows = items.mapNotNull { item ->
            val data = item.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject
                ?: return@mapNotNull null
            val title = data["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val artist = data["artists"]?.jsonObject?.get("items")?.jsonArray
                ?.mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                ?.joinToString(", ")
                .orEmpty()
            SpotifyImportTrack(title = title, artist = artist)
        }
        val total = content["totalCount"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: error("Spotify playlist page had no total count")
        Triple(rows, total, items.size)
    }

    /**
     * Resolves a list of Spotify tracks to full Song objects using Innertube search.
     * Optionally filters or prioritizes matches by specified [language].
     * Reports real-time progress via [onProgress].
     */
    suspend fun resolveToSongs(
        tracks: List<SpotifyImportTrack>,
        language: String? = null,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<SpotifyImportTrack>> =
        resolveToSongs(tracks, language, YtMusicRepository::songCandidates, onProgress)

    internal suspend fun resolveToSongs(
        tracks: List<SpotifyImportTrack>,
        language: String?,
        candidatesFor: suspend (title: String, artist: String) -> List<Song>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<SpotifyImportTrack>> = coroutineScope {
        val total = tracks.size
        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(4) // Bounded concurrency for search queries

        val cleanLang = language?.trim()?.takeIf {
            !it.equals("No Preference", ignoreCase = true) &&
            !it.equals("Multilingual", ignoreCase = true) &&
            it.isNotBlank()
        }

        val knownLanguages = listOf(
            "Tamil", "Telugu", "Kannada", "Malayalam", "Hindi",
            "English", "Punjabi", "Marathi", "Bengali", "Bhojpuri", "Gujarati"
        )

        val deferredResults = tracks.map { track ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val candidates = candidatesFor(
                        track.title,
                        if (cleanLang != null) "${track.artist} $cleanLang" else track.artist,
                    )

                    val target = TrackMatcher.Target(
                        title = track.title,
                        artist = track.artist,
                    )
                    val bestMatch = TrackMatcher.best(candidates, target)

                    val matchedSong = if (cleanLang != null && candidates.isNotEmpty()) {
                        val conflictingLangs = knownLanguages.filter { !it.equals(cleanLang, ignoreCase = true) }
                        val nonConflictingCandidates = candidates.filter { candidate ->
                            val meta = "${candidate.title} ${candidate.artist} ${candidate.albumName.orEmpty()}".lowercase()
                            !conflictingLangs.any {
                                meta.contains("(${it.lowercase()})") ||
                                meta.contains("${it.lowercase()} version") ||
                                meta.contains("${it.lowercase()} song")
                            }
                        }
                        TrackMatcher.best(nonConflictingCandidates, target) ?: bestMatch
                    } else {
                        bestMatch
                    }

                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)

                    if (matchedSong != null) {
                        Pair(matchedSong, null)
                    } else {
                        Pair(null, track)
                    }
                }
            }
        }

        val results = deferredResults.awaitAll()
        val songs = results.mapNotNull { it.first }
        val unmatched = results.mapNotNull { it.second }

        Pair(songs, unmatched)
    }
}
