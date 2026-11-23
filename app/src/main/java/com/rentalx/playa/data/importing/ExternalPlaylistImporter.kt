package com.rentalx.playa.data.importing

import com.rentalx.playa.data.YtMusicRepository
import com.rentalx.playa.data.model.SearchFilter
import com.rentalx.playa.data.model.SearchResult
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Metadata every playlist source can provide without copying audio. */
interface ExternalPlaylistTrack {
    val title: String
    val artist: String
    val album: String?
    val durationMs: Long
    val coverUrl: String?
}

data class BasicExternalPlaylistTrack(
    override val title: String,
    override val artist: String,
    override val album: String?,
    override val durationMs: Long,
    override val coverUrl: String?,
) : ExternalPlaylistTrack

/** Unlimited-by-app, order-preserving metadata migration into playable YouTube Music ids. */
object ExternalPlaylistImporter {
    suspend fun <T : ExternalPlaylistTrack> resolve(
        tracks: List<T>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<T>> = resolve(
        tracks = tracks,
        candidatesFor = YtMusicRepository::songCandidates,
        onProgress = onProgress,
        fallbackCandidatesFor = PublicYouTubeFallback::candidates,
    )

    /** One external row for an interactive page such as a Spotify playlist. */
    suspend fun <T : ExternalPlaylistTrack> match(track: T): Song? = findMatch(
        track = track,
        candidatesFor = YtMusicRepository::songCandidates,
        fallbackCandidatesFor = PublicYouTubeFallback::candidates,
    )

    /**
     * The top YouTube search hit for [track], with no recording match attached.
     *
     * The last rung for a row whose own catalogue no longer serves it. A strict
     * match is the right question when something is being *substituted* for a
     * track that plays; it is the wrong one when the alternative is a row that
     * does nothing, because a SoundCloud extended mix or a sped-up edit fails
     * the runtime check every time. See `SourceResolver.youtubeTopHit`.
     */
    suspend fun <T : ExternalPlaylistTrack> topHit(track: T): Song? {
        val query = listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null
        val target = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs.takeIf { it > 0L }?.div(1000L)?.toInt(),
            album = track.album,
        )
        val rows = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }
        // Same rule as SourceResolver's: a checked hit, never simply the first
        // one — an unchecked top row is how another artist's cover ends up
        // wearing the title of the track that was asked for.
        return TrackMatcher.looseHit(rows, target)
    }

    internal suspend fun <T : ExternalPlaylistTrack> resolve(
        tracks: List<T>,
        candidatesFor: suspend (title: String, artist: String) -> List<Song>,
        onProgress: (completed: Int, total: Int) -> Unit,
        fallbackCandidatesFor: (suspend (title: String, artist: String) -> List<Song>)? = null,
        /** How long one row may take to match; see the guard below. */
        matchTimeoutMs: Long = MATCH_TIMEOUT_MS,
    ): Pair<List<Song>, List<T>> = coroutineScope {
        val completed = AtomicInteger(0)
        val gate = Semaphore(MATCH_PARALLELISM)
        val matches = tracks.mapIndexed { index, track ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    // Every row is awaited before anything is returned, so one
                    // search that never answers used to hold the whole import
                    // at a progress counter that had stopped moving. A row that
                    // cannot be settled in [matchTimeoutMs] counts as a row that
                    // was not found — which is a result the listener can act on.
                    val match = withTimeoutOrNull(matchTimeoutMs) {
                        findMatch(
                            track = track,
                            candidatesFor = candidatesFor,
                            fallbackCandidatesFor = fallbackCandidatesFor,
                        )
                    }
                    val done = completed.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(done, tracks.size) }
                    Triple(index, match?.copy(thumbnailUrl = match.thumbnailUrl ?: track.coverUrl), track)
                }
            }
        }.awaitAll().sortedBy { it.first }
        matches.mapNotNull { it.second } to matches.filter { it.second == null }.map { it.third }
    }

    private suspend fun <T : ExternalPlaylistTrack> findMatch(
        track: T,
        candidatesFor: suspend (title: String, artist: String) -> List<Song>,
        fallbackCandidatesFor: (suspend (title: String, artist: String) -> List<Song>)?,
    ): Song? {
        val target = TrackMatcher.Target(
            title = track.title,
            artist = track.artist,
            durationSec = track.durationMs.takeIf { it > 0L }?.div(1000L)?.toInt(),
            album = track.album,
        )
        // Strict on purpose: this is another service's playlist being recreated,
        // and a near miss puts the wrong song in it permanently. See [TrackMatcher.strictBest].
        TrackMatcher.strictBest(candidatesFor(track.title, track.artist), target)?.let { return it }
        val fallback = fallbackCandidatesFor ?: return null
        return TrackMatcher.strictBest(fallback(track.title, track.artist), target)
    }

    private const val MATCH_PARALLELISM = 4

    /**
     * How long one row may spend in the catalogue before it counts as missing.
     *
     * Generous: two searches and a fallback, each of which has its own request
     * timeout. What it stops is the case the issue was filed about — a search
     * that never comes back, holding an import that showed no way to finish.
     */
    private const val MATCH_TIMEOUT_MS = 20_000L
}
