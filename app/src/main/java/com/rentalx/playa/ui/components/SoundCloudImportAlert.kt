package com.rentalx.playa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rentalx.playa.R
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.soundcloud.SoundCloudImportTrack
import com.rentalx.playa.data.soundcloud.SoundCloudPlaylistImporter
import com.rentalx.playa.data.soundcloud.SoundCloudAccountApi
import com.rentalx.playa.data.soundcloud.SoundCloudImportPlaylist
import com.rentalx.playa.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Imports complete public SoundCloud sets or profile collections without an account token. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SoundCloudImportAlert(
    hazeState: HazeState,
    onImported: (title: String, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<SoundCloudImportTrack>?>(null) }
    var accountPlaylists by remember { mutableStateOf<List<SoundCloudImportPlaylist>>(emptyList()) }
    var publicProfileLoaded by remember { mutableStateOf(false) }
    val soundCloudConnected by AppSettings.soundCloudAccessToken.collectAsStateWithLifecycle()

    LaunchedEffect(soundCloudConnected) {
        if (soundCloudConnected.isBlank()) return@LaunchedEffect
        try {
            val playlists = SoundCloudAccountApi.playlists()
            if (!publicProfileLoaded) accountPlaylists = playlists
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!publicProfileLoaded) accountPlaylists = emptyList()
        }
    }

    fun importPlaylist(playlist: SoundCloudImportPlaylist) {
        working = true
        failed = false
        status = context.getString(R.string.soundcloud_import_fetching)
        scope.launch {
            try {
                val hydrated = if (playlist.sourceUrn != null && soundCloudConnected.isNotBlank()) {
                    SoundCloudAccountApi.hydratePlaylist(playlist)
                } else if (playlist.sourceUrl != null) {
                    SoundCloudPlaylistImporter.fetch(playlist.sourceUrl).copy(title = playlist.title)
                } else {
                    playlist
                }
                val (songs, missed) = SoundCloudPlaylistImporter.resolve(hydrated.tracks) { done, total ->
                    status = context.getString(R.string.spotify_import_matching, done, total)
                }
                if (songs.isEmpty()) error(context.getString(R.string.spotify_import_none))
                onImported(hydrated.title, songs)
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

    fun start() {
        if (!SoundCloudPlaylistImporter.isPlaylistLink(link)) {
            failed = true
            status = context.getString(R.string.soundcloud_import_invalid)
            return
        }
        working = true
        failed = false
        status = context.getString(R.string.soundcloud_import_fetching)
        scope.launch {
            try {
                val collections = SoundCloudPlaylistImporter.fetchPublicCollections(
                    link,
                    context.getString(R.string.auto_liked),
                )
                working = false
                val playlist = collections.singleOrNull()
                if (playlist != null && playlist.sourceUrl == null) {
                    importPlaylist(playlist)
                } else {
                    accountPlaylists = collections
                    publicProfileLoaded = true
                    status = context.getString(R.string.soundcloud_public_choose)
                }
                return@launch
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
            Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.soundcloud_import_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            )
            val missed = unmatched
            if (missed != null) {
                Text(
                    stringResource(R.string.spotify_import_unmatched, missed.size),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    textAlign = TextAlign.Center,
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(missed) { track ->
                        Text(
                            "${track.title} · ${track.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    status ?: stringResource(R.string.soundcloud_import_description),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
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
                        if (publicProfileLoaded) {
                            accountPlaylists = emptyList()
                            publicProfileLoaded = false
                        }
                        failed = false
                        status = null
                    },
                    placeholder = stringResource(R.string.soundcloud_import_hint),
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank() && !working) start() }),
                )
                if (accountPlaylists.isNotEmpty()) {
                    Text(
                        stringResource(if (publicProfileLoaded) R.string.soundcloud_public_choose else R.string.playlists),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        items(accountPlaylists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !working) { importPlaylist(playlist) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                    )
                                    if (playlist.trackCount > 0) {
                                        Text(
                                            playlist.trackCount.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        AlertRule()
        if (unmatched != null) {
            AlertAction(stringResource(R.string.done), true, onDismiss)
        } else {
            AlertAction(
                stringResource(R.string.spotify_import_button),
                true,
                ::start,
                enabled = link.isNotBlank() && !working,
            )
            AlertRule()
            AlertAction(stringResource(R.string.cancel), false, onDismiss, enabled = !working)
        }
    }
}
