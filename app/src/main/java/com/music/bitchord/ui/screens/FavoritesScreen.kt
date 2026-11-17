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
) {
    val nothing = LocalNothingStyleEnabled.current
    val matcher = playingRowMatcher(songs, currentSong)
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
