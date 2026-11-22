package com.music.bitchord.playback

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.TrackRatingStore
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.importing.BasicExternalPlaylistTrack
import com.music.bitchord.data.importing.ExternalPlaylistImporter
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.normalizedArtistName
import com.music.bitchord.data.personalization.TasteProfile
import com.music.bitchord.data.personalization.WaveMemory
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.TrackText
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/** Most liked artists whose radios one wave asks, before it gives up. */
private const val MAX_PERSONAL_WAVE_SEEDS = 24

/** How many tracks the RX Wave station is built from before the queue trims it. */
private const val PERSONAL_WAVE_TRACKS = 40

/** How long after playing a track a wave is not allowed to serve it again. */
private const val PLAYED_COOLDOWN_MS = 72L * 60L * 60L * 1000L


private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * Uploads that advertise themselves as somebody else's edit of a song.
 *
 * Slowed, sped up, nightcore, 8D, karaoke and cover uploads are what a radio
 * answers with when it has run out of the real thing — and one of them in the
 * middle of a station is enough to make the whole queue feel wrong. The rule
 * only bites when the credit does not match the seed's: an artist's own slowed
 * edit is still their song, and a listener whose taste is built on those edits
 * would otherwise lose exactly what they asked for.
 */
private val STRANGER_EDIT = Regex(
    """\b(karaoke|cover|tribute|nightcore|8d|sped ?up|speed ?up|slowed|reverb|""" +
        """instrumental|ringtone|mashup|reaction|lyrics? video|phonk|brazil|brasil|""" +
        """br ?funk|funk ?brasileiro|tiktok|meme|suno|udio|нейросет\w*|нейро-|""" +
        """ai ?cover|ai[ -]generated|ai ?song)\b""",
    RegexOption.IGNORE_CASE,
)

private fun isStrangerEdit(candidate: Song, seed: Song): Boolean {
    if (STRANGER_EDIT.containsMatchIn(seed.title)) return false
    if (!STRANGER_EDIT.containsMatchIn(candidate.title)) return false
    return !TrackMatcher.sharesArtist(seed.artist, candidate.artist)
}

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/** True only for a real YouTube upload id, never an external provider identity. */
internal fun isYouTubeVideoId(id: String): Boolean = YOUTUBE_VIDEO_ID.matches(id)

/**
 * Finds the strict YouTube recording that can seed AutoPlay. SoundCloud, Spotify,
 * VK, addon and device identities are metadata ids, not YouTube video ids; sending
 * one directly to `next` yields an empty/broken RX Wave.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    if (isYouTubeVideoId(song.videoId)) return song.videoId
    return ExternalPlaylistImporter.match(
        BasicExternalPlaylistTrack(
            title = song.title,
            artist = song.artist,
            album = song.albumName,
            durationMs = song.durationMillis(),
            coverUrl = song.thumbnailUrl,
        ),
    )?.videoId
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): Result<List<Song>> {
    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    // A music video plays on with music videos, as YouTube Music does.
    val videos = seedSong.isVideo && seed == seedSong.videoId
    val related = YtMusicRepository.radio(seed, preferVideo = videos).getOrElse { return Result.failure(it) }
        .filterNot { isStrangerEdit(it, seedSong) }
        .let { QueueBuilder.rankRelated(seedSong, it) }
    val extra = QueueBuilder.extend(
        existing = existing,
        candidates = related,
        limit = limit,
        preferVideos = videos,
        excluded = TrackRatingStore.dislikedSongs(),
        seedSong = seedSong,
    )
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}


/**
 * The station behind the "RX Wave" pill.
 *
 * This is only the wiring: likes in, a radio per seed, the planner's answer
 * out. Every rule about what is allowed lives in [PersonalWavePlanner], which
 * is pure and self-tested — see `PersonalWavePlannerTest`, which prints the
 * exact list and the reason for each row.
 *
 * One thing deliberately absent: a fallback. There is no branch here that
 * returns the catalogue's mix, an unfiltered radio list, or "the top tracks"
 * when the filtered pool runs dry. A wave that cannot be built from the
 * listener's own liked artists is short, and a short station is honest.
 */
/** How many of an artist's tracks must be thumbed down before the act goes too. */
private const val DISLIKES_TO_DROP_ARTIST = 2

