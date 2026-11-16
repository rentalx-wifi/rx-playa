package com.music.bitchord

import com.music.bitchord.auth.isYouTubeMusicWebPage
import com.music.bitchord.auth.youtubeMusicWebUserAgent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicWebLoginTest {
    @Test
    fun `session confirmation accepts only the HTTPS Music origin`() {
        listOf(
            "https://music.youtube.com/",
            "https://music.youtube.com/?app=desktop",
            "https://music.youtube.com:443/watch?v=video",
            "https://MUSIC.YOUTUBE.COM/browse/account",
        ).forEach { assertTrue(it, isYouTubeMusicWebPage(it)) }
    }

    @Test
    fun `prefix lookalikes and userinfo cannot enable session capture`() {
        listOf(
            "https://music.youtube.com.attacker.example/",
            "https://music.youtube.com@attacker.example/",
            "https://attacker.example@music.youtube.com/",
            "https://attacker.example/?continue=https://music.youtube.com/",
            "https://music.youtube.com:444/",
            "http://music.youtube.com/",
            "intent://music.youtube.com/",
            "file:///music.youtube.com/",
            "javascript:music.youtube.com",
            "not a URL",
            "",
        ).forEach { assertFalse(it, isYouTubeMusicWebPage(it)) }
        assertFalse(isYouTubeMusicWebPage(null))
    }

    @Test
    fun `desktop user agent keeps the installed Chromium version`() {
        val agent = youtubeMusicWebUserAgent(
            "Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 Chrome/142.0.1.2 Mobile Safari/537.36",
        )
        assertTrue(agent.contains("Chrome/142.0.1.2"))
        assertTrue(agent.contains("X11; Linux x86_64"))
        assertFalse(agent.contains("Android"))
        assertFalse(agent.contains("Mobile"))
        assertFalse(agent.contains("; wv"))
    }

    @Test
    fun `missing platform user agent still selects the desktop web client`() {
        val agent = youtubeMusicWebUserAgent(null)
        assertTrue(agent.contains("Chrome/"))
        assertTrue(agent.contains("Safari/537.36"))
        assertFalse(agent.contains("Mobile"))
    }
}
