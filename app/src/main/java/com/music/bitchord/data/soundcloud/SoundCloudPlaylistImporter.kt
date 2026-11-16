package com.music.bitchord.data.soundcloud

import com.music.bitchord.data.importing.ExternalPlaylistTrack
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.PlaybackSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.TimeUnit
import java.net.URI
import java.io.IOException
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SoundCloudImportTrack(
    override val title: String,
    override val artist: String,
    override val album: String? = null,
    override val durationMs: Long,
    override val coverUrl: String?,
    val sourceUrl: String? = null,
    /** Official stable id, used for account likes when available. */
    val sourceUrn: String? = null,
) : ExternalPlaylistTrack

data class SoundCloudImportPlaylist(
    val title: String,
    val tracks: List<SoundCloudImportTrack>,
    val sourceUrn: String? = null,
    /** Public profile collections are hydrated only after the listener selects one. */
    val sourceUrl: String? = null,
    val trackCount: Int = tracks.size,
)

internal data class SoundCloudSearchPage(
    val tracks: List<SoundCloudImportTrack>,
    val nextOffset: Int?,
    val nextHref: String?,
)

/** Public SoundCloud sets through its mobile API; no account token or Go+ bypass. */
object SoundCloudPlaylistImporter {
    private val redirectClient = Http.client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun isPlaylistLink(input: String): Boolean {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.userInfo != null ||
            uri.port !in setOf(-1, if (scheme == "https") 443 else 80)
        ) return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "soundcloud.com" || host.endsWith(".soundcloud.com")
    }

    suspend fun fetch(input: String): SoundCloudImportPlaylist = withContext(Dispatchers.IO) {
        val root = resolvePublicLink(input)
        if (root.string("kind") == "user") {
            val userId = root.string("id") ?: throw IOException("SoundCloud profile has no id")
            val tracks = publicUserRows(userId, "likes").mapNotNull { row ->
                (row["track"] as? JsonObject)?.toTrack()
            }
            if (tracks.isEmpty()) throw IOException("No public liked tracks found")
            SoundCloudImportPlaylist("${root.string("username").orEmpty()} · SoundCloud likes", tracks)
        } else {
            hydratePublicPlaylist(root)
        }
    }

    suspend fun fetchPublicCollections(input: String, likesTitle: String): List<SoundCloudImportPlaylist> =
        withContext(Dispatchers.IO) {
            val root = resolvePublicLink(input)
            if (root.string("kind") != "user") return@withContext listOf(hydratePublicPlaylist(root))
            val userId = root.string("id") ?: throw IOException("SoundCloud profile has no id")
            val profileUrl = root.string("permalink_url")?.takeIf(::isPlaylistLink)
                ?: throw IOException("SoundCloud profile has no public URL")
            val likes = SoundCloudImportPlaylist(
                title = "${root.string("username").orEmpty()} · $likesTitle",
                tracks = emptyList(),
                sourceUrl = "${profileUrl.trimEnd('/')}/likes",
            )
            listOf(likes) + publicUserRows(userId, "playlists").mapNotNull { row ->
                val url = row.string("permalink_url")?.takeIf(::isPlaylistLink) ?: return@mapNotNull null
                SoundCloudImportPlaylist(
                    title = row.string("title").orEmpty().ifBlank { "SoundCloud playlist" },
                    tracks = emptyList(),
                    sourceUrl = url,
                    trackCount = row.long("track_count")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                )
            }
        }

    internal suspend fun publicUserRows(
        userId: String,
        collection: String,
        request: suspend (String?) -> JsonObject = { SoundCloudApi.userCollection(userId, collection, it) },
    ): List<JsonObject> {
        val rows = ArrayList<JsonObject>()
        val seen = HashSet<String>()
        var next: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val root = request(next)
            val page = root["collection"] as? JsonArray
                ?: throw IOException("SoundCloud returned an invalid public collection")
            rows += page.mapNotNull { it as? JsonObject }
            next = root.string("next_href")
            if (next != null) {
                if (!isTrustedSoundCloudUserContinuation(next, userId, collection)) {
                    throw IOException("SoundCloud profile continuation left the public collection")
                }
                val key = next.toHttpUrlOrNull()!!.newBuilder().removeAllQueryParameters("client_id").build().toString()
                if (!seen.add(key)) throw IOException("SoundCloud profile repeated a page")
            }
        } while (next != null)
        return rows
    }

    private suspend fun resolvePublicLink(input: String): JsonObject {
        require(isPlaylistLink(input)) { "Invalid SoundCloud link" }
        val original = input.trim()
        var url = if (URI(original).host.equals("on.soundcloud.com", ignoreCase = true)) {
            expandShortLink(original)
        } else original
        val parsed = URI(url)
        val path = parsed.path.orEmpty().trimEnd('/')
        if (path.endsWith("/likes") || path.endsWith("/sets")) {
            val profile = path.split('/').filter(String::isNotBlank)
            if (profile.size == 2) url = "https://soundcloud.com/${profile.first()}"
        }
        return SoundCloudApi.resolve(url)
    }

    private suspend fun hydratePublicPlaylist(playlist: JsonObject): SoundCloudImportPlaylist {
        if (playlist.string("kind") != "playlist" && playlist.string("kind") != "system-playlist") {
            throw IOException("SoundCloud URL is not a public playlist or profile")
        }
        // Large public sets contain id stubs: hydrate every position in bounded batches.
        val rows = playlist["tracks"]?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        val order = rows.mapNotNull { it.string("id") }
        val byId = rows.mapNotNull { row ->
            row.string("id")?.takeIf { row.string("title") != null }?.let { it to row }
        }.toMap(LinkedHashMap())
        val missing = order.distinct().filterNot(byId::containsKey)
        missing.chunked(TRACK_BATCH_SIZE).forEach { ids ->
            currentCoroutineContext().ensureActive()
            SoundCloudApi.tracks(ids).forEach { row ->
                row.string("id")?.let { byId[it] = row }
            }
        }
        val tracks = order.mapNotNull { id -> byId[id]?.toTrack() }
        if (tracks.isEmpty()) error("No public tracks found in this SoundCloud playlist")
        return SoundCloudImportPlaylist(playlist.string("title").orEmpty().ifBlank { "SoundCloud playlist" }, tracks)
    }

    suspend fun resolve(
        tracks: List<SoundCloudImportTrack>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<SoundCloudImportTrack>> {
        val playable = ArrayList<Song>(tracks.size)
        val unavailable = ArrayList<SoundCloudImportTrack>()
        tracks.forEachIndexed { index, track ->
            val song = track.toSearchSong(index)
            if (track.sourceUrl.isNullOrBlank()) unavailable += track else playable += song
            onProgress(index + 1, tracks.size)
        }
        return playable to unavailable
    }

    suspend fun search(query: String, limit: Int = 25): List<SoundCloudImportTrack> =
        withContext(Dispatchers.IO) {
            val wanted = limit.coerceAtLeast(1)
            val tracks = LinkedHashMap<String, SoundCloudImportTrack>()
            var offset: Int? = 0
            var continuation: String? = null
            val seenContinuations = HashSet<String>()
            while (tracks.size < wanted) {
                currentCoroutineContext().ensureActive()
                val page = searchPage(
                    query = query,
                    offset = offset ?: break,
                    limit = minOf(50, maxOf(25, wanted - tracks.size)),
                    continuationUrl = continuation,
                )
                page.tracks.forEach { track ->
                    track.sourceUrl?.let { tracks.putIfAbsent(it, track) }
                }
                offset = page.nextOffset
                continuation = page.nextHref
                if (continuation != null && !seenContinuations.add(continuation)) break
                if (page.tracks.isEmpty() && continuation == null) break
            }
            tracks.values.take(wanted)
        }

    internal suspend fun searchPage(
        query: String,
        offset: Int = 0,
        limit: Int = 25,
        continuationUrl: String? = null,
    ): SoundCloudSearchPage =
        withContext(Dispatchers.IO) {
            val clean = query.trim()
            require(clean.isNotEmpty()) { "SoundCloud search query is empty" }
            val pageSize = limit.coerceIn(1, 50)
            val root = SoundCloudApi.searchTracks(
                query = clean,
                offset = offset.coerceAtLeast(0),
                limit = pageSize,
                continuationUrl = continuationUrl,
            )
            val raw = root["collection"]?.jsonArray.orEmpty()
            val tracks = raw.mapNotNull { element -> (element as? JsonObject)?.toTrack() }
                .distinctBy(SoundCloudImportTrack::sourceUrl)
            val total = root["total_results"]?.jsonPrimitive?.longOrNull?.toInt()
            val consumed = raw.size
            val href = root.string("next_href")
                ?.takeIf(::isTrustedSoundCloudSearchContinuation)
            val hrefOffset = href?.let(::searchContinuationOffset)
            val next = when {
                consumed == 0 -> null
                total != null && offset + consumed >= total -> null
                hrefOffset != null && hrefOffset > offset -> hrefOffset
                else -> offset + consumed
            }
            SoundCloudSearchPage(tracks, next, href)
        }

    private fun JsonObject.toTrack(): SoundCloudImportTrack? {
        val title = string("title")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val user = this["user"] as? JsonObject
        val sourceUrl = string("permalink_url")?.takeIf(String::isNotBlank) ?: return null
        val artist = user?.string("username")?.trim()?.takeIf(String::isNotEmpty)
            ?: URI(sourceUrl).path.orEmpty().split('/').firstOrNull(String::isNotBlank)
                ?.replace('-', ' ')?.takeIf(String::isNotBlank)
            ?: "SoundCloud"
        val artwork = string("artwork_url") ?: user?.string("avatar_url")
        return SoundCloudImportTrack(
            title = title,
            artist = artist,
            durationMs = long("duration")?.coerceAtLeast(0L) ?: 0L,
            coverUrl = artwork?.replace("-large.", "-t500x500."),
            sourceUrl = sourceUrl,
            sourceUrn = string("urn") ?: string("id")?.let { "soundcloud:tracks:$it" },
        )
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }

    private fun JsonObject.long(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun expandShortLink(input: String): String {
        var current = input
        repeat(MAX_SHORT_LINK_REDIRECTS) {
            val uri = URI(current)
            if (!uri.host.equals("on.soundcloud.com", ignoreCase = true)) return current
            val request = Request.Builder().url(current).header("User-Agent", SOUNDCLOUD_USER_AGENT).get().build()
            val next = redirectClient.newCall(request).execute().use { response ->
                val location = response.header("Location")
                    ?: throw IOException("SoundCloud short link did not redirect")
                response.request.url.resolve(location)?.toString()
                    ?: throw IOException("SoundCloud returned an invalid redirect")
            }
            if (!isPlaylistLink(next)) throw IOException("SoundCloud short link left SoundCloud")
            current = next
        }
        throw IOException("SoundCloud short link redirected too many times")
    }

    private const val TRACK_BATCH_SIZE = 50
    private const val MAX_SHORT_LINK_REDIRECTS = 5
    private const val SOUNDCLOUD_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36"
}

/** Search metadata is shown immediately; the native SoundCloud stream is resolved only on tap. */
internal fun SoundCloudImportTrack.toSearchSong(index: Int): Song = Song(
    videoId = sourceUrn?.takeIf { it.startsWith("soundcloud:tracks:") }
        ?: "$SOUNDCLOUD_TRACK_PREFIX${sourceUrl.orEmpty().hashCode().toUInt().toString(16)}",
    title = title,
    artist = artist,
    thumbnailUrl = coverUrl,
    durationText = durationMs.takeIf { it > 0L }?.let { ms -> "%d:%02d".format(ms / 60_000L, ms / 1_000L % 60L) },
    albumName = album,
    playbackSource = "SoundCloud",
    playbackSourceType = PlaybackSourceType.SEARCH,
    playbackSourceId = sourceUrl,
)

internal val Song.isSoundCloudSearchResult: Boolean
    get() = videoId.startsWith(SOUNDCLOUD_TRACK_PREFIX)

internal const val SOUNDCLOUD_TRACK_PREFIX = "soundcloud:"

internal fun searchContinuationOffset(raw: String): Int? = runCatching {
    raw.toHttpUrlOrNull()?.queryParameter("offset")?.toIntOrNull()
}.getOrNull()
