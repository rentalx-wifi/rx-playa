package com.rentalx.playa.data.sources

import com.rentalx.playa.data.TrackLog
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.data.yandex.YandexAudio
import com.rentalx.playa.data.yandex.YandexMusicApi
import com.rentalx.playa.data.yandex.YandexMusicPlaylistTrack

private const val TAG = "Playa"

/**
 * Yandex Music as a catalogue and a stream.
 *
 * The account, the API and the signature were already here — [YandexMusicApi]
 * has been importing playlists and metadata for a while — so this is the part
 * that makes it a source the player can walk: a search, and a track id turned
 * into something openable.
 *
 * **What it plays is what the API hands over unencrypted.** The Android
 * rendition arrives encrypted end to end (`encraw`, AES-256-CTR under a key
 * from the same answer) and this app has no reader for it yet, so the stream is
 * asked for as [YandexAudio.TRANSPORT_DIRECT]. When the account is not offered
 * one, this returns null and the walk moves on to the next source — a fallback
 * to a playable copy beats a URL that would decode as noise.
 *
 * Search does not need an account: the public `/search` answers anyone, which
 * keeps Yandex rows in the results for a listener who has not signed in. Their
 * streams then fall through, as they should.
 */
class YandexMusicSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.YANDEX_MUSIC
    override val displayName: String get() = config.label.ifBlank { SourceKind.YANDEX_MUSIC.label }

    /**
     * Ok with or without a token: the catalogue answers anonymously, and a
     * source that reported itself broken here would be skipped entirely —
     * taking its browsable rows with it.
     */
    override suspend fun health(): SourceHealth = SourceHealth.Ok(
        detail = if (token().isBlank()) "Anonymous — sign in to play" else null,
    )

    private val api = YandexMusicApi()

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        val token = token()
        val rows = if (token.isBlank()) {
            YandexMusicApi().searchTracksAnonymous(query, limit)
        } else {
            YandexMusicApi().searchTracks(token, query, limit)
        }
        TrackLog.d(TAG, "▶ Yandex Music search \"$query\" → ${rows.size} tracks")
        val losslessWanted = request is StreamRequest.Lossless
        return rows.take(limit).map { row -> row.toSong(losslessWanted) }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val token = token()
        if (token.isBlank()) return null
        val quality = YandexAudio.qualityFor(
            lossless = request is StreamRequest.Lossless,
            cappedKbps = (request as? StreamRequest.Capped)?.maxKbps,
        )
        val rendition = api
            .fileInfo(token, trackId, quality, transport = YandexAudio.TRANSPORT_DIRECT)
            // The encrypted transport answers with a key; nothing here could
            // read those bytes, so they are not a stream this app has.
            .firstOrNull { it.keyHex == null && it.url.startsWith("https://") }
        if (rendition == null) {
            TrackLog.d(TAG, "  · Yandex Music had no plain rendition for $trackId at $quality")
            return null
        }
        TrackLog.d(TAG, "  ✓ Yandex Music ${rendition.codec} ${rendition.bitrateKbps ?: "?"}kbps")
        return SourceStream(
            url = rendition.url,
            format = StreamFormat(codec = rendition.codec, kbps = rendition.bitrateKbps),
        )
    }

    private fun token(): String = AppSettings.yandexMusicToken.value.trim()

    private fun YandexMusicPlaylistTrack.toSong(losslessWanted: Boolean) = Song(
        videoId = SourceRegistry.trackKey(config.id, id),
        title = title,
        artist = artist,
        albumName = album,
        thumbnailUrl = coverUrl,
        durationText = durationText(durationMs),
        // What the catalogue holds, not what this account may have: the stream
        // answer is the one that decides, and a Plus-less account simply falls
        // through to the next source.
        sourceQuality = if (losslessWanted) "FLAC" else "HIGH",
    )

    private fun durationText(ms: Long): String? {
        val seconds = ms / 1000
        if (seconds <= 0) return null
        return String.format("%d:%02d", seconds / 60, seconds % 60)
    }
}
