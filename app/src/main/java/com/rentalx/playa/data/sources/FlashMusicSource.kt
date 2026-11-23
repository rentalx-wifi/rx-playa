package com.rentalx.playa.data.sources

import com.rentalx.playa.data.Http
import com.rentalx.playa.data.TrackLog
import com.rentalx.playa.data.model.PlaybackSourceType
import com.rentalx.playa.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.Locale

/**
 * A [MusicSource] backed by the user's own FlashMusic server.
 *
 * One catalogue, three tiers: `lossless` hands back the master file exactly as
 * stored, `default` and `high` are Opus at 192 and 320 encoded on the server
 * and cached there. The server decides which of the tracks it holds is the
 * master, so this class never has to reason about quality — it asks for a tier
 * and gets bytes.
 *
 * Slowed and sped-up variants are catalogued on the server as a measured rate
 * on the normal track rather than as files of their own. Playing one at a
 * different speed is the player's job and is not wired up here yet; see
 * [FlashVariant] for the shape the server already reports.
 */
class FlashMusicSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = config.kind
    override val displayName: String get() = config.displayName

    /** The server root, without a trailing slash. */
    private val base: String = config.baseUrl.trim().trimEnd('/')

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Tracks this source has recently handed over, by their own id.
     *
     * Kept for the duration the server claimed: [SourceStream.durationSec] is
     * checked against what the decoder reports before a substitute stream is
     * cut into a track already playing, and a bare id cannot answer that.
     */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, FlashTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, FlashTrack>) = size > MAX_ROWS
        },
    )

    // ── Health ────────────────────────────────────────────────────────────

    /**
     * Whether there is a FlashMusic server on the other end.
     *
     * `/healthz` answers with the catalogue census, which settles both
     * questions at once: the server is up, and it is ours rather than some
     * other JSON that happens to be listening. A refusal here is a URL that is
     * simply wrong, which no amount of retrying fixes.
     */
    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (base.isBlank()) {
            return@withContext SourceHealth.Rejected("A server URL is required")
        }
        val health = fetch("$base/healthz")?.let {
            runCatching { json.decodeFromString(FlashHealth.serializer(), it) }.getOrNull()
        } ?: return@withContext SourceHealth.Unreachable("No answer from $base")

        if (!health.ok) {
            return@withContext SourceHealth.Rejected("That URL is not a FlashMusic server")
        }
        val tracks = health.catalog?.tracks ?: 0
        SourceHealth.Ok(
            when {
                tracks == 0 -> "Connected, catalogue is empty"
                tracks == 1 -> "1 track"
                else -> "$tracks tracks"
            },
        )
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Asks the server for [query] and names every row in this source's terms.
     *
     * The server already understands a variant phrase: "song slowed" resolves
     * to the track it was made from, which is why the rows come back titled
     * with the ordinary name rather than the fan edit's.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (base.isBlank() || query.isBlank()) return@withContext emptyList()

        val url = "$base/api/v1/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("limit", limit.toString())
            ?.build()
            ?: return@withContext emptyList()

        val body = fetch(url.toString()) ?: return@withContext emptyList()
        // Row by row rather than the whole document at once: one row the
        // server and this build disagree about used to take the entire
        // answer with it, leaving a catalogue that looked permanently empty.
        val tracks = elements(body, "tracks").mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(FlashTrack.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "${config.displayName}: skipped a row: ${it.message}") }
                .getOrNull()
        }

        tracks.forEach { rows[it.id] = it }
        tracks.map { it.toSong() }
    }

    /** The named array inside [body], or nothing if the document is not that. */
    private fun elements(body: String, field: String): List<JsonElement> =
        runCatching {
            json.parseToJsonElement(body).jsonObject[field]?.jsonArray.orEmpty()
        }.getOrDefault(emptyList())

    private fun FlashTrack.toSong() = Song(
        videoId = SourceRegistry.trackKey(config.id, id),
        title = title,
        artist = artist,
        albumName = album.ifBlank { null },
        // The cover embedded in the master, extracted and scaled by the server.
        // A track with none answers 404 and the app draws its placeholder.
        thumbnailUrl = "$base/api/v1/tracks/$id/art",
        // What this server *can* serve, which is not the tier asked for at
        // playback time: one catalogue holding two copies of a recording is
        // decided on before either is asked for a URL, and a row that says
        // nothing always loses that tie to a platform that does. See
        // [Song.sourceQuality].
        sourceQuality = renditions.firstOrNull { it.isMaster }?.let { LOSSLESS },
        sourceHiRes = renditions.firstOrNull { it.isMaster }
            ?.let { it.bits > 16 || it.sampleRate > CD_RATE_HZ } == true,
        // Names the row's provenance so the search list can draw the source's
        // badge beside it, exactly as a SoundCloud row gets its own.
        playbackSource = "FlashMusic",
        playbackSourceType = PlaybackSourceType.SEARCH,
        playbackSourceId = id,
        durationText = durationMs.takeIf { it > 0 }?.let {
            val total = (it / 1000).toInt()
            "${total / 60}:${String.format(Locale.ROOT, "%02d", total % 60)}"
        },
    )

    /**
     * The catalogue as rows, for a home shelf. No audio is fetched here.
     *
     * Used when the platform feed has nothing to say — signed out, or a fresh
     * install with no history — because a listener with their own server always
     * has something to play, and a Home page that opens on "nothing here" makes
     * the server look like it did not work.
     */
    suspend fun catalogue(limit: Int = CATALOGUE_SHELF_LIMIT): List<Song> = withContext(Dispatchers.IO) {
        if (base.isBlank()) return@withContext emptyList()
        val url = "$base/api/v1/library/manifest".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("limit", limit.toString())
            ?.build()
            ?: return@withContext emptyList()
        val body = fetch(url.toString()) ?: return@withContext emptyList()
        elements(body, "tracks").mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(FlashTrack.serializer(), element) }.getOrNull()
        }.onEach { rows[it.id] = it }.map { it.toSong() }
    }

    // ── Cross-catalogue matching ───────────────────────────────────────────

    /**
     * Which of [rows] this server already holds, keyed by each row's own id.
     *
     * One request for the whole result list rather than one per row: the caller
     * is a search screen decorating rows as they are drawn, and a round trip
     * per row would cost more than the search it decorates. The server matches
     * on title and artist, and returns only what it recognised.
     */
    suspend fun match(rows: List<Song>): Map<String, String> = withContext(Dispatchers.IO) {
        if (base.isBlank() || rows.isEmpty()) return@withContext emptyMap()

        val request = FlashMatchRequest(
            items = rows.map { FlashMatchItem(title = it.title, artist = it.artist) },
        )
        val body = json.encodeToString(FlashMatchRequest.serializer(), request)
            .toRequestBody(JSON_MEDIA_TYPE)
        val call = Request.Builder().url("$base/api/v1/match").post(body).build()

        val answer = runCatching {
            Http.client.newCall(call).execute().use { response ->
                if (!response.isSuccessful) {
                    TrackLog.w(TAG, "${config.displayName}: match answered ${response.code}")
                    return@use null
                }
                response.body?.string()
            }
        }.onFailure {
            TrackLog.w(TAG, "${config.displayName}: match failed: ${it.message}")
        }.getOrNull() ?: return@withContext emptyMap()

        val parsed = elements(answer, "matches").mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(FlashMatch.serializer(), element) }.getOrNull()
        }

        parsed.mapNotNull { hit ->
            if (hit.trackId.isBlank()) return@mapNotNull null
            rows.getOrNull(hit.index)?.videoId?.let { it to hit.trackId }
        }.toMap()
    }

    // ── Stream ────────────────────────────────────────────────────────────

    /**
     * The URL for one of the three tiers.
     *
     * Nothing is fetched here: the endpoint *is* the stream, so the player
     * opens it and the server transcodes or serves the file. [SourceStream] is
     * built from what the server said about the track at search time, because
     * a HEAD to learn the codec would cost a request per play and the tier
     * already determines it.
     */
    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (base.isBlank() || trackId.isBlank()) return null
        val tier = tierFor(request)

        val row = rows[trackId] ?: fetchTrack(trackId)
        val master = row?.renditions?.firstOrNull { it.isMaster }
        val format = if (tier == TIER_LOSSLESS) {
            StreamFormat(
                codec = master?.codec?.ifBlank { "flac" },
                sampleRateHz = master?.sampleRate?.takeIf { it > 0 },
                bitDepth = master?.bits?.takeIf { it > 0 },
            )
        } else {
            StreamFormat(
                codec = "opus",
                kbps = if (tier == TIER_HIGH) HIGH_KBPS else DEFAULT_KBPS,
                sampleRateHz = OPUS_RATE_HZ,
                // The server does not state the encoder's bitrate; this is the
                // tier we asked for, not a measurement of what arrives.
                kbpsInferred = true,
            )
        }

        return SourceStream(
            url = "$base/api/v1/tracks/$trackId/stream?tier=$tier",
            format = format,
            durationSec = row?.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
        )
    }

    /**
     * Which of the server's tiers this request pays for.
     *
     * The lowest tier the server has is 192, so a ceiling under that still gets
     * 192 — the server has no 128 to offer. That is a gap in the server's
     * ladder, not a silent substitution the player can fix.
     */
    private fun tierFor(request: StreamRequest): String = when (request) {
        StreamRequest.Lossless -> TIER_LOSSLESS
        is StreamRequest.Capped -> if (request.maxKbps >= HIGH_KBPS) TIER_HIGH else TIER_DEFAULT
        StreamRequest.Best -> TIER_HIGH
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    /**
     * One track, for the case where this process has never seen it.
     *
     * [rows] is filled by search and dies with the process, while the queue
     * outlives one: a track restored after a restart used to play with no
     * format and no length, so a FLAC was announced as whatever the tier
     * guessed and the duration check the queue relies on was skipped.
     */
    private fun fetchTrack(trackId: String): FlashTrack? {
        val body = fetch("$base/api/v1/tracks/" + trackId) ?: return null
        return runCatching {
            json.decodeFromJsonElement(FlashTrack.serializer(), json.parseToJsonElement(body))
        }.onFailure {
            TrackLog.w(TAG, "${config.displayName}: unreadable track $trackId: ${it.message}")
        }.getOrNull()?.also { rows[trackId] = it }
    }

    /** One GET, returning the body or null. Every failure is logged, none is thrown. */
    private fun fetch(url: String): String? = runCatching {
        Http.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                TrackLog.w(TAG, "${config.displayName}: $url answered ${response.code}")
                return@use null
            }
            response.body?.string()
        }
    }.onFailure {
        TrackLog.w(TAG, "${config.displayName}: $url failed: ${it.message}")
    }.getOrNull()

    private companion object {
        const val TAG = "Playa"
        const val MAX_ROWS = 256
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val TIER_LOSSLESS = "lossless"
        const val TIER_DEFAULT = "default"
        const val TIER_HIGH = "high"
        const val DEFAULT_KBPS = 192
        const val HIGH_KBPS = 320
        const val OPUS_RATE_HZ = 48_000
        /** 44.1 kHz. Anything above it is what the app calls hi-res. */
        const val CD_RATE_HZ = 44_100
        const val LOSSLESS = "LOSSLESS"
        /** Rows a home shelf asks for when the platform feed is empty. */
        const val CATALOGUE_SHELF_LIMIT = 30
    }
}

