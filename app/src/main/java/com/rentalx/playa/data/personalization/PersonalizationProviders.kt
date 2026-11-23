package com.rentalx.playa.data.personalization

import com.rentalx.playa.data.TrackLog
import com.rentalx.playa.data.YtMusicRepository
import com.rentalx.playa.data.importing.ExternalPlaylistTrack
import com.rentalx.playa.data.model.ArtistRef
import com.rentalx.playa.data.model.HomeShelf
import com.rentalx.playa.data.model.HomeSource
import com.rentalx.playa.data.model.SearchFilter
import com.rentalx.playa.data.model.SearchResult
import com.rentalx.playa.data.model.ShelfItem
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.soundcloud.SoundCloudPlaylistImporter
import com.rentalx.playa.data.soundcloud.SoundCloudAccountApi
import com.rentalx.playa.data.soundcloud.toSearchSong
import com.rentalx.playa.data.vk.VkMusicApi
import com.rentalx.playa.data.sources.AddonSource
import com.rentalx.playa.data.sources.FlashMusicSource
import com.rentalx.playa.data.sources.SourceRegistry
import com.rentalx.playa.data.sources.TrackMatcher
import com.rentalx.playa.data.spotify.SPOTIFY_PAGE_PREFIX
import com.rentalx.playa.data.spotify.SpotifyLibrary
import com.rentalx.playa.data.spotify.SpotifyPlaylist
import com.rentalx.playa.data.spotify.SpotifyTrack
import com.rentalx.playa.data.spotify.toSourceSong
import com.rentalx.playa.data.yandex.YandexMusicApi
import com.rentalx.playa.data.yandex.YandexMusicApiException
import com.rentalx.playa.data.yandex.YandexMusicPlaylistTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.security.MessageDigest

