package com.music.bitchord.data.personalization

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackRatingStore
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.importing.BasicExternalPlaylistTrack
import com.music.bitchord.data.importing.ExternalPlaylistImporter
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.spotify.LocalPlaylistStore
import com.music.bitchord.data.stats.ListeningStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

data class PersonalizedHomeState(
    val profile: TasteProfile = TasteProfile(),
    val shelves: List<HomeShelf> = emptyList(),
    val accounts: Map<HomeSource, String> = emptyMap(),
    val loading: Set<HomeSource> = emptySet(),
    val unavailable: Set<HomeSource> = emptySet(),
    val revision: Long = 0L,
)

/** One lifecycle for provider observations, device signals and bounded recommendation requests. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
internal class PersonalizedHome(
    private val context: Context,
    private val scope: CoroutineScope,
    private val youtubeAccount: StateFlow<String?>,
    private val onChanged: (PersonalizedHomeState) -> Unit,
) {
    private class Connections(
        val youtube: String?,
        val spotify: String,
        val yandex: String,
        val soundCloud: String,
        val vk: String,
        val addons: List<SourceConfig>,
    )
    private val snapshots = PersonalizationSnapshots()
    private val providers = PersonalizationProviders()
    private var connections = Connections(null, "", "", "", "", emptyList())
    private val remoteJobs = mutableMapOf<HomeSource, Job>()
    private var localJob: Job? = null
    private var renderJob: Job? = null
    private var recommendationJob: Job? = null
    private var metadataJob: Job? = null
    private var pollingJob: Job? = null
    private var localSignals = emptyList<TasteSignal>()
    private var localCollections = emptyList<HomeShelf>()
    private var localDirty = true
    private var library: LibraryPage? = null
    private var libraryOwner: String? = null
    private val knownSongs = mutableMapOf<String, Song>()
    private var recommendations = emptyMap<HomeSource, List<TasteTrack>>()
    private var recommendationKey: String? = null
    private val lastRemoteAttemptMs = mutableMapOf<HomeSource, Long>()
    private val lastRemoteSuccessMs = mutableMapOf<HomeSource, Long>()
    private var revision = 0L
    private var connectionEpoch = 0L
    private var visible = false
    private var remoteReadyAtMs = Long.MAX_VALUE
    private var loading = emptySet<HomeSource>()
    private var unavailable = emptySet<HomeSource>()
    private var state = PersonalizedHomeState()

    init {
        TasteSignalStore.init(context)
        scope.launch {
            combine(
                youtubeAccount,
                AppSettings.spotifySpdcToken,
                AppSettings.yandexMusicToken,
                AppSettings.soundCloudAccessToken,
                AppSettings.vkAccessToken,
                SourceRegistry.configs,
            ) { fields ->
                Connections(
                    fields[0] as String?,
                    fields[1] as String,
                    fields[2] as String,
                    fields[3] as String,
                    fields[4] as String,
                    @Suppress("UNCHECKED_CAST") (fields[5] as List<SourceConfig>),
                )
            }.collect { next ->
                connections = next
                val changed = snapshots.select(accountKeys(next))
                if (changed.isEmpty()) return@collect
                connectionEpoch++
                changed.forEach { remoteJobs.remove(it)?.cancel() }
                changed.forEach {
                    lastRemoteAttemptMs.remove(it)
                    lastRemoteSuccessMs.remove(it)
                }
                loading = loading - changed
                unavailable = unavailable - changed
                recommendationJob?.cancel()
                metadataJob?.cancel()
                recommendationKey = null
                recommendations = emptyMap()
                localSignals = emptyList()
                localCollections = emptyList()
                if (HomeSource.YOUTUBE in changed) {
                    library = null
                    libraryOwner = null
                    knownSongs.clear()
                }
                // The old account's cards disappear before IO or metadata matching can suspend.
                revision++
                renderJob?.cancel()
                emit(PersonalizedHomeState(accounts = snapshots.accountSnapshot(), revision = revision))
                localDirty = true
                if (visible) {
                    loadLocal(force = true)
                    if (System.currentTimeMillis() >= remoteReadyAtMs) {
                        changed.filter { it in REMOTE_SOURCES }.forEach { loadRemote(it) }
                    }
                }
                rebuild()
            }
        }
        scope.launch {
            combine(
                OfflinePlaylists.playlists,
                LocalPlaylistStore.playlists,
                TasteSignalStore.state,
                TrackRatingStore.ratings,
                ListeningStats.changes,
                SourceRegistry.configs,
            ) { Unit }
                .debounce(150L).collect {
                    if (visible) loadLocal(force = true) else localDirty = true
                }
        }
        scope.launch {
            LikeState.userChanges.collect {
                if (visible) {
                    rebuild()
                    loadChangedLikeMetadata()
                } else {
                    localDirty = true
                }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        pollingJob?.cancel()
        pollingJob = null
        if (!visible) {
            remoteReadyAtMs = Long.MAX_VALUE
            remoteJobs.values.forEach(Job::cancel)
            remoteJobs.clear()
            recommendationJob?.cancel()
            metadataJob?.cancel()
            if (localJob?.isActive == true) localDirty = true
            localJob?.cancel()
            localJob = null
            renderJob?.cancel()
            if (loading.isNotEmpty()) {
                loading = emptySet()
                revision++
                emit(state.copy(loading = emptySet(), revision = revision))
            }
            return
        }
        remoteReadyAtMs = System.currentTimeMillis() + REMOTE_ACTIVATION_DELAY_MS
        loadLocal(force = localDirty)
        pollingJob = scope.launch {
            // Let the first Home frame render before Spotify may need to create
            // its token WebView on the main thread.
            delay(REMOTE_ACTIVATION_DELAY_MS)
            refresh()
            while (true) {
                delay(REMOTE_REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    fun refresh(force: Boolean = false) {
        if (!visible) {
            if (force) localDirty = true
            return
        }
        if (force) {
            recommendationJob?.cancel()
            recommendationKey = null
            recommendations = emptyMap()
        }
        loadLocal(force)
        val now = System.currentTimeMillis()
        if (!force && now < remoteReadyAtMs) return
        REMOTE_SOURCES.forEach { source ->
            val hasSnapshot = snapshots.has(source)
            val last = if (hasSnapshot) lastRemoteSuccessMs[source] else lastRemoteAttemptMs[source]
            val due = last == null || now - last >= if (hasSnapshot && source !in unavailable) {
                REMOTE_REFRESH_INTERVAL_MS
            } else {
                REMOTE_RETRY_INTERVAL_MS
            }
            if (snapshots.accountSnapshot()[source] != null && remoteJobs[source]?.isActive != true && (force || due)) {
                loadRemote(source)
            }
        }
    }

    fun updateLibrary(page: LibraryPage?, owner: String?) {
        if (owner == null || owner != youtubeAccount.value) return
        library = page
        libraryOwner = owner
        if (visible) rebuild() else localDirty = true
    }

    fun knownSong(id: String): Song? = knownSongs[id] ?: state.profile.tracks.firstOrNull { it.song.videoId == id }?.song

    suspend fun resolve(item: ShelfItem): Result<Song> {
        val epoch = connectionEpoch
        return try {
            val song = item.song ?: throw IOException("No metadata for this card")
            val playable = if (item.requiresMatching) {
                val track = BasicExternalPlaylistTrack(song.title, song.artist, song.albumName,
                    song.durationMillis(), song.thumbnailUrl)
                ExternalPlaylistImporter.resolve(listOf(track)) { _, _ -> }.first.singleOrNull()
                    ?: throw IOException("No playable catalogue match")
            } else {
                if (!isAvailable(song)) throw IOException("This source is no longer available")
                if (song.localUri != null && LocalMediaRepository.present(context, listOf(song)).isEmpty()) {
                    throw IOException("The local file is no longer available")
                }
                song
            }
            currentCoroutineContext().ensureActive()
            if (epoch != connectionEpoch) throw IOException("The profile changed during matching")
            Result.success(playable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IOException(context.getString(R.string.no_tracks_here)))
        }
    }

    private fun loadRemote(source: HomeSource) {
        remoteJobs.remove(source)?.cancel()
        val request = snapshots.begin(source) ?: return
        lastRemoteAttemptMs[source] = System.currentTimeMillis()
        loading += source
        unavailable -= source
        rebuild()
        val yandexToken = connections.yandex
        remoteJobs[source] = scope.launch {
            try {
                val snapshot = when (source) {
                    HomeSource.YOUTUBE -> providers.youtube(request.account)
                    HomeSource.YANDEX -> providers.yandex(request.account, yandexToken)
                    HomeSource.SPOTIFY -> providers.spotify(request.account)
                    HomeSource.SOUNDCLOUD -> providers.soundCloud(request.account)
                    else -> ProviderTasteSnapshot()
                }
                currentCoroutineContext().ensureActive()
                if (!snapshots.publish(request, snapshot)) return@launch
                lastRemoteSuccessMs[source] = System.currentTimeMillis()
                if (snapshot.incomplete) unavailable += source
            } catch (cancelled: CancellationException) {
                if (!visible) lastRemoteAttemptMs.remove(source)
                throw cancelled
            } catch (_: Exception) {
                if (snapshots.isCurrent(request)) unavailable += source
            } finally {
                if (snapshots.isCurrent(request)) {
                    loading -= source
                    if (visible) {
                        rebuild()
                        loadChangedLikeMetadata()
                    } else {
                        localDirty = true
                    }
                }
            }
        }
    }

    private fun loadLocal(force: Boolean = false) {
        if (!force && (!localDirty || localJob?.isActive == true)) return
        if (force) localJob?.cancel()
        val epoch = connectionEpoch
        val owner = youtubeAccount.value
        localJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { deviceSignals(owner) }
                currentCoroutineContext().ensureActive()
                if (epoch != connectionEpoch) return@launch
                localSignals = result.first
                localCollections = result.second
                localDirty = false
                unavailable -= HomeSource.LOCAL
                rebuild()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                localDirty = false
                unavailable += HomeSource.LOCAL
                rebuild()
            }
        }
    }

    private suspend fun deviceSignals(owner: String?): Pair<List<TasteSignal>, List<HomeShelf>> {
        val offline = OfflinePlaylists.playlists.value
        val migrated = LocalPlaylistStore.playlists.value
        val imports = TasteSignalStore.state.value.imports.filter { entry ->
            (entry.owner == null || entry.owner == owner) &&
                (!entry.browseId.startsWith("local:playlist:") || migrated.any { it.browseId == entry.browseId })
        }
        val files = if (LocalMediaRepository.hasStoragePermission(context)) LocalMediaRepository.getLocalMusic(context)
            else emptyList()
        val deviceRows = files + offline.flatMap { it.songs }
        val present = LocalMediaRepository.present(context, deviceRows).associateBy { it.videoId }
        val signals = mutableListOf<TasteSignal>()
        val collections = mutableListOf<HomeShelf>()
        files.forEach { song -> signals += TasteSignal("file:${song.videoId}", HomeSource.LOCAL,
            TasteSignalKind.LIBRARY, song) }
        offline.forEach { playlist ->
            val songs = LocalMediaRepository.present(context, playlist.songs)
            songs.forEachIndexed { index, song -> signals += TasteSignal("offline:${playlist.id}:${song.videoId}",
                HomeSource.LOCAL, if (playlist.isLiked) TasteSignalKind.LIKE else TasteSignalKind.PLAYLIST,
                song, order = index) }
            if (songs.isNotEmpty()) collections += HomeShelf(playlist.title, listOf(ShelfItem(playlist.title,
                "${songs.size}", songs.firstOrNull()?.thumbnailUrl, null, playlist.browseId,
                sources = setOf(HomeSource.LOCAL))))
        }
        val remembered = imports.mapTo(HashSet()) { it.browseId }
        imports.forEach { playlist ->
            playlist.songs.forEach { row -> signals += TasteSignal("import:${playlist.browseId}:${row.id}",
                playlist.source, TasteSignalKind.PLAYLIST, row.song(), atMs = playlist.atMs) }
            val title = migrated.firstOrNull { it.browseId == playlist.browseId }?.title ?: playlist.title
            collections += HomeShelf(playlist.source.label, listOf(ShelfItem(title,
                "${playlist.songs.size}", playlist.songs.firstOrNull()?.art, null, playlist.browseId,
                sources = setOf(playlist.source))))
        }
        migrated.filter { it.browseId !in remembered }.forEach { playlist ->
            playlist.songs.forEach { song -> signals += TasteSignal("import:${playlist.browseId}:${song.videoId}",
                playlist.source, TasteSignalKind.PLAYLIST, song) }
            collections += HomeShelf(playlist.title, listOf(ShelfItem(playlist.title,
                "${playlist.songs.size}", playlist.songs.firstOrNull()?.thumbnailUrl, null, playlist.browseId,
                sources = setOf(playlist.source))))
        }
        TrackRatingStore.ratings.value.values.forEach { rating ->
            val status = rating.likeStatus
            if (status == LikeStatus.INDIFFERENT) return@forEach
            val song = rating.song()
            val source = when {
                song.videoId.startsWith("soundcloud:") -> HomeSource.SOUNDCLOUD
                song.videoId.startsWith("spotify-track:") -> HomeSource.SPOTIFY
                song.videoId.startsWith("vk-audio:") -> HomeSource.VK
                song.localUri != null -> HomeSource.LOCAL
                else -> HomeSource.YOUTUBE
            }
            signals += TasteSignal(
                id = "device-rating:${song.videoId}",
                source = source,
                kind = if (status == LikeStatus.LIKE) TasteSignalKind.LIKE else TasteSignalKind.DISLIKE,
                song = song,
                atMs = rating.changedAtMs,
                account = owner,
            )
        }
        val buckets = ListeningStats.exportAll()
        val metadata = buckets.flatMap { it.tracks }.groupBy { it.id }.mapValues { (_, rows) -> rows.maxBy { it.last } }
        buckets.forEach { bucket ->
            bucket.tracks.forEach trackLoop@ { entry ->
                currentCoroutineContext().ensureActive()
                val id = entry.id
                val addon = SourceRegistry.parseTrackKey(id)
                if (addon != null && !addonEnabled(addon.first)) return@trackLoop
                val last = metadata.getValue(id)
                val song = present[id] ?: Song(id, last.title, last.artist, last.art, artistId = last.artistId,
                    albumId = last.albumId, albumName = last.album)
                val source = when {
                    addon != null -> HomeSource.ADDONS
                    song.localUri != null || id.startsWith("local:") -> HomeSource.LOCAL
                    else -> HomeSource.YOUTUBE
                }
                // One new listen must not make years of old plays appear recent.
                signals += TasteSignal("listens:${bucket.month}:$id", source, TasteSignalKind.LISTEN, song,
                    atMs = entry.last.takeIf { it > 0L }, count = entry.plays,
                    playedMs = entry.ms, requiresMatching = !isAvailable(song))
            }
        }
        return signals to collections
    }

    private fun rebuild() {
        val nextRevision = ++revision
        renderJob?.cancel()
        val accounts = snapshots.accountSnapshot()
        val snapshot = snapshots.snapshot()
        val changes = LikeState.userChanges.value
        val ratings = TasteSignalStore.state.value.ratings.filter { it.account == youtubeAccount.value }.associateBy { it.id }
        snapshot[HomeSource.YOUTUBE]?.signals.orEmpty().mapNotNull { it.song }
            .forEach { knownSongs[it.videoId] = it }
        localSignals.asSequence().filter { it.source == HomeSource.YOUTUBE }.mapNotNull { it.song }
            .forEach { knownSongs[it.videoId] = it }
        val explicit = if (youtubeAccount.value == null) emptyList() else changes.mapNotNull { (id, status) ->
            val song = knownSongs[id] ?: return@mapNotNull null
            if (status == LikeStatus.INDIFFERENT) return@mapNotNull null
            TasteSignal("rating:$id", HomeSource.YOUTUBE,
                if (status == LikeStatus.LIKE) TasteSignalKind.LIKE else TasteSignalKind.DISLIKE,
                song, atMs = ratings[id]?.atMs, account = youtubeAccount.value)
        }
        val librarySignals = if (libraryOwner == youtubeAccount.value && libraryOwner != null) {
            library?.shelves.orEmpty().flatMap { shelf -> shelf.items }.filter { it.browseId?.startsWith("UC") == true }
                .map { item -> TasteSignal("artist:${item.browseId}", HomeSource.YOUTUBE, TasteSignalKind.ARTIST,
                    artist = ArtistRef(item.title, item.browseId), account = libraryOwner) }
        } else emptyList()
        val localSignalRows = localSignals.toList()
        val pending = loading.toSet()
        val failed = unavailable.toSet()
        val recommendationRows = recommendations.toMap()
        val activeRecommendationKey = recommendationKey
        val collectionRows = localCollections.toList()
        val libraryCards = if (libraryOwner == youtubeAccount.value && libraryOwner != null) {
            library?.shelves.orEmpty().flatMap { it.items }
                .filter { it.browseId?.let { id -> !id.startsWith("UC") } == true }
                .map { it.copy(sources = setOf(HomeSource.YOUTUBE)) }
        } else {
            emptyList()
        }
        renderJob = scope.launch {
            val (profile, shelves, key) = withContext(Dispatchers.Default) {
                val remote = snapshot.values.flatMap { it.signals }.filter { signal ->
                    signal.source != HomeSource.YOUTUBE || signal.kind != TasteSignalKind.LIKE ||
                        signal.song?.videoId !in changes
                }
                val signals = remote + localSignalRows + explicit + librarySignals
                val merged = TasteProfiles.merge(signals, accounts)
                val digest = tasteDigest(accounts.toString() +
                    merged.artists.take(3).map { it.artist.name }.joinToString("|") +
                    merged.recentLikes.take(24).map { it.song.videoId }.joinToString("|"))
                Triple(
                    merged,
                    sections(
                        merged,
                        snapshot,
                        recommendationRows.takeIf { digest == activeRecommendationKey }.orEmpty(),
                        collectionRows,
                        libraryCards,
                    ),
                    digest,
                )
            }
            if (nextRevision != revision || accounts != snapshots.accountSnapshot()) return@launch
            if (key != recommendationKey) {
                recommendationJob?.cancel()
                recommendationKey = key
                recommendations = emptyMap()
            }
            emit(PersonalizedHomeState(profile, shelves, accounts, pending, failed, nextRevision))
            if (visible && pending.none { it in REMOTE_SOURCES } && profile.artists.isNotEmpty() &&
                recommendationJob?.isActive != true && recommendations.isEmpty()
            ) {
                loadRecommendations(profile, key)
            }
        }
    }

    private fun loadRecommendations(profile: TasteProfile, key: String) {
        if (!visible) return
        val accounts = snapshots.accountSnapshot()
        val yandexToken = connections.yandex
        val sources = listOf(HomeSource.YOUTUBE, HomeSource.SOUNDCLOUD) +
            listOf(HomeSource.YANDEX, HomeSource.SPOTIFY, HomeSource.VK, HomeSource.ADDONS)
                .filter { it in accounts && it !in unavailable }
        val artists = profile.artists.take(3).map { it.artist.name }
        recommendationJob = scope.launch {
            val gate = Semaphore(2)
            val rows = sources.map { source -> async {
                gate.withPermit {
                    val result = try {
                        withTimeoutOrNull(20_000L) {
                            providers.recommendations(source, artists.first(), yandexToken)
                        }.orEmpty()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    source to result
                }
            } }.awaitAll().toMap().toMutableMap()
            // Other favourite artists get actual YouTube catalogue candidates as well.
            for (artist in artists.drop(1)) {
                currentCoroutineContext().ensureActive()
                val result = try {
                    withTimeoutOrNull(20_000L) { providers.recommendations(HomeSource.YOUTUBE, artist, "") }.orEmpty()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                rows[HomeSource.YOUTUBE] = rows[HomeSource.YOUTUBE].orEmpty() + result
            }
            if (!visible || key != recommendationKey || accounts != snapshots.accountSnapshot()) return@launch
            recommendations = rows
            rebuild()
        }
    }

    private fun sections(
        profile: TasteProfile,
        snapshot: Map<HomeSource, ProviderTasteSnapshot>,
        recommendationRows: Map<HomeSource, List<TasteTrack>>,
        localCollectionRows: List<HomeShelf>,
        libraryCards: List<ShelfItem>,
    ): List<HomeShelf> {
        val sections = mutableListOf<HomeShelf>()
        val candidates = recommendationRows.values.flatten()
        val ranked = TasteProfiles.rankCandidates(profile, candidates + profile.tracks.take(PROFILE_CANDIDATE_LIMIT))
        if (!profile.isEmpty) {
            val forYou = ranked.take(24)
            if (forYou.isNotEmpty()) sections += HomeShelf(context.getString(R.string.personalized_for_you), forYou.map(::card))
            if (profile.recentLikes.isNotEmpty()) sections += HomeShelf(context.getString(R.string.personalized_recent_likes),
                profile.recentLikes.take(24).map(::card))
            profile.artists.take(3).forEach { artist ->
                val songs = ranked.filter { TrackMatcher.sharesArtist(artist.artist.name, it.song.artist) }.take(16)
                if (songs.isNotEmpty()) sections += HomeShelf(context.getString(R.string.personalized_artist_format,
                    artist.artist.name), songs.map(::card))
            }
        }
        HomeSource.entries.forEach { source ->
            val songs = ranked.filter { source in it.sources }.take(16)
            if (songs.isNotEmpty()) sections += HomeShelf(context.getString(R.string.personalized_source_format, label(source)),
                songs.map(::card))
        }
        val collections = (localCollectionRows.flatMap { it.items } +
            snapshot.values.flatMap { it.collections }.flatMap { it.items } + libraryCards)
            .distinctBy { it.browseId ?: "${it.title}\u0000${it.subtitle}" }
        if (collections.isNotEmpty()) sections += HomeShelf(context.getString(R.string.playlists), collections)
        return sections
    }

    private fun card(track: TasteTrack) = ShelfItem(track.song.title, track.song.artist, track.song.thumbnailUrl,
        track.song.videoId, null, track.song.isVideo, track.song, track.sources, track.requiresMatching)

    private fun loadChangedLikeMetadata() {
        if (!visible) return
        val account = youtubeAccount.value ?: return
        val pending = LikeState.userChanges.value.filter { (id, status) ->
            status != LikeStatus.INDIFFERENT && id !in knownSongs && id.matches(Regex("[A-Za-z0-9_-]{11}"))
        }.keys
        if (pending.isEmpty() || metadataJob?.isActive == true) return
        val epoch = connectionEpoch
        metadataJob = scope.launch {
            for (id in pending) {
                currentCoroutineContext().ensureActive()
                val song = YtMusicRepository.trackLinks(id).getOrNull() ?: continue
                if (epoch != connectionEpoch || account != youtubeAccount.value) return@launch
                knownSongs[id] = song
                rebuild()
            }
        }
    }

    private fun emit(next: PersonalizedHomeState) {
        state = next
        onChanged(next)
    }

    private fun label(source: HomeSource): String = when (source) {
        HomeSource.YANDEX -> context.getString(R.string.yandex_music)
        HomeSource.LOCAL -> context.getString(R.string.local_music)
        HomeSource.ADDONS -> context.getString(R.string.search_source_addons)
        HomeSource.VK -> "VK Music"
        else -> source.label
    }

    private fun accountKeys(next: Connections): Map<HomeSource, String> = buildMap {
        next.youtube?.let { put(HomeSource.YOUTUBE, it) }
        next.spotify.takeIf(String::isNotBlank)?.let { put(HomeSource.SPOTIFY, tasteDigest(it)) }
        next.yandex.takeIf(String::isNotBlank)?.let { put(HomeSource.YANDEX, tasteDigest(it)) }
        next.soundCloud.takeIf(String::isNotBlank)?.let { put(HomeSource.SOUNDCLOUD, tasteDigest(it)) }
        next.vk.takeIf(String::isNotBlank)?.let { put(HomeSource.VK, tasteDigest(it)) }
        val addons = next.addons.filter { it.enabled && it.kind == SourceKind.ADDON }
        if (addons.isNotEmpty()) put(HomeSource.ADDONS, tasteDigest(addons.joinToString { it.id + it.baseUrl }))
        put(HomeSource.LOCAL, "device")
    }

    private fun addonEnabled(id: String): Boolean = SourceRegistry.configs.value.any {
        it.id == id && it.kind == SourceKind.ADDON && it.enabled && !it.awaitsLosslessOutput
    }

    private fun isAvailable(song: Song): Boolean = when {
        song.localUri != null -> true
        song.playbackSource == "SoundCloud" && !song.playbackSourceId.isNullOrBlank() -> true
        SourceRegistry.parseTrackKey(song.videoId) != null -> addonEnabled(SourceRegistry.parseTrackKey(song.videoId)!!.first)
        else -> song.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))
    }

    private companion object {
        val REMOTE_SOURCES = listOf(
            HomeSource.YOUTUBE,
            HomeSource.YANDEX,
            HomeSource.SPOTIFY,
            HomeSource.SOUNDCLOUD,
        )
        const val REMOTE_ACTIVATION_DELAY_MS = 350L
        const val REMOTE_RETRY_INTERVAL_MS = 10 * 60_000L
        const val REMOTE_REFRESH_INTERVAL_MS = 15 * 60_000L
        const val PROFILE_CANDIDATE_LIMIT = 240
    }
}
