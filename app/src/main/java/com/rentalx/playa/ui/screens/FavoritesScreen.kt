package com.rentalx.playa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rentalx.playa.R
import com.rentalx.playa.data.model.LikeStatus
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.model.playingRowMatcher
import com.rentalx.playa.ui.components.SongRow
import com.rentalx.playa.ui.icons.PlayaIcons
import com.rentalx.playa.ui.theme.LocalNothingStyleEnabled
import com.rentalx.playa.ui.theme.NothingPanelShape
import com.rentalx.playa.ui.theme.nothingPanelChrome
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.rentalx.playa.download.DownloadProgress
import com.rentalx.playa.download.DownloadSession
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.download.DownloadState
import com.rentalx.playa.download.Downloads
import androidx.compose.runtime.getValue

/** A first-class cross-provider Favorites destination. */
@Composable
fun FavoritesScreen(
    songs: List<Song>,
    likeStatusOf: (Song) -> LikeStatus,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onToggleLike: (Song) -> Unit,
    modifier: Modifier = Modifier,
    /** What the header's download button does with the tracks that are not on the device yet. */
    onDownloadAll: ((List<Song>) -> Unit)? = null,
    /** Stops what the same button started. */
    onCancelDownloads: ((List<Song>) -> Unit)? = null,
    /** Asks again for the tracks the last attempt could not fetch. */
    onRetryFailed: ((List<Song>) -> Unit)? = null,
) {
    val nothing = LocalNothingStyleEnabled.current
    val matcher = playingRowMatcher(songs, currentSong)
    // Two answers, from the two places that own them: the record of what is on
    // disk, and the queue of what is on its way. The header needs both to say
    // "twelve of thirty" and to know whether there is anything left to ask for.
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val session by DownloadSession.state.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val offline = offlineMode || metered == null
    val missing = remember(songs, saved) { songs.filterNot { it.videoId in saved } }
    val inFlight = missing.count { active[it.videoId] is DownloadState.Queued || active[it.videoId] is DownloadState.Running }
    val failedSongs = remember(missing, session) {
        missing.filter { song ->
            session.items.any { it.videoId == song.videoId && it.progress is DownloadProgress.Failed }
        }
    }
    val downloadedCount = songs.size - missing.size
    val downloadState = favoriteDownloadState(
        total = songs.size,
        downloaded = downloadedCount,
        inFlight = inFlight,
        failed = failedSongs.size,
    )
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "favorites-header") {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .clip(if (nothing) NothingPanelShape else RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .nothingPanelChrome(nothing)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = if (nothing) {
                        "${stringResource(R.string.auto_liked).uppercase()} // 01"
                    } else {
                        stringResource(R.string.auto_liked)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.auto_liked),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = songs.size.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (songs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { onSongClick(songs, 0) }) {
                            Text(stringResource(R.string.play))
                        }
                        TextButton(
                            onClick = {
                                val shuffled = songs.shuffled()
                                onSongClick(shuffled, 0)
                            },
                        ) {
                            Text(stringResource(R.string.shuffle))
                        }
                    }
                    if (onDownloadAll != null) {
                        FavoriteDownloadBlock(
                            state = downloadState,
                            onDownload = { onDownloadAll(missing) },
                            onCancel = onCancelDownloads?.let { cancel -> { cancel(missing) } },
                            onRetry = onRetryFailed?.let { retry -> { retry(failedSongs) } },
                        )
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            item(key = "favorites-empty") {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.auto_empty_liked_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.auto_empty_liked_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(songs, key = { _, song -> "favorite:${song.videoId}" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onSongClick(songs, index) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    isCurrent = matcher(song),
                    isPlaying = matcher(song) && isPlaying,
                    // Offline, a row that is not on this device can play nothing:
                    // dimmed rather than hidden, so the list still reads as the
                    // listener's own favourites and the missing ones can be
                    // brought back with the download button above.
                    modifier = Modifier.alpha(if (offline && song.videoId !in saved) 0.45f else 1f),
                    trailing = {
                        IconButton(onClick = { onToggleLike(song) }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                PlayaIcons.StarFilled,
                                contentDescription = stringResource(R.string.remove_from_liked),
                                tint = if (likeStatusOf(song) == LikeStatus.LIKE) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * What the header's download block is allowed to say.
 *
 * One of four, never a mixture: the block used to draw the start button, the
 * cancel button, the spinner and the counter side by side, so a phone at 360dp
 * wrapped "Отмена" one letter per line and grew the card over the list. The
 * state is decided here rather than by four independent booleans in the layout,
 * which is also what makes it testable — see `FavoriteDownloadStateTest`.
 */
internal sealed interface FavoriteDownloadState {

    /** Nothing running, nothing failed: something is still to fetch. */
    data class Idle(val downloaded: Int, val total: Int) : FavoriteDownloadState

    /** Anything queued or running. Cancel belongs here and nowhere else. */
    data class Downloading(val downloaded: Int, val total: Int) : FavoriteDownloadState

    /** The queue has stopped and some tracks did not arrive. */
    data class Failed(val downloaded: Int, val total: Int, val failed: Int) : FavoriteDownloadState

    /** Every track is on this device. */
    data class Complete(val total: Int) : FavoriteDownloadState
}

/**
 * Which of the four it is.
 *
 * Downloading outranks failed: as long as anything is still moving, the queue is
 * working and a retry would only add a second copy of a track already in it.
 * Failed outranks complete for the same reason in reverse — a queue that ended
 * with casualties is not a finished job, however many tracks did land.
 */
internal fun favoriteDownloadState(
    total: Int,
    downloaded: Int,
    inFlight: Int,
    failed: Int,
): FavoriteDownloadState {
    val songs = total.coerceAtLeast(0)
    val here = downloaded.coerceIn(0, songs)
    return when {
        inFlight > 0 -> FavoriteDownloadState.Downloading(here, songs)
        failed > 0 -> FavoriteDownloadState.Failed(here, songs, failed)
        songs > 0 && here >= songs -> FavoriteDownloadState.Complete(songs)
        else -> FavoriteDownloadState.Idle(here, songs)
    }
}

/**
 * The download block of the Favourites header: two short lines at most, and one
 * decision at a time.
 *
 * Every text that shares a line with a button is `weight(1f)` with a single line
 * and no wrapping, because the alternative on a narrow phone is a word folding
 * one letter per row and a card that grows over the list under it. Nothing here
 * is wider than the card, so the header keeps its height whatever the counts.
 */
@Composable
private fun FavoriteDownloadBlock(
    state: FavoriteDownloadState,
    onDownload: () -> Unit,
    onCancel: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val caption = MaterialTheme.typography.bodyMedium
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        when (state) {
            is FavoriteDownloadState.Downloading -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.favorites_downloading, state.downloaded, state.total),
                        style = caption,
                        color = quiet,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onCancel != null) {
                        TextButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { state.downloaded.toFloat() / state.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
            is FavoriteDownloadState.Failed -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.favorites_failed_count, state.failed),
                    style = caption,
                    color = quiet,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onRetry?.invoke() },
                    enabled = onRetry != null,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.favorites_retry, state.failed),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            is FavoriteDownloadState.Complete -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    PlayaIcons.StarFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.favorites_all_offline, state.total),
                    style = caption,
                    color = quiet,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            is FavoriteDownloadState.Idle -> {
                Text(
                    text = stringResource(
                        R.string.favorites_offline_progress,
                        state.downloaded,
                        state.total,
                    ),
                    style = caption,
                    color = quiet,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.favorites_download_all,
                            (state.total - state.downloaded).coerceAtLeast(0),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The four states at the width the layout broke at, so a regression is visible. */
@Preview(name = "Favourites download block · 360dp", widthDp = 360, showBackground = true)
@Composable
private fun FavoriteDownloadBlockPreview() {
    MaterialTheme {
        Column(
            Modifier.width(328.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FavoriteDownloadBlock(FavoriteDownloadState.Idle(12, 231), {}, null, null)
            FavoriteDownloadBlock(FavoriteDownloadState.Downloading(12, 231), {}, {}, null)
            FavoriteDownloadBlock(FavoriteDownloadState.Failed(12, 231, 5), {}, null) {}
            FavoriteDownloadBlock(FavoriteDownloadState.Complete(231), {}, null, null)
        }
    }
}
