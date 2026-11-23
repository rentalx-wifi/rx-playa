package com.rentalx.playa.data.personalization

import com.rentalx.playa.data.model.ArtistRef
import com.rentalx.playa.data.model.HomeSource
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.model.durationMillis
import com.rentalx.playa.data.model.normalizedArtistName
import com.rentalx.playa.data.spotify.isSpotifySourceTrack
import com.rentalx.playa.data.sources.TrackMatcher
import com.rentalx.playa.playback.TrackText
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

enum class TasteSignalKind { LIKE, LISTEN, HISTORY, PLAYLIST, LIBRARY, ARTIST, DISLIKE }

/** A real observation. Missing service timestamps stay missing rather than becoming a new like every refresh. */
data class TasteSignal(
    val id: String,
    val source: HomeSource,
    val kind: TasteSignalKind,
    val song: Song? = null,
    val artist: ArtistRef? = null,
    val atMs: Long? = null,
    val count: Int = 1,
    val playedMs: Long = 0L,
    val order: Int = 0,
    val requiresMatching: Boolean = false,
    /** Non-null only for remote account data. Device history and owned imports are independent. */
    val account: String? = null,
)

data class TasteTrack(
    val song: Song,
    val sources: Set<HomeSource>,
    val score: Double,
    val liked: Boolean,
    val likedAtMs: Long?,
    val likeOrder: Int,
    val plays: Int,
    val lastPlayedAtMs: Long?,
    val requiresMatching: Boolean,
)

data class TasteArtist(val artist: ArtistRef, val score: Double, val sources: Set<HomeSource>)

data class TasteProfile(
    val tracks: List<TasteTrack> = emptyList(),
    val artists: List<TasteArtist> = emptyList(),
    val dislikes: List<Song> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty()
    val recentLikes: List<TasteTrack> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        tracks.filter { it.liked }.sortedWith(
            compareByDescending<TasteTrack> { it.likedAtMs ?: Long.MIN_VALUE }
                .thenBy { it.likeOrder }.thenByDescending { it.score }.thenBy { it.song.videoId },
        )
    }
}

/** Pure deterministic merge/ranking, shared by every provider and testable without accounts or Android. */
object TasteProfiles {
    fun merge(
        signals: List<TasteSignal>,
        accounts: Map<HomeSource, String> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
    ): TasteProfile {
        val active = signals.asSequence()
            .filter { it.account == null || accounts[it.source] == it.account }
            .groupBy { Triple(it.source, it.account, it.id) }
            .values.map { repeated ->
                repeated.maxWithOrNull(compareBy<TasteSignal> { it.atMs ?: 0L }.thenBy { it.count })!!
            }
        val dislikes = active.filter { it.kind == TasteSignalKind.DISLIKE }.mapNotNull { it.song }
        val dislikedIds = dislikes.mapTo(HashSet()) { it.videoId }
        val dislikedTitles = dislikes.groupBy { titleKey(it.title) }
        val groups = LinkedHashMap<String, MutableList<MutableTrack>>()
        val byId = HashMap<String, MutableTrack>()
        val artistScores = LinkedHashMap<String, MutableArtist>()

        fun credit(ref: ArtistRef, source: HomeSource, score: Double) {
            val key = normalizedArtistName(ref.name)
            if (key.isBlank() || key == "various artists" || score <= 0.0) return
            val artist = artistScores.getOrPut(key) { MutableArtist(ref) }
            if (artist.ref.browseId == null && ref.browseId?.startsWith("UC") == true) artist.ref = ref
            artist.score += score
            artist.sources += source
        }

        active.forEach { signal ->
            val weight = weight(signal, nowMs)
            if (signal.kind == TasteSignalKind.ARTIST) {
                signal.artist?.let { credit(it, signal.source, weight) }
                return@forEach
            }
            val song = signal.song?.takeIf { it.title.isNotBlank() && it.videoId.isNotBlank() } ?: return@forEach
            val title = titleKey(song.title)
            if (signal.kind == TasteSignalKind.DISLIKE || song.videoId in dislikedIds ||
                dislikedTitles[title].orEmpty().any { sameRecording(song, it) }
            ) return@forEach
            val bucket = groups.getOrPut(title) { mutableListOf() }
            val track = byId[song.videoId] ?: bucket.firstOrNull { sameRecording(it.song, song) }
                ?: MutableTrack(song, signal.requiresMatching).also { bucket += it }
            byId[song.videoId] = track
            val preferSpotifyIdentity = song.isSpotifySourceTrack && !track.song.isSpotifySourceTrack &&
                track.song.localUri == null
            if (preferSpotifyIdentity || (track.matching && !signal.requiresMatching) ||
                (track.matching == signal.requiresMatching && song.localUri != null && track.song.localUri == null)
            ) {
                track.song = song
                track.matching = signal.requiresMatching
            } else if (track.song.thumbnailUrl == null && song.thumbnailUrl != null) {
                track.song = track.song.copy(thumbnailUrl = song.thumbnailUrl)
            }
            track.sources += signal.source
            track.score += weight
            if (signal.kind == TasteSignalKind.LIKE) {
                track.liked = true
                track.likeOrder = minOf(track.likeOrder, signal.order)
                signal.atMs?.let { track.likedAt = maxOf(track.likedAt ?: Long.MIN_VALUE, it) }
            }
            if (signal.kind == TasteSignalKind.LISTEN) {
                track.plays += signal.count.coerceAtLeast(0)
                signal.atMs?.let { track.lastPlay = maxOf(track.lastPlay ?: Long.MIN_VALUE, it) }
            }
            val refs = song.artists.ifEmpty { listOf(ArtistRef(song.artist, song.artistId)) }
            refs.forEach { credit(it, signal.source, weight / refs.size.coerceAtLeast(1)) }
        }
        val tracks = groups.values.flatten().map { it.freeze() }
            .sortedWith(compareByDescending<TasteTrack> { it.score }.thenBy { it.song.videoId })
        val artists = artistScores.values.map { TasteArtist(it.ref, it.score, it.sources.toSet()) }
            .sortedWith(compareByDescending<TasteArtist> { it.score }.thenBy { normalizedArtistName(it.artist.name) })
        return TasteProfile(tracks, artists, dislikes)
    }

