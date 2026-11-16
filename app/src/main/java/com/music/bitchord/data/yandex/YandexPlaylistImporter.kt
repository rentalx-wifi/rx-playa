package com.music.bitchord.data.yandex

import com.music.bitchord.data.importing.ExternalPlaylistImporter
import com.music.bitchord.data.model.Song

/** Matches Yandex playlist metadata to tracks playable by BitChord. */
object YandexPlaylistImporter {
    suspend fun resolve(
        tracks: List<YandexMusicPlaylistTrack>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<YandexMusicPlaylistTrack>> =
        ExternalPlaylistImporter.resolve(tracks, onProgress)
}
