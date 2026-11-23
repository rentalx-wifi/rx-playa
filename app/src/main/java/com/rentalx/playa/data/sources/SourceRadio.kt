package com.rentalx.playa.data.sources

import com.rentalx.playa.data.TrackLog
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.model.durationMillis
import com.rentalx.playa.data.model.normalizedArtistName
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.data.yandex.YandexMusicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A station's next tracks, built from the catalogues this app can actually
 * reach — not from YouTube, which is what a network in a country Google does
 * not serve simply never answers.
 *
 * The radio used to be one call: the seed's YouTube "next" queue. When that
 * call is refused (403 from a blocked network is the common one, a missing
 * YouTube twin is the other), the station had nothing at all, and the only way
 * to hear anything was to press play on a row by hand.
 *
 * This asks every other configured source for the seed's *artist* — the same
 * question a station asks, in the words each catalogue understands — and keeps
 * what comes back, minus the seed itself. The rows it returns are the sources'
 * own, so playing one goes back to the source that named it rather than
 * paying for a YouTube match.
 */
object SourceRadio {

    /** How long one source is given before the station moves on without it. */
    private const val PER_SOURCE_TIMEOUT_MS = 3_000L

    /**
     * Sources asked for one seed.
     *
     * The planner asks a radio per seed, so every source in this list is a
     * request on the way to the first track: the three the listener's own order
     * puts first answer for a station, and the rest are not worth the wait.
     */
    private const val SOURCES_PER_SEED = 3

    /** How long an artist's answer is reused: seeds repeat across one wave. */
    private const val RESULT_CACHE_MS = 2 * 60 * 1000L

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Song>>>()

    /**
     * Sources that answered too slowly to be worth asking again for a while.
     *
     * A station asks a radio per seed, and one catalogue that takes two seconds
     * per question sets the pace for the whole station — the first wave measures
     * that, and the next five minutes go to the catalogues that answer.
     */
    private val slowSources = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val SLOW_SOURCE_NANOS = 1_200_000_000L
    private const val SLOW_SOURCE_REST_MS = 5 * 60 * 1000L

    /** How long one rotor batch is reused before asking for the next one. */
    private const val WAVE_CACHE_MS = 4 * 60 * 1000L

    @Volatile
    private var cachedWave: Pair<Long, List<Song>>? = null

    /**
     * The account's own «Моя волна», when there is an account at all.
     *
     * Asked before anything this app invents: the rotor already knows what the
     * listener plays, in every genre they play it in — a per-seed artist search
     * can only offer more of the same artist, which is not a station.
     *
     * Cached for a few minutes because the rotor hands out a handful of tracks
     * per session step and charging a request per autoplay tick would drain the
     * account's own queue.
     */
    suspend fun yandexPersonalWave(limit: Int): List<Song> {
        val configId = SourceRegistry.active()
            .firstOrNull { it.kind == SourceKind.YANDEX_MUSIC }
            ?.configId ?: return emptyList()
        val token = AppSettings.yandexMusicToken.value.trim()
        if (token.isBlank()) return emptyList()

        val now = System.currentTimeMillis()
        cachedWave?.let { (at, rows) -> if (now - at < WAVE_CACHE_MS && rows.isNotEmpty()) return rows }

        val rows = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS * 2) {
            runCatching {
                YandexMusicApi().waveTracks(token, limit).map { it.toSourceSong(configId) }
            }.onFailure {
                TrackLog.d("Playa", "wave radio: personal rotor failed: ${it.message}")
            }.getOrDefault(emptyList())
        }.orEmpty()

        if (rows.isNotEmpty()) cachedWave = now to rows
        TrackLog.d("Playa", "wave radio: personal rotor → ${rows.size} tracks")
        return rows
    }

    /**
     * Tracks near [seed], from every source that can answer.
     *
     * [limit] is per source: a catalogue that answers with ten kin of the seed
     * is more useful than four catalogues that each answered one.
     */
    suspend fun related(seed: Song, limit: Int): List<Song> {
        val artist = normalizedArtistName(seed.artist)
        val query = artist.ifBlank { seed.title }.ifBlank { return emptyList() }
        val cacheKey = "$query|$limit"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { (at, rows) -> if (now - at < RESULT_CACHE_MS) return rows }
        val seedKey = seed.durationMillis()
            .let { if (it <= 0) "" else (it / 1000 / 2).toString() }

        val rows = coroutineScope {
            SourceRegistry.active()
                .filter { it.kind != SourceKind.YOUTUBE }
                .sortedBy { it.kind.rank }
                .filter { source ->
                    val until = slowSources[source.configId] ?: 0L
                    now >= until
                }
                .take(SOURCES_PER_SEED)
                .map { source ->
                    async {
                        val startedAt = System.nanoTime()
                        val rows = withTimeoutOrNull(PER_SOURCE_TIMEOUT_MS) {
                            runCatching { source.search(query, limit) }
                                .onFailure {
                                    TrackLog.d("Playa", "wave radio: ${source.displayName} failed: ${it.message}")
                                }
                                .getOrDefault(emptyList())
                        }.orEmpty()
                        if (System.nanoTime() - startedAt > SLOW_SOURCE_NANOS) {
                            slowSources[source.configId] = now + SLOW_SOURCE_REST_MS
                        }
                        rows
                    }
                }
                .awaitAll()
                .flatten()
        }.filterNot { it.videoId == seed.videoId }
            .filterNot { candidate ->
                // The same recording from another catalogue is not a new track.
                artist.isNotBlank() &&
                    normalizedArtistName(candidate.artist) == artist &&
                    candidate.title.equals(seed.title, ignoreCase = true) &&
                    (seedKey.isEmpty() || candidate.durationMillis().let { if (it <= 0) "" else (it / 1000 / 2).toString() } == seedKey)
            }
            .distinctBy { it.videoId }

        TrackLog.d("Playa", "wave radio: \"$query\" → ${rows.size} tracks from non-YouTube sources")
        if (cache.size > 32) cache.clear()
        cache[cacheKey] = now to rows
        return rows
    }
}
