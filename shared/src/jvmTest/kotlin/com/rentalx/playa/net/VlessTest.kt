package com.rentalx.playa.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessTest {

    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"

    @Test
    fun `parses a tls tcp key and keeps the label`() {
        val parsed = Vless.parse(
            "vless://$uuid@example.com:8443?security=tls&type=tcp&sni=edge.example.com#My%20Node",
        )
        val endpoint = parsed.getOrNull()
        assertNotNull(endpoint)
        assertEquals("example.com", endpoint!!.host)
        assertEquals(8443, endpoint.port)
        assertEquals("edge.example.com", endpoint.sni)
        assertTrue(endpoint.tls)
        assertEquals("My Node", endpoint.name)
    }

    @Test
    fun `refuses transports and securities this client cannot speak`() {
        assertTrue(Vless.parse("vless://$uuid@host:443?security=reality&type=tcp").isFailure)
        assertTrue(Vless.parse("vless://$uuid@host:443?security=tls&type=ws").isFailure)
        assertTrue(Vless.parse("vless://$uuid@host:443?security=tls&type=tcp&flow=xtls-rprx-vision").isFailure)
        assertTrue(Vless.parse("vless://not-a-uuid@host:443?security=tls").isFailure)
        assertTrue(Vless.parse("https://example.com").isFailure)
    }

    @Test
    fun `request header names the target and authenticates with the uuid`() {
        val endpoint = Vless.parse("vless://$uuid@host:443?security=tls").getOrThrow()
        val header = Vless.requestHeader(endpoint, "music.youtube.com", 443)

        assertArrayEquals(Vless.uuidBytes(uuid), header.copyOfRange(1, 17))
        assertEquals(0, header[17].toInt())          // no addons
        assertEquals(1, header[18].toInt())          // TCP command
        assertEquals(0x01, header[19].toInt() and 0xff) // 443 high byte
        assertEquals(0xBB, header[20].toInt() and 0xff) // 443 low byte
        assertEquals(0x02, header[21].toInt())       // domain address type
        assertEquals(17, header[22].toInt())         // "music.youtube.com" length
        assertEquals("music.youtube.com", String(header.copyOfRange(23, 40), Charsets.UTF_8))
    }

    @Test
    fun `ipv4 targets use the literal address type`() {
        val endpoint = Vless.parse("vless://$uuid@host:443?security=tls").getOrThrow()
        val header = Vless.requestHeader(endpoint, "203.0.113.9", 80)
        assertEquals(0x01, header[21].toInt())
        assertArrayEquals(byteArrayOf(203.toByte(), 0, 113.toByte(), 9), header.copyOfRange(22, 26))
    }

    @Test
    fun `uuid parsing rejects malformed values`() {
        assertNull(Vless.uuidBytes("b831381d-6324-4d53-ad4f"))
        assertNull(Vless.uuidBytes("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
        assertFalse(Vless.parse("vless://b831381d-6324-4d53-ad4f-8cda48b30811@host:443").isFailure)
    }
}
