package com.music.bitchord

import com.music.bitchord.data.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the updater decides that a release is newer, and which of its APKs is this
 * app's own — the two things that were silently broken: a tag-only release was
 * never offered, and a foreign platform's APK was.
 */
class UpdateVersionTest {

    private fun ordinal(name: String) = AppUpdateChecker.versionOrdinal(name)

    @Test
    fun `the next beta of a revision orders above the installed one`() {
        assertTrue(ordinal("v46b9") > ordinal("1.9-rx-playa-v46b8"))
        assertTrue(ordinal("v46b8") > ordinal("1.9-rx-playa-v46b7"))
    }

    @Test
    fun `the same build is not newer than itself`() {
        assertEquals(ordinal("v46b8"), ordinal("1.9-rx-playa-v46b8"))
        assertEquals(0, ordinal("v46b8").compareTo(ordinal("v46b8")))
    }

    @Test
    fun `a later revision outranks an earlier one of any series`() {
        assertTrue(ordinal("v46b1") > ordinal("v45"))
        assertTrue(ordinal("v45") < ordinal("v46b1"))
    }

    @Test
    fun `a plain version is older than any revision of the fork`() {
        assertEquals(0, ordinal("1.9"))
        assertTrue(ordinal("v46b1") > ordinal("1.9"))
        assertEquals(8, ordinal("1.9-beta8"))
        assertTrue(ordinal("v46b9") > ordinal("1.9-beta8"))
    }

    @Test
    fun `the fork's own asset is picked and the other platform's is ignored`() {
        val assets = listOf(
            "RX-PlayaTV-1.9-rx-playa-v46b9-universal.apk" to "tv",
            "RX-Playa-1.9-rx-playa-v46b9-arm64.apk" to "phone-arm64",
            "RX-Playa-1.9-rx-playa-v46b9-universal.apk" to "phone-universal",
        )

        assertEquals(
            "phone-arm64",
            AppUpdateChecker.chooseApk(assets, prefix = "RX-Playa-", abis = listOf("arm64")),
        )
        assertEquals(
            "phone-universal",
            AppUpdateChecker.chooseApk(assets, prefix = "RX-Playa-", abis = listOf("x86")),
        )
        assertEquals(
            "tv",
            AppUpdateChecker.chooseApk(assets, prefix = "RX-PlayaTV-", abis = listOf("arm64")),
        )
    }

    @Test
    fun `a release with no asset of this platform offers nothing`() {
        val phoneOnly = listOf("RX-Playa-1.9-rx-playa-v46b9-universal.apk" to "phone")
        val tvOnly = listOf("RX-PlayaTV-1.9-rx-playa-v46b9-universal.apk" to "tv")

        assertNull(AppUpdateChecker.chooseApk(tvOnly, prefix = "RX-Playa-", abis = listOf("arm64")))
        assertNull(AppUpdateChecker.chooseApk(phoneOnly, prefix = "RX-PlayaTV-", abis = listOf("arm64")))
    }
}
