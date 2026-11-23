package com.music.bitchord.data.lyrics

import android.content.Context
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How a track's own lyric text is timed.
 *
 * Both kinds are typed by hand in the same field, so the kind is worked out
 * from the text rather than asked for — see [userLyricsFormat].
 */
@Serializable
enum class UserLyricsFormat {
    /** Rows of words with no timing: drawn as a page, never lit line by line. */
    Plain,

    /** `[mm:ss.xx]` in front of each row: the panel lights each line as it comes. */
    Lrc,
}

/**
 * The words a listener wrote for one track themselves, and how they are timed.
 *
 * Kept as the text that was typed rather than as parsed lines: it is what goes
 * back into the editor, and a later build reading LRC better than this one
 * should improve old entries too. [lines] is the parse, worked out once per
 * entry because the player reads it on every frame the panel is open.
 */
@Serializable
data class UserLyrics(
    val text: String,
    val format: UserLyricsFormat = UserLyricsFormat.Plain,
) {
    /** How the player draws it: timed rows for LRC, plain ones otherwise. */
    val lines: List<LyricLine> by lazy(LazyThreadSafetyMode.NONE) {
        when (format) {
            UserLyricsFormat.Lrc -> parseUserLrc(text)
            UserLyricsFormat.Plain -> plainUserLyrics(text)
        }
    }
}

/**
 * The listener's own lyrics, per track, on this device.
 *
 * Some songs have no words anywhere the app can reach — an unreleased track, a
 * foreign b-side, a file the databases have never heard of — and some have the
 * wrong ones. Either way the person listening is the only one who knows the
 * words, so they get to write them down, and those words beat whatever the
 * sources answer (see [com.music.bitchord.data.settings.AppSettings.lyricsPreferUser]).
 *
 * SharedPreferences holding one JSON entry per track, keyed by [lyricsKey]:
 * small, synchronous to read on a screen's first frame, and nothing to open a
 * database for. Every track's entry is independent, so writing one cannot
 * damage another.
 */
object UserLyricsStore {

    private const val PREFS = "user_lyrics"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _revision = MutableStateFlow(0L)

    /**
     * Bumped whenever this store is written to.
     *
     * The player and the track menu read the store where they are drawn; this
     * is what tells them the answer changed, since SharedPreferences knows
     * nothing about Compose.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** What [song]'s words are kept under, or null for a track with no identity at all. */
    internal fun lyricsKey(song: Song): String? {
        val videoId = song.videoId.takeIf { it.isNotBlank() }
        // Every YouTube track has a video id and it is the only identity that
        // survives a re-download, so it is the key wherever the row has a real
        // one. A device file's "video id" is the `content://` uri it was read
        // from — an id in name only — so the file's own uri is used instead.
        if (videoId != null && "://" !in videoId) return videoId
        return song.localUri?.takeIf { it.isNotBlank() } ?: videoId
    }

    /** [song]'s own words, or null when the listener has written none. */
    fun entry(context: Context, song: Song): UserLyrics? =
        lyricsKey(song)?.let { key -> decode(prefs(context).getString(key, null)) }

    /** The same, parsed for the player. Empty for a track nobody has written for. */
    fun lines(context: Context, song: Song): List<LyricLine> = entry(context, song)?.lines.orEmpty()

    /**
     * Keeps [text] as [song]'s words, timing and all worked out here.
     *
     * Blank text is [clear]: an editor emptied and saved is a listener taking
     * their words back, not an entry of nothing that would then shadow the
     * sources for ever.
     */
    fun save(context: Context, song: Song, text: String) {
        val key = lyricsKey(song) ?: return
        val body = text.trim()
        if (body.isEmpty()) {
            clear(context, song)
            return
        }
        prefs(context).edit()
            .putString(key, encode(UserLyrics(body, userLyricsFormat(body))))
            .apply()
        _revision.value += 1
    }

