package com.music.bitchord

import com.music.bitchord.auth.AuthStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a captured cookie header actually carries a secret Innertube requests
 * can be signed with.
 *
 * Pinned because the failure this replaces was silent in both directions and
 * cost users their listening history. The old test was
 * `cookie.contains("SAPISID")`, and the string `__Secure-3PAPISID` contains
 * `SAPISID` — so a cookie jar holding only the `__Secure-` forms passed a check
 * for a cookie it did not have. The app then reported itself signed in, sent the
 * cookie, and sent no `Authorization` header, which Google answers as a stranger:
 * the library degraded quietly and no play was ever written to history.
 *
 * The reverse mistake is just as bad and is what makes the name/value split
 * necessary rather than fussy — a substring test is satisfied by a cookie whose
 * *value* happens to contain the text, or by a name that merely ends in it.
 */
class AuthCookieTest {

    @Test
    fun `plain SAPISID is accepted`() {
        assertTrue(AuthStore.hasApiSid("SID=abc; SAPISID=secret; HSID=def"))
    }

    @Test
    fun `a jar with only the secure forms is accepted`() {
        // The case the substring test got right by accident and the
        // header-signing code then got wrong: signed in, but unsigned.
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-3PAPISID=secret"))
        assertTrue(AuthStore.hasApiSid("SID=abc; __Secure-1PAPISID=secret"))
    }

    @Test
    fun `leading and trailing whitespace does not hide a cookie`() {
        assertTrue(AuthStore.hasApiSid("SID=abc;SAPISID=secret;HSID=def"))
        assertTrue(AuthStore.hasApiSid("  SAPISID = secret  "))
    }

    @Test
    fun `a jar with no signing secret is rejected`() {
        assertFalse(AuthStore.hasApiSid("SID=abc; HSID=def; SSID=ghi; APISID=jkl"))
        assertFalse(AuthStore.hasApiSid(""))
    }

    @Test
    fun `a present but empty value is rejected`() {
        // A cleared cookie is not a credential, and signing with the empty
        // string produces a digest Google refuses rather than an obvious error.
        assertFalse(AuthStore.hasApiSid("SID=abc; SAPISID=; HSID=def"))
    }

    @Test
    fun `manual cookie accepts the Cookie prefix and normalizes whitespace`() {
        assertEquals(
            "SID=fixture; SAPISID=fixture==",
            AuthStore.normalizeGoogleCookieHeader("  Cookie: SID = fixture; SAPISID = fixture==;  "),
        )
    }

    @Test
    fun `manual cookie accepts a secure signing cookie`() {
        assertEquals(
            "__Secure-3PAPISID=fixture",
            AuthStore.normalizeGoogleCookieHeader("__Secure-3PAPISID=fixture"),
        )
    }

    @Test
    fun `manual cookie refuses headers and Set-Cookie attributes`() {
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=fixture\r\nHost: other.example"))
        assertNull(AuthStore.normalizeGoogleCookieHeader("Set-Cookie: SAPISID=fixture"))
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=fixture; Domain=.youtube.com"))
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=fixture; Secure"))
    }

    @Test
    fun `manual cookie rejects ambiguous duplicates but keeps identical ones`() {
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=first; SAPISID=second"))
        assertEquals("SAPISID=fixture", AuthStore.normalizeGoogleCookieHeader("SAPISID=fixture; SAPISID=fixture"))
    }

    @Test
    fun `manual cookie requires a nonempty signer and bounds pasted input`() {
        assertNull(AuthStore.normalizeGoogleCookieHeader("SID=fixture; SAPISID="))
        assertNull(AuthStore.normalizeGoogleCookieHeader("SID=fixture"))
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=" + "x".repeat(65_536)))
    }

    @Test
    fun `manual cookie refuses malformed names and values`() {
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=with a space"))
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=fixture; bad:name=value"))
        assertNull(AuthStore.normalizeGoogleCookieHeader("SAPISID=fixture\u0000"))
    }

    @Test
    fun `the name has to be the whole name`() {
        // `APISID` is a different cookie and cannot sign anything, and a value
        // that merely mentions the text is not the text.
        assertFalse(AuthStore.hasApiSid("APISID=secret"))
        assertFalse(AuthStore.hasApiSid("NOT-SAPISID=secret"))
        assertFalse(AuthStore.hasApiSid("PREF=tz=SAPISID"))
    }
}