/** Reads only provider-supported account metadata; public search is not presented as remote user likes. */
internal class PersonalizationProviders {
    suspend fun youtube(account: String): ProviderTasteSnapshot {
        val liked = part {
            val first = YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC).getOrThrow()
            collectTasteSongPages(first.songs, first.continuation) { cursor ->
                YtMusicRepository.moreSongs(cursor).getOrThrow().let { it.songs to it.continuation }
            }
        }
        val history = part { YtMusicRepository.history().getOrThrow() }
        if (liked.isFailure && history.isFailure) throw IOException("YouTube taste data is unavailable")
        val signals = liked.getOrDefault(emptyList()).mapIndexed { index, song ->
            TasteSignal("like:${song.videoId}", HomeSource.YOUTUBE, TasteSignalKind.LIKE,
                song, order = index, account = account)
        } + history.getOrDefault(emptyList()).mapIndexed { index, song ->
            TasteSignal("history:${song.videoId}", HomeSource.YOUTUBE, TasteSignalKind.HISTORY,
                song, count = 0, order = index, account = account)
        }
        return ProviderTasteSnapshot(signals, incomplete = liked.isFailure || history.isFailure)
    }

    suspend fun yandex(account: String, token: String): ProviderTasteSnapshot {
        val api = YandexMusicApi()
        val uid = api.account(token).uid
        val liked = part { yandexList { api.likedTracks(token, uid) } }
        val artists = part { yandexList { api.likedArtists(token, uid) } }
        val history = part { yandexList { api.historyTracks(token) } }
        if (liked.isFailure && artists.isFailure && history.isFailure) throw IOException("Yandex taste data is unavailable")
        val signals = liked.getOrDefault(emptyList()).mapIndexed { index, like ->
            val song = like.track.song()
            TasteSignal("like:${like.track.id}", HomeSource.YANDEX, TasteSignalKind.LIKE,
                song, atMs = like.likedAtMs, order = index, requiresMatching = true, account = account)
        } + artists.getOrDefault(emptyList()).map { artist ->
            TasteSignal("artist:${artist.name}", HomeSource.YANDEX, TasteSignalKind.ARTIST,
                artist = ArtistRef(artist.name), atMs = artist.likedAtMs, account = account)
        } + history.getOrDefault(emptyList()).mapIndexed { index, entry ->
            TasteSignal("history:$index:${entry.track.id}", HomeSource.YANDEX, TasteSignalKind.HISTORY,
                entry.track.song(), atMs = entry.listenedDayMs, count = 0, order = index,
                requiresMatching = true, account = account)
        }
        return ProviderTasteSnapshot(signals, incomplete = liked.isFailure || artists.isFailure || history.isFailure)
    }

    suspend fun spotify(account: String): ProviderTasteSnapshot = coroutineScope {
        val likedRequest = async { part { SpotifyLibrary.tracks(SpotifyLibrary.LIKED_ID) } }
        val playlistRequest = async { part { SpotifyLibrary.playlists() } }
        val liked = likedRequest.await()
        val playlists = playlistRequest.await()
        if (liked.isFailure && playlists.isFailure) throw IOException("Spotify taste data is unavailable")
        val playlistTaste = playlists.getOrNull()?.let { rows ->
            collectSpotifyPlaylistTaste(account, rows) { id -> SpotifyLibrary.tracks(id) }
        } ?: SpotifyPlaylistTaste(emptyList(), incomplete = true)
        val signals = liked.getOrDefault(emptyList()).mapIndexed { index, track ->
            TasteSignal("like:${track.id}", HomeSource.SPOTIFY, TasteSignalKind.LIKE,
                track.song(), atMs = track.likedAtMs, order = index, requiresMatching = false, account = account)
        } + playlistTaste.signals
        val cards = playlists.getOrDefault(emptyList()).map { playlist ->
            ShelfItem(playlist.name, playlist.owner.orEmpty(), playlist.imageUrl, null,
                SPOTIFY_PAGE_PREFIX + playlist.id, sources = setOf(HomeSource.SPOTIFY))
        }
        ProviderTasteSnapshot(signals,
            collections = if (cards.isEmpty()) emptyList() else listOf(HomeShelf("Spotify", cards)),
            incomplete = liked.isFailure || playlists.isFailure || playlistTaste.incomplete)
    }

    suspend fun soundCloud(account: String): ProviderTasteSnapshot = coroutineScope {
        val likedRequest = async { part { SoundCloudAccountApi.likedTracks() } }
        val playlistRequest = async { part { SoundCloudAccountApi.playlists() } }
        val liked = likedRequest.await()
        val playlists = playlistRequest.await()
        if (liked.isFailure && playlists.isFailure) throw IOException("SoundCloud taste data is unavailable")
        val signals = liked.getOrDefault(emptyList()).mapIndexed { index, track ->
            val song = track.toSearchSong(index)
            TasteSignal(
                id = "like:${track.sourceUrn ?: song.videoId}",
                source = HomeSource.SOUNDCLOUD,
                kind = TasteSignalKind.LIKE,
                song = song,
                order = index,
                requiresMatching = false,
                account = account,
            )
        } + playlists.getOrDefault(emptyList()).flatMapIndexed { playlistIndex, playlist ->
            playlist.tracks.mapIndexed { index, track ->
                TasteSignal(
                    id = "playlist:$playlistIndex:${track.sourceUrn ?: index}",
                    source = HomeSource.SOUNDCLOUD,
                    kind = TasteSignalKind.PLAYLIST,
                    song = track.toSearchSong(index),
                    order = index,
                    requiresMatching = false,
                    account = account,
                )
            }
        }
        val cards = playlists.getOrDefault(emptyList()).mapIndexed { index, playlist ->
            ShelfItem(
                title = playlist.title,
                subtitle = "SoundCloud",
                thumbnailUrl = playlist.tracks.firstNotNullOfOrNull { it.coverUrl },
                videoId = null,
                browseId = null,
                sources = setOf(HomeSource.SOUNDCLOUD),
            )
        }
        ProviderTasteSnapshot(
            signals = signals,
            collections = if (cards.isEmpty()) emptyList() else listOf(HomeShelf("SoundCloud", cards)),
            incomplete = liked.isFailure || playlists.isFailure,
        )
    }

    suspend fun recommendations(source: HomeSource, artist: String, yandexToken: String): List<TasteTrack> {
        val songs = when (source) {
            HomeSource.YOUTUBE -> YtMusicRepository.search(artist, SearchFilter.SONGS).getOrThrow().mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    is SearchResult.Browse -> null
                }
            }
            HomeSource.YANDEX -> YandexMusicApi().searchTracks(yandexToken, artist, 16).map { it.song() }
            HomeSource.SPOTIFY -> SpotifyLibrary.searchTracks(artist, 16).map(SpotifyTrack::toSourceSong)
            HomeSource.SOUNDCLOUD -> SoundCloudPlaylistImporter.search(artist, 16)
                .mapIndexed { index, track -> track.toSearchSong(index) }
            HomeSource.VK -> VkMusicApi.search(artist, 16)
            HomeSource.ADDONS -> {
                val rows = mutableListOf<Song>()
                for (addon in SourceRegistry.active().filterIsInstance<AddonSource>()) {
                    currentCoroutineContext().ensureActive()
                    rows += addon.search(artist, 16, false, null)
                }
                rows
            }
            HomeSource.FLASHMUSIC -> {
                // Recommendations from the listener's own catalogue, the same
                // way addons contribute theirs.
                val rows = mutableListOf<Song>()
                for (server in SourceRegistry.active().filterIsInstance<FlashMusicSource>()) {
                    currentCoroutineContext().ensureActive()
                    rows += server.search(artist, 16, false, null)
                }
                rows
            }
            HomeSource.LOCAL -> emptyList()
        }
        val matching = source == HomeSource.YANDEX
        return songs.filter { TrackMatcher.sharesArtist(artist, it.artist) }.map {
            TasteTrack(it, setOf(source), 0.0, false, null, Int.MAX_VALUE, 0, null, matching)
        }
    }
}

