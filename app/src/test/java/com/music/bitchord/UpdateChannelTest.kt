package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A release's version is read from its title, then its tag, then its assets —
 * rolling channel names are not versions (issue #38).
 */
class UpdateChannelTest {
    @Test
    fun `title wins over a channel tag`() {
        assertEquals(
            "v1.9.4",
            AppUpdateChecker.releaseVersion("RX Playa v1.9.4", "beta", listOf("rx-playa-1.9.4-arm64.apk")),
        )
    }

    @Test
    fun `a channel tag falls through to the asset name`() {
        assertEquals(
            "v1.9.4",
            AppUpdateChecker.releaseVersion(null, "stable", listOf("rxplaya-v1.9.4-universal.apk")),
        )
    }

    @Test
    fun `a plain tag is the version`() {
        assertEquals("v2.0.1", AppUpdateChecker.releaseVersion(null, "v2.0.1", emptyList()))
    }

    @Test
    fun `dotted versions compare by component`() {
        assertEquals(1, AppUpdateChecker.compareVersions("v1.10.0", "1.9.4"))
        assertEquals(-1, AppUpdateChecker.compareVersions("v1.9.3", "1.9.4"))
        assertEquals(0, AppUpdateChecker.compareVersions("v1.9.4", "1.9.4"))
        assertEquals(1, AppUpdateChecker.compareVersions("v107", "v106"))
    }

    @Test
    fun `a release nobody labelled has no version`() {
        assertNull(AppUpdateChecker.releaseVersion("Rolling", "latest", listOf("app-universal.apk")))
    }
}