suspend fun loadPersonalWave(
    profile: TasteProfile,
    disliked: List<Song> = TrackRatingStore.dislikedSongs(),
    exclude: Collection<Song> = emptyList(),
    limit: Int = PERSONAL_WAVE_TRACKS,
    random: Random = Random.Default,
    nowMs: Long = System.currentTimeMillis(),
    /** Build the station even if every row in it played recently — the last resort before a one-seed radio. */
    ignoreRecent: Boolean = false,
): Result<List<Song>> {
    val liked = profile.tracks.filter { it.liked && it.song.videoId.isNotBlank() }
    if (liked.isEmpty()) return Result.success(emptyList())

    val likedArtists = liked
        .groupBy({ normalizedArtistName(it.song.artist) }, { it })
        .filterKeys { it.isNotEmpty() }
        .mapValues { (_, tracks) -> tracks.size.toDouble() }

    // Rule 12: every press asks a different sample of the liked artists. Taking
    // the first N likes in the same order was half of why the station never changed.
    val seeds = PersonalWavePlanner.rotateSeeds(
        candidates = (profile.recentLikes.map { it.song } + liked.sortedByDescending { it.score }.map { it.song })
            .filter { it.videoId.isNotBlank() && it.title.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy { normalizedArtistName(it.artist) }
            .map { song ->
                PersonalWavePlanner.SeedCandidate(
                    song = song,
                    weight = 1.0 + (likedArtists[normalizedArtistName(song.artist)] ?: 0.0),
                )
            },
        limit = MAX_PERSONAL_WAVE_SEEDS,
        random = random,
    )
    if (seeds.isEmpty()) return Result.success(emptyList())

    val historyArtists = profile.tracks
        .filterNot { it.liked }
        .map { normalizedArtistName(it.song.artist) }
        .filter { it.isNotEmpty() }
        .toSet()

    // Rule 11: a station is what comes next, not a replay. What an earlier press
    // served, what the player has played since, and what is still sitting in the
    // queue are all out — that is the whole difference between "my wave" and
    // "the same forty tracks in a circle".
    val served = WaveMemory.recentlyServed(nowMs)
    val playedRecently = profile.tracks.filter { track ->
        val at = track.lastPlayedAtMs ?: 0L
        at > 0L && at >= nowMs - PLAYED_COOLDOWN_MS
    }
    val recentVideoIds = if (ignoreRecent) emptySet() else buildSet {
        addAll(served.videoIds)
        playedRecently.forEach { add(it.song.videoId) }
        exclude.forEach { add(it.videoId) }
    }
    val recentRecordings = if (ignoreRecent) emptySet() else buildSet {
        addAll(served.recordings)
        playedRecently.forEach { add(PersonalWavePlanner.recordingKey(it.song)) }
        exclude.forEach { add(PersonalWavePlanner.recordingKey(it)) }
    }

    val plan = PersonalWavePlanner.plan(
        input = PersonalWavePlanner.Input(
            seeds = seeds.map { PersonalWavePlanner.Seed(it, liked = true) },
            likedRecordings = liked.mapTo(HashSet()) { PersonalWavePlanner.recordingKey(it.song) },
            likedArtists = likedArtists,
            historyArtists = historyArtists,
            // One thumb-down is a verdict on one track, not on the act: the
            // planner bans a disliked recording on its own, and an artist only
            // once the likes are a pattern. Desagreeing with a single song used
            // to take the whole artist out of the station, which is a much
            // bigger statement than the button makes.
            dislikedArtists = disliked
                .groupingBy { normalizedArtistName(it.artist) }
                .eachCount()
                .filterValues { count -> count >= DISLIKES_TO_DROP_ARTIST }
                .keys,
            dislikedRecordings = disliked.mapTo(HashSet()) { PersonalWavePlanner.recordingKey(it) },
            limit = limit,
            recentVideoIds = recentVideoIds,
            recentRecordings = recentRecordings,
            random = random,
        ),
        radio = { seed ->
            val videoId = youtubeSeedFor(seed) ?: return@plan emptyList()
            YtMusicRepository.radio(videoId, preferVideo = seed.isVideo).getOrNull().orEmpty()
        },
    )
    WaveMemory.remember(plan.picks.map { it.song }, nowMs)
    TrackLog.d(
        "BitChord",
        "wave: ${plan.seedsAsked.size} seeds asked, ${plan.picks.size} picked, " +
            "${plan.rejects.size} rejected, ${recentVideoIds.size} recent ids skipped",
    )
    return Result.success(plan.picks.map { it.song })
}
