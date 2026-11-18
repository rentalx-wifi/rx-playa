package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.normalizedArtistName
import kotlin.random.Random

/**
 * The planning half of RX Wave: everything that decides what plays, with no
 * network and no Android in it.
 *
 * It exists as a separate, pure piece because the audio half — which radios to
 * ask — can only be judged by looking at what it produced. Everything here is
 * therefore a function of (likes, history, radios), and every decision is
 * recorded: [WavePlan.picks] carries the score and the reason a track got in,
 * and [WavePlan.rejects] every track that did not, with the rule that stopped
 * it. That log is what the self-test prints and what a "why is this in my wave"
 * question is answered from.
 *
 * ### The rules, in the order they are applied
 *
 * 1. A track must have an id, a title and an artist.
 * 2. A disliked artist, or a disliked recording, never plays.
 * 3. Anything already in the likes is not a recommendation.
 * 4. One recording appears once: by id, and by title plus runtime (a radio
 *    returns the same master under several uploads).
 * 5. A 30-second interlude or an hour-long "mix" is not a station track.
 * 6. Re-uploads are banned outright, for everybody, however liked the artist:
 *    slowed, reverb, sped up, nightcore, TikTok, 8D, karaoke, cover, tribute,
 *    instrumental, mashup, reaction, phonk, Brazilian funk. This is the exact
 *    class of upload that made the wave feel like somebody else's playlist.
 * 7. Chart and playlist filler is banned by title: top-N lists, "hits",
 *    "viral", "trending", "playlist", year-stamped compilations.
 * 8. An artist is *known* when they are in the likes, or in the history **and**
 *    named by the radios of at least one liked seed. History alone is not
 *    taste: it is whatever previous autoplay pushed, phonk included.
 * 9. An artist the likes do not know joins only when several seed radios name
 *    them — a neighbour of the taste, not a chart pick — and never more than
 *    one such artist in [DISCOVERY_EVERY] rows.
 * 10. An artist takes at most [MAX_PER_ARTIST] rows, and never two in a row.
 * 11. A track that was served by an earlier wave or played since goes to the
 *     back of the station. Press the pill twice and the second one opens with
 *     tracks the first one did not use; a repeat only appears once the fresh
 *     pool is smaller than the station.
 * 12. The order carries a little jitter, so the head of the station and the
 *     handful of newcomers are not the same rows on every press. Which liked
 *     artists get asked at all is also a sample — see [rotateSeeds].
 *
 * There is no branch that returns an unfiltered radio list. When the filtered
 * pool is too small the planner asks for more seeds; when the seeds run out it
 * returns the shortstation it managed to build.
 */
internal object PersonalWavePlanner {

    /** One liked track used to ask a radio. */
    data class Seed(val song: Song, val liked: Boolean)

    /** A seed offered to [rotateSeeds], with how much its radio is worth asking. */
    data class SeedCandidate(val song: Song, val weight: Double)

