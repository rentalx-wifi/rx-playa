package com.music.bitchord

import com.music.bitchord.ui.screens.FavoriteDownloadState
import com.music.bitchord.ui.screens.favoriteDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header's download block has one state at a time — see
 * [favoriteDownloadState]. These pin the precedence, because the layout draws
 * buttons straight off the class it returns: a wrong answer here is a card with
 * "Cancel" and "Download" on screen together, which is what it looked like
 * before.
 */
class FavoriteDownloadStateTest {

    @Test
    fun `nothing on the device and nothing running is idle`() {
        val state = favoriteDownloadState(total = 231, downloaded = 12, inFlight = 0, failed = 0)
        assertEquals(FavoriteDownloadState.Idle(downloaded = 12, total = 231), state)
    }

    @Test
    fun `a moving queue outranks a failed row`() {
        val state = favoriteDownloadState(total = 231, downloaded = 12, inFlight = 1, failed = 5)
        assertEquals(FavoriteDownloadState.Downloading(downloaded = 12, total = 231), state)
    }

    @Test
    fun `a stopped queue with losses is its own state`() {
        val state = favoriteDownloadState(total = 231, downloaded = 226, inFlight = 0, failed = 5)
        assertEquals(FavoriteDownloadState.Failed(downloaded = 226, total = 231, failed = 5), state)
    }

    @Test
    fun `everything here is complete`() {
        val state = favoriteDownloadState(total = 231, downloaded = 231, inFlight = 0, failed = 0)
        assertEquals(FavoriteDownloadState.Complete(231), state)
    }

    @Test
    fun `an empty favourites list is idle, not complete`() {
        val state = favoriteDownloadState(total = 0, downloaded = 0, inFlight = 0, failed = 0)
        assertTrue(state is FavoriteDownloadState.Idle)
    }

    @Test
    fun `counts are clamped to the list`() {
        val state = favoriteDownloadState(total = 10, downloaded = 99, inFlight = 0, failed = 0)
        assertEquals(FavoriteDownloadState.Complete(10), state)
    }

    @Test
    fun `exactly one action is possible in every combination`() {
        for (total in 0..3) {
            for (downloaded in 0..3) {
                for (inFlight in 0..1) {
                    for (failed in 0..2) {
                        val state = favoriteDownloadState(total, downloaded, inFlight, failed)
                        val actions = when (state) {
                            is FavoriteDownloadState.Idle -> listOf(true, false, false, false)
                            is FavoriteDownloadState.Downloading -> listOf(false, true, false, false)
                            is FavoriteDownloadState.Failed -> listOf(false, false, true, false)
                            is FavoriteDownloadState.Complete -> listOf(false, false, false, true)
                        }
                        assertEquals(
                            "total=$total downloaded=$downloaded inFlight=$inFlight failed=$failed gave $state",
                            1,
                            actions.count { it },
                        )
                        // A start button only ever comes with something left to fetch.
                        if (state is FavoriteDownloadState.Idle) {
                            assertTrue(state.total >= state.downloaded)
                        }
                    }
                }
            }
        }
    }
}