    fun rankCandidates(profile: TasteProfile, candidates: List<TasteTrack>): List<TasteTrack> {
        val distinct = mutableListOf<TasteTrack>()
        val byId = HashMap<String, Int>()
        val byTitle = HashMap<String, MutableList<Int>>()
        val dislikedIds = profile.dislikes.mapTo(HashSet()) { it.videoId }
        val dislikedTitles = profile.dislikes.groupBy { titleKey(it.title) }
        candidates.forEach { candidate ->
            val title = titleKey(candidate.song.title)
            if (candidate.song.videoId in dislikedIds ||
                dislikedTitles[title].orEmpty().any { sameRecording(it, candidate.song) }
            ) return@forEach
            val existing = byId[candidate.song.videoId] ?: byTitle[title].orEmpty()
                .firstOrNull { sameRecording(distinct[it].song, candidate.song) }
            if (existing == null) {
                byId[candidate.song.videoId] = distinct.size
                byTitle.getOrPut(title) { mutableListOf() } += distinct.size
                distinct += candidate
            } else {
                byId[candidate.song.videoId] = existing
                val previous = distinct[existing]
                val preferred = when {
                    candidate.song.localUri != null && previous.song.localUri == null -> candidate
                    previous.song.localUri != null && candidate.song.localUri == null -> previous
                    candidate.song.isSpotifySourceTrack && !previous.song.isSpotifySourceTrack -> candidate
                    previous.song.isSpotifySourceTrack && !candidate.song.isSpotifySourceTrack -> previous
                    previous.requiresMatching && !candidate.requiresMatching -> candidate
                    !previous.requiresMatching && candidate.requiresMatching -> previous
                    candidate.score > previous.score -> candidate
                    else -> previous
                }
                distinct[existing] = preferred.copy(
                    sources = previous.sources + candidate.sources,
                    score = maxOf(previous.score, candidate.score),
                    liked = previous.liked || candidate.liked,
                    likedAtMs = listOfNotNull(previous.likedAtMs, candidate.likedAtMs).maxOrNull(),
                    likeOrder = minOf(previous.likeOrder, candidate.likeOrder),
                    plays = maxOf(previous.plays, candidate.plays),
                    lastPlayedAtMs = listOfNotNull(previous.lastPlayedAtMs, candidate.lastPlayedAtMs).maxOrNull(),
                )
            }
        }
        val artistScores = profile.artists.associate { normalizedArtistName(it.artist.name) to it.score }
        // Compute each score once. A sort comparator must not scan and normalize the entire artist library.
        val ranked = distinct.map { candidate ->
            val names = candidate.song.artists.map { normalizedArtistName(it.name) }
                .ifEmpty { listOf(normalizedArtistName(candidate.song.artist)) }.toSet()
            val affinity = names.sumOf { artistScores[it] ?: 0.0 }
            candidate to (candidate.score + ln(1.0 + affinity))
        }.sortedWith(compareByDescending<Pair<TasteTrack, Double>> { it.second }
            .thenBy { it.first.song.videoId }).map { it.first }
        return diversify(ranked)
    }

