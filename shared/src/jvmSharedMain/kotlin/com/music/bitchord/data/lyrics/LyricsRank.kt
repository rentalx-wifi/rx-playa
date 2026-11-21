package com.music.bitchord.data.lyrics

/**
 * How good a set of lines is, for the panel that shows them.
 *
 * The panel used to wait for the whole priority race before anything appeared,
 * so a source answering in two hundred milliseconds was held behind one that had
 * not answered yet. Answers now go on screen as they land — and this number is
 * what decides whether a later one is an upgrade or a regression:
 *
 *  - [WORD] — every line carries its own timing to the syllable, so the panel can
 *    light up the exact word being sung.
 *  - [TIMED] — lines carry a time, which is enough to follow the song.
 *  - [PLAIN] — text with no timing at all: readable, and last in line.
 */
const val PLAIN_LYRICS = 0

/** Lines that carry a time, so the panel can follow the song. */
const val TIMED_LYRICS = 1

/** Lines timed to the syllable, the best a source can answer with. */
const val WORD_LYRICS = 2

fun lyricsRank(lines: List<LyricLine>): Int = when {
    lines.any { it.isWordSynced } -> WORD_LYRICS
    lines.any { it.timeMs > 0 } -> TIMED_LYRICS
    else -> PLAIN_LYRICS
}
