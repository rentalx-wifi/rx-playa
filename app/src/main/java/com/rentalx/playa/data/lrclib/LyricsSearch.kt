package com.rentalx.playa.data.lrclib

import com.rentalx.playa.data.Http
import com.rentalx.playa.data.model.LyricsHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The other direction of the lyrics feature: instead of asking for the words of
 * a song we already have, ask which song has the words the listener remembers.
 *
 * LRCLIB's `search` endpoint is the whole of it — free, key-less, and answered
 * from the same host the lyrics lookup already talks to. Issue #28.
 */
object LyricsSearch {

    private const val BASE = "https://lrclib.net/api/search"

    /** LRCLIB asks clients to name themselves; same agent as the lyrics lookup. */
    private const val AGENT = "RXMusic"

    /**
     * A lyric line is typed, not looked up, so this is a keystroke path: the
     * app's shared 30s read timeout would leave a spinner-shaped hole in the
     * search page if LRCLIB were slow or unreachable. Six seconds is longer
     * than any answer this endpoint has ever taken and short enough that the
     * listener has moved on before the miss is noticed.
     *
     * Derived from [Http.client] rather than built fresh: `newBuilder` keeps
     * the connection pool and dispatcher, so this costs nothing but a copy.
     */
    private val client = Http.client.newBuilder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Songs whose lyric contains [query], best first — empty for anything that
     * goes wrong.
     *
     * Empty is the honest answer here: the section drawn above the results only
     * exists when there are hits, so a failure and a genuinely unmatched line
     * look the same on screen — like nothing — and neither can break the search
     * the listener is actually running.
     */
    suspend fun search(query: String): List<LyricsHit> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty()) return@withContext emptyList()
        runCatching { fetch(text) }.getOrElse { emptyList() }
    }

    private fun fetch(query: String): List<LyricsHit> {
        val url = BASE.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body.string()
            val rows = Json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
            return rows.mapNotNull(::hitOf)
        }
    }

    /** One result; an entry without a track name can't be searched for, so it's dropped. */
    private fun hitOf(element: JsonElement): LyricsHit? {
        val row = element as? JsonObject ?: return null
        val track = row.string("trackName") ?: return null
        return LyricsHit(
            id = row["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            trackName = track,
            artistName = row.string("artistName").orEmpty(),
            albumName = row.string("albumName"),
            // LRCLIB sends a length as a JSON number and not always as one:
            // the API answers "duration": 196.0, which no integer reader takes.
            durationSeconds = row["duration"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * Whether what's in the field reads like a remembered line of a song rather
 * than the name of one.
 *
 * The distinction is what keeps the lyric section off every ordinary search:
 * "coldplay" or "yesterday" is a name, and asking a lyric database about it
 * returns either nothing or a wall of songs with that word somewhere in the
 * text. Four words is where an ordinary query stops being a name and starts
 * being something someone is trying to recall; quotation marks say the same
 * thing explicitly, and a single word never does.
 */
fun looksLikeSongLyric(query: String): Boolean {
    val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.size < 2) return false
    return words.size >= LYRIC_WORD_COUNT || QUOTES.containsMatchIn(query)
}

/** Where a name ends and a phrase begins. */
private const val LYRIC_WORD_COUNT = 4

private val WHITESPACE = Regex("\\s+")

/** Typographic quotes included: phones substitute them for the ASCII pair as you type. */
private val QUOTES = Regex("[\"'«»“”„‟]")
