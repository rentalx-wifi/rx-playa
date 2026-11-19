package com.music.bitchord.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.music.bitchord.R
import com.music.bitchord.data.importing.YouTubeMusicPlaylistImport
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Copies a YouTube Music playlist into this app by its link.
 *
 * The link is the whole input: there is no account to connect and no matching
 * to do, because the rows already are the exact uploads being asked for. What
 * an import like this does need is to be told that a private playlist of
 * somebody else's simply will not open — that is what the error line says when
 * the first page comes back empty.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun YouTubeMusicImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun start() {
        if (working) return
        if (YouTubeMusicPlaylistImport.browseIdOf(link) == null) {
            failed = true
            status = context.getString(R.string.youtube_import_invalid_link)
            return
        }
        working = true
        failed = false
        status = context.getString(R.string.youtube_import_reading)
        scope.launch {
            try {
                val imported = YouTubeMusicPlaylistImport.load(link) { count ->
                    status = context.resources.getQuantityString(
                        R.plurals.youtube_import_progress,
                        count,
                        count,
                    )
                }
                onImported(imported.title, privacy, imported.songs)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failed = true
                status = context.getString(R.string.spotify_import_failed, e.message.orEmpty())
            }
            working = false
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.youtube_import_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = status ?: stringResource(R.string.youtube_import_description),
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
                placeholder = stringResource(R.string.youtube_import_link_hint),
                enabled = !working,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            if (signedIn) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.who_can_see_it),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
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
        AlertRule()
        AlertAction(
            label = stringResource(R.string.youtube_import_button),
            emphasised = true,
            onClick = ::start,
            enabled = link.isNotBlank() && !working,
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
