package com.music.bitchord.data.soundcloud

import android.net.Uri
import com.music.bitchord.data.Http
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

internal data class SoundCloudStream(
    val url: String,
    val bitrateKbps: Int,
    val mimeType: String?,
    val headers: Map<String, String>,
)

internal data class SoundCloudTranscoding(
    val endpoint: String,
    val preset: String,
    val protocol: String,
    val mimeType: String?,
)

internal data class SoundCloudPlaybackMetadata(
    val trackAuthorization: String?,
    val transcodings: List<SoundCloudTranscoding>,
    val durationMs: Long,
)

/** Resolves a public SoundCloud page directly to its non-DRM AAC-HLS stream. */
internal object SoundCloudPlayback {
    private data class Cached(val stream: SoundCloudStream, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val extraction = Mutex()

    suspend fun resolve(sourceUrl: String): SoundCloudStream {
        require(isTrustedSoundCloudUrl(sourceUrl)) { "Invalid SoundCloud track URL" }
        val now = System.currentTimeMillis()
        cache[sourceUrl]?.takeIf { now - it.atMs < CACHE_MS }?.let { return it.stream }
        return extraction.withLock {
            val refreshedAt = System.currentTimeMillis()
            cache[sourceUrl]?.takeIf { refreshedAt - it.atMs < CACHE_MS }?.let { return@withLock it.stream }
            // Prefer the official account endpoint when the listener connected
            // SoundCloud. It survives public web-client-id rotation and can also
            // represent account-visible tracks. Public mode below remains fully
            // functional when no OAuth application has been configured.
            val official = runCatching { SoundCloudAccountApi.officialStream(sourceUrl) }.getOrNull()
            if (official != null) {
                runCatching {
                    val manifestUrl = SoundCloudManifestStore.prepare(
                        signedUrl = official.url,
                        durationMs = official.durationMs,
                        headers = PLAYBACK_HEADERS,
                    )
                    SoundCloudStream(
                        url = manifestUrl,
                        bitrateKbps = official.bitrateKbps,
                        mimeType = "application/x-mpegURL",
                        headers = PLAYBACK_HEADERS,
                    )
                }.getOrNull()?.let { resolved ->
                    cache[sourceUrl] = Cached(resolved, System.currentTimeMillis())
                    return@withLock resolved
                }
            }
            // NewPipe 0.26.3 ignores SoundCloud's aac_96k transcoding and
            // resolves a page through desktop soundcloud.com. The former makes
            // a large part of the 2026 catalogue unplayable; the latter stalls
            // on networks where only the desktop host is filtered. The mobile
            // API gives us both AAC variants without touching that page.
            val metadata = parseSoundCloudPlaybackMetadata(SoundCloudApi.resolve(sourceUrl))
            var lastFailure: Throwable? = null
            val resolved = metadata.transcodings
                .sortedWith(compareBy<SoundCloudTranscoding>(::soundCloudTranscodingRank))
                .firstNotNullOfOrNull { transcoding ->
                    runCatching {
                        val url = SoundCloudApi.signedStreamUrl(
                            transcoding.endpoint,
                            metadata.trackAuthorization,
                        )
                        val manifestUrl = SoundCloudManifestStore.prepare(
                            signedUrl = url,
                            durationMs = metadata.durationMs,
                            headers = PLAYBACK_HEADERS,
                        )
                        SoundCloudStream(
                            url = manifestUrl,
                            bitrateKbps = soundCloudBitrateKbps(transcoding.preset),
                            mimeType = "application/x-mpegURL",
                            headers = PLAYBACK_HEADERS,
                        )
                    }.onFailure { lastFailure = it }.getOrNull()
                }
                ?: throw IOException("No public SoundCloud HLS stream is available", lastFailure)
            cache[sourceUrl] = Cached(resolved, System.currentTimeMillis())
            if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
            resolved
        }
    }

    fun playbackUri(mediaId: String, sourceUrl: String): String =
        "bitchord://soundcloud?id=${android.net.Uri.encode(mediaId)}&url=${android.net.Uri.encode(sourceUrl)}"

    fun isTrustedSoundCloudUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return uri.scheme.equals("https", ignoreCase = true) &&
            (host == "soundcloud.com" || host.endsWith(".soundcloud.com"))
    }

    private const val CACHE_MS = 10 * 60_000L
    private const val MAX_CACHE_ENTRIES = 64
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36"
    private val PLAYBACK_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://soundcloud.com/",
    )
}

