package com.rentalx.playa.ui.player

import com.rentalx.playa.data.NerdStats
import com.rentalx.playa.data.model.FLASH_MUSIC_NAME
import com.rentalx.playa.data.sources.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The player's quality badge on a track the listener's own server served.
 *
 * The three tiers are the server's own, so the badge names them rather than
 * measuring the transcode: a FLAC master reads "Lossless" at whatever
 * resolution it is, 320 is "Hi-Res", 192 is the plain name.
 */
class FlashMusicBadgeTest {

    private fun stats(
        mimeType: String = "audio/opus",
        bitrateKbps: Int? = null,
        claimedKbps: Int? = null,
        source: String? = FLASH_MUSIC_NAME,
    ) = NerdStats.Snapshot(
        mimeType = mimeType,
        bitrateKbps = bitrateKbps,
        sampleRateHz = 48_000,
        channels = 2,
        claimed = StreamFormat(codec = mimeType.substringAfter('/'), kbps = claimedKbps, kbpsInferred = true),
        sourceName = source,
    )

    @Test
    fun `lossless from our server names itself`() {
        assertEquals(
            "FlashMusic Lossless",
            flashMusicLabel(stats(mimeType = "audio/flac")),
        )
    }

    @Test
    fun `the high tier is named hi-res`() {
        assertEquals(
            "FlashMusic Hi-Res",
            flashMusicLabel(stats(claimedKbps = 320, bitrateKbps = 300)),
        )
    }

    @Test
    fun `the default tier is the plain name`() {
        assertEquals(
            "FlashMusic",
            flashMusicLabel(stats(claimedKbps = 192, bitrateKbps = 180)),
        )
    }

    @Test
    fun `a tier is taken from what was asked for before what arrived`() {
        // VBR overshoots; the tier is the server's, not the encoder's.
        assertEquals(
            "FlashMusic Hi-Res",
            flashMusicLabel(stats(claimedKbps = 320, bitrateKbps = 120)),
        )
    }

    @Test
    fun `other sources keep their own labels`() {
        assertNull(flashMusicLabel(stats(mimeType = "audio/flac", source = "JioSaavn")))
        assertNull(flashMusicLabel(stats(source = null)))
    }
}
