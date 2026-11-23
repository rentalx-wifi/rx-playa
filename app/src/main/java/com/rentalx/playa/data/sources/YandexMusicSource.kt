package com.rentalx.playa.data.sources

import com.rentalx.playa.data.TrackLog
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.data.yandex.YandexDownloadInfo
import com.rentalx.playa.data.yandex.YandexMusicApi
import com.rentalx.playa.data.yandex.YandexMusicApiException
import com.rentalx.playa.data.yandex.YandexMusicPlaylistTrack

private const val TAG = "Playa"

/**
 * Yandex Music as one of the sources rather than as an integration beside them.
 *
 * The walk in [SourceResolver] asks this source before YouTube, so a track both
 * catalogues hold plays from Yandex — at FLAC when the connected account has
 * Plus — and anything Yandex cannot supply falls through to YouTube exactly as
 * before.
 *
 * **How the audio is fetched.** Two endpoints, both answering unencrypted.
 * `/tracks/{id}/download-info` lists the lossy tiers and a one-minute
 * `downloadInfoUrl` that is exchanged for a signed storage address; `/get-file-info`
 * carries the FLAC, which that endpoint never has, and answers `raw` only to the
 * desktop client's key and name (see [YandexMusicApi.losslessLink]). The
 * encrypted `encraw` transport is not asked for at all: nothing here could read
 * those bytes.
 *
 * It reads its token from settings on every call rather than holding one, so
 * connecting, reconnecting or signing out takes effect on the next track
 * instead of on the next launch.
 */
class YandexMusicSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    private val api = YandexMusicApi()

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.YANDEX_MUSIC
    override val displayName: String get() = config.label.ifBlank { SourceKind.YANDEX_MUSIC.label }

    private fun token(): String? = AppSettings.yandexMusicToken.value.trim().takeIf { it.isNotBlank() }

    /**
     * One `/account/status` call, which is both the cheapest way to prove the
     * token still works and the only way to know whether the answer will be
     * lossless. [SourceHealth.Rejected] rather than unreachable when there is no
     * token at all: nothing is wrong with the network, and the sources screen
     * should point at the account instead of at the connection.
     */
    override suspend fun health(): SourceHealth {
        val token = token()
            ?: return SourceHealth.Rejected("Sign in to Yandex Music in Account & integrations")
        return try {
            val account = api.account(token)
            SourceHealth.Ok(
                if (account.hasPlus) "${account.displayName} · Plus" else account.displayName,
            )
        } catch (_: YandexMusicApiException.Unauthorized) {
            SourceHealth.Rejected("Yandex Music rejected the token")
        } catch (_: YandexMusicApiException.AccessDenied) {
            SourceHealth.Rejected("Yandex Music denied access to this account")
        } catch (failure: Throwable) {
            SourceHealth.Unreachable(failure.message ?: "Yandex Music is unreachable")
        }
    }

    /**
     * [waitForAll] and [request] are both moot here: one endpoint answers, and
     * the catalogue publishes its rows without a tier — the rendition is chosen
     * per stream, when the account is actually asked what it may have.
     */
    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val token = token() ?: return emptyList()
        val rows = api.searchTracks(token, query, limit)
        TrackLog.d(TAG, "▶ Yandex Music \"$query\" → ${rows.size} tracks")
        return rows.map { it.toSong() }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val token = token() ?: return null
        if (request is StreamRequest.Lossless) {
            // `download-info` never carries FLAC; the signed file endpoint does,
            // and answers `flac-mp4` — FLAC in an MP4 container, which the
            // player reads as FLAC.
            api.losslessLink(token, trackId)?.let { lossless ->
                val isFlac = lossless.codec.startsWith("flac")
                TrackLog.d(TAG, "  ✓ Yandex Music lossless ${lossless.codec}")
                return SourceStream(
                    url = lossless.url,
                    format = StreamFormat(codec = if (isFlac) "flac" else lossless.codec),
                    belowRequest = !isFlac,
                )
            }
        }
        val renditions = api.downloadInfo(token, trackId).ifEmpty { return null }
        val chosen = selectRendition(renditions.filterNot { it.preview }, request)
            ?: selectRendition(renditions, request)
            ?: return null
        val url = chosen.downloadInfoUrl?.let { api.resolveDownloadLink(it, chosen.codec) }
            ?: return null
        TrackLog.d(TAG, "  ✓ Yandex Music ${chosen.codec} ${chosen.bitrateKbps}kbps")
        return SourceStream(
            url = url,
            format = StreamFormat(codec = chosen.codec, kbps = chosen.bitrateKbps.takeIf { it > 0 }),
            // A lossless request that only the lossy tiers could answer is a
            // degraded stream, and saying so is what lets QualityUpgrade look
            // again once playback has started.
            belowRequest = request is StreamRequest.Lossless && chosen.codec != "flac",
        )
    }

    /**
     * The rendition to open for [request].
     *
     * Lossless asks for FLAC and, when the account cannot have it, falls back to
     * the best lossy tier rather than failing — a track that plays at 320kbps
     * beats a track that does not play, which is the same rule
     * [SourceStream.belowRequest] exists to record.
     */
    private fun selectRendition(
        renditions: List<YandexDownloadInfo>,
        request: StreamRequest,
    ): YandexDownloadInfo? = when (request) {
        StreamRequest.Lossless ->
            renditions.filter { it.codec == "flac" }.maxByOrNull { it.bitrateKbps }
                ?: renditions.maxByOrNull { it.bitrateKbps }
        is StreamRequest.Capped ->
            renditions.filter { it.bitrateKbps <= request.maxKbps }.maxByOrNull { it.bitrateKbps }
                ?: renditions.minByOrNull { it.bitrateKbps }
        StreamRequest.Best -> renditions.maxByOrNull { it.bitrateKbps }
    }

    /**
     * A catalogue row as a song this app can play.
     *
     * The id is the source key rather than a YouTube video id: playback goes
     * back through [YandexMusicSource.stream] for it, so the audio is Yandex's
     * own and no YouTube copy stands in.
     */
    private fun YandexMusicPlaylistTrack.toSong(): Song = toSourceSong(configId)
}

/**
 * A catalogue row as a song this app can play.
 *
 * The id is the source key rather than a YouTube video id: playback goes back
 * through [YandexMusicSource.stream] for it, so the audio is Yandex's own and
 * no YouTube copy stands in. Shared with the search screen, which lists Yandex
 * rows as Yandex's rather than matching each one onto YouTube first — matching
 * cost a lookup per row and handed back a YouTube id, which is why a track
 * picked from Yandex used to play from YouTube (issue #29).
 */
internal fun YandexMusicPlaylistTrack.toSourceSong(configId: String): Song = Song(
    videoId = SourceRegistry.trackKey(configId, id),
    title = title,
    artist = artist,
    albumName = album,
    thumbnailUrl = coverUrl,
    durationText = durationMs.takeIf { it > 0L }?.let { ms ->
        String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60_000, (ms / 1_000) % 60)
    },
)
