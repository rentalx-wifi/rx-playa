package com.rentalx.playa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.rentalx.playa.R
import com.rentalx.playa.data.model.PlaylistPrivacy
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.yandex.YandexMusicApi
import com.rentalx.playa.data.yandex.YandexMusicPlaylist
import com.rentalx.playa.data.yandex.YandexMusicPlaylistTrack
import com.rentalx.playa.data.yandex.YandexPlaylistImporter
import com.rentalx.playa.data.yandex.extractYandexPlaylistReference
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Selects an account playlist, matches it on YouTube Music, and saves a Playa-playable copy. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun YandexPlaylistImportAlert(
    hazeState: HazeState,
    token: String,
    signedIn: Boolean,
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { YandexMusicApi() }
    var playlists by remember { mutableStateOf<List<YandexMusicPlaylist>>(emptyList()) }
    var selected by remember { mutableStateOf<YandexMusicPlaylist?>(null) }
    var link by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var unmatched by remember { mutableStateOf<List<YandexMusicPlaylistTrack>?>(null) }

    LaunchedEffect(token) {
        loading = true
        failed = false
        status = context.getString(R.string.yandex_import_loading)
        runCatching {
            val account = api.account(token)
            api.playlists(token, account.uid)
        }.onSuccess {
            playlists = it
            status = if (it.isEmpty()) context.getString(R.string.yandex_import_empty) else null
        }.onFailure {
            failed = true
            status = context.getString(R.string.spotify_import_failed, it.message.orEmpty())
        }
        loading = false
    }

    fun start() {
        val reference = link.trim().takeIf(String::isNotEmpty)?.let(::extractYandexPlaylistReference)
        if (link.isNotBlank() && reference == null) {
            failed = true
            status = context.getString(R.string.yandex_import_invalid_link)
            return
        }
        val chosenPlaylist = selected
        if (reference == null && chosenPlaylist == null) {
            failed = true
            status = context.getString(R.string.yandex_import_select)
            return
        }
        working = true
        failed = false
        status = context.getString(R.string.yandex_import_reading)
        scope.launch {
            try {
                val (playlist, tracks) = if (reference != null) {
                    api.playlistByReference(token, reference)
                } else {
                    requireNotNull(chosenPlaylist) to api.playlistTracks(token, requireNotNull(chosenPlaylist))
                }
                if (tracks.isEmpty()) error(context.getString(R.string.spotify_import_empty_playlist))
                val (songs, missed) = YandexPlaylistImporter.resolve(tracks) { done, total ->
                    status = context.getString(R.string.spotify_import_matching, done, total)
                }
                if (songs.isEmpty()) error(context.getString(R.string.spotify_import_none))
                onImported(playlist.title, privacy, songs)
                if (missed.isEmpty()) onDismiss() else unmatched = missed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status = context.getString(R.string.spotify_import_failed, e.message.orEmpty())
            }
            working = false
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.yandex_import_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            val missed = unmatched
            if (missed != null) {
                Text(
                    text = stringResource(R.string.spotify_import_unmatched, missed.size),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(missed) { track ->
                        Text(
                            text = "${track.title} · ${track.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    text = status ?: stringResource(R.string.yandex_import_description),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = when {
                        failed -> MaterialTheme.colorScheme.error
                        status != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )
                PillTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        if (failed) {
                            failed = false
                            status = null
                        }
                    },
                    placeholder = stringResource(R.string.yandex_import_link_hint),
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp), strokeWidth = 2.dp)
                } else if (playlists.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(playlists, key = { "${it.uid}:${it.kind}" }) { playlist ->
                            val chosen = playlist == selected
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (chosen) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .clickable(enabled = !working) {
                                        selected = playlist
                                        failed = false
                                        status = null
                                    }
                                    .padding(9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AsyncImage(
                                    model = playlist.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        context.resources.getQuantityString(
                                            R.plurals.song_count_plural,
                                            playlist.trackCount,
                                            playlist.trackCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (signedIn) {
                        Text(
                            text = stringResource(R.string.who_can_see_it),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlaylistPrivacy.entries.forEach { option ->
                                val chosen = option == privacy
                                Box(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(percent = 50))
                                        .background(
                                            if (chosen) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        .clickable(enabled = !working) { privacy = option }
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                                        color = if (chosen) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        AlertRule()
        if (unmatched != null) {
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        } else {
            AlertAction(
                label = stringResource(R.string.spotify_import_button),
                emphasised = true,
                onClick = ::start,
                enabled = (selected != null || link.isNotBlank()) && !working && !loading,
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.cancel),
                emphasised = false,
                onClick = onDismiss,
                enabled = !working,
            )
        }
    }
}
