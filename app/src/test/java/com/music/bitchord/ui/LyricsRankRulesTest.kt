package com.music.bitchord.ui

import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the lyrics panel will and will not replace.
 *
 * The panel shows the first answer that lands and only ever improves, so the
 * rank is the whole rule: word-timed beats timed beats plain text, and a later
 * answer of the same rank changes nothing.
 */
class LyricsRankRulesTest {

    private fun plain(text: String = "line") = LyricLine(timeMs = 0L, text = text)

    private fun timed(timeMs: Long = 1_000L) = LyricLine(timeMs = timeMs, text = "line")

    private fun wordSynced() = LyricLine(
        timeMs = 1_000L,
        text = "one two",
        words = listOf(
            LyricWord(startMs = 1_000L, endMs = 1_500L, text = "one"),
            LyricWord(startMs = 1_500L, endMs = 2_000L, text = "two"),
        ),
    )

    @Test
    fun `text without timing ranks last`() {
        assertEquals(PLAIN_LYRICS, lyricsRank(listOf(plain(), plain())))
    }

    @Test
    fun `timed lines rank above plain text`() {
        assertEquals(TIMED_LYRICS, lyricsRank(listOf(plain(), timed())))
    }

    @Test
    fun `a single word-timed line wins the whole set`() {
        assertEquals(WORD_LYRICS, lyricsRank(listOf(timed(), wordSynced())))
    }

    @Test
    fun `an empty answer is plain`() {
        assertEquals(PLAIN_LYRICS, lyricsRank(emptyList()))
    }
}