internal fun parseSoundCloudPlaybackMetadata(root: JsonObject): SoundCloudPlaybackMetadata {
    if (root.string("kind") != "track") throw IOException("SoundCloud URL is not a track")
    if (root["streamable"]?.jsonPrimitive?.booleanOrNull == false) {
        throw IOException("This SoundCloud track is not streamable")
    }
    when (root.string("policy")?.uppercase()) {
        "BLOCK", "SNIP" -> throw IOException("This SoundCloud track is not publicly available")
    }
    val rows = (root["media"] as? JsonObject)?.get("transcodings") as? JsonArray
    val transcodings = rows.orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val format = row["format"] as? JsonObject ?: return@mapNotNull null
        val endpoint = row.string("url")?.takeIf(::isTrustedSoundCloudApiUrl) ?: return@mapNotNull null
        val protocol = format.string("protocol") ?: return@mapNotNull null
        if (protocol != "hls") return@mapNotNull null
        SoundCloudTranscoding(
            endpoint = endpoint,
            preset = row.string("preset").orEmpty(),
            protocol = protocol,
            mimeType = format.string("mime_type"),
        )
    }
    if (transcodings.isEmpty()) throw IOException("No public SoundCloud HLS stream is available")
    val durationMs = root.long("full_duration") ?: root.long("duration")
        ?: throw IOException("SoundCloud track duration is unavailable")
    return SoundCloudPlaybackMetadata(root.string("track_authorization"), transcodings, durationMs)
}

internal fun soundCloudTranscodingRank(stream: SoundCloudTranscoding): Int = when {
    stream.preset.startsWith("aac_160k") -> 0
    stream.preset.startsWith("aac_96k") -> 1
    stream.preset.contains("mp3_128") -> 2
    stream.preset.contains("opus_64") -> 3
    stream.preset == "abr_sq" -> 4
    stream.mimeType?.contains("mp4a", ignoreCase = true) == true -> 5
    else -> 6
}

internal fun soundCloudBitrateKbps(preset: String): Int = when {
    preset.contains("160") -> 160
    preset.contains("128") || preset == "abr_sq" -> 128
    preset.contains("96") -> 96
    preset.contains("64") -> 64
    else -> 0
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "null" }

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

/**
 * SoundCloud's current CloudFront route honours ranges for AAC fragments but
 * ignores them for `playlist.m3u8`, then stalls part-way through that small
 * text file on affected networks. Only its first complete segment line is
 * needed: every following fragment uses the same wildcard-signed query and a
 * sequential `dataNNN.m4s` name. Build the equivalent finite VOD playlist in
 * memory and let Media3 fetch the real official fragments by bounded range.
 */
internal object SoundCloudManifestStore {
    private data class Entry(val bytes: ByteArray, val atMs: Long)