    /** Forgets [song]'s own words, leaving whatever the sources answer. */
    fun clear(context: Context, song: Song) {
        val key = lyricsKey(song) ?: return
        prefs(context).edit().remove(key).apply()
        _revision.value += 1
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── The parts with no Context in them, which is what the tests touch ──────

    internal fun encode(entry: UserLyrics): String = json.encodeToString(UserLyrics.serializer(), entry)

    /** Null for anything unreadable — a half-written entry is no lyrics, not a crash. */
    internal fun decode(raw: String?): UserLyrics? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(UserLyrics.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.text.isNotBlank() }
    }
}

/**
 * Which of the two the text is.
 *
 * Any bare timing is enough to make it LRC: a listener who pasted a file from
 * a lyrics site pasted the whole file, and one stamp is proof of that. Text
 * with none is a page of words, which is a perfectly good answer too.
 */
internal fun userLyricsFormat(text: String): UserLyricsFormat =
    if (LRC_STAMP.containsMatchIn(text)) UserLyricsFormat.Lrc else UserLyricsFormat.Plain

/**
 * `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]` or `[mm:ss.xxx]` — the shapes the
 * databases write and the ones people paste, which are not quite the same set.
 * A stamp that sits anywhere in the line still counts; see [timedRows].
 */
private val LRC_STAMP = Regex("""\[(\d{1,3}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")

/**
 * The timed lines of a pasted LRC file.
 *
 * Forgiving on purpose, because this text comes out of a clipboard rather than
 * out of a provider: `[00:12]` is accepted as readily as `[00:12.34]`, and one
 * row carrying several stamps — how a repeated chorus is written — becomes one
 * line per stamp. Rows with no stamp at all are dropped: those are the file's
 * own tags (`[ar:…]`, `[ti:…]`, `[offset:…]`) or its blank lines, and a player
 * has nothing to do with either.
 *
 * A stamp with nothing after it is kept as a timed blank, which the panel draws
 * as an instrumental break only while that stretch is playing.
 */
internal fun parseUserLrc(text: String): List<LyricLine> = text.lineSequence()
    .flatMap { line -> timedRows(line).asSequence() }
    .sortedBy { it.timeMs }
    .toList()

/** One row of an LRC file, once per stamp it carries. */
private fun timedRows(line: String): List<LyricLine> {
    val times = mutableListOf<Long>()
    var cursor = 0
    while (true) {
        while (cursor < line.length && line[cursor].isWhitespace()) cursor++
        val stamp = LRC_STAMP.find(line, cursor)?.takeIf { it.range.first == cursor } ?: break
        times += stampMs(stamp)
        cursor = stamp.range.last + 1
    }
    if (times.isEmpty()) return emptyList()
    val body = line.substring(cursor).trim()
    return times.map { LyricLine(timeMs = it, text = body) }
}

/** `mm:ss.xx` as milliseconds. One fraction digit is tenths, three are milliseconds. */
private fun stampMs(stamp: MatchResult): Long {
    val (minutes, seconds, fraction) = stamp.destructured
    val fractionMs = when (fraction.length) {
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        3 -> fraction.toLong()
        else -> 0L
    }
    return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
}

/**
 * Untimed text as rows the panel can draw.
 *
 * Blank lines survive as a single break each, which is how verses are separated
 * in anything typed by hand, and the leading and trailing ones are dropped so
 * the panel does not open on nothing.
 */
internal fun plainUserLyrics(text: String): List<LyricLine> {
    val rows = mutableListOf<LyricLine>()
    text.lineSequence().forEach { raw ->
        val row = raw.trim()
        if (row.isNotEmpty()) {
            rows += LyricLine(timeMs = 0L, text = row)
        } else if (rows.isNotEmpty() && !rows.last().isGap) {
            rows += LyricLine(timeMs = 0L, text = "")
        }
    }
    while (rows.isNotEmpty() && rows.last().isGap) rows.removeAt(rows.lastIndex)
    return rows
}
