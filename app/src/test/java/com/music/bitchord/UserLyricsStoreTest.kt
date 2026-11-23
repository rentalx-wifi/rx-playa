package com.music.bitchord

import com.music.bitchord.data.lyrics.UserLyrics
import com.music.bitchord.data.lyrics.UserLyricsFormat
import com.music.bitchord.data.lyrics.UserLyricsStore
import com.music.bitchord.data.lyrics.plainUserLyrics
import com.music.bitchord.data.lyrics.parseUserLrc
import com.music.bitchord.data.lyrics.userLyricsFormat
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text a listener types into a track's menu and the reader that turns it
 * into lines the panel can draw.
 *
 * This is user input from a clipboard, so it is messier than anything a
 * provider sends: timestamps in shapes no LRC writer agrees on, one row holding
 * a whole repeated chorus, file tags mixed in with the words. What is checked
 * here is that each of those still ends up as lines — a file silently becoming
 * an empty lyric sheet is the failure this is written against.
 */
class UserLyricsStoreTest {

    @Test
    fun `plain text becomes untimed rows`() {
        val lines = plainUserLyrics("first line\nsecond line")

        assertEquals(listOf("first line", "second line"), lines.map { it.text })
        assertEquals(listOf(0L, 0L), lines.map { it.timeMs })
    }

    @Test
    fun `a blank line separates verses and the edges are dropped`() {
        val lines = plainUserLyrics("\n\nverse one\n\n\nverse two\n\n")

        assertEquals(listOf("verse one", "", "verse two"), lines.map { it.text })
        assertEquals(listOf(false, true, false), lines.map { it.isGap })
    }

    @Test
    fun `centisecond stamps are timed and ordered`() {
        val lines = parseUserLrc("[00:12.34]second\n[00:01.50]first")

        assertEquals(listOf("first", "second"), lines.map { it.text })
        assertEquals(listOf(1_500L, 12_340L), lines.map { it.timeMs })
    }

    @Test
    fun `a stamp with no fraction is still a stamp`() {
        val lines = parseUserLrc("[01:05]words")

        assertEquals(65_000L, lines.single().timeMs)
        assertEquals("words", lines.single().text)
    }

    @Test
    fun `one fraction digit is tenths and three are milliseconds`() {
        assertEquals(1_200L, parseUserLrc("[00:01.2]a").single().timeMs)
        assertEquals(1_234L, parseUserLrc("[00:01.234]a").single().timeMs)
    }

    @Test
    fun `a row carrying several stamps becomes one line per stamp`() {
        val lines = parseUserLrc("[00:10.00][01:20.00]chorus")

        assertEquals(listOf("chorus", "chorus"), lines.map { it.text })
        assertEquals(listOf(10_000L, 80_000L), lines.map { it.timeMs })
    }

    @Test
    fun `file tags and blank rows are not lyrics`() {
        val lines = parseUserLrc("[ti:Untitled]\n[ar:Nobody]\n[offset:-200]\n\n[00:03.00]only line")

        assertEquals(listOf("only line"), lines.map { it.text })
    }

    @Test
    fun `a stamp with nothing after it marks a break`() {
        val lines = parseUserLrc("[00:03.00]words\n[00:20.00]")

        assertEquals(2, lines.size)
        assertTrue(lines.last().isGap)
    }

    @Test
    fun `the format is read off the text`() {
        assertEquals(UserLyricsFormat.Lrc, userLyricsFormat("[00:03.00]words"))
        assertEquals(UserLyricsFormat.Plain, userLyricsFormat("words\nand more words"))
    }

    @Test
    fun `an entry parses itself by its own format`() {
        val timed = UserLyrics("[00:03.00]words", UserLyricsFormat.Lrc)
        val plain = UserLyrics("words\nmore words", UserLyricsFormat.Plain)

        assertEquals(listOf(3_000L), timed.lines.map { it.timeMs })
        assertEquals(listOf(0L, 0L), plain.lines.map { it.timeMs })
    }

    @Test
    fun `an entry survives the store's own encoding`() {
        val entry = UserLyrics("[00:03.00]words\n[00:09.00]\"quoted\" — ünïcode", UserLyricsFormat.Lrc)

        assertEquals(entry, UserLyricsStore.decode(UserLyricsStore.encode(entry)))
    }

    @Test
    fun `an unreadable entry is no lyrics rather than a crash`() {
        assertNull(UserLyricsStore.decode(null))
        assertNull(UserLyricsStore.decode(""))
        assertNull(UserLyricsStore.decode("{not json"))
        assertNull(UserLyricsStore.decode("""{"text":"   ","format":"Plain"}"""))
    }

    @Test
    fun `a youtube track is keyed by its video id`() {
        assertEquals("dQw4w9WgXcQ", UserLyricsStore.lyricsKey(song(videoId = "dQw4w9WgXcQ")))
    }

    @Test
    fun `a device file is keyed by its own uri`() {
        val content = "content://media/external/audio/media/42"

        assertEquals(content, UserLyricsStore.lyricsKey(song(videoId = content, localUri = content)))
    }

    @Test
    fun `a track with no identity at all has no key`() {
        assertNull(UserLyricsStore.lyricsKey(song(videoId = "", localUri = null)))
    }

    private fun song(videoId: String, localUri: String? = null) = Song(
        videoId = videoId,
        title = "title",
        artist = "artist",
        thumbnailUrl = null,
        localUri = localUri,
    )
}
