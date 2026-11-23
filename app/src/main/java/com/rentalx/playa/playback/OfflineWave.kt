package com.rentalx.playa.playback

import com.rentalx.playa.data.model.Song
import kotlin.random.Random

/**
 * The queue RX Wave plays when there is no network: only the files on this
 * device.
 *
 * The online station is built from seed artists and their catalogues
 * ([loadPersonalWave]); none of that exists offline, so this is a different
 * question — "what of what I own comes next" — and it is answered without
 * asking anything of the network.
 *
 * What it keeps from the online station, because both are about a station
 * rather than a playlist:
 *
 *  - a press never hands back the queue it was pressed over;
 *  - no artist twice in a row while another choice exists;
 *  - liked tracks lead, since those are the ones the listener kept closest;
 *  - nothing repeats within the answer.
 *
 * Pure and free of Android types, so `OfflineWaveTest` can pin the rules.
 */
object OfflineWave {

    /** Enough for a long drive without being the whole library in one queue. */
    const val LIMIT = 60

    /**
     * Two liked tracks, then one other, repeating — likes lead without the
     * station opening as a solid block of favourites.
     */
    private const val LIKED_RUN = 2

    fun plan(
        downloaded: List<Song>,
        likedIds: Set<String>,
        excludeIds: Set<String> = emptySet(),
        limit: Int = LIMIT,
        random: Random = Random.Default,
    ): List<Song> {
        // A download always has localUri; a record without one is a file the
        // user deleted under the index, and playing it offline is impossible.
        val owned = downloaded
            .filter { it.videoId.isNotBlank() && it.localUri != null }
            .distinctBy { PersonalWavePlanner.recordingKey(it) }
        if (owned.isEmpty()) return emptyList()

        val fresh = owned.filterNot { it.videoId in excludeIds }
        // Everything already in the queue still beats silence: the wave is
        // asked for on purpose, and an empty station is not an answer.
        val pool = (if (fresh.isEmpty()) owned else fresh).shuffled(random)

        val liked = pool.filter { it.videoId in likedIds }
        val rest = pool.filterNot { it.videoId in likedIds }
        val weighted = buildList(limit.coerceAtMost(pool.size)) {
            var l = 0
            var r = 0
            while ((l < liked.size || r < rest.size) && size < limit) {
                repeat(LIKED_RUN) {
                    if (l < liked.size && size < limit) add(liked[l++])
                }
                if (r < rest.size && size < limit) add(rest[r++])
            }
        }
        return spaced(weighted, limit)
    }

    /**
     * Greedy artist spacing: take the first row whose artist differs from the
     * one before it, and fall back to the next row when only the same artist is
     * left. Two tracks by one artist in a row is a small thing; dropping one
     * would be worse.
     */
    private fun spaced(rows: List<Song>, limit: Int): List<Song> {
        val remaining = rows.toMutableList()
        val out = ArrayList<Song>(minOf(limit, rows.size))
        var lastArtist = ""
        while (remaining.isNotEmpty() && out.size < limit) {
            val index = remaining.indexOfFirst { !it.artist.equals(lastArtist, ignoreCase = true) }
            val picked = remaining.removeAt(if (index >= 0) index else 0)
            lastArtist = picked.artist
            out += picked
        }
        return out
    }
}
