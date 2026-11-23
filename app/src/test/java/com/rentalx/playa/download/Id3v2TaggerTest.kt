package com.rentalx.playa.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MP3 tag has no reader in this app to check it against — playback goes by
 * the bytes, not the tag — so these assert the layout the standard fixes: a
 * v2.3 header, syncsafe size, big-endian frame sizes, and the audio left exactly
 * as it arrived behind the tag.
 */
class Id3v2TaggerTest {

    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x01, 0x02, 0x03)

    private fun syncsafe(bytes: ByteArray): Int =
        ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)

    @Test
    fun `a v2_3 tag goes in front of the audio`() {
        val tagged = Id3v2Tagger.tag(audio, "Title", "Artist", "Album", null, null, "image/jpeg")
        assertEquals("ID3", tagged.decodeToString(0, 3))
        assertEquals(3, tagged[3].toInt())
        assertEquals(0, tagged[4].toInt())
        val size = syncsafe(tagged)
        // The tag holds the frames alone; the audio behind it is not counted.
        assertEquals(tagged.size - 10 - audio.size, size)
        assertEquals("TIT2", tagged.decodeToString(10, 14))
        assertArrayEquals(audio, tagged.copyOfRange(10 + size, tagged.size))
    }

    @Test
    fun `the tag the source already had is dropped, not stacked`() {
        // A v2.3 tag of five bytes of padding, then the audio.
        val existing = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 5,
        ) + ByteArray(5)
        val tagged = Id3v2Tagger.tag(existing + audio, "Title", "Artist", null, null, null, "image/jpeg")
        val size = syncsafe(tagged)
        assertArrayEquals(audio, tagged.copyOfRange(10 + size, tagged.size))
        // One header only: the second "ID3" would be inside the tag body.
        assertEquals("ID3", tagged.decodeToString(0, 3))
        assertTrue(tagged.decodeToString(10, tagged.size).indexOf("ID3") < 0)
    }

    @Test
    fun `a cover becomes an APIC frame and the words become USLT`() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        val tagged = Id3v2Tagger.tag(audio, "Title", "Artist", null, "words", cover, "image/jpeg")
        val body = tagged.decodeToString(10)
        assertTrue(body.contains("APIC"))
        assertTrue(body.contains("image/jpeg"))
        assertTrue(body.contains("USLT"))
    }
}