// ── Wire format ────────────────────────────────────────────────────────────

@Serializable
private data class FlashHealth(
    val ok: Boolean = false,
    val catalog: FlashCatalog? = null,
)

@Serializable
private data class FlashCatalog(
    val tracks: Int = 0,
    val renditions: Int = 0,
    val variants: Int = 0,
)

@Serializable
private data class FlashMatchRequest(val items: List<FlashMatchItem>)

@Serializable
private data class FlashMatchItem(val title: String, val artist: String)

@Serializable
private data class FlashMatch(
    val index: Int,
    @SerialName("trackId") val trackId: String = "",
    @SerialName("durationMs") val durationMs: Long = 0,
)

@Serializable
private data class FlashTrack(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("durationMs") val durationMs: Long = 0,
    val renditions: List<FlashRendition> = emptyList(),
    val variants: List<FlashVariant> = emptyList(),
)

/** One stored encoding; exactly one per track is the master the tiers are made from. */
@Serializable
private data class FlashRendition(
    val codec: String = "",
    @SerialName("sampleRate") val sampleRate: Int = 0,
    val bits: Int = 0,
    val bitrate: Int = 0,
    @SerialName("isMaster") val isMaster: Boolean = false,
)

/**
 * A slowed or sped-up edit. Normally [path] is empty and the player applies
 * [rate] itself; a non-empty path means the server had to keep the file
 * because the edit could not be reproduced from the base recording, and in
 * that case [rate] is always 1 — the speed is already in the audio.
 */
@Serializable
private data class FlashVariant(
    val kind: String = "",
    val rate: Double = 1.0,
    @SerialName("pitchFollowsRate") val pitchFollowsRate: Boolean = true,
    val reverb: Boolean = false,
    val verified: Boolean = false,
    val path: String = "",
)
