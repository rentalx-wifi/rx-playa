package com.rentalx.playa.data.importing

import com.rentalx.playa.data.model.Song
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException

internal data class PlaylistImportWriteResult(
    val cloudPlaylistId: String?,
    val acceptedCloudTrackCount: Int,
    val localBrowseId: String?,
) {
    val savedLocally: Boolean get() = localBrowseId != null
    val browseId: String get() = localBrowseId ?: "VL${checkNotNull(cloudPlaylistId)}"
}

/** A failed cloud write keeps its acknowledged prefix and saves the complete resolved list locally. */
internal suspend fun writeImportedPlaylist(
    videoIds: List<String>,
    songs: List<Song>,
    createPlaylist: suspend (List<String>) -> Result<String>,
    addToPlaylist: suspend (String, List<String>) -> Result<Map<String, String>>,
    saveLocally: (List<Song>) -> String,
): PlaylistImportWriteResult {
    var playlistId: String? = null
    var acceptedCount = 0
    val cloudWrite = runCatching {
        currentCoroutineContext().ensureActive()
        val initialBatch = videoIds.take(PLAYLIST_WRITE_BATCH_SIZE)
        val createdId = createPlaylist(initialBatch).getOrThrow()
        playlistId = createdId
        acceptedCount = initialBatch.size

        for (chunk in videoIds.drop(PLAYLIST_WRITE_BATCH_SIZE).chunked(PLAYLIST_WRITE_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            addToPlaylist(createdId, chunk).getOrThrow()
            // The returned map contains removal ids, not a count; success acknowledges the whole batch.
            acceptedCount += chunk.size
        }
    }.onFailure { if (it is CancellationException) throw it }

    // Even a non-cooperative uploader must not turn an abandoned import into a local success.
    currentCoroutineContext().ensureActive()
    return PlaylistImportWriteResult(
        cloudPlaylistId = playlistId,
        acceptedCloudTrackCount = acceptedCount,
        localBrowseId = if (cloudWrite.isFailure) saveLocally(songs) else null,
    )
}

private const val PLAYLIST_WRITE_BATCH_SIZE = 50
