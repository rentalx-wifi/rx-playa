package com.rentalx.playa.ui

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rentalx.playa.data.AppleCatalogue
import com.rentalx.playa.data.YtMusicRepository
import com.rentalx.playa.data.canvas.AppleArtistArtRepository
import com.rentalx.playa.data.canvas.CanvasArtwork
import com.rentalx.playa.data.canvas.CanvasRepository
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.download.Downloads

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort

    // One collector for the whole app. Every row that shows a "downloaded"
    // mark used to call this, and each call subscribed to the flow with its own
    // state and lifecycle observer — a hundred rows, a hundred subscriptions,
    // all of them on the scroll path.
    private val cachedIds = mutableStateOf(Downloads.saved.value.keys)
    private val cacheScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var cacheStarted = false

    @Composable
    override fun downloadedIds(): Set<String> {
        if (!cacheStarted) {
            cacheStarted = true
            cacheScope.launch {
                Downloads.saved.collect { saved -> cachedIds.value = saved.keys }
            }
        }
        return cachedIds.value
    }

    override suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value || album.isBlank() || artist.isBlank()) return null
        return CanvasRepository.canvasForAlbum(album, artist)
    }

    /**
     * The clip the artist page plays, off the same proven Apple lookup — so
     * opening the artist from the card costs nothing more, and a card never
     * moves with somebody else's face. The YouTube page it is proved against is
     * fetched only when that lookup hasn't settled already.
     */
    override suspend fun artistMotion(browseId: String, artistName: String): CanvasArtwork? {
        if (!AppSettings.animatedCanvas.value) return null
        val art = AppleArtistArtRepository.artFor(browseId, artistName) {
            YtMusicRepository.artistLanding(browseId).getOrNull()?.let(AppleCatalogue::titlesOf).orEmpty()
        }
        return art?.videoUrl?.let { CanvasArtwork(url = it) }
    }
}