    /**
     * Picks the seeds of one wave: a weighted sample without replacement.
     *
     * Always taking the first N likes was half of why the station never
     * changed — the same radios answered the same tracks and scored them the
     * same way. A like still outranks a neighbour of a like, so the sample leans
     * on the taste, but which liked artists get asked moves from press to press.
     */
    fun rotateSeeds(candidates: List<SeedCandidate>, limit: Int, random: Random): List<Song> {
        if (candidates.size <= limit) return candidates.shuffled(random).map { it.song }
        // Efraimidis–Spirakis: one uniform per candidate raised to 1/weight,
        // sorted down. One pass, no rejection loop, and the weights hold.
        return candidates
            .map { it.song to Math.pow(random.nextDouble(), 1.0 / it.weight.coerceAtLeast(MIN_SEED_WEIGHT)) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Everything the planner is allowed to look at. */
    data class Input(
        val seeds: List<Seed>,
        /** Liked recordings, for rule 3. */
        val likedRecordings: Set<RecordingKey>,
        /** Every artist the likes name, with a weight per like. */
        val likedArtists: Map<String, Double>,
        /** Artists seen in history only, for rule 8. */
        val historyArtists: Set<String>,
        val dislikedArtists: Set<String>,
        val dislikedRecordings: Set<RecordingKey>,
        val limit: Int,
        /** Tracks served by an earlier wave or played recently, for rule 11. */
        val recentVideoIds: Set<String> = emptySet(),
        val recentRecordings: Set<RecordingKey> = emptySet(),
        /** Rule 12. Fixed in tests, unpredictable in the app. */
        val random: Random = Random.Default,
    )

    /** Title plus runtime bucket: the same master under two uploads is one track. */
    data class RecordingKey(val title: String, val seconds: Int)

    data class Pick(val song: Song, val score: Double, val reason: String, val fresh: Boolean = true)
    data class Reject(val song: Song, val reason: String)

    data class Result(
        val picks: List<Pick>,
        val rejects: List<Reject>,
        val seedsAsked: List<Song>,
        val artistHits: Map<String, Int>,
    )

    private const val MAX_PER_ARTIST = 2
    private const val SCORE_JITTER = 1.0
    private const val MIN_SEED_WEIGHT = 0.25
    private const val DISCOVERY_EVERY = 5
    private const val MIN_TRACK_MS = 60_000L
    private const val MAX_TRACK_MS = 15 * 60_000L
    private const val LIKED_ARTIST_BONUS = 4.0
    private const val CONSENSUS_BONUS = 2.0
    private const val SEED_ARTIST_BONUS = 1.0
    private const val HISTORY_ARTIST_BONUS = 0.5

    /** The exact class of upload that is never a recommendation. */
    private val BANNED_EDITION = Regex(
        """\b(karaoke|cover|tribute|nightcore|8d|sped ?up|speed ?up|slowed|reverb|""" +
            """instrumental|ringtone|mashup|reaction|lyrics? ?video|phonk|brazil|brasil|""" +
            """br ?funk|funk ?brasileiro|tiktok|meme|remix|bootleg|flip|""" +
            """mixtape|1 ?hour|hour-?long|loop|suno|udio|нейросет\w*|нейро-|""" +
            """ai ?cover|ai[ -]generated|ai ?song)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Chart and playlist filler, by title. */
    private val CHART_FILLER = Regex(
        """\b(top ?\d{1,3}|billboard|hits?|viral|trending|playlist|charts?|""" +
            """best of \d{4}|mix \d{4}|compilation)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun recordingKey(song: Song): RecordingKey = recordingKey(song.title, song.durationMillis())

    /** Same key without a [Song] at hand: what [WaveMemory] keeps between runs. */
    fun recordingKey(title: String, durationMs: Long): RecordingKey {
        val seconds = durationMs.let { if (it <= 0L) 0 else (it / 1000L).toInt() }
        return RecordingKey(TrackText.titleKey(title), seconds / 2)
    }

    /**
     * Builds the station from [input], asking [radio] for one seed at a time.
     *
     * Seeds are taken in the order they are given (likes first, one per artist,
     * already handled by the caller). Radios are asked until the pool of
     * allowable tracks is comfortably larger than [Input.limit] or the seeds run
     * out — that is the "next seed" growth path, and it never loosens a rule.
     */
    suspend fun plan(input: Input, radio: suspend (Song) -> List<Song>): Result {
        val rejects = mutableListOf<Reject>()
        val seenIds = HashSet<String>()
        val seenRecordings = HashSet<RecordingKey>()
        val artistHits = HashMap<String, Int>()
        val seedArtists = input.seeds.mapTo(HashSet()) { normalizedArtistName(it.song.artist) }
        val candidates = mutableListOf<Song>()
        val passed = mutableListOf<Pick>()
        val asked = mutableListOf<Song>()
        val radios = mutableListOf<List<Song>>()

        for (seed in input.seeds) {
            if (candidates.size >= input.limit * 3) break
            val tracks = runCatching { radio(seed.song) }.getOrNull().orEmpty()
            if (tracks.isEmpty()) continue
            asked += seed.song
            radios += tracks
            tracks.mapTo(HashSet()) { normalizedArtistName(it.artist) }
                .forEach { artist -> artistHits[artist] = (artistHits[artist] ?: 0) + 1 }
            candidates += tracks
        }

        // Rule 8: history is not a pass. It is the record of what autoplay
        // pushed, and letting it make an artist eligible is what kept feeding
        // the station its own junk. It is kept here only as a scoring hint for
        // artists that qualified another way.
        val historyConfirmed = input.historyArtists

        for (song in candidates) {
            val reject = rejection(
                song = song,
                input = input,
                seedArtists = seedArtists,
                historyConfirmed = historyConfirmed,
                artistHits = artistHits,
                radiosAsked = radios.size,
                seenIds = seenIds,
                seenRecordings = seenRecordings,
            )
            if (reject != null) {
                if (reject.second) rejects += Reject(song, reject.first)
                continue
            }
            // Passed every rule — score it. A liked artist is the strongest
            // signal; radios agreeing on one is the next; the seed's own artist
            // gets a nudge so a station on an artist starts with their kin. The
            // jitter is rule 12 and smaller than any one of those signals, so it
            // reorders equals without promoting a lesser track over a better one.
            val artist = normalizedArtistName(song.artist)
            val score = buildScore(artist, song, input, seedArtists, historyConfirmed, artistHits) +
                input.random.nextDouble() * SCORE_JITTER
            val fresh = song.videoId !in input.recentVideoIds &&
                recordingKey(song) !in input.recentRecordings
            passed += Pick(
                song = song,
                score = score,
                reason = reasonFor(artist, song, input, seedArtists, historyConfirmed, artistHits) +
                    if (fresh) "" else ", звучал недавно",
                fresh = fresh,
            )
        }

        // Rule 11 is an order: everything fresh first, repeats only once no fresh
        // row is left for that slot.
        val ordered = passed.sortedWith(
            compareByDescending<Pick> { it.fresh }.thenByDescending { it.score },
        )
        val picks = ArrayList<Pick>(input.limit)
        val perArtist = HashMap<String, Int>()
        var lastArtist = ""
        var sinceDiscovery = DISCOVERY_EVERY
        for (pick in ordered) {
            if (picks.size >= input.limit) break
            val artist = normalizedArtistName(pick.song.artist)
            if ((perArtist[artist] ?: 0) >= MAX_PER_ARTIST) continue
            if (artist == lastArtist) continue
            val liked = artist in input.likedArtists
            val confirmed = artist in historyConfirmed
            if (!liked && !confirmed) {
                // Rule 9: a newcomer only every DISCOVERY_EVERY rows.
                if (sinceDiscovery < DISCOVERY_EVERY) continue
                sinceDiscovery = 0
            } else {
                sinceDiscovery++
            }
            picks += pick
            perArtist[artist] = (perArtist[artist] ?: 0) + 1
            lastArtist = artist
        }

        return Result(picks, rejects, asked, artistHits)
    }

    /** Null when the track passes, otherwise (reason, worth showing) — reason always worth showing. */
    private fun rejection(
        song: Song,
        input: Input,
        seedArtists: Set<String>,
        historyConfirmed: Set<String>,
        artistHits: Map<String, Int>,
        radiosAsked: Int,
        seenIds: MutableSet<String>,
        seenRecordings: MutableSet<RecordingKey>,
    ): Pair<String, Boolean>? {
        if (song.videoId.isBlank() || song.title.isBlank() || song.artist.isBlank()) {
            return "пустые поля" to false
        }
        val artist = normalizedArtistName(song.artist)
        if (artist in input.dislikedArtists) return "артист в дизлайках" to true
        val key = recordingKey(song)
        if (key in input.dislikedRecordings) return "трек в дизлайках" to true
        if (key in input.likedRecordings) return "уже в лайках" to true
        val runtime = song.durationMillis()
        if (runtime in 1 until MIN_TRACK_MS) return "короче минуты" to true
        if (runtime > MAX_TRACK_MS) return "длиннее 15 минут" to true
        if (BANNED_EDITION.containsMatchIn(song.title)) return "перезалив/эдит (${editionWord(song.title)})" to true
        // The uploader's own name gives them away even when the title is clean:
        // "Sewerslvt slowed", "Nightcore Channel", "MC Phonkzilla". A liked
        // artist whose *name* carries such a word is left alone — that is their
        // name, not an edit of somebody else's song.
        if (artist !in input.likedArtists && BANNED_EDITION.containsMatchIn(song.artist)) {
            return "канал-перезалив (${editionWord(song.artist)})" to true
        }
        if (CHART_FILLER.containsMatchIn(song.title)) return "чарт/сборник в названии" to true
        if (song.title.length > 120) return "название-простыня" to true
        // Dedupe is last, so a row that both repeats and is junk is reported as
        // junk rather than as a duplicate of junk.
        if (!seenIds.add(song.videoId)) return "дубль по id" to false
        if (!seenRecordings.add(key)) return "дубль по названию и длительности" to true
        if (artist in input.likedArtists) return null
        val hits = artistHits[artist] ?: 0
        // A newcomer has to be a neighbour: named by more than one radio, and
        // not by all of them. An unknown artist that *every* radio pushes is
        // the platform's own promotion — a chart act — and that is exactly the
        // "leftover pop" this station kept serving.
        // History alone is still not a pass: an artist the device has actually
        // played needs one radio to name them, and that widens the pool without
        // letting autoplay's own leftovers back in.
        if (hits < 2 && !(hits >= 1 && artist in historyConfirmed)) {
            return "артист не из вкуса и не подтверждён нишей ($hits радио)" to true
        }
        if (radiosAsked > 2 && hits * 2 >= radiosAsked) {
            return "платформенный мейнстрим: почти в каждом радио ($hits из $radiosAsked)" to true
        }
        return null
    }

    private fun buildScore(
        artist: String,
        song: Song,
        input: Input,
        seedArtists: Set<String>,
        historyConfirmed: Set<String>,
        artistHits: Map<String, Int>,
    ): Double {
        var score = 0.0
        if (artist in input.likedArtists) score += LIKED_ARTIST_BONUS + (input.likedArtists[artist] ?: 0.0)
        if (artist in historyConfirmed) score += HISTORY_ARTIST_BONUS
        if (artist in seedArtists) score += SEED_ARTIST_BONUS
        score += (artistHits[artist] ?: 0) * CONSENSUS_BONUS
        return score
    }

    private fun reasonFor(
        artist: String,
        song: Song,
        input: Input,
        seedArtists: Set<String>,
        historyConfirmed: Set<String>,
        artistHits: Map<String, Int>,
    ): String = buildList {
        if (artist in input.likedArtists) add("артист из лайков")
        if (artist in historyConfirmed) add("артист из истории, подтверждён радио")
        if (artist in seedArtists) add("артист сида")
        add("радио согласны ×${artistHits[artist] ?: 1}")
    }.joinToString(", ")

    private fun editionWord(title: String): String =
        BANNED_EDITION.find(title)?.value?.lowercase() ?: "-"
}
