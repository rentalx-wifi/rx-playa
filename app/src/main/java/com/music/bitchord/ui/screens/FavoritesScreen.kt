package com.music.bitchord.ui.screens

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
import com.music.bitchord.R
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playingRowMatcher
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.LocalNothingStyleEnabled
import com.music.bitchord.ui.theme.NothingPanelShape
import com.music.bitchord.ui.theme.nothingPanelChrome
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.Downloads
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
) {
    val nothing = LocalNothingStyleEnabled.current
    val matcher = playingRowMatcher(songs, currentSong)
    // Two answers, from the two places that own them: the record of what is on
    // disk, and the queue of what is on its way. The header needs both to say
    // "twelve of thirty" and to know whether there is anything left to ask for.
    val saved by Downloads.saved.collectAsStateWithLifecycle()
    val active by Downloads.active.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    val offlineMode by AppSettings.offlineMode.collectAsStateWithLifecycle()
    val offline = offlineMode || metered == null
    val missing = remember(songs, saved) { songs.filterNot { it.videoId in saved } }
    val inFlight = missing.count { active[it.videoId] is DownloadState.Queued || active[it.videoId] is DownloadState.Running }
    val downloadedCount = songs.size - missing.size
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
                    if (onDownloadAll != null && onCancelDownloads != null) {
                        Row(
                            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (missing.isEmpty()) {
                                Icon(
                                    BitChordIcons.StarFilled,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.favorites_all_offline, downloadedCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Button(onClick = { onDownloadAll(missing) }) {
                                    Text(stringResource(R.string.favorites_download_all, missing.size))
                                }
                                if (inFlight > 0) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    OutlinedButton(onClick = { onCancelDownloads(missing) }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        R.string.favorites_offline_progress,
                                        downloadedCount,
                                        songs.size,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                                BitChordIcons.StarFilled,
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
