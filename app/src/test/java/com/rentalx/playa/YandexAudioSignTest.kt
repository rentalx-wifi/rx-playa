package com.rentalx.playa

import com.rentalx.playa.data.yandex.YandexAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The signature the Yandex API demands before it hands out a stream.
 *
 * Computed the same way the Android client does — an HMAC over the request's own
 * values in their fixed order with the commas stripped — so a wrong separator,
 * a wrong order or a padded base64 fails here instead of as a 403 on a phone.
 */
class YandexAudioSignTest {

    private val key = "p93jhgh689SBReK6ghtw62"

    /** The reference implementation, written out rather than shared with the code under test. */
    private fun expected(timestamp: Long, trackId: String, quality: String, codecs: String): String {
        val message = "$timestamp$trackId$quality${codecs.replace(",", "")}encraw"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray())).trimEnd('=')
    }

    @Test
    fun `signature matches the reference implementation`() {
        val codecs = YandexAudio.CODECS.joinToString(",")
        assertEquals(
            expected(1_700_000_000L, "12345678", "lossless", codecs),
            YandexAudio.sign(1_700_000_000L, "12345678", "lossless"),
        )
    }

    @Test
    fun `commas never reach the signed message`() {
        val message = YandexAudio.signedMessage(1L, "42", "nq")
        assertTrue(message, message.contains("42nq"))
        assertTrue(message, !message.contains(","))
        assertTrue("encraw transport is part of the message", message.endsWith("encraw"))
    }

    @Test
    fun `signature changes with the timestamp and the tier`() {
        val base = YandexAudio.sign(1_000L, "1", "nq")
        assertNotEquals(base, YandexAudio.sign(2_000L, "1", "nq"))
        assertNotEquals(base, YandexAudio.sign(1_000L, "1", "lossless"))
        assertNotEquals(base, YandexAudio.sign(1_000L, "2", "nq"))
    }

    @Test
    fun `a metered connection asks for the tier it can afford`() {
        assertEquals(YandexAudio.QUALITY_LOSSLESS, YandexAudio.qualityFor(lossless = true, cappedKbps = null))
        assertEquals(YandexAudio.QUALITY_NORMAL, YandexAudio.qualityFor(lossless = false, cappedKbps = null))
        assertEquals(YandexAudio.QUALITY_LOW, YandexAudio.qualityFor(lossless = false, cappedKbps = 128))
        assertEquals(YandexAudio.QUALITY_NORMAL, YandexAudio.qualityFor(lossless = false, cappedKbps = 256))
    }

    @Test
    fun `a refused network is told apart from a bad request`() {
        assertTrue(YandexAudio.isNetworkRefusal(403))
        assertTrue(YandexAudio.isNetworkRefusal(451))
        assertTrue(!YandexAudio.isNetworkRefusal(401))
    }
}