    /**
     * Breaks up runs by one artist without disturbing the ranking otherwise.
     *
     * A liked artist carries enough weight that its tracks legitimately reach the
     * top of a personalised list, but a listener reads three in a row as "this is
     * the same handful of songs again". Later rows are pushed behind the run
     * instead of being dropped.
     */
    private fun diversify(items: List<TasteTrack>): List<TasteTrack> {
        if (items.size < 3) return items
        val out = ArrayList<TasteTrack>(items.size)
        val deferred = ArrayDeque<TasteTrack>()
        var previous: String? = null
        var run = 0
        items.forEach { item ->
            val key = TrackText.titleKey(item.song.artist)
            if (key == previous && run >= 2) {
                deferred += item
            } else {
                run = if (key == previous) run + 1 else 1
                previous = key
                out += item
            }
        }
        out += deferred
        return out
    }

    fun sameRecording(left: Song, right: Song): Boolean {
        if (left.videoId.isNotBlank() && left.videoId == right.videoId) return true
        if (titleKey(left.title) != titleKey(right.title)) return false
        if (!TrackMatcher.sharesArtist(left.artist, right.artist)) return false
        if (left.isExplicit != null && right.isExplicit != null && left.isExplicit != right.isExplicit) return false
        val a = left.durationMillis()
        val b = right.durationMillis()
        // A lyric, live or music-video upload of one recording runs tens of
        // seconds off the album cut, so an exact-title, shared-credit pair
        // is given room for that instead of counting as two songs.
        return a <= 0L || b <= 0L || abs(a - b) <= 30000L
    }


    private fun titleKey(title: String): String = TrackText.titleKey(title)

    private fun weight(signal: TasteSignal, nowMs: Long): Double {
        val days = signal.atMs?.let { (nowMs - it).coerceAtLeast(0L) / 86_400_000.0 }
        fun decay(halfLife: Double) = days?.let { exp(-ln(2.0) * it / halfLife) } ?: 1.0
        return when (signal.kind) {
            TasteSignalKind.LIKE -> (6.0 + 1.0 / (1.0 + signal.order.coerceAtLeast(0) * 0.05)) * decay(180.0)
            TasteSignalKind.LISTEN -> (2.0 * ln(1.0 + signal.count.coerceAtLeast(0)) +
                (signal.playedMs.coerceAtLeast(0L) / 60_000.0).coerceAtMost(2.0) * 0.25) * decay(30.0)
            TasteSignalKind.HISTORY -> decay(30.0) / (1.0 + signal.order.coerceAtLeast(0) * 0.05)
            TasteSignalKind.PLAYLIST -> 1.5 * decay(180.0)
            TasteSignalKind.LIBRARY -> 0.15
            TasteSignalKind.ARTIST -> 12.0 * decay(180.0)
            TasteSignalKind.DISLIKE -> 0.0
        }
    }

    private class MutableTrack(var song: Song, var matching: Boolean) {
        val sources = linkedSetOf<HomeSource>()
        var score = 0.0
        var liked = false
        var likedAt: Long? = null
        var likeOrder = Int.MAX_VALUE
        var plays = 0
        var lastPlay: Long? = null
        fun freeze() = TasteTrack(song, sources.toSet(), score, liked, likedAt, likeOrder, plays, lastPlay, matching)
    }

    private class MutableArtist(var ref: ArtistRef) {
        var score = 0.0
        val sources = linkedSetOf<HomeSource>()
    }
}
