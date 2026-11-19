package com.music.bitchord.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.music.bitchord.R
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.ui.MainViewModel
import dev.chrisbanes.haze.HazeState

/**
 * Every "move a playlist here" dialog, in one place.
 *
 * They were five blocks inside `BitChordApp`, and that composable is one method
 * at the JVM's size ceiling — adding the YouTube Music one pushed it over with
 * "Method too large". The bodies are unchanged; only where they live moved.
 *
 * Four of the five end the same way: rows that become YouTube ids are written
 * into a playlist, and SoundCloud's are kept as they are because they are
 * native SoundCloud rows that a Google playlist cannot hold.
 */
@Composable
fun ImportDialogsHost(
    hazeState: HazeState,
    signedIn: Boolean,
    yandexToken: String,
    showSpotify: Boolean,
    showYandex: Boolean,
    showYoutube: Boolean,
    showSoundCloud: Boolean,
    showVk: Boolean,
    onCloseSpotify: () -> Unit,
    onCloseYandex: () -> Unit,
    onCloseYoutube: () -> Unit,
    onCloseSoundCloud: () -> Unit,
    onCloseVk: () -> Unit,
    onImported: (HomeSource, String, PlaylistPrivacy, List<Song>) -> Unit,
    onImportedSoundCloud: (String, List<Song>) -> Unit,
) {
    if (showSpotify) {
        BackHandler(onBack = onCloseSpotify)
        SpotifyImportAlert(
            hazeState = hazeState,
            signedIn = signedIn,
            onImported = { title, privacy, songs -> onImported(HomeSource.SPOTIFY, title, privacy, songs) },
            onDismiss = onCloseSpotify,
        )
    }
    if (showYandex) {
        BackHandler(onBack = onCloseYandex)
        YandexPlaylistImportAlert(
            hazeState = hazeState,
            token = yandexToken,
            signedIn = signedIn,
            onImported = { title, privacy, songs -> onImported(HomeSource.YANDEX, title, privacy, songs) },
            onDismiss = onCloseYandex,
        )
    }
    if (showYoutube) {
        BackHandler(onBack = onCloseYoutube)
        YouTubeMusicImportAlert(
            hazeState = hazeState,
            signedIn = signedIn,
            onImported = { title, privacy, songs -> onImported(HomeSource.YOUTUBE, title, privacy, songs) },
            onDismiss = onCloseYoutube,
        )
    }
    if (showSoundCloud) {
        BackHandler(onBack = onCloseSoundCloud)
        SoundCloudImportAlert(
            hazeState = hazeState,
            onImported = onImportedSoundCloud,
            onDismiss = onCloseSoundCloud,
        )
    }
    if (showVk) {
        BackHandler(onBack = onCloseVk)
        VkPlaylistImportAlert(
            hazeState = hazeState,
            onImported = { title, songs ->
                onImported(HomeSource.VK, title, PlaylistPrivacy.PRIVATE, songs)
            },
            onDismiss = onCloseVk,
        )
    }
}

/**
 * Writes an imported playlist and opens it.
 *
 * The same ending for every import: the toast says whether it landed in the
 * account or on this device, and a playlist that came back with an id opens
 * straight away so the listener can see what arrived.
 */
fun importPlaylist(
    viewModel: MainViewModel,
    context: Context,
    signedIn: Boolean,
    notice: (String) -> Unit,
    source: HomeSource,
    title: String,
    privacy: PlaylistPrivacy,
    songs: List<Song>,
) {
    viewModel.createPlaylistWithVideoIds(
        title,
        privacy,
        songs.map { it.videoId },
        songs,
        source = source,
    ) { browseId, savedTitle, savedLocally ->
        notice(
            context.getString(
                if (savedLocally && signedIn) R.string.spotify_import_local_fallback
                else R.string.spotify_import_done,
                savedTitle,
            ),
        )
        browseId?.let { id ->
            viewModel.openDetail(id, savedTitle, "${songs.size} songs", songs.firstOrNull()?.thumbnailUrl)
        }
    }
}

/**
 * SoundCloud's own ending: its rows are not YouTube ids, so they are kept as a
 * local playlist rather than being matched into a Google one.
 */
fun importSoundCloudPlaylist(
    viewModel: MainViewModel,
    context: Context,
    notice: (String) -> Unit,
    title: String,
    songs: List<Song>,
) {
    val playlist = LocalPlaylistStore.savePlaylist(
        title = title,
        songs = songs,
        source = HomeSource.SOUNDCLOUD,
    )
    notice(context.getString(R.string.spotify_import_done, title))
    viewModel.openDetail(
        playlist.browseId,
        title,
        "${songs.size} songs",
        songs.firstOrNull()?.thumbnailUrl,
    )
}
