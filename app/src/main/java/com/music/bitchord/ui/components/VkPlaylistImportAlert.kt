package com.music.bitchord.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.importing.BasicExternalPlaylistTrack
import com.music.bitchord.data.importing.ExternalPlaylistImporter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.vk.VkPlaylistImporter
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VkPlaylistImportAlert(
    hazeState: HazeState,
    onImported: (String, List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultTitle = stringResource(R.string.vk_import_title)
    var title by remember { mutableStateOf(defaultTitle) }
    var text by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<BasicExternalPlaylistTrack>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        failed = false
        status = context.getString(R.string.vk_import_reading)
        scope.launch {
            try {
                text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        VkPlaylistImporter.readText(it)
                    } ?: error("The selected file cannot be read")
                }
                status = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status = context.getString(R.string.vk_import_invalid)
            } finally {
                working = false
            }
        }
    }

    fun start() {
        working = true
        failed = false
        status = context.getString(R.string.vk_import_reading)
        scope.launch {
            try {
                val tracks = withContext(Dispatchers.Default) { VkPlaylistImporter.parse(text) }
                val (songs, missed) = ExternalPlaylistImporter.resolve(tracks) { done, total ->
                    status = context.getString(R.string.spotify_import_matching, done, total)
                }
                if (songs.isEmpty()) {
                    failed = true
                    status = context.getString(R.string.spotify_import_none)
                    return@launch
                }
                onImported(title.trim().ifBlank { defaultTitle }, songs)
                if (missed.isEmpty()) onDismiss() else unmatched = missed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                status = context.getString(R.string.vk_import_invalid)
            } finally {
                working = false
            }
        }
    }

    AlertScaffold(hazeState, onDismiss = { if (!working) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(defaultTitle, style = MaterialTheme.typography.titleMedium)
            val missed = unmatched
            if (missed != null) {
                Text(
                    stringResource(R.string.spotify_import_unmatched, missed.size),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(missed) { track ->
                        Text("${track.artist} — ${track.title}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                Text(
                    status ?: stringResource(R.string.vk_import_description),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PillTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = stringResource(R.string.playlist_name),
                    enabled = !working,
                )
                val hint = stringResource(R.string.vk_import_hint)
                BasicTextField(
                    value = text,
                    onValueChange = {
                        failed = it.length > 4 * 1024 * 1024
                        if (!failed) text = it
                        status = if (failed) context.getString(R.string.vk_import_invalid) else null
                    },
                    enabled = !working,
                    minLines = 4,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp)).padding(12.dp),
                    decorationBox = { field ->
                        if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
                        field()
                    },
                )
            }
        }
        AlertRule()
        if (unmatched != null) {
            AlertAction(stringResource(R.string.done), true, onDismiss)
        } else {
            AlertAction(
                stringResource(R.string.choose_file), false,
                { filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                enabled = !working,
            )
            AlertRule()
            AlertAction(stringResource(R.string.spotify_import_button), true, ::start, enabled = !working && text.isNotBlank())
            AlertRule()
            AlertAction(stringResource(R.string.cancel), false, onDismiss, enabled = !working)
        }
    }
}
