package com.music.bitchord.data.lrclib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate between the ordinary search and the lyric lookup.
 *
 * Getting it wrong is quietly expensive in both directions: too eager and every
 * second search grows a section of loosely-related songs, too strict and the
 * feature never appears at all. The cases below are the shapes a search field
 * actually sees — a name, a name and a word, a half-remembered line, a phrase
 * in quotes — not arbitrary strings.
 */
class LyricsSearchTest {

    @Test
    fun `a single word is a name, never a lyric`() {
        assertFalse(looksLikeSongLyric("yesterday"))
        assertFalse(looksLikeSongLyric("  Yesterday  "))
        // Even quoted: one word says nothing about what the line was.
        assertFalse(looksLikeSongLyric("\"yesterday\""))
    }

    @Test
    fun `an ordinary artist and title query stays an ordinary search`() {
        assertFalse(looksLikeSongLyric("coldplay"))
        assertFalse(looksLikeSongLyric("coldplay yellow"))
        assertFalse(looksLikeSongLyric("the beatles yesterday"))
        assertFalse(looksLikeSongLyric(""))
        assertFalse(looksLikeSongLyric("   "))
    }

    @Test
    fun `four words are long enough to be a remembered line`() {
        assertTrue(looksLikeSongLyric("i said something wrong"))
        assertTrue(looksLikeSongLyric("  and I will always   love you "))
    }

    @Test
    fun `quotes mark a phrase whatever its length`() {
        assertTrue(looksLikeSongLyric("\"hello darkness\""))
        assertTrue(looksLikeSongLyric("«я тебя никогда не забуду»"))
        assertTrue(looksLikeSongLyric("“shape of my heart”"))
    }
}
