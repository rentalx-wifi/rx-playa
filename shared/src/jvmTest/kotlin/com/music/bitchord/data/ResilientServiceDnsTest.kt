package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResilientServiceDnsTest {
    @Test fun onlyIntegratedMusicHostsUseEncryptedFallback() {
        assertTrue(isResilientServiceHost("api-partner.spotify.com"))
        assertTrue(isResilientServiceHost("api-v2.soundcloud.com"))
        assertTrue(isResilientServiceHost("playback.media-streaming.soundcloud.cloud"))
        assertTrue(isResilientServiceHost("rr1---sn.googlevideo.com"))
        assertFalse(isResilientServiceHost("spotify.com.attacker.example"))
        assertFalse(isResilientServiceHost("example.com"))
    }

    @Test fun dnsJsonKeepsOnlyValidIpv4Answers() {
        val raw = """{"Answer":[
          {"type":5,"data":"alias.example"},
          {"type":1,"data":"104.199.65.124"},
          {"type":1,"data":"999.1.1.1"},
          {"type":28,"data":"2001:db8::1"},
          {"type":1,"data":"104.199.65.124"}
        ]}"""
        assertEquals(listOf("104.199.65.124"), parseDnsAddresses(raw))
    }
}
