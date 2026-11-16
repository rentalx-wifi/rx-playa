package com.music.bitchord.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TlsFragmentTest {

    private fun hello(payloadSize: Int, tail: ByteArray = ByteArray(0)): ByteArray {
        val header = byteArrayOf(0x16, 0x03, 0x01, (payloadSize shr 8).toByte(), payloadSize.toByte())
        val payload = ByteArray(payloadSize) { (it % 200).toByte() }
        return header + payload + tail
    }

    @Test
    fun `split produces two records carrying the original payload in order`() {
        val original = hello(120)
        val split = TlsFragment.splitFirstRecord(original, 2)
        assertNotNull(split)
        split!!

        val firstLength = ((split[3].toInt() and 0xff) shl 8) or (split[4].toInt() and 0xff)
        val secondHeader = 5 + firstLength
        val secondLength = ((split[secondHeader + 3].toInt() and 0xff) shl 8) or
            (split[secondHeader + 4].toInt() and 0xff)

        assertEquals(2, firstLength)
        assertEquals(118, secondLength)
        assertEquals(original.size + 5, split.size)

        val payload = split.copyOfRange(5, 5 + firstLength) +
            split.copyOfRange(secondHeader + 5, secondHeader + 5 + secondLength)
        assertArrayEquals(original.copyOfRange(5, original.size), payload)
        assertEquals(0x16.toByte(), split[0])
        assertEquals(0x16.toByte(), split[secondHeader])
    }

    @Test
    fun `bytes after the first record are copied verbatim`() {
        val tail = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        val split = TlsFragment.splitFirstRecord(hello(40, tail), 5)
        assertNotNull(split)
        assertArrayEquals(tail, split!!.copyOfRange(split.size - tail.size, split.size))
    }

    @Test
    fun `anything that is not one complete handshake record is refused`() {
        assertNull(TlsFragment.splitFirstRecord(ByteArray(0), 2))
        assertNull(TlsFragment.splitFirstRecord(byteArrayOf(0x17, 0x03, 0x01, 0x00, 0x04), 2))
        assertNull(TlsFragment.splitFirstRecord(hello(10).copyOfRange(0, 8), 2))
        assertNull(TlsFragment.splitFirstRecord(hello(10), 0))
        assertNull(TlsFragment.splitFirstRecord(hello(10), 10))
        assertNull(TlsFragment.splitFirstRecord(hello(10), -1))
    }
}
