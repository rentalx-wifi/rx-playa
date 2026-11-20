package com.music.bitchord

import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.isDeviceFile
import com.music.bitchord.ui.likeBelongsToOfflinePlaylist
import com.music.bitchord.ui.likeStatusFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heart rule — see [likeStatusFor]. The reported bug was a downloaded track
 * whose account rating was LIKE and whose heart in Favourites stayed dark,
 * because the row was taken for a device file.
 */
class LikeStatusRulesTest {

    private fun song(id: String, localUri: String? = null) = Song(
        videoId = id,
        title = "Track",
        artist = "Artist",
        thumbnailUrl = null,
        localUri = localUri,
    )

    private val downloaded = song("dQw4w9WgXcQ", localUri = "file:///music/Track.m4a")
    private val deviceFile = song(
        "content://media/external/audio/media/42",
        localUri = "content://media/external/audio/media/42",
    )

    @Test
    fun `a downloaded track is not a device file`() {
        assertFalse(downloaded.isDeviceFile)
    }

    @Test
    fun `a scanned file is a device file`() {
        assertTrue(deviceFile.isDeviceFile)
    }

    @Test
    fun `a downloaded track keeps the like the account gave it`() {
        assertEquals(
            LikeStatus.LIKE,
            likeStatusFor(
                isDeviceFile = downloaded.isDeviceFile,
                localUri = downloaded.localUri,
                durable = LikeStatus.LIKE,
                offlineLiked = emptySet(),
            ),
        )
    }

    @Test
    fun `a downloaded track the account never rated is indifferent`() {
        assertEquals(
            LikeStatus.INDIFFERENT,
            likeStatusFor(downloaded.isDeviceFile, downloaded.localUri, null, emptySet()),
        )
    }

    @Test
    fun `a device file is liked only through the device list`() {
        assertEquals(
            LikeStatus.INDIFFERENT,
            likeStatusFor(deviceFile.isDeviceFile, deviceFile.localUri, null, emptySet()),
        )
        assertEquals(
            LikeStatus.LIKE,
            likeStatusFor(
                deviceFile.isDeviceFile,
                deviceFile.localUri,
                null,
                setOf(deviceFile.localUri!!),
            ),
        )
    }

    @Test
    fun `a downloaded copy liked as a file still shows liked`() {
        assertEquals(
            LikeStatus.LIKE,
            likeStatusFor(
                downloaded.isDeviceFile,
                downloaded.localUri,
                null,
                setOf(downloaded.localUri!!),
            ),
        )
    }

    @Test
    fun `a dislike wins on every surface`() {
        assertEquals(
            LikeStatus.DISLIKE,
            likeStatusFor(downloaded.isDeviceFile, downloaded.localUri, LikeStatus.DISLIKE, setOf(downloaded.localUri!!)),
        )
    }

    @Test
    fun `the tap is written where the heart is read`() {
        assertFalse(likeBelongsToOfflinePlaylist(downloaded))
        assertTrue(likeBelongsToOfflinePlaylist(deviceFile))
    }
}
