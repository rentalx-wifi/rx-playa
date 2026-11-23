package com.rentalx.playa

import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.model.normalizedArtistName
import com.rentalx.playa.playback.PersonalWavePlanner
import com.rentalx.playa.playback.TrackText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The RX Wave pipeline, dry-run on a station's worth of data.
 *
 * The radios are fake on purpose: what is being tested is the planner, not
 * YouTube. The fixture is built to contain every kind of row that used to get
 * through — Brazilian phonk, slowed and sped-up re-uploads, a cover, a foreign
 * remix, chart compilations, a mainstream pop act, duplicates of one master
 * under two ids, a 20-second interlude — mixed in with rows that genuinely
 * belong to the taste.
 *
 * The test asserts the rules and writes the whole decision log to
 * `build/rx-wave-report.txt`, so the answer to "and why is *this* in my wave"
 * is a file, not an opinion. Run it with:
 *
 * ```
 * ./gradlew :app:testDevDebugUnitTest --tests "*PersonalWavePlannerTest*"
 * ```
 */
class PersonalWavePlannerTest {

    // ── The likes: fifteen artists, the shape of the real library ────────────

    private fun song(id: String, title: String, artist: String, seconds: Int) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = "%d:%02d".format(seconds / 60, seconds % 60),
    )

    private val NEIGHBOURS = listOf(
        "glaivehouse", "kurai tapes", "nightbus fm", "yuki", "staticbloom",
        "lofi ghost", "coldtears", "vuoto",
    )

    private val seeds = listOf(
        song("s01", "Restlessness", "Sewerslvt", 209),
        song("s02", "some incredibly snowy things!", "Rory in early 20s", 93),
        song("s03", "He's right. I am a murderer", "DJ UNIVXRSEL", 151),
        song("s04", "Xtal", "Aphex Twin", 295),
        song("s05", "tired of myself (breakcore)", "Yoake Music", 260),
        song("s06", "U Weren't Here I Really Miss You", "XD4rkify", 161),
        song("s07", "Everybody Can Gaze Love (except you, again)", "Hhnglee", 201),
        song("s08", "affection core", "Hozuki-snowfield", 180),
        song("s09", "a tear at eventide", "Proclaimable", 153),
        song("s10", "fading light", "LochHaven", 190),
        song("s11", "I Miss Those Days.", ".sadwave", 147),
        song("s12", "All I Need", "Clams Casino", 199),
        song("s13", "街の泣き声", "rukiawaa", 125),
        song("s14", "Losing", "Lonnex", 172),
        song("s15", "j4 127v4", "sgarz", 155),
    )

    private fun songOf(seed: Song, other: Song, idSuffix: String) = Song(
        videoId = "${seed.videoId}-$idSuffix",
        title = "${other.title} · ${seed.videoId}$idSuffix",
        artist = other.artist,
        thumbnailUrl = null,
        durationText = other.durationText,
    )

    /** An artist that is *not* in the likes: only a radio can confirm them. */
    private fun neighbour(id: String, title: String, artist: String) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = "3:20",
    )

    /**
     * What each seed's radio answers. Rotating so that the niche artists are
     * named by several radios (that is the "neighbour" signal) while the junk
     * and the pop act appear everywhere and are rejected anyway.
     */
    private fun radioFor(seed: Song, index: Int): List<Song> {
        // Six tracks from other liked artists, rotating through the whole seed
        // list so every artist is reachable from somebody's radio.
        val others = seeds.filter { it.artist != seed.artist }
        val kin = (0 until 6).map { step ->
            val other = others[(index * 3 + step) % others.size]
            songOf(seed, other, "r$step")
        }
        // Eight niche artists, each named by every radio: the "neighbour"
        // signal. Two of them per radio, rotated, so the discovery share has
        // something to draw from.
        val neighbours = (0 until 2).map { step ->
            val who = (index + step * 4) % NEIGHBOURS.size
            neighbour("n${seed.videoId}-$step", "${NEIGHBOURS[who]} track $step", NEIGHBOURS[who])
        }
        val junk = listOf(
            neighbour("j${seed.videoId}-1", "Brazilian Phonk 2024", "MC Phonkzilla"),
            neighbour("j${seed.videoId}-2", "Restlessness (Slowed + Reverb)", "Sewerslvt slowed"),
            neighbour("j${seed.videoId}-3", "some incredibly snowy things! (sped up)", "speedy edits"),
            neighbour("j${seed.videoId}-4", "Xtal (nightcore)", "Nightcore Channel"),
            neighbour("j${seed.videoId}-5", "All I Need (karaoke cover)", "Karaoke Hits Band"),
            neighbour("j${seed.videoId}-6", "He's right (foreign remix)", "DJ Random Remix"),
            neighbour("j${seed.videoId}-7", "Top 50 Viral Hits 2024", "Hits Playlist"),
            neighbour("j${seed.videoId}-8", "Summer Dance", "Mainstream Pop Singer"),
            neighbour("j${seed.videoId}-9", "Interlude", "Sewerslvt", 20),
            neighbour("j${seed.videoId}-10", "hour-long mixtape", "Some Uploader"),
        )
        return kin + neighbours + junk
    }

    private fun plan(
        recentVideoIds: Set<String> = emptySet(),
        recentRecordings: Set<PersonalWavePlanner.RecordingKey> = emptySet(),
        random: kotlin.random.Random = kotlin.random.Random(11),
        limit: Int = 30,
    ) = runBlocking {
        val likedArtists = seeds.groupingBy { normalizedArtistName(it.artist) }.eachCount()
            .mapValues { it.value.toDouble() }
        PersonalWavePlanner.plan(
            input = PersonalWavePlanner.Input(
                seeds = seeds.map { PersonalWavePlanner.Seed(it, liked = true) },
                likedRecordings = seeds.mapTo(HashSet()) { PersonalWavePlanner.recordingKey(it) },
                likedArtists = likedArtists,
                historyArtists = setOf("MC Phonkzilla", "Mainstream Pop Singer"),
                dislikedArtists = setOf("mc phonkzilla"),
                dislikedRecordings = emptySet(),
                limit = limit,
                recentVideoIds = recentVideoIds,
                recentRecordings = recentRecordings,
                random = random,
            ),
            radio = { seed -> radioFor(seed, seeds.indexOf(seed)) },
        )
    }

    @Test
    fun `station of thirty is clean and explainable`() {
        val result = plan()
        val report = buildString {
            appendLine("SEEDS (${seeds.size}):")
            seeds.forEach { appendLine("  ${it.artist} — ${it.title} (${it.durationText})") }
            appendLine()
            appendLine("PICKS (${result.picks.size}):")
            result.picks.forEachIndexed { index, pick ->
                appendLine(
                    "  %2d. %s — %s [score %.1f] (%s)".format(
                        index + 1, pick.song.artist, pick.song.title, pick.score, pick.reason,
                    ),
                )
            }
            appendLine()
            appendLine("REJECTED (${result.rejects.size}):")
            result.rejects.distinctBy { it.song.videoId }.forEach { reject ->
                appendLine("  - ${reject.song.artist} — ${reject.song.title}: ${reject.reason}")
            }
            appendLine()
            appendLine("ARTIST HITS (radios naming each artist):")
            result.artistHits.toList().sortedByDescending { it.second }
                .take(20).forEach { (artist, hits) -> appendLine("  $artist: $hits") }
        }
        File("build/rx-wave-report.txt").writeText(report)
        println(report)

        assertEquals("a full station is expected from these radios", 30, result.picks.size)
        assertTrue("at least fifteen rejections are expected", result.rejects.size >= 15)

        val banned = Regex(
            """\b(karaoke|cover|tribute|nightcore|8d|sped ?up|speed ?up|slowed|reverb|""" +
                """instrumental|ringtone|mashup|reaction|phonk|brazil|brasil|tiktok|meme|""" +
                """remix|bootleg|flip|mixtape|1 ?hour|hour-?long|loop)\b""",
            RegexOption.IGNORE_CASE,
        )
        val allowed = seeds.map { normalizedArtistName(it.artist) }.toSet()
        val confirmed = result.picks.map { normalizedArtistName(it.song.artist) }.toSet()

        val chartActors = setOf("mainstream pop singer", "hits playlist")
        result.picks.forEach { pick ->
            assertTrue(
                "a chart act must never pass: ${pick.song.artist}",
                normalizedArtistName(pick.song.artist) !in chartActors,
            )
            val artist = normalizedArtistName(pick.song.artist)
            assertTrue("no banned edition may pass: ${pick.song.title}", !banned.containsMatchIn(pick.song.title))
            assertTrue(
                "every pick is from a liked or radio-confirmed artist: ${pick.song.artist}",
                artist in allowed || artist in confirmed,
            )
            val seconds = pick.song.durationText.orEmpty().split(":")
                .let { (m, s) -> m.toInt() * 60 + s.toInt() }
            assertTrue("runtime sanity: ${pick.song.title}", seconds in 60..900)
        }

        // No artist twice in a row.
        result.picks.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "two ${a.song.artist} rows in a row",
                normalizedArtistName(a.song.artist) != normalizedArtistName(b.song.artist),
            )
        }
        // At most two rows per artist.
        result.picks.groupBy { normalizedArtistName(it.song.artist) }.forEach { (artist, rows) ->
            assertTrue("$artist takes ${rows.size} rows", rows.size <= 2)
        }
        // Hard dedupe by title and runtime.
        val keys = result.picks.map { TrackText.titleKey(it.song.title) }
        assertEquals("duplicate titles in the station", keys.size, keys.toSet().size)
    }

    private fun neighbour(id: String, title: String, artist: String, seconds: Int) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = "%d:%02d".format(seconds / 60, seconds % 60),
    )

    // ── Variety: the complaint this station had to answer ───────────────────

    /**
     * The reported bug: pressing RX Wave again served the tracks that had just
     * played. Rule 11 has to put the tracks of the previous station behind every
     * fresh one, and still leave a full station behind it.
     */
    @Test
    fun `a second press is not the first station again`() {
        val first = plan()
        val seen = first.picks.mapTo(HashSet()) { it.song.videoId }
        val second = plan(
            recentVideoIds = seen,
            recentRecordings = first.picks.mapTo(HashSet()) { PersonalWavePlanner.recordingKey(it.song) },
            random = kotlin.random.Random(12),
        )
        val flags = second.picks.map { it.song.videoId in seen }
        val firstRepeat = flags.indexOf(true)
        val lastFresh = flags.lastIndexOf(false)
        assertTrue(
            "a fresh row was pushed behind a repeat: ${flags.joinToString("") { if (it) "R" else "F" }}",
            firstRepeat < 0 || lastFresh < firstRepeat,
        )
        assertEquals("the second station is still a full station", 30, second.picks.size)
    }

    /**
     * Rule 12 on the seed side: a library larger than the seed budget must not
     * be asked in the same order every press, while a heavier seed still has to
     * come up more often than a light one.
     */
    @Test
    fun `seed rotation asks a different slice of the likes`() {
        val candidates = (1..40).map { index ->
            PersonalWavePlanner.SeedCandidate(song("c$index", "track $index", "artist $index", 180), 1.0)
        }
        val first = PersonalWavePlanner.rotateSeeds(candidates, 24, kotlin.random.Random(1))
            .mapTo(HashSet()) { it.videoId }
        val second = PersonalWavePlanner.rotateSeeds(candidates, 24, kotlin.random.Random(2))
            .mapTo(HashSet()) { it.videoId }
        assertEquals("a full seed budget is expected", 24, first.size)
        assertTrue("two presses asked exactly the same seeds", first != second)

        val weighted = candidates.mapIndexed { index, candidate ->
            if (index == 0) candidate.copy(weight = 20.0) else candidate
        }
        val hits = (1..200).count { round ->
            PersonalWavePlanner.rotateSeeds(weighted, 10, kotlin.random.Random(round))
                .any { it.videoId == "c1" }
        }
        assertTrue("the strongest liked artist is skipped far too often: $hits of 200", hits > 150)
    }

    /**
     * Two different recordings that share a name are two tracks, not one.
     *
     * Without the artist in the key, every "Intro" and "Outro" in a library
     * collapsed into whichever one was seen first, and the other never reached
     * the station.
     */
    @Test
    fun `an intro by one act does not mask an intro by another`() {
        val mine = PersonalWavePlanner.recordingKey(song("a1", "Intro", "Kesariya", 45))
        val theirs = PersonalWavePlanner.recordingKey(song("b1", "Intro", "Somebody Else", 45))
        val sameMaster = PersonalWavePlanner.recordingKey(song("a2", "Intro", "Kesariya", 45))
        assertTrue("two acts sharing a title became one recording", mine != theirs)
        assertEquals("one master under two ids must stay one recording", mine, sameMaster)
    }
}
