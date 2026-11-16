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
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.TrackText
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/** How many of the listener's own tracks seed the RX Wave station. */
private const val PERSONAL_WAVE_SEEDS = 3

/** How many tracks the RX Wave station is built from before the queue trims it. */
private const val PERSONAL_WAVE_TRACKS = 40

/** How many rows one artist may take in the station. */
private const val PERSONAL_WAVE_PER_ARTIST = 2

/** Below this, strangers top the station up rather than leaving it short. */
private const val MIN_PERSONAL_WAVE_TRACKS = 6

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
        """instrumental|ringtone|mashup|reaction|lyrics? video)\b""",
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
 * The station behind the "RX Wave" pill, built from what the listener liked.
 *
 * The catalogue's My Supermix was the wrong answer here: it belongs to the
 * account of the session the app holds, and for a guest it is the same bland
 * selection for everybody — which read as the app recommending music nobody had
 * asked for. This asks the local taste profile instead: up to four liked tracks
 * of theirs, one per artist, and each one's YouTube radio.
 *
 * The radio answers are then filtered, and the filter is the whole point. A
 * station is judged by its worst row, not its best: one stranger's slowed cover
 * in the middle of someone's music is enough to make the whole thing feel like
 * a random playlist. So a candidate is kept only when its artist is one the
 * profile actually knows — anything else is dropped outright, however well the
 * radio ranked it. If that leaves too short a station, the answer is *more
 * seeds*, not looser rules: the loop below keeps asking the radios of further
 * liked tracks before it ever accepts an unknown artist, and a short station is
 * preferred to a padded one.
 *
 * Also, no matter the stage: a disliked artist or recording never appears; a
 * track the profile already holds is left out (a station that plays the library
 * back is not a station); a stranger's upload advertising itself as slowed,
 * sped up, karaoke, cover or 8D is dropped; one recording appears once; no
 * artist takes more than [PERSONAL_WAVE_PER_ARTIST] rows and none twice in a row.
 */
suspend fun loadPersonalWave(
    profile: TasteProfile,
    disliked: List<Song> = TrackRatingStore.dislikedSongs(),
    limit: Int = PERSONAL_WAVE_TRACKS,
): Result<List<Song>> {
    if (profile.isEmpty) return Result.success(emptyList())

    // Likes first, then whatever is played most — one track per artist, so the
    // seeds are different corners of what they listen to.
    val likedSeeds = (profile.recentLikes.map { it.song } +
        profile.tracks.filter { it.liked }.sortedByDescending { it.score }.map { it.song })
    val seeds = (likedSeeds + profile.tracks.sortedByDescending { it.score }.map { it.song })
        .filter { it.videoId.isNotBlank() && it.title.isNotBlank() && it.artist.isNotBlank() }
        .distinctBy { normalizedArtistName(it.artist) }
    if (seeds.isEmpty()) return Result.success(emptyList())

    val artistWeight = profile.artists
        .mapNotNull { artist ->
            normalizedArtistName(artist.artist.name).takeIf { it.isNotEmpty() }?.let { it to artist.score }
        }
        .toMap()
    val knownRecordings = profile.tracks
        .map { normalizedArtistName(it.song.artist) to TrackText.titleKey(it.song.title) }
        .toHashSet()
    val likedTitles = profile.tracks.filter { it.liked }
        .map { TrackText.titleKey(it.song.title) }
        .toHashSet()
    val dislikedArtists = disliked.mapTo(HashSet()) { normalizedArtistName(it.artist) }
    val dislikedTitles = disliked.mapTo(HashSet()) { TrackText.titleKey(it.title) }

    // The edits nobody wants from an artist they never chose. Applied only to
    // strangers: a listener whose taste *is* slowed edits gets them from the
    // artists the profile already names.
    val seenIds = HashSet<String>()
    val seenTitles = HashSet<String>()
    val known = mutableListOf<Pair<Song, Double>>()
    val strangers = mutableListOf<Song>()

    // More seeds than the first pass needs, because a strict filter empties a
    // three-seed station quickly. Each round asks the next liked artist's radio
    // until the station is long enough or the seeds run out.
    for (seed in seeds) {
        if (known.size >= limit) break
        val videoId = youtubeSeedFor(seed) ?: continue
        val candidates = runCatching {
            YtMusicRepository.radio(videoId, preferVideo = seed.isVideo).getOrNull().orEmpty()
        }.getOrNull().orEmpty()
        for (song in candidates) {
            if (song.videoId.isBlank() || song.title.isBlank() || song.artist.isBlank()) continue
            val artist = normalizedArtistName(song.artist)
            val title = TrackText.titleKey(song.title)
            val weight = artistWeight[artist] ?: 0.0
            if (artist in dislikedArtists) continue
            if (title in dislikedTitles) continue
            if (artist to title in knownRecordings) continue
            if (!seenIds.add(song.videoId) || !seenTitles.add(title)) continue
            // A stranger advertising an edit, or an edit of a track they liked.
            if (isStrangerEdit(song, seed)) continue
            if (weight > 0.0) {
                known += song to weight
            } else {
                strangers += song
            }
        }
    }

    // Known artists only, strongest first. The strangers are the last resort and
    // are added *after* everything the profile knows, in radio order, so they
    // can only ever top up a station rather than colour one.
    val preferred = known.sortedByDescending { it.second }.map { it.first }
    val ordered = if (preferred.size >= MIN_PERSONAL_WAVE_TRACKS) {
        preferred
    } else {
        preferred + strangers.distinctBy { TrackText.titleKey(it.title) }
    }
    if (ordered.isEmpty()) return Result.success(emptyList())

    val picked = mutableListOf<Song>()
    val perArtist = HashMap<String, Int>()
    var lastArtist = ""
    for (song in ordered) {
        if (picked.size >= limit) break
        val artist = normalizedArtistName(song.artist)
        val used = perArtist[artist] ?: 0
        if (used >= PERSONAL_WAVE_PER_ARTIST) continue
        if (artist == lastArtist) continue
        picked += song
        perArtist[artist] = used + 1
        lastArtist = artist
    }
    TrackLog.d(
        "BitChord",
        "wave: ${seeds.size} seeds, ${known.size} known artists' tracks, " +
            "${strangers.size} strangers, ${picked.size} queued",
    )
    if (picked.isEmpty()) return Result.success(emptyList())
    return Result.success(picked)
}
