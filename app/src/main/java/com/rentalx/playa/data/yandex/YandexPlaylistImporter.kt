package com.rentalx.playa.data.yandex

import com.rentalx.playa.data.importing.ExternalPlaylistImporter
import com.rentalx.playa.data.model.Song

/** Matches Yandex playlist metadata to tracks playable by Playa. */
object YandexPlaylistImporter {
    suspend fun resolve(
        tracks: List<YandexMusicPlaylistTrack>,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Pair<List<Song>, List<YandexMusicPlaylistTrack>> =
        ExternalPlaylistImporter.resolve(tracks, onProgress)
}