internal data class SpotifyPlaylistTaste(
    val signals: List<TasteSignal>,
    val incomplete: Boolean,
)

/** Playlist contents are taste observations too; failures stay partial instead of blocking every other playlist. */
internal suspend fun collectSpotifyPlaylistTaste(
    account: String,
    playlists: List<SpotifyPlaylist>,
    tracks: suspend (String) -> List<SpotifyTrack>,
): SpotifyPlaylistTaste = coroutineScope {
    val gate = Semaphore(SPOTIFY_PLAYLIST_PARALLELISM)
    val results = playlists.filterNot { it.id == SpotifyLibrary.LIKED_ID }.map { playlist ->
        async {
            gate.withPermit {
                try {
                    val rows = tracks(playlist.id).distinctBy { it.id }
                    currentCoroutineContext().ensureActive()
                    Result.success(rows.mapIndexed { index, track ->
                        TasteSignal(
                            id = "playlist:${playlist.id}:${track.id}",
                            source = HomeSource.SPOTIFY,
                            kind = TasteSignalKind.PLAYLIST,
                            song = track.song(),
                            order = index,
                            requiresMatching = false,
                            account = account,
                        )
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
        }
    }.awaitAll()
    SpotifyPlaylistTaste(
        signals = results.flatMap { it.getOrDefault(emptyList()) },
        incomplete = results.any { it.isFailure },
    )
}

/** Complete liked metadata, with cooperative cancellation and an explicit error for a repeated cursor. */
internal suspend fun collectTasteSongPages(
    first: List<Song>,
    firstCursor: String?,
    nextPage: suspend (String) -> Pair<List<Song>, String?>,
): List<Song> {
    val rows = first.toMutableList()
    val seen = HashSet<String>()
    var cursor = firstCursor
    while (cursor != null) {
        currentCoroutineContext().ensureActive()
        if (!seen.add(cursor)) throw IOException("Personalization page cursor repeated")
        val (next, following) = nextPage(cursor)
        currentCoroutineContext().ensureActive()
        rows += next
        cursor = following
    }
    return rows.distinctBy { it.videoId }
}

internal fun ExternalPlaylistTrack.metadataSong(source: HomeSource, id: String? = null, artists: List<String> = emptyList()): Song =
    Song(
        videoId = "taste:${source.name.lowercase()}:${id ?: tasteDigest("$title|$artist|$durationMs")}",
        title = title,
        artist = artist,
        thumbnailUrl = coverUrl,
        durationText = durationMs.takeIf { it > 0L }?.let { "${it / 60_000}:${(it / 1000) % 60}".let { raw ->
            raw.substringBefore(":") + ":" + raw.substringAfter(":").padStart(2, '0')
        } },
        albumName = album,
        artists = artists.map { ArtistRef(it) },
    )

private fun YandexMusicPlaylistTrack.song() = metadataSong(HomeSource.YANDEX, id, artistNames)
private fun SpotifyTrack.song() = toSourceSong().copy(artists = artists.map { ArtistRef(it) })

internal fun tasteDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * A Yandex list that came back empty, which that API expresses as "no result"
 * rather than as an empty array — an account with nothing liked yet, or a
 * history that has not synced, is not a broken account, and it must not put
 * "Yandex Music is unavailable" on the home page.
 */
private suspend fun <T> yandexList(block: suspend () -> List<T>): List<T> = try {
    block()
} catch (_: YandexMusicApiException.EmptyResult) {
    emptyList()
}

private suspend fun <T> part(block: suspend () -> T): Result<T> = try {    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    // The caller turns a failed part into "some data is unavailable", which on
    // its own says nothing about why — the answer is almost always a status
    // code from the provider, and the log is the only place it survives.
    TrackLog.w(
        "Playa",
        "taste part failed: ${failure::class.simpleName}: ${failure.message}",
    )
    Result.failure(IOException("Provider data is unavailable", failure))
}

private const val SPOTIFY_PLAYLIST_PARALLELISM = 2