    private val manifests = ConcurrentHashMap<String, Entry>()
    private val ids = AtomicLong()
    private val client = Http.client.newBuilder()
        .callTimeout(MANIFEST_PREFIX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(MANIFEST_PREFIX_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun prepare(
        signedUrl: String,
        durationMs: Long,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        require(isTrustedSoundCloudMediaUrl(signedUrl)) { "Invalid SoundCloud manifest URL" }
        val request = Request.Builder().url(signedUrl).get().apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val prefix = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("SoundCloud manifest failed (${response.code})")
            val source = response.body.source()
            var initUrl: String? = null
            var firstSegmentUrl: String? = null
            var firstSegmentDurationMs: Long? = null
            var pendingDurationMs: Long? = null
            var targetDurationMs = DEFAULT_SEGMENT_MS
            var lines = 0
            while (lines++ < MAX_PREFIX_LINES) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("#EXT-X-MAP:")) {
                    initUrl = MAP_URI.find(line)?.groupValues?.getOrNull(1)
                } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    targetDurationMs = line.substringAfter(':').trim().toLongOrNull()
                        ?.times(1_000L)?.coerceAtLeast(1_000L) ?: targetDurationMs
                } else if (line.startsWith("#EXTINF:")) {
                    pendingDurationMs = line.substringAfter(':').substringBefore(',').trim()
                        .toDoubleOrNull()?.times(1_000.0)?.toLong()?.coerceAtLeast(1L)
                } else if (!line.startsWith('#') && line.isNotBlank()) {
                    val resolved = response.request.url.resolve(line.trim())?.toString()
                    if (resolved != null && resolved.contains("/data") && resolved.contains(".m4s")) {
                        firstSegmentUrl = resolved
                        firstSegmentDurationMs = pendingDurationMs
                        break
                    }
                }
            }
            val init = initUrl?.let { response.request.url.resolve(it)?.toString() }
                ?: throw IOException("SoundCloud manifest has no init fragment")
            val first = firstSegmentUrl ?: throw IOException("SoundCloud manifest has no media fragment")
            ManifestPrefix(
                init,
                first,
                firstSegmentDurationMs ?: targetDurationMs,
            )
        }
        val manifest = synthesizeSoundCloudManifest(
            prefix.initUrl,
            prefix.firstSegmentUrl,
            durationMs,
            prefix.segmentDurationMs,
        )
        // Resolving metadata and reading the first playlist lines can succeed
        // while the media CDN itself is blocked. Probe the exact ranged request
        // Media3 will use before declaring the native stream playable; failure
        // then reaches PlaybackService's strict YouTube fallback instead of a
        // spinner followed by a late ExoPlayer error.
        probeFirstSegment(prefix.firstSegmentUrl, headers)
        val id = ids.incrementAndGet().toString(36)
        if (manifests.size >= MAX_MANIFESTS) manifests.clear()
        manifests[id] = Entry(manifest.encodeToByteArray(), System.currentTimeMillis())
        "$SCHEME://$id/playlist.m3u8"
    }

    fun bytes(uri: Uri): ByteArray? {
        if (uri.scheme != SCHEME) return null
        val id = uri.authority ?: return null
        val entry = manifests[id] ?: return null
        if (System.currentTimeMillis() - entry.atMs > MANIFEST_TTL_MS) {
            manifests.remove(id, entry)
            return null
        }
        return entry.bytes
    }

    internal const val SCHEME = "rx-sc-manifest"
    private const val MANIFEST_PREFIX_TIMEOUT_SECONDS = 6L
    private const val MAX_PREFIX_LINES = 24
    private const val MAX_MANIFESTS = 96
    private const val MANIFEST_TTL_MS = 10 * 60_000L
    private val MAP_URI = Regex("""URI="([^"]+)"""")

    private fun probeFirstSegment(url: String, headers: Map<String, String>) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1")
            .get()
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            val range = response.header("Content-Range")
            if (response.code != 206 || range.isNullOrBlank()) {
                throw IOException("SoundCloud media CDN did not honor a bounded range (${response.code})")
            }
            if (response.body.byteStream().read() < 0) {
                throw IOException("SoundCloud media CDN returned an empty segment")
            }
        }
    }

    private data class ManifestPrefix(
        val initUrl: String,
        val firstSegmentUrl: String,
        val segmentDurationMs: Long,
    )
}

internal fun synthesizeSoundCloudManifest(
    initUrl: String,
    firstSegmentUrl: String,
    durationMs: Long,
    segmentDurationMs: Long = DEFAULT_SEGMENT_MS,
): String {
    require(isTrustedSoundCloudMediaUrl(initUrl)) { "Invalid SoundCloud init fragment" }
    require(isTrustedSoundCloudMediaUrl(firstSegmentUrl)) { "Invalid SoundCloud media fragment" }
    require(durationMs > 0L) { "Invalid SoundCloud duration" }
    val first = firstSegmentUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid SoundCloud media fragment")
    val file = first.pathSegments.lastOrNull().orEmpty()
    val match = SEGMENT_FILE.matchEntire(file)
        ?: throw IllegalArgumentException("Unexpected SoundCloud fragment name")
    val width = match.groupValues[1].length
    require(segmentDurationMs > 0L) { "Invalid SoundCloud segment duration" }
    val firstIndex = match.groupValues[1].toIntOrNull()
        ?: throw IllegalArgumentException("Invalid SoundCloud segment index")
    val count = ceil(durationMs / segmentDurationMs.toDouble()).toInt().coerceAtLeast(1)

    return buildString(count * 900) {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-TARGETDURATION:11")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-MAP:URI=\"$initUrl\"")
        repeat(count) { index ->
            val remainingMs = (durationMs - index * segmentDurationMs).coerceAtLeast(1L)
            val segmentMs = minOf(segmentDurationMs, remainingMs)
            append("#EXTINF:")
            append(String.format(Locale.US, "%.3f", segmentMs / 1_000.0))
            appendLine(",")
            val segmentName = "data${(firstIndex + index).toString().padStart(width, '0')}.m4s"
            appendLine(
                first.newBuilder()
                    .removePathSegment(first.pathSize - 1)
                    .addPathSegment(segmentName)
                    .build(),
            )
        }
        appendLine("#EXT-X-ENDLIST")
    }
}

private val SEGMENT_FILE = Regex("""data(\d+)\.m4s""")
private const val DEFAULT_SEGMENT_MS = 10_000L
