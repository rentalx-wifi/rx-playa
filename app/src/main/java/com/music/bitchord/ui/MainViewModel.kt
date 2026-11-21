package com.music.bitchord.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.R
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.auth.SignedIn
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.auth.profileId
import com.music.bitchord.auth.sessionId
import com.music.bitchord.auth.adjacentProfile
import com.music.bitchord.data.AppUpdateChecker
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.TrackRatingStore
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.personalization.PersonalizedHome
import com.music.bitchord.data.personalization.PersonalizedHomeState
import com.music.bitchord.data.personalization.TasteProfiles
import com.music.bitchord.data.personalization.TasteSignalStore
import com.music.bitchord.data.personalization.tasteDigest
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.AppleArtistPhotos
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import android.util.Log
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import com.music.bitchord.data.spotify.SPOTIFY_PAGE_PREFIX
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyTrack
import com.music.bitchord.data.spotify.isSpotifySourceTrack
import com.music.bitchord.data.spotify.toSourceSong
import com.music.bitchord.data.importing.BasicExternalPlaylistTrack
import com.music.bitchord.data.importing.ExternalPlaylistImporter
import com.music.bitchord.data.importing.writeImportedPlaylist
import com.music.bitchord.data.soundcloud.SoundCloudPlaylistImporter
import com.music.bitchord.data.soundcloud.toSearchSong
import com.music.bitchord.data.soundcloud.SoundCloudAccountApi
import com.music.bitchord.data.vk.VkMusicApi
import com.music.bitchord.data.vk.isVkSourceTrack
import com.music.bitchord.data.yandex.YandexMusicApi
import com.music.bitchord.data.lyrics.EmbeddedLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.lyricsRank
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.navigation.AppNavigator
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.auth.CapturedSession
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.AccountChannel
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.withoutRepeatsOf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.SongMenu
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.WatchTarget
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.download.Downloads
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.music.bitchord.data.sources.AddonSource
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.BoundedContextRequests
import com.music.bitchord.playback.DirectSourceGate
import com.music.bitchord.playback.contextEntryKey
import com.music.bitchord.playback.StreamChoice
import com.music.bitchord.playback.TrackText
import com.music.bitchord.ui.screens.CACHE_FOLDER_BROWSE_ID
import com.music.bitchord.data.LocalGroup
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.matchesSearch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Every catalogue the Search tab can query, separately or as one merged music search. */
enum class SearchSource { ALL, YOUTUBE, YANDEX, SPOTIFY, SOUNDCLOUD, VK, LIBRARY, ADDONS }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val authStore = AuthStore(app)

    // Process-wide, so the playback service offers Like on the same tracks.
    private val _signedIn = SignedIn.state(app)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _personalizedHome = MutableStateFlow(PersonalizedHomeState())
    val personalizedHomeState = _personalizedHome.asStateFlow()
    private lateinit var personalizedHome: PersonalizedHome
    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = combine(_home, _personalizedHome) { base, personalized ->
        val dislikedIds = personalized.profile.dislikes.mapTo(HashSet()) { it.videoId }
        val native = (base as? UiState.Success)?.data.orEmpty().map { shelf ->
            shelf.copy(items = shelf.items.filter { item ->
                item.videoId !in dislikedIds
            }.map { item -> if (item.sources.isEmpty()) item.copy(sources = setOf(HomeSource.YOUTUBE)) else item })
        }.filter { it.items.isNotEmpty() }
        val titles = personalized.shelves.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
        val supplementary = native.filterNot { it.title.lowercase(Locale.ROOT) in titles }
            .withoutRepeatsOf(personalized.shelves)
        if (personalized.shelves.isNotEmpty()) UiState.Success(personalized.shelves + supplementary)
        else if (base is UiState.Success) UiState.Success(native) else base
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * Token for the next page of Home shelves; null once there's nothing
     * more. Declared here rather than by [loadMoreHome] because [init] calls
     * [loadHome] synchronously up to its first suspension point — a property
     * declared after [init] would still be null when that runs.
     */
    private var homeContinuation: String? = null

    /** Titles already on screen, so a later page can't repeat a shelf. */
    private val homeSeenTitles = mutableSetOf<String>()

    private val _homeLoadingMore = MutableStateFlow(false)

    /**
     * Requests still filling out the first Play page. The core feed and each
     * supplement browse run in parallel and publish as they land, so the list
     * goes Success while several shelves are still on the wire — counted here
     * so the feed can say more is coming instead of just ending mid-load.
     */
    private val _homePendingShelves = MutableStateFlow(0)

    /** True whenever shelves are still due at the end of the feed, from either source. */
    val homeLoadingMore: StateFlow<Boolean> =
        combine(_homeLoadingMore, _homePendingShelves) { paging, pending -> paging || pending > 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _homeRecentlyPlayedLoading = MutableStateFlow(false)
    val homeRecentlyPlayedLoading: StateFlow<Boolean> = _homeRecentlyPlayedLoading.asStateFlow()
    private val homeLoadGeneration = AtomicLong(0L)

    /** Whether [home] on screen was built with no connection — see [loadOfflineHome]. */
    private var homeBuiltOffline = false

    /**
     * Home's "Top Picks for You" row: null until built, empty if there was
     * nothing to build it from. Declared ahead of [init] for the same reason
     * as [homeContinuation] — [loadHome] reaches it synchronously.
     */
    private val _topPicks = MutableStateFlow<List<TopPick>?>(null)
    val topPicks: StateFlow<List<TopPick>?> = _topPicks.asStateFlow()
    private val _topPicksLoading = MutableStateFlow(false)
    val topPicksLoading: StateFlow<Boolean> = _topPicksLoading.asStateFlow()
    private var topPicksJob: Job? = null

    private val _explore = MutableStateFlow<UiState<List<MoodGenreSection>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<MoodGenreSection>>> = _explore.asStateFlow()

    private val _selectedMoodGenre = MutableStateFlow<MoodGenre?>(null)
    val selectedMoodGenre: StateFlow<MoodGenre?> = _selectedMoodGenre.asStateFlow()

    private val _moodGenreShelves = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val moodGenreShelves: StateFlow<UiState<List<HomeShelf>>> = _moodGenreShelves.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    /** The mixed YouTube Music result page is the fast, useful default. */
    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _searchLoadingMore = MutableStateFlow(false)
    val searchLoadingMore: StateFlow<Boolean> = _searchLoadingMore.asStateFlow()

    /** Increments once per first-page request so the UI can reset its list. */
    private val _searchScrollReset = MutableStateFlow(0)
    val searchScrollReset: StateFlow<Int> = _searchScrollReset.asStateFlow()

    /** True while a committed search is in flight — gates suggestion/media callbacks. */
    private var searchSubmitted = false

    /**
     * What the search page offers while a query is being typed, led by the
     * query itself.
     *
     * Non-empty *is* the signal that the field is mid-edit, so the screen
     * needs no second flag: these rows are shown in place of the results
     * whenever there are any, and cleared the moment a search is actually run
     * — see [submitSearch], [searchFor].
     *
     * Element 0 is always the raw text as typed. It's put there by the
     * keystroke itself rather than taken from the response, so the row the
     * thumb is already heading for is correct before the network answers, and
     * stays correct if it never does — YouTube's list never contains the
     * half-typed text, only completions of it.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /**
     * Live media results shown alongside typeahead suggestions. Populated by a
     * lightweight search that runs in parallel with text completions; the UI
     * renders these as playable track cards and browse items below the text
     * suggestion rows. Cleared when the user commits to a search or empties
     * the field.
     */
    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeaheadResults: StateFlow<List<SearchResult>> = _typeaheadResults.asStateFlow()

    /** Where the Search tab looks — see [setSearchSource]. */
    private val _searchSource = MutableStateFlow(SearchSource.ALL)
    val searchSource: StateFlow<SearchSource> = _searchSource.asStateFlow()

    /**
     * The Addons source's answer for the submitted query: every enabled addon
     * searched at once and their rows interleaved. Null until something has
     * been submitted. These songs carry source track keys, so a tap plays the
     * addon's own stream with no YouTube lookup in between.
     */
    private val _addonResults = MutableStateFlow<UiState<List<Song>>?>(null)
    val addonResults: StateFlow<UiState<List<Song>>?> = _addonResults.asStateFlow()
    private var addonSearchJob: Job? = null
    private var addonSearchGeneration = 0L

    private val _platformResults = MutableStateFlow<UiState<List<Song>>?>(null)
    val platformResults: StateFlow<UiState<List<Song>>?> = _platformResults.asStateFlow()
    private val _platformLoadingMore = MutableStateFlow(false)
    val platformLoadingMore: StateFlow<Boolean> = _platformLoadingMore.asStateFlow()
    private var platformSearchJob: Job? = null
    private var platformLoadMoreJob: Job? = null
    private var platformSearchGeneration = 0L
    private var soundCloudSearchQuery: String? = null
    private var soundCloudNextOffset: Int? = null
    private var soundCloudNextHref: String? = null

    /** The words typed into the Addons search before, newest first. */
    val addonSearchHistory: StateFlow<List<String>> = SearchHistory.addonRecent

    fun removeAddonSearch(query: String) = SearchHistory.removeAddonQuery(query)

    fun clearAddonSearchHistory() = SearchHistory.clearAddonQueries()

    /** Every track in the Local Music folder, read when the Library source is picked. */
    private val _librarySongs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)

    /**
     * The Library source's answer for the current query: the Local Music
     * folder narrowed by the same match its own filter box uses, so the two
     * places never disagree about what is on the device. Null while the field
     * is empty — there is nothing asked yet.
     */
    val libraryResults: StateFlow<UiState<List<Song>>?> = combine(_librarySongs, _query) { state, query ->
        val term = query.trim()
        when {
            term.isEmpty() -> null
            state is UiState.Success -> UiState.Success(state.data.filter { it.matchesSearch(term) })
            else -> state
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // The search pipeline's own state. Declared here, above [init], because
    // that is where the collector is started from and a property declared
    // below it would still be null when it runs. See [startSearchPipeline].

    /**
     * Buffered so an emission is never lost to a collector that happens to be
     * mid-search, and [BufferOverflow.DROP_OLDEST] because when two arrive
     * together the later one is the one meant.
     */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same arrangement as [searchRequests], for the typeahead — where the
     * drop policy earns its keep rather than just being safe: this one really
     * does take a keystroke each, and a fast typist's backlog should collapse
     * to the prefix they ended on instead of being worked through a letter at
     * a time.
     */
    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val newestRequestId = AtomicLong(0L)

    /**
     * Results of recent searches, so a query searched before is answered
     * without asking again. That covers the two ways a query is repeated most:
     * a filter tab, which re-runs the same text against a different tab and
     * then usually goes back, and a term tapped out of the recent searches.
     *
     * Its other half is [prefixMatch], which is what the typeahead makes worth
     * keeping: picking "coldplay yellow" off a list is normally preceded by
     * having searched "coldplay", and those results are close enough to leave
     * up for the moment the narrower one takes rather than blanking the page
     * to a spinner.
     */
    private data class SearchCacheEntry(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    private data class SearchSession(
        val key: String,
        val requestId: Long,
        val filter: SearchFilter,
        val continuation: String?,
    )

    private val searchCache = LruCache<String, SearchCacheEntry>(SEARCH_CACHE_ENTRIES)
    private var searchSession: SearchSession? = null

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null
    private val manualLyricsJobs = mutableMapOf<LyricsSource, Job>()

    private val _lyricsProviderStates = MutableStateFlow(
        LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED },
    )
    val lyricsProviderStates: StateFlow<Map<LyricsSource, LyricsProviderState>> =
        _lyricsProviderStates.asStateFlow()

    /** Completed hits are retained for the playing track so choosing one is instant. */
    private val lyricsProviderResults = ConcurrentHashMap<LyricsSource, LyricsRepository.Result>()

    private data class LyricsRequest(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val album: String?,
    )

    private var currentLyricsRequest: LyricsRequest? = null
    private var lyricsGeneration = 0L
    private var selectedLyricsSource: LyricsSource? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: List<Any>? = null

    /**
     * How good the lines currently on screen are — see [lyricsRank].
     *
     * A source that answers in two hundred milliseconds used to sit behind one
     * that had not answered at all, and the panel stayed empty until the race
     * finished. Now the first answer is shown as it lands, and this is what
     * keeps a late, worse one from replacing it: the panel only ever improves.
     */
    private var lyricsShownRank = -1

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a downloaded track had its lyrics
     * fetched once already and written into its own file (see `LyricsTag`), so
     * asking the same servers again is a round trip to arrive at a string that
     * is on disk — and one that fails outright with the connection off, which
     * is what made a downloaded song show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = lyricsLookupKey(
            videoId, sources, AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
        )
        if (lyricsFor == key) return
        // The duration lands a beat after the track, and a database match needs
        // it. Turned away here rather than inside the job below: claiming the
        // lookup first and giving it up asynchronously means the very re-trigger
        // that carries the duration can arrive while the claim still stands, be
        // dropped as a duplicate, and leave the track marked as being looked up
        // by nobody — which is what left a paused track loading for ever, since
        // pausing is when the duration is most likely to arrive a frame late.
        if (localUri == null && durationMs <= 0L) return
        lyricsFor = key
        lyricsGeneration += 1
        val generation = lyricsGeneration
        currentLyricsRequest = LyricsRequest(videoId, title, artist, durationMs, album)
        selectedLyricsSource = null
        manualLyricsJobs.values.forEach(Job::cancel)
        manualLyricsJobs.clear()
        lyricsProviderResults.clear()
        _lyricsProviderStates.value =
            LyricsSource.entries.associateWith { LyricsProviderState.NOT_FETCHED }
        _lyrics.value = null
        lyricsShownRank = -1
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    _lyrics.value = embedded
                    // No source to name: what the file records is the lyrics,
                    // not which of the eight services they came from months ago.
                    _lyricsSource.value = null
                    _lyricsChecked.value = true
                    return@launch
                }
            }
            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
                onSourceStarted = { source -> providerStarted(generation, source) },
                onSourceResult = { source, result ->
                    providerFinished(generation, source, result)
                },
                onSourceCancelled = { source -> providerCancelled(generation, source) },
            )
            val selected = selectedLyricsSource?.let(lyricsProviderResults::get) ?: found
            if (selected != null) {
                // Never replaces better lines with worse ones: a word-timed
                // answer that arrived first stays, whatever the priority race
                // settled on afterwards.
                val rank = lyricsRank(selected.lines)
                if (rank >= lyricsShownRank) {
                    lyricsShownRank = rank
                    showLyricsResult(selected)
                }
            }
            _lyricsChecked.value = true
        }
    }

    /**
     * Selects a provider from the player drawer. Completed hits are applied
     * from memory; misses are inert; only an untouched provider goes online.
     */
    fun selectLyricsProvider(source: LyricsSource) {
        val request = currentLyricsRequest ?: return
        when (_lyricsProviderStates.value[source]) {
            LyricsProviderState.FOUND -> {
                selectedLyricsSource = source
                lyricsProviderResults[source]?.let(::showLyricsResult)
            }
            LyricsProviderState.FETCHING -> {
                // Apply it as soon as the already-running automatic attempt completes.
                selectedLyricsSource = source
            }
            LyricsProviderState.NOT_FETCHED, null -> {
                selectedLyricsSource = source
                fetchLyricsProvider(request, lyricsGeneration, source)
            }
            LyricsProviderState.NOT_FOUND -> Unit
        }
    }

    private fun fetchLyricsProvider(
        request: LyricsRequest,
        generation: Long,
        source: LyricsSource,
    ) {
        if (manualLyricsJobs[source]?.isActive == true) return
        manualLyricsJobs[source] = viewModelScope.launch {
            LyricsRepository.lyrics(
                videoId = request.videoId,
                title = request.title,
                artist = request.artist,
                durationMs = request.durationMs,
                album = request.album,
                sources = setOf(source),
                order = listOf(source),
                prioritizeSyllableSync = false,
                onSourceStarted = { provider -> providerStarted(generation, provider) },
                onSourceResult = { provider, result ->
                    providerFinished(generation, provider, result)
                },
                onSourceCancelled = { provider -> providerCancelled(generation, provider) },
            )
        }
    }

    private fun providerStarted(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FETCHING) }
    }

    private fun providerFinished(
        generation: Long,
        source: LyricsSource,
        result: LyricsRepository.Result?,
    ) {
        if (generation != lyricsGeneration) return
        if (result == null) {
            _lyricsProviderStates.update { it + (source to LyricsProviderState.NOT_FOUND) }
            return
        }
        lyricsProviderResults[source] = result
        _lyricsProviderStates.update { it + (source to LyricsProviderState.FOUND) }
        when {
            // A tap on a source is the last word: it wins over any automatic
            // replacement, and nothing upgrades away from it.
            selectedLyricsSource == source -> showLyricsResult(result)
            selectedLyricsSource == null -> showLyricsIfBetter(result)
        }
    }

    /** Puts a source's answer on screen when it beats what is already there. */
    private fun showLyricsIfBetter(result: LyricsRepository.Result) {
        val rank = lyricsRank(result.lines)
        if (rank <= lyricsShownRank) return
        lyricsShownRank = rank
        showLyricsResult(result)
    }

    private fun providerCancelled(generation: Long, source: LyricsSource) {
        if (generation != lyricsGeneration) return
        _lyricsProviderStates.update { states ->
            if (states[source] == LyricsProviderState.FETCHING) {
                states + (source to LyricsProviderState.NOT_FETCHED)
            } else {
                states
            }
        }
        // A tap may have selected a provider while the priority race was still
        // using it. If that race then cancels the loser, honour the tap with a
        // dedicated request instead of leaving the row stuck at "Fetching".
        if (selectedLyricsSource == source) {
            currentLyricsRequest?.let { fetchLyricsProvider(it, generation, source) }
        }
    }

    private fun showLyricsResult(result: LyricsRepository.Result) {
        _lyrics.value = result.lines
        _lyricsSource.value = result.source
        _lyricsChecked.value = true
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    /**
     * The channels this login can act as. Empty until the picker asks for
     * them — it is one more request per sign-in and nothing else on the
     * settings page needs the answer.
     */
    private val _channels = MutableStateFlow<List<AccountChannel>>(emptyList())
    val channels: StateFlow<List<AccountChannel>> = _channels.asStateFlow()

    private val _channelsLoading = MutableStateFlow(false)
    val channelsLoading: StateFlow<Boolean> = _channelsLoading.asStateFlow()

    /**
     * The chosen channel's [AccountChannel.key], or null while the app is
     * acting as whichever channel YouTube Music serves by default.
     */
    private val _selectedChannelKey = MutableStateFlow(
        authStore.activeSession?.activeProfileId ?: authStore.channelPageId ?: authStore.channelDataSyncId,
    )
    val selectedChannelKey: StateFlow<String?> = _selectedChannelKey.asStateFlow()

    private val _selectedChannelName = MutableStateFlow(
        authStore.activeSession?.profiles?.firstOrNull { it.profileId == authStore.activeProfileId }?.name
            ?: authStore.channelName,
    )
    val selectedChannelName: StateFlow<String?> = _selectedChannelName.asStateFlow()

    /** Source of truth for Google sessions and their YouTube identities. */
    private val _googleAccounts = MutableStateFlow(authStore.sessions)
    val googleAccounts: StateFlow<List<GoogleAccountSession>> = _googleAccounts.asStateFlow()
    private val _activeAccountId = MutableStateFlow(authStore.activeSession?.accountId)
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()
    private val _activeProfileId = MutableStateFlow(authStore.activeProfileId)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /** In-memory cache is partitioned by account and profile; it is never shared. */
    private data class ListenerSnapshot(
        val account: Account?, val library: UiState<LibraryPage>,
        val history: UiState<List<Song>>, val playlists: List<UserPlaylist>,
        val owned: Map<String, Boolean>,
    )
    private val listenerCache = mutableMapOf<String, ListenerSnapshot>()

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()

    /**
     * The app's back stack: the routes pushed over the tabs, interleaved with
     * [detailStack]. Held here so it survives a configuration change along
     * with the release pages it is ordered against.
     */
    val navigator = AppNavigator(
        detailDepth = { _detailStack.value.size },
        popDetail = ::closeDetail,
        clearDetails = ::clearDetail,
    )

    /** UI-side continuation fetches that can be handed to the playback service. */
    private val fillInJobs = mutableMapOf<String, Job>()

    private val _releaseLibrary = MutableStateFlow<Map<String, LibraryState>>(emptyMap())

    /**
     * Library state of releases that are only a card on some page — the artist
     * page's top release — keyed by browse id. They have no page of their own on
     * the stack to carry it, so it is read off the release and kept here.
     */
    val releaseLibrary: StateFlow<Map<String, LibraryState>> = _releaseLibrary.asStateFlow()

    /** Always null in this fork; retained only so the shared TV shell remains source-compatible. */
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    /** Reads whether the release [browseId] is saved, once; a no-op if already known. */
    fun loadReleaseLibrary(browseId: String) {
        if (browseId in _releaseLibrary.value) return
        viewModelScope.launch {
            YtMusicRepository.releaseLibraryState(browseId).getOrNull()?.let { state ->
                _releaseLibrary.value += (browseId to state)
            }
        }
    }

    /** Saves the release [browseId] to the library or takes it out, as [toggleLibrary] does for a page. */
    fun toggleReleaseLibrary(browseId: String) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            val current = _releaseLibrary.value[browseId]
                ?: YtMusicRepository.releaseLibraryState(browseId).getOrNull()
                ?: return@launch
            val target = !current.saved
            _releaseLibrary.value += (browseId to current.copy(saved = target))
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                libraryStale = true
            } else {
                _releaseLibrary.value += (browseId to current)
            }
        }
    }


    // ---- Ratings, library and playlists -------------------------------------

    /**
     * Ratings this session has set, which win over whatever the library feed
     * last said.
     *
     * Kept apart from the library rather than folded into it because the two
     * answer different questions: Liked Music is what YouTube knew when the
     * page was fetched, and this is what the user has done since. Layering
     * them ([likeStatuses]) means a tap shows immediately without the library
     * having to be re-fetched, and a later refresh can't undo it.
     */
    /** Every rating known for this account: the library's, then this session's. */
    val likeStatuses: StateFlow<Map<String, LikeStatus>> =
        combine(_library, LikeState.overrides) { library, overrides ->
            val liked = (library as? UiState.Success)?.data?.likedSongs
                ?.associate { it.videoId to LikeStatus.LIKE }
                .orEmpty()
            liked + overrides
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** One Favorites tab: durable local/provider likes plus the account library. */
    val favoriteSongs: StateFlow<List<Song>> = combine(
        _library,
        TrackRatingStore.ratings,
        OfflinePlaylists.playlists,
        LikeState.overrides,
    ) { library, stored, offline, overrides ->
        val durable = stored.values.asSequence()
            .filter { it.likeStatus == LikeStatus.LIKE }
            .sortedByDescending { it.changedAtMs }
            .map { it.song() }
            .toList()
        val onDevice = offline.firstOrNull { it.isLiked }?.songs.orEmpty()
        val account = (library as? UiState.Success)?.data?.likedSongs.orEmpty().filter { song ->
            val durableStatus = stored[song.videoId]?.likeStatus
            durableStatus == null || durableStatus == LikeStatus.LIKE
        }
        (durable + onDevice + account).distinctBy { it.videoId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun likeStatusOf(videoId: String): LikeStatus =
        likeStatuses.value[videoId] ?: LikeStatus.INDIFFERENT

    /**
     * Sets (or clears) the thumbs rating on [videoId].
     *
     * Written to the screen first and rolled back if YouTube refuses. A rating
     * is a one-tap, low-stakes action taken while a song is playing; waiting
     * on a round trip before the heart fills reads as the tap not having
     * registered, and people tap again.
     */
    fun setLike(videoId: String, status: LikeStatus) {
        if (!requireSignIn()) return
        val previous = likeStatusOf(videoId)
        if (previous == status) return
        val identity = tasteListenerKey()
        LikeState.set(videoId, status)
        viewModelScope.launch {
            val result = YtMusicRepository.rate(videoId, status)
            if (identity != tasteListenerKey()) return@launch
            result.fold(
                onSuccess = {
                    TasteSignalStore.rememberRating(identity, videoId, status)
                    // Liked Music is now out of date either way.
                    libraryStale = true
                    if (status != LikeStatus.LIKE) dropFromLikedLists(videoId)
                    // Clearing the heart means forgetting the song, not
                    // demoting it — see [forgetFromLibrary].
                    val unliked = previous == LikeStatus.LIKE &&
                        status == LikeStatus.INDIFFERENT
                    if (unliked) forgetFromLibrary(videoId)
                },
                onFailure = {
                    LikeState.set(videoId, previous)
                },
            )
        }
    }

    /**
     * Takes an un-liked track out of the library as well, and reports whether
     * it did.
     *
     * Liking and saving are two independent flags on YouTube's side, and
     * clearing only the first leaves the song saved — still feeding the
     * Library tab's Artists shelf, still in the library feeds, with nowhere
     * left in this app to reach it and finish the job. Clearing the heart
     * reads as "forget this song", so it clears both.
     *
     * The token is fetched here rather than taken from [songMenu] because the
     * heart in the player never opens a menu, so there is often nothing
     * cached to take. One extra request, on an action nobody performs in bulk.
     * A song that was never saved has no removal token and this is a no-op.
     */
    private suspend fun forgetFromLibrary(videoId: String): Boolean {
        val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return false
        val token = menu.removeFromLibraryToken?.takeIf { menu.inLibrary } ?: return false
        if (YtMusicRepository.setLibraryStatus(token).isFailure) return false
        // The menu may be the one on screen; don't leave it offering a
        // removal that has already happened.
        _songMenu.value = _songMenu.value?.copy(inLibrary = false)
        return true
    }

    /**
     * Takes an un-liked track out of the lists that exist *because* it was
     * liked — the Library tab's Liked Music section, and the Liked Music page
     * itself if it happens to be open.
     *
     * Marking the library stale isn't enough on its own: that only acts when
     * the tab is next opened, and un-liking is nearly always done from inside
     * one of these two lists, looking straight at the row. Leaving it there
     * reads as the tap not having worked — the menu says "Like" again while
     * the song sits in Liked Music.
     *
     * Only ever removes. A track liked from somewhere else doesn't get spliced
     * into a list that YouTube orders for itself; the next fetch places it.
     */
    private fun dropFromLikedLists(videoId: String) {
        val library = (_library.value as? UiState.Success)?.data
        if (library != null && library.likedSongs.any { it.videoId == videoId }) {
            _library.value = UiState.Success(
                library.copy(likedSongs = library.likedSongs.filterNot { it.videoId == videoId }),
            )
        }
        _detailStack.value = _detailStack.value.map { page ->
            val songs = (page.songs as? UiState.Success)?.data
            if (page.browseId != YtMusicRepository.LIKED_MUSIC || songs == null) {
                page
            } else {
                page.copy(songs = UiState.Success(songs.filterNot { it.videoId == videoId }))
            }
        }
    }

    /** The heart: liked becomes neutral, anything else becomes liked. */
    fun toggleLike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    /** As [toggleLike], for the thumb-down. */
    fun toggleDislike(videoId: String): LikeStatus? {
        if (!requireSignIn()) return null
        val previous = likeStatusOf(videoId)
        setLike(
            videoId,
            if (previous == LikeStatus.DISLIKE) {
                LikeStatus.INDIFFERENT
            } else {
                LikeStatus.DISLIKE
            },
        )
        return previous
    }

    /** Provider-neutral rating used by the main player, menus and Favorites. */
    fun setLike(song: Song, status: LikeStatus) {
        val previous = likeStatusOf(song.videoId)
        if (previous == status) return
        val previousEntry = TrackRatingStore.entry(song.videoId)
        if (song.localUri != null && status == LikeStatus.DISLIKE && OfflinePlaylists.isLiked(song)) {
            OfflinePlaylists.toggleLike(song)
        }
        TrackRatingStore.set(song, status)

        val youtubeTrack = song.videoId.matches(Regex("[A-Za-z0-9_-]{11}")) &&
            !song.isSpotifySourceTrack && !song.isVkSourceTrack &&
            !song.videoId.startsWith("soundcloud:")
        when {
            youtubeTrack && _signedIn.value -> {
                val identity = tasteListenerKey()
                viewModelScope.launch {
                    YtMusicRepository.rate(song.videoId, status).fold(
                        onSuccess = {
                            TasteSignalStore.rememberRating(identity, song.videoId, status)
                            libraryStale = true
                            if (status != LikeStatus.LIKE) dropFromLikedLists(song.videoId)
                            if (previous == LikeStatus.LIKE && status == LikeStatus.INDIFFERENT) {
                                forgetFromLibrary(song.videoId)
                            }
                        },
                        onFailure = { TrackRatingStore.restore(song, previous, previousEntry) },
                    )
                }
            }
            song.videoId.startsWith("soundcloud:") && AppSettings.soundCloudAccessToken.value.isNotBlank() -> {
                viewModelScope.launch {
                    runCatching { SoundCloudAccountApi.setLiked(song.videoId, status == LikeStatus.LIKE) }
                }
            }
            else -> Unit // Spotify, VK, guest YouTube and local-provider rows stay device-local.
        }
    }

    fun toggleLike(song: Song) = setLike(
        song,
        if (likeStatusOf(song.videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    fun toggleDislike(song: Song): LikeStatus {
        val previous = likeStatusOf(song.videoId)
        setLike(song, if (previous == LikeStatus.DISLIKE) LikeStatus.INDIFFERENT else LikeStatus.DISLIKE)
        return previous
    }

    /**
     * Saves the album or playlist [browseId] to the library, or takes it out.
     *
     * Written to the screen first and rolled back if YouTube refuses, for the
     * same reason [setLike] is: it is one tap on a page the user is looking at,
     * and a control that waits on a round trip before it changes reads as a tap
     * that missed.
     *
     * A page with no [DetailPage.library] is one YouTube never offered to save
     * — a local page, an auto-playlist, a generated mix — and the UI has no
     * control on it to have been tapped, so this is a no-op rather than a guess.
     */
    fun toggleLibrary(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value.firstOrNull { it.browseId == browseId }?.library ?: return
        val target = !current.saved
        setSavedOnPage(browseId, target)
        viewModelScope.launch {
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                // The Library tab's Albums/Playlists shelf is now out of date.
                libraryStale = true
            } else {
                setSavedOnPage(browseId, current.saved)
            }
        }
    }

    /**
     * Subscribes to the artist page [browseId]'s channel, or unsubscribes.
     *
     * The release equivalent is [toggleLibrary], and this behaves the same way:
     * optimistic, rolled back on refusal, and a no-op on a page whose header
     * never offered a subscribe button — a signed-out response among them.
     */
    fun toggleSubscription(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value
            .firstOrNull { it.browseId == browseId }?.subscription ?: return
        val target = !current.subscribed
        setSubscribedOnPage(browseId, target)
        viewModelScope.launch {
            if (YtMusicRepository.setSubscribed(current.channelId, target).isSuccess) {
                // The Library tab's Subscriptions shelf is now out of date.
                libraryStale = true
            } else {
                setSubscribedOnPage(browseId, current.subscribed)
            }
        }
    }

    /** As [setSavedOnPage], for the artist header's subscribe button. */
    private fun setSubscribedOnPage(browseId: String, subscribed: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val subscription = page.subscription
            if (page.browseId != browseId || subscription == null) {
                page
            } else {
                page.copy(subscription = subscription.copy(subscribed = subscribed))
            }
        }
    }

    /**
     * Restates whether a page is saved. By id rather than by index: the user may
     * have pushed or popped pages while the write was in flight.
     */
    private fun setSavedOnPage(browseId: String, saved: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val library = page.library
            if (page.browseId != browseId || library == null) {
                page
            } else {
                page.copy(library = library.copy(saved = saved))
            }
        }
    }

    /**
     * The open track menu's account state, or null while it is still being
     * fetched. Only one menu can be open at a time, so one slot is enough.
     */
    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu: StateFlow<SongMenu?> = _songMenu.asStateFlow()

    private var songMenuJob: Job? = null

    /** In-flight liked-library continuation sync — see [syncLikedMusic]. */
    private var likedSyncJob: Job? = null

    /**
     * Loads the account state behind an opening track menu — the library
     * tokens, and any rating the response happens to state.
     *
     * The rating is only ever taken when it *adds* something: a LIKE or a
     * DISLIKE the library couldn't have told us, such as a disliked track or
     * one liked past the tenth page of Liked Music. An INDIFFERENT is
     * discarded.
     *
     * That asymmetry is not fussiness. This lookup reads a watch queue, and a
     * watch queue routinely renders a liked track with no rating on it at all;
     * believing that silence downgraded songs sitting in Liked Music to
     * "not liked" a beat after their menu opened — the label changing under
     * the user, with no request sent and nothing removed.
     */
    fun loadSongMenu(videoId: String?) {
        songMenuJob?.cancel()
        _songMenu.value = null
        if (videoId == null || !_signedIn.value) return
        songMenuJob = viewModelScope.launch {
            val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return@launch
            _songMenu.value = menu
            LikeState.rememberStated(videoId, menu.likeStatus)
        }
    }

    /** The account's own playlists, for the picker and the library tab. */
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    /** Re-fetched rather than cached for the session: playlists are edited here. */
    fun loadPlaylists() {
        if (!_signedIn.value || _playlistsLoading.value) return
        val identity = listenerKey()
        _playlistsLoading.value = true
        viewModelScope.launch {
            YtMusicRepository.userPlaylists().onSuccess { if (identity == listenerKey()) _playlists.value = it }
            if (identity == listenerKey()) _playlistsLoading.value = false
        }
    }

    /**
     * The library feed's Playlists shelf, rewritten by [edit].
     *
     * Every playlist edit has to do this by hand, because the library tab reads
     * `_library` and nothing else — [playlists] is the picker's list, not the
     * tab's — so a rename that only updated that list left the card on screen
     * still bearing the old name.
     *
     * Re-fetching instead is what this replaces, and it did not work: YouTube's
     * `FEmusic_liked_playlists` is eventually consistent, and a fetch fired the
     * moment an edit returns reliably answers with the state from *before* it.
     * So the edit was applied, the feed denied it, and the denial is what
     * reached the screen — the bug this exists to fix. The re-fetch still
     * happens, via [libraryStale], once the tab is next opened and the feed has
     * caught up.
     *
     * A shelf that isn't there yet is created by [edit] returning rows for it
     * (a first playlist has no shelf to add to), and one left empty is dropped —
     * see [LibraryScreen], which draws the create tile with or without a shelf.
     */
    private fun editPlaylistShelf(edit: (List<ShelfItem>) -> List<ShelfItem>) {
        val page = (_library.value as? UiState.Success)?.data ?: return
        val existing = page.shelves.firstOrNull { it.title == YtMusicRepository.PLAYLISTS_SHELF }
        val items = edit(existing?.items.orEmpty())
        if (items == existing?.items) return
        val shelves = when {
            existing == null && items.isEmpty() -> return
            // No shelf yet: this is the account's first playlist, so the feed
            // has never had one to send. Leads the page, as the feed orders it.
            existing == null ->
                listOf(HomeShelf(YtMusicRepository.PLAYLISTS_SHELF, items)) + page.shelves
            // Emptied by deleting the last playlist. Dropped rather than left as
            // a heading over nothing; the create tile is drawn either way.
            items.isEmpty() -> page.shelves.filterNot { it === existing }
            else -> page.shelves.map { if (it === existing) existing.copy(items = items) else it }
        }
        val updated = page.copy(shelves = shelves)
        _library.value = UiState.Success(updated)
        personalizedHome.updateLibrary(updated, tasteListenerKey())
    }

    /**
     * Restates a playlist's name everywhere it is currently drawn: its card in
     * the library, the picker's list, and its own open page — header and top
     * bar both, which read [DetailPage.title].
     */
    private fun setPlaylistTitle(playlist: UserPlaylist, title: String) {
        TasteSignalStore.renameImport(playlist.browseId, title)
        _playlists.value = _playlists.value.map {
            if (it.playlistId == playlist.playlistId) it.copy(title = title) else it
        }
        editPlaylistShelf { items ->
            items.map { if (it.browseId == playlist.browseId) it.copy(title = title) else it }
        }
        _detailStack.value = _detailStack.value.map {
            if (it.browseId == playlist.browseId) it.copy(title = title) else it
        }
    }

    /**
     * Adds [song] to every playlist in [playlists] — the picker's ticked rows —
     * and reports how each went, once, when they all have.
     *
     * Not optimistic, unlike a rating: the picker closes on the tap and there is
     * nothing left of it to update, and a playlist that shows a track it turned
     * out not to have taken is worse than one that shows it a moment late. The
     * playlist's own page is the exception — see [addOne].
     *
     * YouTube itself has no objection to a duplicate row, so whether the track
     * is already there is checked here, against the playlist's open page or a
     * fresh fetch of it, and a real duplicate is never sent.
     *
     * One at a time rather than all at once: each check reads the playlist it
     * is about, and a burst of parallel edits is exactly what YouTube answers
     * with a rate limit. The duplicate check is per playlist, so a track
     * already in one of them is skipped there and still added to the rest.
     */
    fun addToPlaylists(
        playlists: List<UserPlaylist>,
        song: Song,
        onResult: (added: Int, alreadyThere: Int, failed: Int) -> Unit = { _, _, _ -> },
    ) {
        if (playlists.isEmpty()) return
        // Local playlists take the row whether or not there is a session; only
        // the account's own lists need one.
        val local = playlists.filter { it.playlistId.startsWith("sp_local_") || it.browseId.startsWith("local:playlist:") }
        val remote = playlists - local.toSet()
        var localAdded = 0
        var localAlready = 0
        local.forEach { playlist ->
            val added = com.music.bitchord.data.spotify.LocalPlaylistStore
                .addSongs(playlist.browseId, listOf(song))
            if (added > 0) localAdded++ else localAlready++
        }
        if (remote.isEmpty()) {
            onResult(localAdded, localAlready, 0)
            return
        }
        if (!requireSignIn()) {
            onResult(localAdded, localAlready, remote.size)
            return
        }
        viewModelScope.launch {
            val outcomes = remote.map { addOne(it, song) }
            onResult(
                localAdded + outcomes.count { it == AddOutcome.ADDED },
                localAlready + outcomes.count { it == AddOutcome.ALREADY_THERE },
                outcomes.count { it == AddOutcome.FAILED },
            )
        }
    }

    private enum class AddOutcome { ADDED, ALREADY_THERE, FAILED }

    private suspend fun addOne(playlist: UserPlaylist, song: Song): AddOutcome {
        val openSongs = (_detailStack.value.firstOrNull { it.browseId == playlist.browseId }
            ?.songs as? UiState.Success)?.data
        val known = openSongs
            ?: YtMusicRepository.allSongs(playlist.browseId).getOrNull()
        if (known?.any { it.videoId == song.videoId } == true) return AddOutcome.ALREADY_THERE
        return YtMusicRepository.addToPlaylist(playlist.playlistId, listOf(song.videoId)).fold(
            onSuccess = { added ->
                libraryStale = true
                // The playlist's page may be open behind the picker — it is
                // reachable from a row's own menu on it — so the track goes
                // into it for the same reason [addSuggestedSong] does.
                appendToOpenPlaylist(playlist.browseId, song, added[song.videoId])
                AddOutcome.ADDED
            },
            onFailure = { AddOutcome.FAILED },
        )
    }

    /**
     * Creates a playlist, seeded with [song] when the flow started from a
     * track's menu — one request, so it can't half-succeed into an empty
     * playlist the user has to add to again.
     */
    fun createPlaylist(title: String, privacy: PlaylistPrivacy, song: Song? = null) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        // Without an account a playlist is still a playlist. It lives on this
        // device, holds whatever is dropped into it, and moves with a backup;
        // requiring a YouTube session for a list of songs was the thing people
        // kept asking not to need.
        if (!_signedIn.value) {
            val local = com.music.bitchord.data.spotify.LocalPlaylistStore
                .savePlaylist(name, listOfNotNull(song))
            editPlaylistShelf { items ->
                listOf(
                    ShelfItem(
                        title = local.title,
                        subtitle = if (local.songs.isEmpty()) "" else "${local.songs.size}",
                        thumbnailUrl = local.songs.firstOrNull()?.thumbnailUrl,
                        videoId = null,
                        browseId = local.browseId,
                    ),
                ) + items.filterNot { it.browseId == local.browseId }
            }
            return
        }
        if (!requireSignIn()) return
        viewModelScope.launch {
            YtMusicRepository.createPlaylist(
                title = name,
                privacy = privacy,
                videoIds = listOfNotNull(song?.videoId),
            ).fold(
                onSuccess = { playlistId ->
                    // Nothing to look up for a playlist this account has just
                    // made: it is the owner by construction, so its card is
                    // editable the moment it appears rather than one request
                    // after someone holds it.
                    setPlaylistOwned("VL$playlistId", true)
                    libraryStale = true
                    val created = UserPlaylist(
                        playlistId = playlistId,
                        title = name,
                        // Only what this request itself establishes. Both
                        // surfaces that draw it leave a blank one out, so an
                        // unseeded playlist gets a card of just its name rather
                        // than a guess at what the feed will call it.
                        subtitle = if (song != null) "1 song" else "",
                        thumbnailUrl = song?.thumbnailUrl,
                    )
                    // Drawn from what was just sent rather than waited for: the
                    // library feed does not have this playlist yet, and the
                    // fetch that used to run here answered without it — see
                    // [editPlaylistShelf]. Leads the shelf because it is the
                    // newest, which is the order the feed itself comes in.
                    _playlists.value = listOf(created) +
                        _playlists.value.filterNot { it.playlistId == created.playlistId }
                    editPlaylistShelf { items ->
                        listOf(
                            ShelfItem(
                                title = created.title,
                                subtitle = created.subtitle,
                                thumbnailUrl = created.thumbnailUrl,
                                videoId = null,
                                browseId = created.browseId,
                            ),
                        ) + items.filterNot { it.browseId == created.browseId }
                    }
                },
                onFailure = {},
            )
        }
    }

    fun createPlaylistWithVideoIds(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String>,
        songs: List<Song> = emptyList(),
        source: HomeSource = HomeSource.LOCAL,
        /** [savedLocally] is true when the playlist went to this device, not YouTube Music. */
        onResult: ((browseId: String?, title: String, savedLocally: Boolean) -> Unit)? = null,
    ) {
        val name = title.trim().ifBlank { text(R.string.new_playlist) }
        val owner = tasteListenerKey()
        viewModelScope.launch {
            if (authStore.isSignedIn) {
                val result = writeImportedPlaylist(
                    videoIds = videoIds,
                    songs = songs,
                    createPlaylist = { initialBatch ->
                        if (owner != tasteListenerKey()) throw CancellationException("Import account changed")
                        YtMusicRepository.createPlaylist(name, privacy, initialBatch)
                    },
                    addToPlaylist = { playlistId, batch ->
                        if (owner != tasteListenerKey()) throw CancellationException("Import account changed")
                        YtMusicRepository.addToPlaylist(playlistId, batch)
                    },
                    saveLocally = { completeSongs ->
                        com.music.bitchord.data.spotify.LocalPlaylistStore
                            .savePlaylist(name, completeSongs, source).browseId
                    },
                )
                if (owner != tasteListenerKey()) return@launch
                result.cloudPlaylistId?.let { playlistId ->
                    setPlaylistOwned("VL$playlistId", true)
                    libraryStale = true
                    val created = UserPlaylist(
                        playlistId = playlistId,
                        title = name,
                        subtitle = "${result.acceptedCloudTrackCount} songs",
                        thumbnailUrl = null,
                    )
                    _playlists.value = listOf(created) +
                        _playlists.value.filterNot { it.playlistId == created.playlistId }
                    editPlaylistShelf { items ->
                        listOf(
                            ShelfItem(
                                title = created.title,
                                subtitle = created.subtitle,
                                thumbnailUrl = created.thumbnailUrl,
                                videoId = null,
                                browseId = created.browseId,
                            ),
                        ) + items.filterNot { it.browseId == created.browseId }
                    }
                }
                TasteSignalStore.rememberImport(name, source, songs, result.browseId,
                    owner.takeUnless { result.savedLocally })
                onResult?.invoke(result.browseId, name, result.savedLocally)
            } else {
                val local = com.music.bitchord.data.spotify.LocalPlaylistStore.savePlaylist(name, songs, source)
                TasteSignalStore.rememberImport(name, source, songs, local.browseId, null)
                onResult?.invoke(local.browseId, local.title, true)
            }
        }
    }

    /**
     * Drops [song] from the playlist page it is being read on, and takes the
     * row out from under the reader rather than waiting for a re-fetch.
     */
    fun removeFromPlaylist(browseId: String, song: Song) {
        val setVideoId = song.setVideoId ?: return
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.removeFromPlaylist(
                playlistId,
                listOf(setVideoId to song.videoId),
            ).fold(
                onSuccess = {
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        val songs = (page.songs as? UiState.Success)?.data
                        if (page.browseId != browseId || songs == null) {
                            page
                        } else {
                            page.copy(
                                songs = UiState.Success(
                                    songs.filterNot { it.setVideoId == setVideoId },
                                ),
                            )
                        }
                    }
                },
                onFailure = {},
            )
        }
    }

    /**
     * Adds one of [DetailPage.suggestedSongs] to the playlist it was suggested
     * for: out of that section, and into the track list above it.
     *
     * Both halves, because either alone is a worse answer than doing nothing.
     * Only removing it — which is what this used to do — reads as the track
     * having been discarded rather than added: it leaves the Suggested list and
     * turns up nowhere, and the playlist it was added to looks unchanged until
     * the page is closed and reopened. Only adding it would leave YouTube still
     * suggesting a track that is now in the playlist.
     *
     * The row goes in complete, per-entry id included, because
     * [YtMusicRepository.addToPlaylist] reports the one it was just filed
     * under — so "Remove from this playlist" works on it immediately rather
     * than after a re-fetch. A response that named no id still adds the row;
     * it just can't offer to take it back out yet.
     */
    fun addSuggestedSong(browseId: String, song: Song) {
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.addToPlaylist(playlistId, listOf(song.videoId)).fold(
                onSuccess = { added ->
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != browseId) {
                            page
                        } else {
                            page.copy(
                                suggestedSongs = page.suggestedSongs
                                    .filterNot { it.videoId == song.videoId },
                            )
                        }
                    }
                    appendToOpenPlaylist(browseId, song, added[song.videoId])
                },
                onFailure = {},
            )
        }
    }

    /**
     * Puts [song] at the end of the playlist page at [browseId], if that page
     * is open — where YouTube itself puts it, so the order survives the next
     * fetch.
     *
     * An empty playlist counts as open: it renders as an empty-state message, and the
     * first track added to one has to replace that message rather than be
     * dropped for want of a list to join. Only that message, though — any other
     * error is a page that failed to load, whose real contents are unknown, and
     * answering it with a one-track listing would be a playlist invented out of
     * a network failure. A page still loading is left alone too: the fetch in
     * flight is newer than this and will land with the addition already in it.
     */
    private fun appendToOpenPlaylist(browseId: String, song: Song, setVideoId: String?) {
        val added = song.copy(setVideoId = setVideoId)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId != browseId) return@map page
            val songs = when (val state = page.songs) {
                is UiState.Success -> state.data
                is UiState.Error -> if (state.message == text(R.string.no_tracks_here)) emptyList() else return@map page
                UiState.Loading -> return@map page
            }
            // Already there — a track added twice is two real entries on
            // YouTube's side, but a duplicate row from a double tap is not
            // something the user asked for.
            if (songs.any { it.videoId == song.videoId }) return@map page
            page.copy(
                songs = UiState.Success(
                    songs + added.copy(
                        thumbnailUrl = added.thumbnailUrl ?: page.thumbnailUrl,
                    ),
                ),
            )
        }
    }

    /**
     * Renames a playlist, and says so everywhere it is named — see
     * [setPlaylistTitle]. Renaming is nearly always done from the playlist's
     * own page or its card, so there is always something on screen still
     * showing the old name.
     */
    fun renamePlaylist(playlist: UserPlaylist, title: String) {
        if (!requireSignIn()) return
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        viewModelScope.launch {
            YtMusicRepository.renamePlaylist(playlist.playlistId, name).fold(
                onSuccess = {
                    setPlaylistTitle(playlist, name)
                    libraryStale = true
                },
                onFailure = {},
            )
        }
    }

    /**
     * The playlist's entries as they stand on YouTube, for the reorder page —
     * see [YtMusicRepository.playlistEntries] for why not the open page's list.
     */
    fun loadPlaylistEntries(playlist: UserPlaylist, onResult: (Result<List<Song>>) -> Unit) {
        if (!requireSignIn()) return onResult(Result.failure(IllegalStateException("signed out")))
        viewModelScope.launch {
            onResult(YtMusicRepository.playlistEntries(playlist.browseId))
        }
    }

    /**
     * Saves a new running order for [playlist], sent as the fewest moves that
     * get from [original] to [reordered], and puts the open page — if this
     * playlist's is — into that order straight away rather than after a
     * re-fetch the feed may answer with the old order.
     */
    fun reorderPlaylist(
        playlist: UserPlaylist,
        original: List<Song>,
        reordered: List<Song>,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!requireSignIn()) return onResult(false)
        viewModelScope.launch {
            YtMusicRepository.reorderPlaylist(
                playlist.playlistId,
                current = original.mapNotNull { it.setVideoId },
                target = reordered.mapNotNull { it.setVideoId },
            ).fold(
                onSuccess = {
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != playlist.browseId || page.songs !is UiState.Success) {
                            page
                        } else {
                            page.copy(songs = UiState.Success(reordered.withArtwork(page.thumbnailUrl)))
                        }
                    }
                    onResult(true)
                },
                onFailure = { onResult(false) },
            )
        }
    }

    /**
     * Saves a new running order for a playlist kept on this device — the
     * counterpart to [reorderPlaylist] for one with no YouTube Music page and
     * so no account to be signed in to.
     *
     * The open page is reloaded from the store rather than patched here: the
     * store is the one thing that knows what the playlist holds now.
     *
     * [pageId] is the page to bring into the new order — the store's own
     * `local:playlist:` id, which [UserPlaylist.browseId] cannot spell back from
     * an account playlist id.
     */
    fun reorderLocalPlaylist(playlist: UserPlaylist, pageId: String, reordered: List<Song>) {
        com.music.bitchord.data.spotify.LocalPlaylistStore
            .reorderSongs(playlist.playlistId, reordered)
        reloadLocalDetail(pageId)
    }

    fun deletePlaylist(playlist: UserPlaylist) {
        // A local playlist is deleted here and now: sending it to YouTube would
        // fail for a row that never existed there, and would leave the card on
        // screen with no way to remove it.
        if (playlist.playlistId.startsWith("sp_local_") ||
            playlist.browseId.startsWith("local:playlist:")
        ) {
            com.music.bitchord.data.spotify.LocalPlaylistStore.deletePlaylist(playlist.playlistId)
            editPlaylistShelf { items -> items.filterNot { it.browseId == playlist.browseId } }
            return
        }
        if (!requireSignIn()) return
        viewModelScope.launch {
            YtMusicRepository.deletePlaylist(playlist.playlistId).fold(
                onSuccess = {
                    TasteSignalStore.forgetImport(playlist.browseId)
                    _playlists.value = _playlists.value
                        .filterNot { it.playlistId == playlist.playlistId }
                    // The card in the library tab, which is the surface the
                    // deletion was almost certainly ordered from — and which the
                    // re-fetch that used to stand in for this left in place; see
                    // [editPlaylistShelf].
                    editPlaylistShelf { items ->
                        items.filterNot { it.browseId == playlist.browseId }
                    }
                    // Its page may be the one open; a deleted playlist has
                    // nothing left to show.
                    _detailStack.value = _detailStack.value
                        .filterNot { it.browseId == playlist.browseId }
                    navigator.onDetailStackShrunk(_detailStack.value.size)
                    libraryStale = true
                },
                onFailure = {},
            )
        }
    }

    /**
     * Whether [browseId] is a playlist this account can be asked to edit.
     *
     * Only ever yes for a playlist [playlistOwned] has confirmed the account
     * made. "In this account's library" is not the same thing and cannot stand
     * in for it: `FEmusic_liked_playlists` lists a playlist saved from someone
     * else in exactly the shape it lists one this account created, so a lookup
     * in [playlists] alone called a stranger's playlist editable and the menus
     * offered Rename and Delete on it — neither of which YouTube would have
     * honoured.
     *
     * Strict rather than permissive-until-proven, so that every surface gives
     * the same answer for the same playlist. The permissive version was right on
     * a playlist's own page — where the page load supplies the answer — and
     * wrong on a card until that page had been opened once, which is a menu that
     * changes its mind about what a playlist is depending on where it is held.
     */
    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null || _playlistOwned.value[browseId] != true) return null
        return _playlists.value.firstOrNull { it.browseId == browseId }
    }

    /**
     * Which playlists in this account's library the account actually made, by
     * browse id — see
     * [com.music.bitchord.data.innertube.InnertubeParser.parsePlaylistOwned].
     * An id absent from the map is one nothing has asked about yet, which is not
     * the same as a no.
     *
     * Observable, because the answer routinely arrives after whatever wanted it
     * is already on screen: a card's menu opens with nothing fetched, and the
     * rows that depend on this appear as [resolvePlaylistOwnership] answers.
     */
    private val _playlistOwned = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playlistOwned: StateFlow<Map<String, Boolean>> = _playlistOwned.asStateFlow()

    /**
     * Finds out who made the playlist at [browseId], if it isn't already known.
     *
     * Only the playlist's own page states this, so a surface that has no page —
     * a card in the library, a search result — has to ask for one. Which is why
     * this is on demand rather than swept up front: the alternative is a request
     * per playlist every time the library loads, for a question most of them
     * will never be asked.
     *
     * Silent about anything that isn't a playlist in this account's library.
     * Nothing else can be renamed or deleted whatever the answer, so asking
     * would be a request spent to rule out what was never on offer.
     */
    fun resolvePlaylistOwnership(browseId: String?) {
        if (!_signedIn.value || browseId == null) return
        if (browseId in _playlistOwned.value || browseId in ownershipInFlight) return
        if (_playlists.value.none { it.browseId == browseId }) return
        ownershipInFlight += browseId
        viewModelScope.launch {
            YtMusicRepository.playlistOwned(browseId).onSuccess { owned ->
                if (owned != null) setPlaylistOwned(browseId, owned)
            }
            // Released either way. A failed lookup that stayed marked would
            // never be retried, leaving Rename off the user's own playlist for
            // the rest of the session over one dropped request.
            ownershipInFlight -= browseId
        }
    }

    /** Guards against a second lookup while the first is still out. */
    private val ownershipInFlight = mutableSetOf<String>()

    private fun setPlaylistOwned(browseId: String, owned: Boolean) {
        _playlistOwned.value = _playlistOwned.value + (browseId to owned)
    }


    /**
     * Guards every account write. All of them are signed-in-only, and the UI
     * hides them for guests — this is the backstop for a session that expired
     * between the menu opening and the tap.
     */
    private fun requireSignIn(): Boolean = _signedIn.value

    /**
     * Whether the library needs re-fetching. Set by every write above and
     * acted on when the tab is next opened, for the same reason [homeStale]
     * exists: rearranging a page under whoever is reading it is worse than
     * showing it a moment out of date.
     */
    private var libraryStale = false

    /** Call when the library tab becomes visible. */
    fun onLibraryShown() {
        loadPlaylists()
        if (!libraryStale) return
        libraryStale = false
        if (_library.value is UiState.Success) refresh(Feed.LIBRARY)
    }

    init {
        val tasteAccount = combine(_signedIn, _activeAccountId, _activeProfileId) { signed, _, _ ->
            if (signed) tasteListenerKey() else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, tasteListenerKey())
        personalizedHome = PersonalizedHome(app, viewModelScope, tasteAccount) { next ->
            val previous = _personalizedHome.value
            if (previous.accounts[HomeSource.YOUTUBE] != next.accounts[HomeSource.YOUTUBE] && _home.value is UiState.Success) {
                homeLoadGeneration.incrementAndGet()
                _home.value = UiState.Loading
                homeContinuation = null
                homeSeenTitles.clear()
            }
            _personalizedHome.value = next
        }
        startSearchPipeline()
        startSuggestPipeline()
        startTypeaheadMediaPipeline()
        loadHome()
        // A page built offline is the downloads and nothing else. The feed is
        // fetched as soon as the connection comes back, without a tap, but only
        // when the page on screen is that offline one.
        viewModelScope.launch {
            AppSettings.meteredConnection.drop(1).collect { metered ->
                if (metered != null && homeBuiltOffline) loadHome()
            }
        }
        // Explore waits for its tab (see [onExploreShown]): its category list
        // and the dozen-odd artwork browses after it went out at launch with
        // Home's own requests, to the same host, for a page not yet opened.
        if (_signedIn.value) {
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
        viewModelScope.launch {
            // drop(1): the current value is just the count so far, not a play.
            PlaybackTracker.registeredPlays.drop(1).collect { homeStale = true }
        }
        viewModelScope.launch {
            BoundedContextRequests.snapshot.collect { snapshot ->
                snapshot?.let(::applyBoundedContextSnapshot)
            }
        }
        viewModelScope.launch {
            AppSettings.filterNonMusicAudio.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == "local:all" }) {
                    reloadLocalDetail("local:all")
                }
            }
        }
        viewModelScope.launch {
            AppSettings.soundCloudAccessToken.collectLatest { token ->
                if (token.isBlank()) return@collectLatest
                runCatching {
                    val likes = SoundCloudAccountApi.likedTracks()
                        .mapIndexed { index, track -> track.toSearchSong(index) }
                    TrackRatingStore.importLikes(likes)
                }
            }
        }
        viewModelScope.launch {
            AppSettings.spotifySpdcToken.collectLatest { token ->
                if (token.isBlank()) return@collectLatest
                runCatching {
                    TrackRatingStore.importLikes(
                        SpotifyLibrary.tracks(SpotifyLibrary.LIKED_ID).map(SpotifyTrack::toSourceSong),
                    )
                }
            }
        }
        viewModelScope.launch {
            AppSettings.yandexMusicToken.collectLatest { token ->
                if (token.isBlank()) return@collectLatest
                runCatching {
                    val api = YandexMusicApi()
                    val uid = api.account(token).uid
                    val rows = api.likedTracks(token, uid).map { it.track }
                    TrackRatingStore.importLikes(
                        ExternalPlaylistImporter.resolve(rows) { _, _ -> }.first,
                    )
                }
            }
        }
        viewModelScope.launch {
            // Emptied from Settings while the folder sits open underneath.
            AudioCache.contentsChanged.drop(1).collect {
                if (_detailStack.value.any { page -> page.browseId == CACHE_FOLDER_BROWSE_ID }) {
                    reloadLocalDetail(CACHE_FOLDER_BROWSE_ID)
                }
            }
        }
        viewModelScope.launch {
            AppSettings.webdavUrl.drop(1).collect {
                reloadRemoteDetail(com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID)
            }
        }
        viewModelScope.launch {
            combine(
                AppSettings.smbHost,
                AppSettings.smbShare,
                AppSettings.smbBasePath,
                AppSettings.smbUsername,
                AppSettings.smbPassword,
            ) { fields -> fields.toList() }
                .drop(1)
                .debounce(300)
                .collect { reloadRemoteDetail(com.music.bitchord.data.smb.SmbConfig.BROWSE_ID) }
        }
    }

    /**
     * Whether a play has been registered since the home feed was last fetched.
     *
     * The feed leads with listening history, so it's out of date the moment a
     * track starts — but re-fetching there would rearrange the page under
     * whoever is reading it, and the tab is usually in the background anyway.
     * It's re-fetched when the tab is next opened instead.
     */
    private var homeStale = false

    /** Call when the home tab becomes visible. */
    fun onHomeShown() {
        // Showing the tab is not a reason to fetch while there is nothing to
        // fetch from, and the refresh below would put account shelves back on
        // top of the offline page.
        if (AudioCache.isOffline()) {
            loadOfflineHome()
            return
        }
        personalizedHome.refresh()
        if (libraryStale && _signedIn.value) {
            libraryStale = false
            loadLibrary()
        }
        if (!homeStale) return
        homeStale = false
        // A first load already in flight will pick the new play up by itself.
        if (_home.value is UiState.Success) refreshHomeRecents()
    }

    /**
     * Re-fetches only the Recents shelf, swapping it in place. The rest of
     * Home stays exactly as it was, so a shelf someone was eyeing doesn't
     * vanish because a track started playing.
     */
    private fun refreshHomeRecents() {
        if (!_signedIn.value) return
        val identity = listenerKey()
        val generation = homeLoadGeneration.get()
        viewModelScope.launch {
            YtMusicRepository.homeRecentlyPlayed().onSuccess { shelf ->
                // A full load that started meanwhile fetches Recents itself.
                if (!isCurrentHomeLoad(identity, generation)) return@onSuccess
                shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
            }
        }
    }

    private fun loadAccount() {
        val identity = listenerKey()
        viewModelScope.launch {
            val account = YtMusicRepository.account().getOrNull()
            if (identity != listenerKey()) return@launch
            _account.value = account
            // A channel picked in the in-app browser arrives as ids and nothing
            // else — the page's `ytcfg` never says what the channel is called.
            // The account menu, asked *as* that channel, answers with its name,
            // which is what the settings row needs to show.
            if (account != null && _selectedChannelKey.value != null) {
                _selectedChannelName.value = account.name
                val accountId = _activeAccountId.value
                val profileId = _activeProfileId.value
                val session = accountId?.let { id -> authStore.sessions.firstOrNull { it.accountId == id } }
                val updated = session?.let { saved -> saved.copy(
                    name = account.name, email = account.email,
                    profiles = saved.profiles.map { profile ->
                        if (profile.profileId == profileId) profile.copy(
                            name = account.name, handle = account.email, avatar = account.thumbnailUrl,
                        ) else profile
                    },
                ) }
                if (updated != null) {
                    authStore.upsertSession(updated, activate = false)
                    _googleAccounts.value = authStore.sessions
                }
            }
        }
    }

    /**
     * A feed that can be pulled down to refresh. Tracked per feed rather than
     * as one flag: a pull on Library while Home is still refreshing in the
     * background shouldn't leave the wrong tab showing a loader.
     */
    enum class Feed { HOME, EXPLORE, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    /**
     * Re-fetches [feed] in place. Unlike the `load*` entry points this leaves
     * the current content on screen rather than dropping back to the loading
     * state — a refresh that swapped the page for a spinner would be a worse
     * experience than the stale content it replaces.
     */
    fun refresh(feed: Feed) {
        if (feed in _refreshing.value) return
        if (feed == Feed.LIBRARY && !_signedIn.value) return
        val identity = listenerKey()
        _refreshing.value = _refreshing.value + feed
        viewModelScope.launch {
            when (feed) {
                Feed.HOME -> refreshHome(identity)
                Feed.EXPLORE -> fetchExplore()
                Feed.LIBRARY -> fetchLibrary(identity)
            }
            _refreshing.value = _refreshing.value - feed
        }
    }

    fun loadExplore() {
        exploreRequested = true
        _explore.value = UiState.Loading
        viewModelScope.launch { fetchExplore() }
    }

    private var exploreRequested = false

    /** Call when the Explore tab becomes visible; loads it the first time. */
    fun onExploreShown() {
        if (!exploreRequested) loadExplore()
    }

    private suspend fun fetchExplore() {
        val state = YtMusicRepository.moodAndGenres().fold(
            onSuccess = { sections ->
                if (sections.isEmpty()) {
                    UiState.Error(text(R.string.nothing_to_explore))
                } else {
                    UiState.Success(sections)
                }
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
        _explore.value = state
        (state as? UiState.Success)?.data?.let(::loadMoodGenreArtwork)
    }

    /**
     * Category buttons do not carry thumbnails themselves. Resolve a small
     * number at a time from the shelves they open, so the grid gains real art
     * without saturating the browse client or delaying the category list.
     */
    private fun loadMoodGenreArtwork(sections: List<MoodGenreSection>) {
        viewModelScope.launch {
            val limiter = Semaphore(4)
            coroutineScope {
                sections.flatMap(MoodGenreSection::items).forEach { item ->
                    launch {
                        val artwork = limiter.withPermit {
                            YtMusicRepository.moodGenreArtwork(item.browseId, item.params).getOrNull()
                        } ?: return@launch
                        val current = (_explore.value as? UiState.Success)?.data ?: return@launch
                        _explore.value = UiState.Success(current.map { section ->
                            section.copy(items = section.items.map { currentItem ->
                                if (currentItem.browseId == item.browseId && currentItem.params == item.params) {
                                    currentItem.copy(thumbnailUrl = artwork)
                                } else {
                                    currentItem
                                }
                            })
                        })
                    }
                }
            }
        }
    }

    fun openMoodGenre(item: MoodGenre) {
        _selectedMoodGenre.value = item
        _moodGenreShelves.value = UiState.Loading
        viewModelScope.launch {
            _moodGenreShelves.value = YtMusicRepository.moodGenreShelves(item.browseId, item.params).fold(
                onSuccess = { shelves ->
                    if (shelves.isEmpty()) UiState.Error(text(R.string.nothing_to_explore))
                    else UiState.Success(shelves)
                },
                onFailure = { UiState.Error(it.friendly()) },
            )
        }
    }

    fun closeMoodGenre(): Boolean {
        if (_selectedMoodGenre.value == null) return false
        _selectedMoodGenre.value = null
        return true
    }

    /**
     * Home with no connection: the downloaded tracks, and nothing else.
     *
     * The personalised shelves are cleared with it — they are built from
     * account data and would otherwise be merged back into the same page by
     * [home], which is the one thing an offline listener must not see.
     */
    private fun loadOfflineHome() {
        val generation = homeLoadGeneration.incrementAndGet()
        homeBuiltOffline = true
        homeContinuation = null
        homeSeenTitles.clear()
        _homeLoadingMore.value = false
        _homeRecentlyPlayedLoading.value = false
        _homePendingShelves.value = 0
        _topPicks.value = null
        _topPicksLoading.value = false
        _personalizedHome.value = PersonalizedHomeState()
        viewModelScope.launch {
            val context = getApplication<Application>()
            val songs = runCatching { Downloads.getDownloadedSongs(context) }.getOrDefault(emptyList())
            if (generation != homeLoadGeneration.get()) return@launch
            _home.value = if (songs.isEmpty()) {
                UiState.Error(text(R.string.offline_nothing_downloaded))
            } else {
                UiState.Success(
                    listOf(
                        HomeShelf(
                            title = text(R.string.offline_downloaded_title),
                            subtitle = text(R.string.offline_downloaded_subtitle),
                            layout = ShelfLayout.LIST,
                            moreBrowseId = "local:downloads",
                            items = songs.take(30).map { song ->
                                ShelfItem(
                                    title = song.title,
                                    subtitle = song.artist,
                                    thumbnailUrl = song.thumbnailUrl,
                                    videoId = song.videoId,
                                    browseId = null,
                                    song = song,
                                )
                            },
                        ),
                    ),
                )
            }
        }
    }

    /** Tapping a tab should leave any pushed page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
        navigator.onDetailStackShrunk(0)
    }

    fun loadHome() {
        // Offline the feed has nothing to say: every shelf on it comes from a
        // service. What this device still has is the downloads, and they are
        // the whole page — asking the network anyway would replace them with
        // an error a second later.
        if (AudioCache.isOffline()) {
            loadOfflineHome()
            return
        }
        homeBuiltOffline = false
        personalizedHome.refresh()
        val identity = listenerKey()
        val generation = homeLoadGeneration.incrementAndGet()
        _home.value = UiState.Loading
        homeContinuation = null
        homeSeenTitles.clear()
        _homeLoadingMore.value = false
        _homeRecentlyPlayedLoading.value = _signedIn.value
        // The core feed plus one browse per supplement. Recently played is left
        // out: it has its own skeleton at the head of the page rather than the
        // one at the tail.
        _homePendingShelves.value = 1 + YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.size
        loadTopPicks(identity, rebuild = false)
        viewModelScope.launch {
            launch {
                try {
                    YtMusicRepository.home()
                        .onSuccess { feed ->
                            if (!isCurrentHomeLoad(identity, generation)) return@onSuccess
                            homeContinuation = feed.continuation
                            publishHomeShelves(feed.shelves)
                        }
                        .onFailure { failure ->
                            if (isCurrentHomeLoad(identity, generation) && _home.value !is UiState.Success) {
                                _home.value = UiState.Error(failure.friendly())
                            }
                        }
                } finally {
                    homeShelfRequestSettled(identity, generation)
                }
            }
            if (_signedIn.value) {
                launch {
                    YtMusicRepository.homeRecentlyPlayed()
                        .onSuccess { shelf ->
                            if (isCurrentHomeLoad(identity, generation)) {
                                _homeRecentlyPlayedLoading.value = false
                                shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
                            }
                        }
                        .onFailure {
                            if (isCurrentHomeLoad(identity, generation)) _homeRecentlyPlayedLoading.value = false
                        }
                }
            }
            YtMusicRepository.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId ->
                launch {
                    try {
                        YtMusicRepository.homeSupplement(browseId).onSuccess { shelves ->
                            if (isCurrentHomeLoad(identity, generation)) publishHomeShelves(shelves)
                        }
                    } finally {
                        homeShelfRequestSettled(identity, generation)
                    }
                }
            }
        }
    }

    /**
     * Puts the Top Picks row on screen. A row lasts one hour of the clock —
     * 3:00 to 4:00, not sixty minutes from whenever it was first built — and
     * the first open in a new hour builds the next one. Until that's ready the
     * last row stays up, so Home never waits on it; within the hour an open
     * makes no requests for it at all. [rebuild] builds one regardless.
     */
    private fun loadTopPicks(identity: String?, rebuild: Boolean) {
        topPicksJob?.cancel()
        val file = File(getApplication<Application>().filesDir, TOP_PICKS_FILE)
        val slot = topPicksSlot()
        topPicksJob = viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()?.let(TopPicks::decode)
            }?.takeIf { it.listener == identity && it.picks.isNotEmpty() }
            stored?.let { AppleArtistPhotos.seed(it.photos) }
            if (stored != null) {
                _topPicks.value = stored.picks
                topPicksSlotShown = stored.day
            } else if (!rebuild) {
                _topPicks.value = null
            }
            if (!rebuild && stored?.day == slot) {
                _topPicksLoading.value = false
                return@launch
            }
            _topPicksLoading.value = _topPicks.value == null
            val picks = buildTopPicks()?.takeIf { it.isNotEmpty() }
            if (identity != listenerKey()) return@launch
            _topPicksLoading.value = false
            if (picks == null) {
                if (_topPicks.value == null) _topPicks.value = emptyList()
                return@launch
            }
            _topPicks.value = picks
            topPicksSlotShown = slot
            withContext(Dispatchers.IO) {
                runCatching {
                    file.writeText(TopPicks.encode(TopPicks.Stored(identity, slot, picks, AppleArtistPhotos.snapshot())))
                }
            }
        }
    }

    /** The hour the row on screen was built in, e.g. `2026-10-21T15`. */
    private var topPicksSlotShown: String? = null

    /**
     * Called when the app comes back to the foreground: if the clock has
     * crossed into a new hour since the row on screen was built, build the
     * next one. A no-op otherwise, and while one is building.
     */
    fun refreshTopPicksIfStale() {
        if (AudioCache.isOffline()) return
        personalizedHome.refresh()
        if (_signedIn.value && _library.value !is UiState.Loading) loadLibrary()
        if (topPicksJob?.isActive == true) return
        if (topPicksSlotShown == null || topPicksSlotShown == topPicksSlot()) return
        loadTopPicks(listenerKey(), rebuild = false)
    }

    /** Which hour of the clock [now] falls in: the date and the hour, 00 to 23. */
    private fun topPicksSlot(now: LocalDateTime = LocalDateTime.now()): String =
        "${now.toLocalDate()}T%02d".format(now.hour)

    private suspend fun buildTopPicks(): List<TopPick>? {
        // Home's first page may already hold the account's mixes; give it a
        // moment to land so the builder reads them off it instead of asking.
        val shelves = withTimeoutOrNull(8_000L) { _home.first { it !is UiState.Loading } }
            .let { (it as? UiState.Success)?.data.orEmpty() }
        return try {
            TopPicks.build(shelves, localTaste())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BitChord", "topPicks failed: ${e.message}")
            null
        }
    }

    /** This app's own Replay chart for the month — Top Picks' second opinion, and all it has signed out. */
    private suspend fun localTaste(): TopPicks.LocalTaste {
        val month = runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull()
            ?: return TopPicks.LocalTaste()
        return TopPicks.LocalTaste(
            artists = month.artists.take(10).map { ArtistRef(name = it.title, browseId = it.browseId) },
            songs = month.songs.take(5).map { it.song },
            genres = month.genres.take(3).map { it.title },
        )
    }

    /**
     * One of the parallel first-page requests has finished. Ignored once a newer
     * load has taken over, which has already reset the count for its own fan-out.
     */
    private fun homeShelfRequestSettled(identity: String?, generation: Long) {
        if (!isCurrentHomeLoad(identity, generation)) return
        _homePendingShelves.value = (_homePendingShelves.value - 1).coerceAtLeast(0)
    }

    private fun isCurrentHomeLoad(identity: String?, generation: Long) =
        identity == listenerKey() && generation == homeLoadGeneration.get()

    private suspend fun publishHomeShelves(shelves: List<HomeShelf>, prepend: Boolean = false) {
        val tuned = retuneRecommendations(shelves)
        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        if (prepend) {
            // YouTube's core home can also contain a stale "Recently played"
            // shelf. The dedicated history endpoint wins and replaces it.
            val replacing = tuned.map { it.title.lowercase(Locale.ROOT) }.toSet()
            homeSeenTitles.addAll(replacing)
            _home.value = UiState.Success(tuned + existing.filterNot {
                it.title.lowercase(Locale.ROOT) in replacing
            })
            return
        }
        val added = tuned.withoutRepeatsOf(existing)
            .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
        if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
    }

    /**
     * The home feed's "for you" shelves, rebuilt around what this device
     * actually listens to.
     *
     * YouTube's own "Trending for you" is a global popularity list with the
     * listener's name on it: the same major-label rows for everybody, which is
     * why it reads as junk next to a feed that knows what you played yesterday.
     * The rows are therefore filtered by taste — a shelf item survives only when
     * one of its credits is an artist in [TasteProfile][com.music.bitchord.data.personalization.TasteProfile],
     * and the survivors are ordered by how strongly that taste is held. Disliked
     * recordings are dropped outright.
     *
     * A shelf that cannot fill itself from taste is topped up from the radio of
     * the listener's strongest track, which is a different question of YouTube
     * and returns related recordings rather than the chart. If even that leaves
     * too few rows, the shelf is removed: an empty section is better than one
     * that recommends nothing the listener would play.
     *
     * Only shelves whose titles announce themselves as recommendations are
     * touched. "Listen again", "New releases" and the mood shelves are either
     * already personal or deliberately editorial, and reranking them would be
     * answering a question nobody asked.
     */
    private suspend fun retuneRecommendations(shelves: List<HomeShelf>): List<HomeShelf> {
        val taste = _personalizedHome.value.profile.tracks
            .filter { it.song.videoId.isNotBlank() }
            .sortedByDescending { it.score }
            .take(TASTE_ARTIST_POOL)
        if (taste.size < MIN_TASTE_TRACKS) return shelves
        val disliked = TrackRatingStore.dislikedSongs()
        return shelves.mapNotNull { shelf ->
            if (!shelf.looksLikeRecommendations()) return@mapNotNull shelf
            val ranked = shelf.items.asSequence()
                .filter { it.videoId != null }
                .filterNot { TrackRatingStore.isDisliked(it.asSong(), disliked) }
                .map { item -> item to tasteAffinity(item, taste) }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .toList()
            val items = if (ranked.size >= MIN_RECOMMENDED_ITEMS) {
                ranked.take(MAX_RECOMMENDED_ITEMS)
            } else {
                ranked + radioTopUp(ranked, taste, disliked)
            }
            if (items.size < MIN_RECOMMENDED_ITEMS) null else shelf.copy(items = items)
        }
    }

    /** How strongly [item]'s credits match something in [taste]; zero when nothing does. */
    private fun tasteAffinity(
        item: ShelfItem,
        taste: List<com.music.bitchord.data.personalization.TasteTrack>,
    ): Double {
        val title = TrackText.titleKey(item.title)
        var best = 0.0
        for (entry in taste) {
            val sameArtist = TrackMatcher.sharesArtist(item.subtitle, entry.song.artist)
            val sameTitle = title.isNotBlank() && title == TrackText.titleKey(entry.song.title)
            when {
                sameArtist && sameTitle -> return RECORDING_AFFINITY + entry.score
                sameArtist -> best = maxOf(best, entry.score)
            }
        }
        return best
    }

    /**
     * Related recordings for a shelf that taste alone cannot fill.
     *
     * The seed is the strongest track the listener has, and the answer is
     * YouTube's related queue for it — the same source AutoPlay uses. Bounded
     * in time because this runs while the page is being drawn: a radio that
     * takes longer than the budget is not worth holding the feed for.
     */
    private suspend fun radioTopUp(
        alreadyRanked: List<ShelfItem>,
        taste: List<com.music.bitchord.data.personalization.TasteTrack>,
        disliked: List<Song>,
    ): List<ShelfItem> {
        val seed = taste.firstOrNull()?.song ?: return emptyList()
        val related = withTimeoutOrNull(RECOMMEND_RADIO_TIMEOUT_MS) {
            YtMusicRepository.radio(seed.videoId).getOrNull()
        }.orEmpty()
        val seen = alreadyRanked.mapNotNull { it.videoId }.toSet() + seed.videoId
        return related.asSequence()
            .filterNot { TrackRatingStore.isDisliked(it, disliked) }
            .filterNot { it.videoId in seen }
            .distinctBy { it.videoId }
            .take(MAX_RECOMMENDED_ITEMS)
            .map { song ->
                ShelfItem(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = null,
                    isVideo = song.isVideo,
                )
            }
            .toList()
    }

    /** Whether a shelf's title announces it as a recommendation rather than a section. */
    private fun HomeShelf.looksLikeRecommendations(): Boolean {
        val key = title.lowercase(Locale.ROOT)
        return RECOMMENDATION_TITLES.any { key.contains(it) }
    }

    private fun ShelfItem.asSong(): Song = Song(
        videoId = videoId.orEmpty(),
        title = title,
        artist = subtitle,
        thumbnailUrl = thumbnailUrl,
    )

    /** Refreshes the core feed without blanking the current Play page first. */
    private suspend fun refreshHome(identity: String?) = coroutineScope {
        personalizedHome.refresh(force = true)
        val recent = if (_signedIn.value) async { YtMusicRepository.homeRecentlyPlayed() } else null
        // Top Picks keeps to its hour; a pull refreshes the feed, not the
        // picks, unless the hour has already turned over.
        if (topPicksJob?.isActive != true) loadTopPicks(identity, rebuild = false)
        YtMusicRepository.home().onSuccess { feed ->
            if (identity != listenerKey()) return@onSuccess
            homeContinuation = feed.continuation
            homeSeenTitles.clear()
            val shelves = retuneRecommendations(feed.shelves).withoutRepeatsOf(emptyList())
                .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
            if (shelves.isNotEmpty()) _home.value = UiState.Success(shelves)
        }
        recent?.await()?.onSuccess { shelf ->
            if (identity == listenerKey()) shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
        }
    }

    /**
     * Called as the Home list nears its end. A no-op while a page is already
     * in flight, once the feed is exhausted, or before the first page has
     * loaded — [homeContinuation] covers all three by construction.
     */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (_homeLoadingMore.value) return
        val identity = listenerKey()
        _homeLoadingMore.value = true
        viewModelScope.launch {
            YtMusicRepository.moreHome(token).onSuccess { feed ->
                if (identity == listenerKey()) {
                    val existing = (_home.value as? UiState.Success)?.data ?: emptyList()
                    val added = retuneRecommendations(feed.shelves).withoutRepeatsOf(existing)
                        .filter { homeSeenTitles.add(it.title.lowercase(Locale.ROOT)) }
                    // A page with nothing new signals the feed has looped back on
                    // itself rather than run dry with a token still attached —
                    // treat it the same as exhausted so scrolling can't spin here.
                    homeContinuation = feed.continuation.takeIf { added.isNotEmpty() }
                    if (added.isNotEmpty()) _home.value = UiState.Success(existing + added)
                }
            }
            if (identity == listenerKey()) _homeLoadingMore.value = false
        }
    }

    fun loadLibrary() {
        if (!_signedIn.value) return
        val identity = listenerKey()
        _library.value = UiState.Loading
        viewModelScope.launch { fetchLibrary(identity) }
    }

    private suspend fun fetchLibrary(identity: String?) {
        val tasteIdentity = tasteListenerKey()
        val next = YtMusicRepository.library().fold(
            onSuccess = { page ->
                // Liked Music is published with just its first page on the tab;
                // the rest of the collection is synced into LikeState here, in
                // this ViewModel's scope, so it is cancelled with the screen and
                // a liked track past the first page still reads as liked.
                page.likedContinuation?.let { token -> syncLikedMusic(identity, token) }
                if (page.isEmpty) UiState.Error(text(R.string.library_empty))
                else UiState.Success(page.copy(likedContinuation = null))
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
        if (identity == listenerKey() && tasteIdentity == tasteListenerKey()) {
            _library.value = next
            personalizedHome.updateLibrary((next as? UiState.Success)?.data, tasteIdentity)
        }
    }

    /**
     * Follows Liked Music's continuation chain to exhaustion, seeding each
     * page's ids into [LikeState] so every liked track reads as liked.
     *
     * Scoped to [viewModelScope] — a re-fetch of the library cancels and
     * replaces it, and it dies with the screen. It only ever seeds ids, never
     * retaining the full songs for pages already behind the tab.
     *
     * [identity] is the listener this token belongs to, checked before every
     * page: [LikeState] is a single shared map, not scoped per account, so a
     * sync still in flight when the listener switches must stop rather than
     * go on seeding the old account's likes into the new one's session.
     */
    private fun syncLikedMusic(identity: String?, token: String) {
        likedSyncJob?.cancel()
        likedSyncJob = viewModelScope.launch {
            YtMusicRepository.syncLikedMusic(token) { next ->
                if (identity != listenerKey()) null else YtMusicRepository.moreSongs(next).getOrNull()
            }
        }
    }

    /**
     * The account's listening history.
     *
     * Loaded on each visit rather than cached: it is a page whose whole subject
     * is what happened most recently, and one that opened showing the state it
     * was in last time would be answering a different question. Guests get the
     * signed-out message straight away, since there is no account to have a
     * history on.
     */
    fun loadHistory() {
        if (!_signedIn.value) {
            _history.value = UiState.Error(text(R.string.history_sign_in_required))
            return
        }
        val identity = listenerKey()
        _history.value = UiState.Loading
        viewModelScope.launch {
            val next = YtMusicRepository.history().fold(
                onSuccess = { songs ->
                    if (songs.isEmpty()) UiState.Error(text(R.string.history_empty))
                    else UiState.Success(songs)
                },
                onFailure = { UiState.Error(it.friendly()) },
            )
            if (identity == listenerKey()) _history.value = next
        }
    }

    /** Removes a track from the account's listening history via its feedback token. Optimistically removes it from the in-memory list on success; leaves the list untouched on failure. */
    fun removeFromHistory(token: String, onSuccess: () -> Unit = {}, onFailure: (Throwable) -> Unit = {}) {
        viewModelScope.launch {
            YtMusicRepository.removeFromHistory(token).onSuccess {
                // Optimistically remove only the first entry whose feedbackToken matches, so the list updates immediately without a loading flash
                val current = _history.value
                if (current is UiState.Success) {
                    _history.value = current.copy(data = current.data.filterNot { it.feedbackToken == token })
                }
                // Invalidate the in-memory history cache so the next load fetches fresh data from YouTube
                YtMusicRepository.invalidateHistoryCache()
                onSuccess()
            }.onFailure {
                // Keep the existing history list untouched — do NOT replace it with an error
                Log.w("BitChord", "removeFromHistory failed: ${it.message}")
                onFailure(it)
            }
        }
    }

    /** Recent searches, kept on device as rich entity records. */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    /**
     * Records a full entity payload in the history — used when the user taps
     * a suggestion, result row, or typeahead card so the recents list shows
     * real artwork and metadata instead of raw text.
     */
    fun recordEntity(entity: SearchHistoryEntity) = SearchHistory.record(entity)

    /**
     * Records a track entity from a search hit.
     * Checks _typeaheadResults first (live media while typing), then _results
     * (committed search). Falls back to recording the raw query if no song data
     * is available.
     */
    fun recordSearch() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        // Prefer typeahead results — they are live and always available while typing.
        val fromTypeahead = _typeaheadResults.value.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (fromTypeahead is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        if (fromTypeahead is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = fromTypeahead.song.videoId,
                title = fromTypeahead.song.title,
                subtitle = listOfNotNull(fromTypeahead.song.artist).joinToString(" · "),
                artworkUrl = fromTypeahead.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
            return
        }
        // Fall back to committed search results.
        val topResult = (_results.value as? UiState.Success)?.data?.firstOrNull {
            it is SearchResult.TopTrack || it is SearchResult.Track
        }
        if (topResult is SearchResult.Track) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else if (topResult is SearchResult.TopTrack) {
            recordEntity(SearchHistoryEntity(
                id = topResult.song.videoId,
                title = topResult.song.title,
                subtitle = listOfNotNull(topResult.song.artist).joinToString(" · "),
                artworkUrl = topResult.song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ))
        } else {
            // Fallback: record as a raw-text entity for backwards compatibility
            recordEntity(SearchHistoryEntity(
                id = "q:$q",
                title = q,
                subtitle = "",
                artworkUrl = null,
                entityType = EntityType.TRACK,
            ))
        }
    }

    /**
     * Runs a term the user picked out of a list rather than typed — a recent
     * search, or one of [suggestions] — and floats it to the top of the
     * history. Picking is as deliberate as submitting, so it searches on the
     * spot.
     *
     * Recording happens downstream via [recordSearch] when the user actually
     * interacts with a result (taps a song, album, or artist card), which
     * ensures the saved entity carries real artwork and metadata instead of
     * a blank placeholder.
     */
    fun searchFor(term: String) {
        _query.value = term
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        // Picking a suggestion or history item is deliberate, but it must NOT
        // pollute the user's YTMusic server-side search history — only an
        // explicit keyboard submission should do that.  Use the anonymous
        // endpoint so YouTube does not log this query against the account.
        when (_searchSource.value) {
            SearchSource.YOUTUBE -> runSearch(anonymous = true)
            SearchSource.ADDONS -> runAddonSearch(term.trim())
            SearchSource.ALL,
            SearchSource.YANDEX,
            SearchSource.SPOTIFY,
            SearchSource.SOUNDCLOUD,
            SearchSource.VK -> runPlatformSearch(term.trim())
            SearchSource.LIBRARY -> Unit
        }
    }

    fun removeSearch(id: String) = SearchHistory.remove(id)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onQueryChange(newValue: String) {
        _query.value = newValue
        if (newValue.isBlank()) {
            searchSubmitted = false
            _suggestions.value = emptyList()
            _typeaheadResults.value = emptyList()
            _results.value = null
            cancelAddonSearch()
            cancelPlatformSearch()
            // The list is about to switch from search results to recent
            // searches (or the empty state) — without this it can keep
            // whatever scroll offset the results list was left at, landing
            // the new, much shorter list somewhere other than the top.
            _searchScrollReset.value += 1
            return
        }
        // Reset the submission gate so typeahead pipelines fire again.
        searchSubmitted = false
        // The Library source answers from the device on every keystroke (see
        // [libraryResults]); YouTube's completions have nothing to add to it.
        when (_searchSource.value) {
            SearchSource.ALL,
            SearchSource.YANDEX,
            SearchSource.SPOTIFY,
            SearchSource.SOUNDCLOUD,
            SearchSource.VK -> {
                cancelPlatformSearch()
                return
            }
            SearchSource.ADDONS -> {
                cancelAddonSearch()
                return
            }
            SearchSource.LIBRARY -> return
            SearchSource.YOUTUBE -> Unit
        }
        // While typing, surface text completions — the pipeline already feeds
        // them through [suggestRequests] and publishes results via typeahead.
        suggestRequests.tryEmit(newValue)
    }

    /**
     * Commits the current query text: clears suggestions/typeahead, runs the
     * full search, and records the term in history for future recall.
     */
    fun submitSearch() {
        val q = _query.value.trim()
        if (q.isEmpty() || _searchSource.value == SearchSource.LIBRARY) return
        // No connection: the catalogue cannot answer, so the same box answers
        // from the downloads and the device library instead of spinning and
        // then failing.
        if (AudioCache.isOffline()) {
            runOfflineSearch(q)
            return
        }
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        when (_searchSource.value) {
            SearchSource.ADDONS -> runAddonSearch(q)
            SearchSource.ALL,
            SearchSource.YANDEX,
            SearchSource.SPOTIFY,
            SearchSource.SOUNDCLOUD,
            SearchSource.VK -> runPlatformSearch(q)
            SearchSource.YOUTUBE -> runSearch(anonymous = false)
            SearchSource.LIBRARY -> Unit
        }
    }

    /**
     * The search that works with no connection: downloads first, then the
     * files on the device.
     *
     * Both are read here rather than cached, because either can have changed
     * since the last search — a track finished downloading while the field was
     * open. The source chips move to YouTube, which is the list these rows are
     * drawn in; leaving the source on a platform that cannot answer would show
     * an empty page next to the results.
     */
    private fun runOfflineSearch(query: String) {
        searchSubmitted = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        _addonResults.value = null
        _platformResults.value = null
        cancelPlatformSearch()
        cancelAddonSearch()
        if (_searchSource.value != SearchSource.YOUTUBE) _searchSource.value = SearchSource.YOUTUBE
        _searchScrollReset.value += 1
        _results.value = UiState.Loading
        val tokens = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val downloaded = runCatching { Downloads.getDownloadedSongs(context) }
                .getOrDefault(emptyList())
            val device = if (LocalMediaRepository.hasStoragePermission(context)) {
                runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val found = (downloaded + device)
                .distinctBy { it.videoId }
                .filter { song ->
                    val haystack = "${song.title} ${song.artist}".lowercase(Locale.ROOT)
                    tokens.all { it in haystack }
                }
            if (_query.value.trim() != query) return@launch
            _results.value = if (found.isEmpty()) {
                UiState.Error(text(R.string.offline_search_empty))
            } else {
                UiState.Success(found.map { SearchResult.Track(it) })
            }
        }
    }

    private fun runPlatformSearch(q: String) {
        cancelPlatformSearch()
        val generation = platformSearchGeneration
        _results.value = null
        _addonResults.value = null
        _platformResults.value = UiState.Loading
        _searchScrollReset.value += 1
        val source = _searchSource.value
        platformSearchJob = viewModelScope.launch {
            val next = try {
                when (source) {
                    SearchSource.ALL -> searchAllPlatforms(q)
                    SearchSource.YANDEX -> searchYandex(q, PLATFORM_SEARCH_LIMIT)
                    SearchSource.SPOTIFY -> searchSpotify(q, PLATFORM_SEARCH_LIMIT)
                    SearchSource.SOUNDCLOUD -> searchSoundCloud(q, SOUNDCLOUD_PAGE_SIZE)
                    SearchSource.VK -> searchVk(q, PLATFORM_SEARCH_LIMIT)
                    else -> emptyList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == platformSearchGeneration && source == _searchSource.value) {
                    _platformResults.value = UiState.Error(error.message ?: text(R.string.failed))
                }
                return@launch
            }
            currentCoroutineContext().ensureActive()
            if (generation == platformSearchGeneration && source == _searchSource.value && q == _query.value.trim()) {
                _platformResults.value = UiState.Success(next)
            }
        }
    }

    private fun cancelPlatformSearch() {
        platformSearchGeneration++
        platformSearchJob?.cancel()
        platformSearchJob = null
        platformLoadMoreJob?.cancel()
        platformLoadMoreJob = null
        _platformLoadingMore.value = false
        soundCloudSearchQuery = null
        soundCloudNextOffset = null
        soundCloudNextHref = null
        _platformResults.value = null
    }

    fun loadMorePlatformResults() {
        if (_searchSource.value != SearchSource.SOUNDCLOUD || platformLoadMoreJob?.isActive == true) return
        val query = soundCloudSearchQuery ?: return
        val offset = soundCloudNextOffset ?: return
        val generation = platformSearchGeneration
        platformLoadMoreJob = viewModelScope.launch {
            _platformLoadingMore.value = true
            try {
                val page = withTimeoutOrNull(SOUNDCLOUD_SEARCH_TIMEOUT_MS) {
                    SoundCloudPlaylistImporter.searchPage(
                        query = query,
                        offset = offset,
                        limit = SOUNDCLOUD_PAGE_SIZE,
                        continuationUrl = soundCloudNextHref,
                    )
                } ?: return@launch
                currentCoroutineContext().ensureActive()
                if (generation != platformSearchGeneration || _searchSource.value != SearchSource.SOUNDCLOUD ||
                    _query.value.trim() != query
                ) return@launch
                val current = (_platformResults.value as? UiState.Success<List<Song>>)?.data.orEmpty()
                val added = page.tracks.mapIndexed { index, track -> track.toSearchSong(current.size + index) }
                _platformResults.value = UiState.Success((current + added).distinctBy { it.videoId })
                soundCloudNextOffset = page.nextOffset
                soundCloudNextHref = page.nextHref
            } finally {
                if (generation == platformSearchGeneration) _platformLoadingMore.value = false
            }
        }
    }

    private suspend fun searchAllPlatforms(query: String): List<Song> = coroutineScope {
        val youtube = async {
            val rows = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            currentCoroutineContext().ensureActive()
            rows.mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    is SearchResult.Browse -> null
                }
            }
        }
        val yandex = async {
            val token = AppSettings.yandexMusicToken.value
            // Anonymous callers get the public endpoint, so Yandex contributes to
            // "all platforms" without an account instead of silently vanishing.
            optionalPlatformRows {
                if (token.isBlank()) {
                    YandexMusicApi().searchTracksAnonymous(query, ALL_PLATFORM_PER_SOURCE)
                } else {
                    YandexMusicApi().searchTracks(token, query, ALL_PLATFORM_PER_SOURCE)
                }
            }
        }
        val spotify = async {
            if (AppSettings.spotifySpdcToken.value.isBlank()) emptyList<Song>() else optionalPlatformRows {
                SpotifyLibrary.searchTracks(query, ALL_PLATFORM_PER_SOURCE).map(SpotifyTrack::toSourceSong)
            }
        }
        val soundCloud = async {
            optionalPlatformRows {
                withTimeoutOrNull(SOUNDCLOUD_SEARCH_TIMEOUT_MS) {
                    SoundCloudPlaylistImporter.search(query, ALL_PLATFORM_PER_SOURCE)
                }.orEmpty().mapIndexed { index, track -> track.toSearchSong(index) }
            }
        }
        val vk = async {
            optionalPlatformRows { VkMusicApi.search(query, ALL_PLATFORM_PER_SOURCE) }
        }
        val yandexSongs = ExternalPlaylistImporter.resolve(yandex.await()) { _, _ -> }.first
        currentCoroutineContext().ensureActive()
        mergePlatformSearchRows(spotify.await() + soundCloud.await() + vk.await() + youtube.await() + yandexSongs)
    }

    /** One recording, one row; keep a real provider identity over its catalogue duplicate. */
    private fun mergePlatformSearchRows(rows: List<Song>): List<Song> {
        val merged = mutableListOf<Song>()
        rows.forEach { candidate ->
            val index = merged.indexOfFirst { TasteProfiles.sameRecording(it, candidate) }
            if (index < 0) {
                merged += candidate
            } else if (platformIdentityPriority(candidate) > platformIdentityPriority(merged[index])) {
                merged[index] = candidate
            }
        }
        return merged
    }

    private fun platformIdentityPriority(song: Song): Int = when {
        song.isSpotifySourceTrack || song.playbackSource == "Spotify" -> 3
        song.videoId.startsWith("soundcloud:") || song.playbackSource == "SoundCloud" -> 2
        song.isVkSourceTrack || song.playbackSource == "VK Music" -> 2
        else -> 1
    }

    private suspend fun <T> optionalPlatformRows(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun searchYandex(query: String, limit: Int): List<Song> {
        val token = AppSettings.yandexMusicToken.value
        // Without an account the public search endpoint still answers; the rows
        // are then matched to playable recordings like any other provider.
        val tracks = if (token.isNotBlank()) {
            YandexMusicApi().searchTracks(token, query, limit)
        } else {
            YandexMusicApi().searchTracksAnonymous(query, limit)
        }
        return ExternalPlaylistImporter.resolve(tracks) { _, _ -> }.first
    }

    private suspend fun searchSpotify(query: String, limit: Int): List<Song> {
        val cached = cachedSpotifySongs(query, limit, likedOnly = false)
        if (AppSettings.spotifySpdcToken.value.isBlank()) {
            check(cached.isNotEmpty()) { text(R.string.tap_to_connect) }
            return cached
        }
        return try {
            SpotifyLibrary.searchTracks(query, limit).map(SpotifyTrack::toSourceSong).ifEmpty { cached }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cached
        }
    }

    private fun cachedSpotifySongs(query: String, limit: Int, likedOnly: Boolean): List<Song> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val rows = if (likedOnly) _personalizedHome.value.profile.recentLikes
        else _personalizedHome.value.profile.tracks
        return rows.asSequence()
            .filter { HomeSource.SPOTIFY in it.sources && it.song.isSpotifySourceTrack }
            .map { it.song }
            .filter { song ->
                val text = listOf(song.title, song.artist, song.albumName.orEmpty()).joinToString(" ").lowercase()
                terms.all(text::contains)
            }
            .distinctBy(Song::videoId)
            .take(limit)
            .toList()
    }

    private suspend fun searchSoundCloud(query: String, limit: Int): List<Song> {
        val page = withTimeoutOrNull(SOUNDCLOUD_SEARCH_TIMEOUT_MS) {
            SoundCloudPlaylistImporter.searchPage(query, offset = 0, limit = limit)
        } ?: error(text(R.string.couldnt_load_tracks))
        // Do not make the search spinner wait for up to 25 stream resolutions.
        // Metadata appears immediately; the selected row's native SoundCloud
        // AAC-HLS stream is resolved on demand in MainActivity.
        soundCloudSearchQuery = query
        soundCloudNextOffset = page.nextOffset
        soundCloudNextHref = page.nextHref
        return page.tracks.mapIndexed { index, track -> track.toSearchSong(index) }
    }

    private suspend fun searchVk(query: String, limit: Int): List<Song> {
        check(AppSettings.vkAccessToken.value.isNotBlank()) { text(R.string.tap_to_connect) }
        if (com.music.bitchord.BuildConfig.VK_AUDIO_SCOPE_APPROVED) {
            return VkMusicApi.search(query, limit)
        }
        // VK ID remains useful before VK approves the exceptional audio scope:
        // the dedicated source becomes an explicitly labelled strict fallback,
        // never a claim that these bytes or rows came from VK Music.
        return YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    is SearchResult.Browse -> null
                }
            }
            .take(limit)
            .map { it.copy(playbackSource = "YouTube (VK fallback)") }
    }

    /**
     * Asks every enabled addon for [q] in parallel. A slow or failing addon
     * only costs its own rows; the list is the rest of them, round-robin so
     * no single catalogue buries the others.
     */
    private fun runAddonSearch(q: String) {
        SearchHistory.recordAddonQuery(q)
        cancelAddonSearch()
        val generation = addonSearchGeneration
        _searchScrollReset.value += 1
        // Every enabled addon, including ones the connection's quality setting
        // skips for playback: their rows are still worth seeing, and the
        // search page dims them and says why on a tap — see `isBlocked` there.
        val addons = SourceRegistry.active().filterIsInstance<AddonSource>()
        if (addons.isEmpty()) {
            _addonResults.value = UiState.Error(text(R.string.search_addons_none))
            return
        }
        _addonResults.value = UiState.Loading
        addonSearchJob = viewModelScope.launch {
            val perAddon = coroutineScope {
                addons.map { addon ->
                    async {
                        withTimeoutOrNull(ADDON_SEARCH_TIMEOUT_MS) {
                            optionalPlatformRows { addon.search(q, limit = ADDON_SEARCH_LIMIT) }
                        } ?: emptyList()
                    }
                }.map { it.await() }
            }
            val merged = buildList {
                val longest = perAddon.maxOfOrNull { it.size } ?: 0
                for (i in 0 until longest) perAddon.forEach { rows -> rows.getOrNull(i)?.let(::add) }
            }
            currentCoroutineContext().ensureActive()
            if (generation == addonSearchGeneration && _searchSource.value == SearchSource.ADDONS &&
                q == _query.value.trim()
            ) {
                _addonResults.value = UiState.Success(merged)
            }
        }
    }

    private fun cancelAddonSearch() {
        addonSearchGeneration++
        addonSearchJob?.cancel()
        addonSearchJob = null
        _addonResults.value = null
    }

    /**
     * Points the Search tab at YouTube Music or at the Local Music folder.
     *
     * The query carries over: whatever is in the field is answered again by
     * the source just picked, so flipping is a way to compare the two rather
     * than a reset.
     */
    fun setSearchSource(source: SearchSource) {
        if (_searchSource.value == source) return
        _searchSource.value = source
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        cancelPlatformSearch()
        cancelAddonSearch()
        _searchScrollReset.value += 1
        when (source) {
            SearchSource.LIBRARY -> loadLibrarySongs()
            SearchSource.YOUTUBE -> submitSearch()
            SearchSource.ADDONS -> if (_query.value.isNotBlank()) submitSearch()
            SearchSource.ALL,
            SearchSource.YANDEX,
            SearchSource.SPOTIFY,
            SearchSource.SOUNDCLOUD,
            SearchSource.VK -> if (_query.value.isNotBlank()) submitSearch()
        }
    }

    /**
     * Reads the Local Music folder for the Library source. Re-read on every
     * switch to it, so files added since — or a permission granted since —
     * are in the next answer; the last list stays up meanwhile.
     */
    fun loadLibrarySongs() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            _librarySongs.value = if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                runCatching { LocalMediaRepository.getLocalMusic(context) }.fold(
                    onSuccess = { songs ->
                        if (songs.isEmpty()) UiState.Error(text(R.string.no_local_audio_found))
                        else UiState.Success(songs)
                    },
                    onFailure = { UiState.Error(text(R.string.no_local_audio_found)) },
                )
            }
        }
    }

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        searchSubmitted = true
        runSearch()
    }

    /**
     * A search asked for, as a request the pipeline below decides what to do
     * with.
     *
     * [requestId] is what makes a late answer harmless: a response is only
     * written to the screen if its id is still the newest one asked for.
     */
    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
        val anonymous: Boolean = true,
    )

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    /**
     * The results of the longest earlier query this one starts with — near
     * enough to leave up while the narrower search runs.
     */
    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return searchCache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
            ?.rows
    }

    private fun runSearch(anonymous: Boolean = true) {
        val query = _query.value
        if (query.isBlank()) {
            // Nothing in flight can still be waiting to overwrite this: the
            // id it would be checked against has already moved past it.
            newestRequestId.incrementAndGet()
            searchSession = null
            _searchLoadingMore.value = false
            _results.value = null
            return
        }
        val id = newestRequestId.incrementAndGet()
        _searchScrollReset.value += 1
        searchSession = null
        _searchLoadingMore.value = false
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id, anonymous))
    }

    /**
     * The search pipeline, started once and left running for the lifetime of
     * the view model.
     *
     * The point of it being one long-lived collector is that a new search no
     * longer cancels the request before it out of a fresh coroutine.
     * Cancelling a call mid-flight tears down its socket, and on a pooled HTTP
     * client that is felt by whatever picks that connection up next — which is
     * how one search could end in "Software caused connection abort" for a
     * request that was never itself in any trouble.
     *
     * There is no debounce here any more, and nothing to absorb: a search is
     * only ever asked for by a deliberate act — the search button, a
     * suggestion or history row, a filter tab — so the request that arrives is
     * already the one the user meant, and making them wait out a timer for it
     * would be a delay with nothing behind it. Typing asks
     * [startSuggestPipeline] for completions instead and leaves the results
     * alone.
     */
    private fun startSearchPipeline() = viewModelScope.launch {
        searchRequests
            .collectLatest { request ->
                // Offline the answer is built locally by [runOfflineSearch]; a
                // request here would only overwrite it with an error.
                if (AudioCache.isOffline()) return@collectLatest
                val key = cacheKey(request.query, request.filter)
                // Something to look at immediately: the exact answer if this
                // query has been run before, otherwise the closest earlier
                // one. Only fall back to a spinner with neither.
                val exact = searchCache.get(key)
                val cached = exact?.rows ?: prefixMatch(request.query, request.filter)
                _results.value = cached?.let { UiState.Success(it) } ?: UiState.Loading
                // An explicit submit still has to reach the server even when
                // an anonymous lookup cached the same query: that call is what
                // records it in the account's history.
                if (exact != null && request.anonymous) {
                    searchSession = SearchSession(key, request.requestId, request.filter, exact.continuation)
                    return@collectLatest
                }

                // Search is YouTube's alone. A module is a *substitution*
                // layer, not a catalogue to browse: it never has cover art,
                // radio, related tracks or an album page, so its rows arrived
                // in the results list looking like YouTube's and then behaved
                // nothing like them. Every track found here takes the ordinary
                // YouTube path and is handed to the module at playback time —
                // see [SourceResolver.substituteForYouTube] — which upgrades
                // the ones it holds without any of them having to be a
                // separate row to pick between.
                val result = YtMusicRepository.searchPage(request.query, request.filter, request.anonymous)
                // A search that has been superseded shouldn't land on screen,
                // whether it succeeded or failed.
                if (request.requestId != newestRequestId.get()) return@collectLatest
                _results.value = result.fold(
                    onSuccess = { page -> published(page, key, request.requestId) },
                    onFailure = { failure -> UiState.Error(failure.friendly()) },
                )
            }
    }

    /**
     * Continues the visible search only when the list reaches its end. This is
     * deliberately separate from the first-page request: waiting for every
     * continuation was the reason a search sat on a spinner for seconds.
     */
    fun loadMoreSearchResults() {
        val session = searchSession ?: return
        val token = session.continuation ?: return
        if (_searchLoadingMore.value) return
        _searchLoadingMore.value = true
        viewModelScope.launch {
            val next = YtMusicRepository.searchContinuation(token, session.filter)
            val stillCurrent = searchSession == session && session.requestId == newestRequestId.get()
            if (stillCurrent) {
                next.onSuccess { page ->
                    val current = (_results.value as? UiState.Success)?.data.orEmpty()
                    val merged = (current + page.rows).distinctBy(::searchResultKey)
                    searchCache.put(session.key, SearchCacheEntry(merged, page.continuation))
                    searchSession = session.copy(continuation = page.continuation)
                    _results.value = UiState.Success(merged)
                }
                _searchLoadingMore.value = false
            }
        }
    }

    /**
     * The typeahead pipeline, alongside [startSearchPipeline] and for the same
     * structural reason — one long-lived collector rather than a coroutine per
     * keystroke, so a lookup the user has typed past doesn't take a pooled
     * socket down with it.
     *
     * This one *does* debounce, and that isn't the timer that was taken off the
     * search. It's two orders of magnitude shorter, and it's paid for by the
     * request behind it being a few hundred bytes rather than a full page of
     * results — a burst of keystrokes shouldn't each cost a round trip, but the
     * gap has to be short enough that the list is up before the next letter is
     * typed. Nothing is waiting on it either way: the row the user typed is
     * already on screen from the keystroke itself.
     *
     * A failure is left on the floor. There is no worthwhile way to report
     * "couldn't suggest anything" in a list of suggestions, and the typed text
     * is standing there as a working first row regardless.
     */
    @OptIn(FlowPreview::class)
    private fun startSuggestPipeline() = viewModelScope.launch {
        // Whether a list for [input] is still wanted. False once the field has
        // moved on: typed further, or searched — which empties [_suggestions],
        // and a late answer writing to it would reopen the suggestions over
        // the results the user is by then reading.
        fun stillWanted(input: String) = _query.value == input && !searchSubmitted

        suggestRequests
            .debounce(SUGGEST_DEBOUNCE_MS)
            .collectLatest { input ->
                if (AudioCache.isOffline()) return@collectLatest
                if (!stillWanted(input)) return@collectLatest
                val fetched = YtMusicRepository.searchSuggestions(input).getOrNull()
                    ?: return@collectLatest
                // Asked again on the way back; the field is live throughout.
                if (!stillWanted(input)) return@collectLatest
                _suggestions.value = listOf(input) +
                    fetched.filterNot { it.equals(input, ignoreCase = true) }
            }
    }

    /**
     * Parallel pipeline that fetches live media results (tracks, artists,
     * albums) for the current query text. Runs alongside [startSuggestPipeline]
     * with its own debounce so a fast typist doesn't saturate the network.
     */
    @OptIn(FlowPreview::class)
    private fun startTypeaheadMediaPipeline() = viewModelScope.launch {
        suggestRequests
            .debounce(TYPEAHEAD_MEDIA_DEBOUNCE_MS)
            .collectLatest { input ->
                if (AudioCache.isOffline()) return@collectLatest
                if (input.isBlank()) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                // Only show media results while the user is still typing — not
                // reading committed search results. The query-text check on its
                // own isn't enough: a late suggestion callback can repopulate
                // _suggestions after submission, and we must not re-open the
                // typeahead dropdown under an already-committed search.
                if (searchSubmitted) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                val result = YtMusicRepository.searchTypeahead(input).getOrNull()
                // If the field moved on, drop the result silently.
                if (_query.value != input) {
                    _typeaheadResults.value = emptyList()
                    return@collectLatest
                }
                // Cap results so the dropdown doesn't grow unbounded.
                _typeaheadResults.value = result?.rows.orEmpty().take(TYPEAHEAD_MAX_RESULTS)
            }
    }

    /** Caches and publishes the initial result page without waiting for later pages. */
    private fun published(
        page: YtMusicRepository.SearchPage,
        key: String,
        requestId: Long,
    ): UiState<List<SearchResult>> {
        val rows = page.rows
        if (rows.isEmpty()) return UiState.Error(text(R.string.no_results))
        searchCache.put(key, SearchCacheEntry(rows, page.continuation))
        searchSession = SearchSession(key, requestId, _filter.value, page.continuation)
        prefetchTopResult(rows)
        return UiState.Success(rows)
    }

    private fun searchResultKey(row: SearchResult): String = when (row) {
        is SearchResult.TopTrack -> "v:${row.song.videoId}"
        is SearchResult.Track -> "v:${row.song.videoId}"
        is SearchResult.Browse -> "b:${row.item.browseId}"
    }

    /**
     * The enabled non-YouTube sources, asked at the same time and returned
     * split at YouTube's own place in the order.
     *
     * The split is what makes the Sources screen's ordering visible where it
     * matters most. A library server ranked above YouTube puts its own copies
     * at the top of the results — which is the whole point of ranking it there —
     * and one ranked below appears under them instead.
     *
     * Only the Songs filter fans out: albums, artists and playlists are
     * browse-shaped, and [MusicSource] deliberately answers for tracks only.
     *
     * Asked of the playback list rather than every enabled source, so a result
     * offered here is one this connection's ceiling would actually let play —
     * a row that can only be tapped into a YouTube stream is a lie about where
     * the track is coming from.
     */
    private suspend fun sourceResults(
        query: String,
        filter: SearchFilter,
    ): Pair<List<SearchResult>, List<SearchResult>> = coroutineScope {
        if (filter != SearchFilter.SONGS) return@coroutineScope emptyList<SearchResult>() to emptyList()
        val active = SourceRegistry.activeForPlayback()
        val youtubeRank = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
            .let { if (it < 0) active.size else it }

        val answers = active
            .filter { it.kind != SourceKind.YOUTUBE }
            .map { source ->
                source to async {
                    // Per-source, so one slow or unreachable server delays the
                    // results by at most this much rather than for as long as
                    // its socket takes to give up.
                    runCatching {
                        withTimeout(SOURCE_SEARCH_TIMEOUT_MS) { source.search(query, SOURCE_SEARCH_LIMIT) }
                    }.getOrDefault(emptyList())
                }
            }

        val above = mutableListOf<SearchResult>()
        val below = mutableListOf<SearchResult>()
        answers.forEach { (source, job) ->
            val rows = job.await().map { SearchResult.Track(it) }
            val rank = active.indexOfFirst { it.configId == source.configId }
            if (rank in 0 until youtubeRank) above += rows else below += rows
        }
        above to below
    }

    /**
     * Warms the stream URL for the top song result the instant results land,
     * not when it's tapped. [AudioCache] gives a head start to whatever's
     * already queued; a fresh search has nothing queued yet, and the top
     * result is overwhelmingly what gets tapped — see [play][MainActivity.play].
     * The exact search result is warmed because video tracks now play their
     * own audio by default; catalogue matching is only requested manually.
     */
    private fun prefetchTopResult(rows: List<SearchResult>) {
        val song = rows.firstNotNullOfOrNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        } ?: return
        // Direct Source Stream fetches nothing before it is played. Warming
        // here would also pin JioSaavn's quick answer ahead of the addon.
        if (DirectSourceGate.isActive()) return
        viewModelScope.launch {
            runCatching {
                val audio = song
                // A source-backed row resolves through its own source already
                // and never takes the YouTube path — warming either half of
                // this for one would be work nothing asks for.
                if (SourceRegistry.parseTrackKey(audio.videoId) != null) return@runCatching
                // JioSaavn first, on the same reasoning as the queue's
                // read-ahead: it is the copy that will actually be played if it
                // has the track, so warming YouTube's URL instead warms the one
                // that loses. Pinned through [StreamChoice] so playback opens
                // this very stream rather than racing for it again — see
                // [SourceResolver.prefetchSubstitute], which requires it.
                val warmed = SourceResolver.prefetchSubstitute(
                    TrackMatcher.Target(
                        title = audio.title,
                        artist = audio.artist,
                        durationSec = TrackMatcher.secondsOf(audio.durationText),
                        album = audio.albumName,
                        isExplicit = audio.isExplicit,
                        isVideo = audio.isVideo,
                    ),
                )
                if (warmed != null) {
                    StreamChoice.remember(audio.videoId, warmed, substituted = true)
                    return@runCatching
                }
                // Disabled, or hasn't got it: the tap path falls back to
                // YouTube, so that is what is worth having ready.
                StreamResolver.resolve(audio.videoId)
            }
        }
    }

    companion object {
        /** Today's Top Picks row, kept so the next launch paints it before any request. */
        private const val TOP_PICKS_FILE = "top_picks_v4.json"

        /** Per-addon budget for the Addons search tab; a slow addon is left out, not waited on. */
        private const val ADDON_SEARCH_TIMEOUT_MS = 12_000L
        private const val ADDON_SEARCH_LIMIT = 20

        private const val PLATFORM_SEARCH_LIMIT = 25
        private const val SOUNDCLOUD_PAGE_SIZE = 50
        private const val ALL_PLATFORM_PER_SOURCE = 8
        private const val SOUNDCLOUD_SEARCH_TIMEOUT_MS = 15_000L

        /** How many taste entries a recommendation shelf is scored against. */
        private const val TASTE_ARTIST_POOL = 60

        /** Below this much listening history there is no taste to retune with. */
        private const val MIN_TASTE_TRACKS = 5

        private const val MIN_RECOMMENDED_ITEMS = 4
        private const val MAX_RECOMMENDED_ITEMS = 20

        /** A row that is the recording itself outranks any artist-only match. */
        private const val RECORDING_AFFINITY = 1_000.0

        /** How long the radio top-up may hold the feed. */
        private const val RECOMMEND_RADIO_TIMEOUT_MS = 4_000L

        /**
         * Titles that mark a shelf as a recommendation rather than a section,
         * matched case-insensitively in the listener's own language.
         */
        private val RECOMMENDATION_TITLES = listOf(
            "тренд", "trending", "для вас", "for you", "хит", "chart", "чарт", "топ",
        )

        /** How many Spotify tracks are looked up on YouTube Music at once. */

        /**
         * How long a keystroke waits before the typeahead is asked about it.
         *
         * Not the search's timer — searches aren't on a timer any more. This
         * one only stops a fast typist spending a round trip per letter, so it
         * wants to be as short as it can be while still collapsing a burst:
         * long enough that "cold" isn't four lookups, short enough that the
         * list is up by the time the thumb has left the key.
         */
        const val SUGGEST_DEBOUNCE_MS = 180L

        /**
         * Debounce for the parallel media-search pipeline. Slightly longer than
         * text suggestions so it doesn't fire on every single keystroke — a
         * full search is heavier than a suggestion request, and the UI only
         * needs a few results to fill the dropdown.
         */
        const val TYPEAHEAD_MEDIA_DEBOUNCE_MS = 350L

        /**
         * Maximum number of live media results shown in the typeahead dropdown.
         * Enough to give variety without making the list unscrollable.
         */
        const val TYPEAHEAD_MAX_RESULTS = 15

        const val SEARCH_CACHE_ENTRIES = 100

        /**
         * How long any one source gets to answer a search.
         *
         * Short on purpose: these run alongside the YouTube search, and their
         * only job is to be *there* when it lands. A home server reached over
         * a VPN that takes eight seconds has effectively not answered, and
         * holding the whole result list for it would make search feel worse
         * for the sake of results the user can still get by searching again.
         */
        const val SOURCE_SEARCH_TIMEOUT_MS = 4000L

        /** Enough to be worth scrolling, short enough not to bury YouTube's own rows. */
        const val SOURCE_SEARCH_LIMIT = 12

        /**
         * What a page with an empty listing says.
         *
         * Named because it is a state one can be got *out* of, not just a
         * message: an own playlist with nothing in it lands here, and adding the
         * first track to it has to be able to tell "this page is empty" apart
         * from "this page failed to load" — see [appendToOpenPlaylist].
         */

        /**
         * What a downloaded playlist's page says once the files under it are
         * gone.
         *
         * A record here outlives the folder it names — the user is expected to
         * manage Downloads with a file manager — so this is a state its page has
         * to be able to reach, not an error. Named because three places say it:
         * the page, its refresh, and the long-press menu that queues it without
         * opening it.
         */

        fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
            // Built for playlists, but an album downloaded whole opens through
            // the same prefix, and its page should read as the album it is.
            browseId.startsWith(Downloads.PLAYLIST_PREFIX) ->
                Downloads.recordIdOf(browseId)
                    ?.let { Downloads.collections.value[it] }
                    ?.takeIf { !it.playlist }
                    ?.let { BrowseType.ALBUM }
                    ?: BrowseType.PLAYLIST
            browseId.startsWith(LocalGroups.PREFIX) -> LocalGroups.typeOf(browseId) ?: fallback
            browseId.startsWith(OfflinePlaylists.PREFIX) -> BrowseType.PLAYLIST
            browseId.startsWith("UC") -> BrowseType.ARTIST
            browseId.startsWith("MPREb") || browseId.startsWith("VLOLAK") || browseId.startsWith("OLAK") -> BrowseType.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
            else -> fallback
        }
    }

    /**
     * A remote file library behind a `local:` page: how to list it and what
     * an empty listing says. One entry per library keeps the detail loader,
     * the refresher and the queue collector from each repeating the switch —
     * a third library adds one line here and nothing anywhere else.
     */
    private data class RemoteLibrary(
        val emptyRes: Int,
        val songs: suspend () -> List<Song>,
    )

    private fun remoteLibrary(browseId: String): RemoteLibrary? = when (browseId) {
        com.music.bitchord.data.webdav.WebDavConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.webdav_empty,
                com.music.bitchord.data.webdav.WebDavRepository::getSongs,
            )
        com.music.bitchord.data.smb.SmbConfig.BROWSE_ID ->
            RemoteLibrary(
                R.string.smb_empty,
                com.music.bitchord.data.smb.SmbRepository::getSongs,
            )
        else -> null
    }

    private suspend fun remoteSongsState(remote: RemoteLibrary): UiState<List<Song>> =
        com.music.bitchord.data.remote.RemoteListing.state(runCatching { remote.songs() }, text(remote.emptyRes))

    /**
     * Re-reads an open remote-library page after its server settings change.
     * A no-op when the page isn't open — the next visit lists fresh anyway.
     */
    private fun reloadRemoteDetail(browseId: String) {
        if (_detailStack.value.any { page -> page.browseId == browseId }) {
            reloadLocalDetail(browseId)
        }
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
    ) {
        // A fast double tap used to push two identical loading pages and launch
        // two identical browse requests. Besides wasting the connection, both
        // completions then raced to update every matching browse id in the
        // stack. The page is pushed synchronously, so this closes that window
        // without suppressing a deliberate revisit after the first page loads.
        if (_detailStack.value.lastOrNull()?.let {
                it.browseId == browseId && it.songs is UiState.Loading
            } == true
        ) return
        if (browseId.startsWith(SPOTIFY_PAGE_PREFIX)) {
            openSpotifyPage(browseId, title, subtitle, thumbnailUrl)
            return
        }
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
        )
        viewModelScope.launch {
            var sections = emptyList<HomeShelf>()
            // Callers that open an artist from a track — the player, the
            // long-press menu — only have that track's cover art and its full
            // credit ("A, B & C") to hand, so the page swaps in the artist's
            // own picture and name once they arrive.
            var artwork: String? = null
            var name: String? = null
            /**
             * The credit line, when the page had to supply its own.
             *
             * Only a link tapped outside the app arrives with neither — see
             * [com.music.bitchord.playback.MusicLink]. Every other caller was
             * looking at a card that already said this.
             */
            var credit: String? = null
            /** Set when the track list carries on past its first response. */
            var more: String? = null
            /** Tracks YouTube offers to round the playlist out — see [DetailPage.suggestedSongs]. */
            var suggested: List<Song> = emptyList()
            /** Whether this release is already saved — see [DetailPage.library]. */
            var library: LibraryState? = null
            /** YouTube's own "About" blurb — see [DetailPage.description]. */
            var description: String? = null
            /** Artist header stats — see [DetailPage.subscriberCountText]. */
            var subscriberCountText: String? = null
            var monthlyListenerCount: String? = null
            /** Whether this artist is subscribed to — see [DetailPage.subscription]. */
            var subscription: SubscriptionState? = null
            /** The artist header's own Mix target. */
            var radio: WatchTarget? = null
            val localPlaylist = com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(browseId)
            val remote = remoteLibrary(browseId)
            // A release downloaded whole and opened by its YouTube id — the
            // Playlists shelf, a search hit — used to wait on the network for
            // tracks already on the device, and showed an error with no
            // connection at all. Its downloaded copy goes up first; the
            // online listing replaces it if and when that arrives.
            val onDevice = if (localPlaylist == null && remote == null && !browseId.startsWith("local:")) {
                downloadedCopyOf(browseId)
            } else {
                emptyList()
            }
            if (onDevice.isNotEmpty()) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId && it.songs is UiState.Loading) it.copy(songs = UiState.Success(onDevice)) else it
                }
            }
            if (localPlaylist != null) {
                name = localPlaylist.title
                credit = getApplication<Application>().getString(R.string.local_playlist_subtitle, localPlaylist.songs.size)
                artwork = localPlaylist.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
            }
            // Named by the store rather than by whatever opened it, which
            // may be a "Playing from" label from before a rename.
            OfflinePlaylists.get(browseId)?.let { name = it.title }
            val local = localPageState(browseId)
            // Reopened by id alone — the player's "Playing from" — a page off
            // the device arrives with only its title, and offline nothing from
            // YouTube or Apple ever lends it a cover. It takes the cover and
            // credit its own card had instead.
            if (local is UiState.Success) {
                val record = Downloads.recordIdOf(browseId)?.let { Downloads.collections.value[it] }
                val group = LocalGroups.groupOf(browseId, local.data)
                if (thumbnailUrl == null) artwork = record?.thumbnailUrl ?: group?.thumbnailUrl
                if (subtitle.isBlank()) credit = (record?.subtitle ?: group?.subtitle)?.ifBlank { null }
            }
            val state = when {
                local != null -> local
                resolved == BrowseType.ARTIST -> {
                    YtMusicRepository.artistPage(browseId).fold(
                        onSuccess = { page ->
                            sections = page.sections
                            artwork = page.thumbnailUrl
                            name = page.name
                            description = page.description
                            subscriberCountText = page.subscriberCountText
                            monthlyListenerCount = page.monthlyListenerCount
                            subscription = page.subscription
                            radio = page.radio
                            if (page.songs.isEmpty()) {
                                UiState.Error(text(R.string.no_tracks_here))
                            } else {
                                UiState.Success(page.songs.withArtwork(thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
                else -> {
                    YtMusicRepository.browseSongs(browseId).fold(
                        onSuccess = { page ->
                            // Free here — the page that returned these rows is
                            // the one thing that states who made the playlist,
                            // so its own menu never has to go and ask. Recorded
                            // even when the listing came back empty.
                            page.owned?.let { setPlaylistOwned(browseId, it) }
                            // Only for the caller that had nothing: a card's own
                            // title is what the user just tapped, and must not
                            // be swapped for the header's wording underneath them.
                            page.header?.let { header ->
                                if (title.isBlank()) name = header.title
                                // Album cards often only carry the artist, while
                                // the page header also carries the release year.
                                // Prefer that richer line so the year appears
                                // directly below the artist on the album page.
                                if (resolved == BrowseType.ALBUM && header.subtitle.isNotBlank()) {
                                    credit = header.subtitle
                                } else if (subtitle.isBlank()) {
                                    credit = header.subtitle
                                }
                                if (thumbnailUrl == null) artwork = header.thumbnailUrl
                            }
                            description = page.description
                            if (page.songs.isEmpty()) {
                                UiState.Error(text(R.string.no_tracks_here))
                            } else {
                                more = page.continuation
                                suggested = page.suggested.withArtwork(thumbnailUrl ?: artwork)
                                library = page.library
                                UiState.Success(page.songs.withArtwork(thumbnailUrl ?: artwork))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
            }
            // Update by id — the user may have pushed another page meanwhile.
            // A page showing its downloaded copy takes only a real listing: a
            // failed or empty fetch leaves the downloaded tracks up.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId &&
                    (it.songs is UiState.Loading || (onDevice.isNotEmpty() && state is UiState.Success))
                ) {
                    it.copy(
                        songs = state,
                        sections = sections,
                        thumbnailUrl = artwork ?: it.thumbnailUrl,
                        title = name ?: it.title,
                        subtitle = credit ?: it.subtitle,
                        suggestedSongs = suggested,
                        continuation = more,
                        library = library,
                        description = description,
                        subscriberCountText = subscriberCountText,
                        monthlyListenerCount = monthlyListenerCount,
                        subscription = subscription,
                        radio = radio,
                    )
                } else {
                    it
                }
            }
            // Only once the first page is on screen: [fillIn] appends to it,
            // and has nothing to append to before this.
            more?.let { fillIn(browseId, it, thumbnailUrl ?: artwork) }
        }
    }

    /**
     * A Spotify playlist opened as an ordinary playlist page.
     *
     * Spotify's rows go up as soon as they are read, each marked as still
     * waiting on its YouTube Music version (see [isMatchPending]); the matches
     * then arrive in order and swap the rows in place, so the page is usable
     * before the last song has been found.
     */
    private fun openSpotifyPage(browseId: String, title: String, subtitle: String, thumbnailUrl: String?) {
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = BrowseType.PLAYLIST,
        )
        viewModelScope.launch {
            val playlistId = browseId.removePrefix(SPOTIFY_PAGE_PREFIX)
            fun open() = _detailStack.value.any { it.browseId == browseId }
            fun setSongs(songs: UiState<List<Song>>) {
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(songs = songs) else it
                }
            }
            // The list only had a thumbnail; the full-size cover replaces it
            // once this page has asked for it.
            launch {
                val cover = runCatching { SpotifyLibrary.cover(playlistId) }.getOrNull() ?: return@launch
                _detailStack.value = _detailStack.value.map {
                    if (it.browseId == browseId) it.copy(thumbnailUrl = cover) else it
                }
            }
            val tracks = try {
                SpotifyLibrary.tracks(playlistId) { soFar ->
                    setSongs(UiState.Success(soFar.map(SpotifyTrack::toSourceSong)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val cached = if (playlistId == SpotifyLibrary.LIKED_ID) {
                    cachedSpotifySongs(query = "", limit = Int.MAX_VALUE, likedOnly = true)
                } else {
                    emptyList()
                }
                if (cached.isNotEmpty()) {
                    setSongs(UiState.Success(cached))
                    return@launch
                }
                setSongs(UiState.Error(failure.message ?: text(R.string.failed)))
                return@launch
            }
            if (tracks.isEmpty()) {
                setSongs(UiState.Error(text(R.string.spotify_empty_tracks)))
                return@launch
            }
            if (open()) setSongs(UiState.Success(tracks.map(SpotifyTrack::toSourceSong)))
        }
    }

    /**
     * Pushes one album, playlist or artist out of a device folder as a page of
     * its own, with the rows the folder already had in hand — so it opens on
     * its tracks rather than on a skeleton while the folder is read again.
     * [reloadLocalDetail] keeps it current from there, like any `local:` page.
     */
    fun openLocalGroup(group: LocalGroup) {
        if (_detailStack.value.lastOrNull()?.browseId == group.browseId) return
        _detailStack.value += DetailPage(
            browseId = group.browseId,
            title = group.title,
            subtitle = group.subtitle,
            thumbnailUrl = group.thumbnailUrl,
            songs = if (group.songs.isEmpty()) UiState.Error(text(R.string.no_tracks_here)) else UiState.Success(group.songs),
            type = group.type,
        )
    }

    /** Renames an open page in place — a device playlist, whose title is the store's. */
    fun retitleDetail(browseId: String, title: String) {
        if (title.isBlank()) return
        _detailStack.value = _detailStack.value.map { if (it.browseId == browseId) it.copy(title = title) else it }
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val state = localPageState(browseId) ?: return@launch
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    /**
     * The listing behind a page whose tracks come from this device or a file
     * server rather than YouTube — a device folder, a release downloaded whole,
     * a playlist in the app's own store, a remote library, or one album or
     * artist grouped out of a folder — or null for every other page.
     *
     * The one place each of those is read, so opening a page, refreshing it
     * and queueing it from a menu can't disagree about what is on it.
     */
    private suspend fun localPageState(browseId: String): UiState<List<Song>>? {
        val context = getApplication<Application>()
        fun nonEmpty(songs: List<Song>, empty: () -> String): UiState<List<Song>> =
            if (songs.isEmpty()) UiState.Error(empty()) else UiState.Success(songs)

        if (OfflinePlaylists.isOfflinePlaylist(browseId)) {
            // A playlist deleted while its page was still reachable — from
            // "Playing from", say — is an empty page, not a YouTube lookup.
            val playlist = OfflinePlaylists.get(browseId) ?: return UiState.Error(text(R.string.no_tracks_here))
            return nonEmpty(LocalMediaRepository.present(context, playlist.songs)) {
                text(if (playlist.isLiked) R.string.offline_liked_empty else R.string.offline_playlist_empty)
            }
        }
        com.music.bitchord.data.spotify.LocalPlaylistStore.getPlaylist(browseId)?.let { playlist ->
            return nonEmpty(playlist.songs) { text(R.string.spotify_import_empty_playlist) }
        }
        remoteLibrary(browseId)?.let { return remoteSongsState(it) }
        LocalGroups.folderOf(browseId)?.let { folderId ->
            // Read off a fresh listing of the folder it came from: a grouping
            // has nothing of its own to read, and whatever the folder says
            // when it can't be listed is what this page says too.
            return when (val folder = localPageState(folderId)) {
                is UiState.Success -> nonEmpty(LocalGroups.songsOf(browseId, folder.data)) { text(R.string.no_tracks_here) }
                null -> UiState.Error(text(R.string.no_tracks_here))
                else -> folder
            }
        }
        return when {
            Downloads.recordIdOf(browseId) != null ->
                nonEmpty(downloadedPlaylist(browseId)) { text(R.string.downloaded_playlist_empty) }
            browseId == "local:downloads" ->
                nonEmpty(Downloads.getDownloadedSongs(context)) { "No downloaded tracks" }
            browseId == CACHE_FOLDER_BROWSE_ID -> cachedSongsState()
            browseId == "local:all" -> if (!LocalMediaRepository.hasStoragePermission(context)) {
                UiState.Error(text(R.string.storage_required_read))
            } else {
                nonEmpty(LocalMediaRepository.getLocalMusic(context)) { text(R.string.no_local_audio_found) }
            }
            else -> null
        }
    }

    /**
     * The tracks of the downloaded playlist [browseId] names that are still on
     * disk, in the order the playlist had.
     *
     * The download record already names those files, so opening this page must
     * not scan every unrelated download first. [Downloads.getCollectionSongs]
     * verifies only this collection and still collapses aliases that point to
     * the same saved file.
     *
     * Empty is the honest answer for a record whose files have all been deleted
     * from under it, and callers turn that into an empty-state message rather than
     * into a blank page.
     */
    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * The downloaded copy of the release YouTube calls [browseId], if there
     * is one. A playlist is filed under whichever id it was downloaded from,
     * which may or may not carry the `VL` its page id does.
     */
    private suspend fun downloadedCopyOf(browseId: String): List<Song> {
        val bare = browseId.removePrefix("VL")
        val id = Downloads.collections.value.keys.firstOrNull { it.removePrefix("VL") == bare } ?: return emptyList()
        return Downloads.getCollectionSongs(getApplication(), id)
    }

    /**
     * The Cached songs folder: the YouTube and JioSaavn tracks the song cache
     * is holding, most recently played first. See [AudioCache.cachedSongs].
     */
    private suspend fun cachedSongsState(): UiState<List<Song>> {
        val songs = AudioCache.cachedSongs().map { it.song }
        return if (songs.isEmpty()) UiState.Error(text(R.string.cached_songs_empty)) else UiState.Success(songs)
    }

    /**
     * Follows a detail page's continuations in the background, appending each
     * page to what is already being read.
     *
     * A playlist of a few hundred tracks is several round trips, and taking
     * them before showing anything meant a spinner for all of them. Growing
     * the list underneath the reader is also what makes it safe to keep
     * following continuations however deep the playlist runs — nobody is
     * waiting on the last one — so a playlist past YouTube's ~1000-track,
     * ten-page shelf still loads to the end instead of stopping there.
     *
     * Stops the moment the page leaves the stack: there is no one to append
     * for. A page that adds nothing new (below) is the other exit, for a feed
     * that loops back on itself instead of running dry.
     */
    private fun fillIn(browseId: String, token: String, artworkFallback: String?) {
        fillInJobs[browseId]?.cancel()
        fillInJobs[browseId] = viewModelScope.launch {
            // Publish and draw the first response before spending the shared
            // connection on page two. On a long playlist the continuation can
            // otherwise start in the same main-loop turn as the state update,
            // making a ready first screen feel as if it were still loading.
            delay(150)
            var next: String? = token
            while (next != null) {
                val fetched = YtMusicRepository.moreSongs(next).getOrNull() ?: return@launch
                // Repository calls may finish after cancellation. Do not let
                // an in-flight UI page append after the playback service has
                // taken the continuation over.
                currentCoroutineContext().ensureActive()
                // The same page can be on the stack twice (opened again from
                // inside itself). One walk per browseId grows every copy; a
                // second walk replaces the first, so each copy merges by key
                // and only takes a token from a page that taught it something.
                val stack = _detailStack.value
                if (stack.none { it.browseId == browseId }) return@launch
                var progressed = false
                val updated = stack.map { current ->
                    if (current.browseId != browseId) return@map current
                    val existing = (current.songs as? UiState.Success)?.data ?: return@map current
                    val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
                    val added = fetched.songs
                        .filter { known.add(it.contextEntryKey()) }
                        .withArtwork(artworkFallback)
                    val knownVideoIds = (existing + added).mapTo(HashSet()) { it.videoId }
                    // Suggestions can arrive on a later page than the real
                    // tracks, once the playlist's own continuation runs dry —
                    // see parsePlaylistShelf — so they're tracked separately
                    // rather than folded into [known].
                    val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                    val addedSuggested = fetched.suggested
                        .filter { it.videoId !in knownVideoIds && knownSuggested.add(it.videoId) }
                        .withArtwork(artworkFallback)
                    if (added.isEmpty() && addedSuggested.isEmpty()) return@map current
                    progressed = true
                    current.copy(
                        songs = UiState.Success(existing + added),
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                        continuation = fetched.continuation,
                    )
                }
                // A page with nothing new for any copy means the feed has looped
                // back rather than run dry with a token still attached.
                if (!progressed) return@launch
                _detailStack.value = updated
                next = fetched.continuation
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (fillInJobs[browseId] === job) fillInJobs.remove(browseId)
            }
        }
    }

    /**
     * Applies the service-owned canonical context to its matching open page.
     *
     * The service only materialises a small queue window, while its registry
     * keeps the complete canonical list. Mirroring that registry here avoids a
     * page that appears to stop growing as soon as playback starts.
     */
    private fun applyBoundedContextSnapshot(snapshot: BoundedContextRequests.Snapshot) {
        val browseId = snapshot.request.source.id ?: return
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return
        val page = stack[index]
        if (page.type != BrowseType.ALBUM && page.type != BrowseType.PLAYLIST) return
        // Exactly one loader walks the continuation. While the service owns
        // it the page's own fill-in stops; when the service gives up (or never
        // took it — a party, a Cast race) the page resumes from the newest token.
        if (snapshot.serviceOwned) {
            if (snapshot.continuationToken != null) fillInJobs.remove(browseId)?.cancel()
        } else if (snapshot.released && snapshot.continuationToken != null &&
            fillInJobs[browseId]?.isActive != true
        ) {
            fillIn(browseId, snapshot.continuationToken, page.thumbnailUrl)
        }
        val existing = (page.songs as? UiState.Success)?.data ?: return
        val known = existing.mapTo(HashSet()) { it.contextEntryKey() }
        val added = snapshot.songs
            .filter { known.add(it.contextEntryKey()) }
            .withArtwork(page.thumbnailUrl)
        // Never move the page's token backwards: a page whose own fill-in is
        // ahead of the service would otherwise hand a later tap a token for
        // songs it already has, which the registry rejects as a duplicate page.
        val continuation = if (snapshot.songs.size >= existing.size) snapshot.continuationToken else page.continuation
        if (added.isEmpty() && page.continuation == continuation) return
        _detailStack.value = stack.toMutableList().also {
            it[index] = page.copy(
                songs = UiState.Success(existing + added),
                continuation = continuation,
            )
        }
    }

    /**
     * Returns the newest page snapshot for a bounded playback handoff. The
     * page's own fill-in keeps running until the service reports that it has
     * taken the continuation over (see [applyBoundedContextSnapshot]); the
     * tap may yet go down another path, and cancelling here left the page
     * permanently cut short whenever it did.
     */
    fun takePlaybackContext(browseId: String): DetailPage? {
        val stack = _detailStack.value
        val index = stack.indexOfLast { it.browseId == browseId }
        if (index < 0) return null
        val mirrored = BoundedContextRequests.snapshot.value
            ?.takeIf { it.request.source.id == browseId }
        if (mirrored != null) {
            applyBoundedContextSnapshot(mirrored)
            return _detailStack.value.getOrNull(index)
        }
        return stack[index]
    }

    /**
     * An album's track listing doesn't repeat the cover on every row — the
     * page carries it once — so rows arrive with no artwork and stay blank
     * through to the queue and the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /**
     * Home and Explore cards don't say what they point at, and an artist
     * fetched as an album only yields the five songs on its landing page.
     * YouTube's browse ids are prefixed by kind, so use that.
     *
     * Public because the long-press menus ask the same question of a card
     * before offering to queue what is behind it — an artist is not a running
     * order, so it gets no queue actions.
     */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType =
        Companion.browseTypeOf(browseId, fallback)

    /**
     * Every track behind an album or playlist, handed to [onResult] once it is
     * all in.
     *
     * What a long-press on a card is acting on. A card has nothing but a browse
     * id — its page was never opened, so there is no track list anywhere to
     * read — and "add this album to the queue" means the whole album, so this
     * follows continuations to the end rather than taking the first page.
     *
     * Runs in [viewModelScope], not the caller's: the sheet the tap came from
     * closes immediately, and a three-hundred-track playlist must not be
     * abandoned halfway because of it.
     */
    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = when (val local = localPageState(browseId)) {
                is UiState.Success -> Result.success(local.data)
                is UiState.Error -> Result.failure(IllegalStateException(local.message))
                UiState.Loading -> Result.failure(IllegalStateException(text(R.string.no_tracks_here)))
                null -> YtMusicRepository.allSongs(browseId)
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        navigator.onDetailStackShrunk(_detailStack.value.size)
        return true
    }

    /**
     * Loads the channel list, unless it is already in hand.
     *
     * @param force refetch even if it is — after a switch, since the list
     *   itself reports which channel is active.
     */
    fun loadChannels(force: Boolean = false) {
        if (!_signedIn.value) return
        if (_channelsLoading.value) return
        if (!force && _channels.value.isNotEmpty()) return
        viewModelScope.launch {
            _channelsLoading.value = true
            YtMusicRepository.accountChannels()
                .onSuccess {
                    _channels.value = it
                    persistDetectedProfiles(it)
                }
            _channelsLoading.value = false
        }
    }

    /**
     * Acts as [channel] from here on.
     *
     * Everything already on screen belongs to the channel being left — its
     * library, its hearts, its playlists — so the switch clears them and
     * refetches rather than letting the new identity's pages arrive one at a
     * time on top of the old one's.
     */
    fun selectChannel(channel: AccountChannel) {
        val accountId = _activeAccountId.value ?: return
        val profile = YouTubeProfile(
            profileId = profileId(channel.pageId, channel.dataSyncId, channel.name),
            name = channel.name, handle = channel.subtitle, avatar = channel.thumbnailUrl,
            pageId = channel.pageId, dataSyncId = channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        )
        selectProfile(accountId, profile.profileId, profile)
    }

    /**
     * A session lifted out of the in-app browser — a fresh sign-in, or the same
     * login now pointed at a different channel.
     *
     * One path for both because they differ in exactly one thing: whether the
     * cookie is new. What follows — adopt the identity the captured page
     * reported, remember it, and refetch everything that belongs to a listener
     * — is the same work either way.
     */
    fun onWebSession(
        session: CapturedSession,
        mode: WebSessionMode,
        onComplete: (Boolean) -> Unit = {},
    ) {
        if (!session.loggedIn) {
            onComplete(false)
            return
        }
        val oldAccountId = _activeAccountId.value
        val oldProfileId = _activeProfileId.value
        viewModelScope.launch {
            // Validate the exact cookie/profile pair before writing any of it.
            // The old implementation persisted first and unconditionally set
            // signedIn=true; a half-finished channel chooser therefore became
            // a durable broken "Personal" account.
            Innertube.cookie = session.cookie
            Innertube.adoptSessionScope(
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                visitorData = session.visitorData,
                clientVersion = session.clientVersion,
                loggedIn = true,
            )
            Innertube.selectChannel(session.pageId, session.dataSyncId, session.authUser)

            val account = withTimeoutOrNull(20_000L) {
                var result = YtMusicRepository.account()
                if (result.isFailure) {
                    delay(750L)
                    result = YtMusicRepository.account()
                }
                result.getOrNull()
            }
            if (account == null) {
                restoreActiveSession(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }

            // A channel switch is another identity under the same Google
            // login, never a new Google account. For a fresh sign-in, matching
            // a non-empty email upgrades the existing entry instead of adding
            // a duplicate after cookies rotate.
            val accountId = when (mode) {
                WebSessionMode.SWITCH_CHANNEL -> oldAccountId
                WebSessionMode.SIGN_IN -> authStore.sessions.firstOrNull {
                    account.email.isNotBlank() && it.email.equals(account.email, ignoreCase = true)
                }?.accountId ?: sessionId(session.cookie, null)
            }
            if (accountId == null) {
                restoreActiveSession(oldAccountId, oldProfileId)
                onComplete(false)
                return@launch
            }
            val previous = authStore.sessions.firstOrNull { it.accountId == accountId }
            val selectedProfileId = profileId(session.pageId, session.dataSyncId, account.name)
            val profile = YouTubeProfile(
                profileId = selectedProfileId,
                name = account.name,
                handle = account.email,
                avatar = account.thumbnailUrl,
                pageId = session.pageId,
                dataSyncId = session.dataSyncId,
                authUser = session.authUser,
                isBrandAccount = session.pageId != null,
            )
            val hasRealIdentity = profile.pageId != null || profile.dataSyncId != null
            val profiles = previous?.profiles.orEmpty().filterNot { known ->
                known.profileId == profile.profileId ||
                    (hasRealIdentity && known.profileId.startsWith("profile:"))
            } + profile
            val stored = GoogleAccountSession(
                accountId = accountId,
                cookie = session.cookie,
                name = account.name,
                email = account.email,
                profiles = profiles,
                activeProfileId = profile.profileId,
            )

            val wasSignedIn = _signedIn.value
            if (wasSignedIn) cacheCurrentListener()
            authStore.upsertSession(stored)
            authStore.cookie = session.cookie // legacy compatibility only
            _googleAccounts.value = authStore.sessions
            _activeAccountId.value = accountId
            _activeProfileId.value = profile.profileId
            _selectedChannelKey.value = profile.profileId
            _selectedChannelName.value = account.name
            _account.value = account
            _channels.value = emptyList()
            _signedIn.value = true

            // Every resolver verdict and personalised page belongs to the
            // identity that was active before validation succeeded.
            StreamResolver.onSessionChanged()
            if (wasSignedIn) clearListenerState()
            reloadForAccount()
            loadChannels(force = true)
            onComplete(true)
        }
    }

    /** Put request signing back exactly as it was after a rejected candidate. */
    private fun restoreActiveSession(accountId: String?, selectedProfileId: String?) {
        val account = authStore.sessions.firstOrNull { it.accountId == accountId }
        val profile = account?.profiles?.firstOrNull { it.profileId == selectedProfileId }
        Innertube.cookie = account?.cookie
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }

    /** Selects an identity without ever allowing a response to replace it. */
    fun selectProfile(accountId: String, selectedProfileId: String, supplied: YouTubeProfile? = null) {
        val source = _googleAccounts.value.firstOrNull { it.accountId == accountId } ?: return
        val profile = supplied ?: source.profiles.firstOrNull { it.profileId == selectedProfileId } ?: return
        if (accountId == _activeAccountId.value && profile.profileId == _activeProfileId.value) return
        cacheCurrentListener()
        val index = source.profiles.indexOfFirst { it.profileId == profile.profileId }
        val updatedProfiles = if (index >= 0) {
            source.profiles.toMutableList().apply { set(index, profile) }
        } else {
            source.profiles + profile
        }
        val updated = source.copy(
            profiles = updatedProfiles,
            activeProfileId = profile.profileId,
        )
        authStore.upsertSession(updated)
        authStore.select(accountId, profile.profileId)
        _googleAccounts.value = authStore.sessions
        _activeAccountId.value = accountId
        _activeProfileId.value = profile.profileId
        authStore.cookie = updated.cookie
        Innertube.cookie = updated.cookie
        Innertube.selectChannel(profile.pageId, profile.dataSyncId, profile.authUser)
        _selectedChannelKey.value = profile.profileId
        _selectedChannelName.value = profile.name
        StreamResolver.onSessionChanged()
        clearListenerState(restoreCached = true)
        reloadForAccount()
    }

    /** Returns false at an edge, allowing the avatar to play its elastic cue. */
    fun stepProfile(forward: Boolean): Boolean {
        val target = adjacentProfile(_googleAccounts.value, _activeAccountId.value, _activeProfileId.value, forward)
            ?: return false
        selectProfile(target.first, target.second)
        return true
    }

    fun removeAccount(accountId: String) {
        val fallback = authStore.removeAccount(accountId)
        _googleAccounts.value = authStore.sessions
        if (fallback == null) { signOut(); return }
        selectProfile(fallback.accountId, fallback.activeProfileId ?: fallback.profiles.firstOrNull()?.profileId ?: return)
    }

    private fun persistDetectedProfiles(channels: List<AccountChannel>) {
        val accountId = _activeAccountId.value ?: return
        val current = authStore.sessions.firstOrNull { it.accountId == accountId } ?: return
        val detected = channels.map { channel -> YouTubeProfile(
            profileId(channel.pageId, channel.dataSyncId, channel.name), channel.name, channel.subtitle,
            channel.thumbnailUrl, channel.pageId, channel.dataSyncId,
            isBrandAccount = channel.pageId != null,
        ) }
        // A profile captured before its channel existed carries a provisional,
        // name-hash id (see profileId()) rather than the pageId/dataSyncId Google
        // reports here once the channel is live. Once Google actually reports a
        // channel, that placeholder is stale — it would have been listed here
        // too if it still lacked real ids — and it's dropped rather than kept
        // alongside the identity it was standing in for. An empty response
        // (channel not created yet, or a transient miss) must never drop it.
        val stalePlaceholder = if (detected.isEmpty()) emptySet() else current.profiles
            .filter { it.profileId.startsWith("profile:") }
            .map { it.profileId }
            .toSet()
        // Keep the captured active identity if Google's endpoint temporarily
        // omits it; an empty/partial response must never erase a selection.
        val profiles = (current.profiles.filter { known ->
            known.profileId !in stalePlaceholder && detected.none { it.profileId == known.profileId }
        } + detected)
        val selected = when {
            current.activeProfileId != null && current.activeProfileId !in stalePlaceholder -> current.activeProfileId
            current.activeProfileId in stalePlaceholder -> detected.singleOrNull()?.profileId
            else -> null
        } ?: profiles.firstOrNull()?.profileId
        authStore.upsertSession(current.copy(profiles = profiles, activeProfileId = selected), activate = false)
        _googleAccounts.value = authStore.sessions
    }

    /**
     * Drops everything on screen that belongs to the identity being left.
     *
     * The hearts, the playlists and the library are all answers to "who is
     * asking", so keeping them across a switch shows the new channel the old
     * one's music until each page happens to be refetched.
     */
    private fun cacheCurrentListener() {
        val key = listenerKey() ?: return
        listenerCache[key] = ListenerSnapshot(
            _account.value, _library.value, _history.value, _playlists.value, _playlistOwned.value,
        )
    }

    fun setPersonalizedHomeVisible(visible: Boolean) = personalizedHome.setVisible(visible)

    suspend fun resolveHomeSong(item: ShelfItem): Result<Song> = personalizedHome.resolve(item)

    /**
     * Keeps Spotify's identity and presentation while resolving a strictly
     * matching stream that RX Playa is legally able to play itself.
     */
    suspend fun resolveSpotifySourceSong(song: Song): Song? {
        if (!song.isSpotifySourceTrack) return song
        val match = ExternalPlaylistImporter.match(
            BasicExternalPlaylistTrack(
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationMs = song.durationMillis(),
                coverUrl = song.thumbnailUrl,
            ),
        ) ?: return null
        return match.copy(
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl ?: match.thumbnailUrl,
            durationText = song.durationText ?: match.durationText,
            artists = song.artists.ifEmpty { match.artists },
            albumName = song.albumName ?: match.albumName,
            playbackSource = "Spotify",
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    /** Strict playable copy used only when SoundCloud's own CDN fails its probe. */
    suspend fun resolveSoundCloudFallback(song: Song): Song? {
        if (!song.videoId.startsWith("soundcloud:")) return song
        val match = ExternalPlaylistImporter.match(
            BasicExternalPlaylistTrack(
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationMs = song.durationMillis(),
                coverUrl = song.thumbnailUrl,
            ),
        ) ?: return null
        return match.copy(
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl ?: match.thumbnailUrl,
            durationText = song.durationText ?: match.durationText,
            artists = song.artists.ifEmpty { match.artists },
            albumName = song.albumName ?: match.albumName,
            playbackSource = "YouTube (SoundCloud fallback)",
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    /**
     * The last playable copy for a SoundCloud row whose own stream is gone: the
     * top YouTube hit for its title and artist, with no recording match.
     *
     * Tried after [resolveSoundCloudFallback], never instead of it. A SoundCloud
     * upload that is an extended mix, a sped-up edit or a live take has no
     * runtime-compatible YouTube row, and refusing all of them is what turned a
     * dead CDN into rows that lit up and then did nothing.
     */
    suspend fun resolveSoundCloudTopHit(song: Song): Song? {
        if (!song.videoId.startsWith("soundcloud:")) return null
        val match = runCatching {
            ExternalPlaylistImporter.topHit(
                BasicExternalPlaylistTrack(
                    title = song.title,
                    artist = song.artist,
                    album = song.albumName,
                    durationMs = song.durationMillis(),
                    coverUrl = song.thumbnailUrl,
                ),
            )
        }.getOrNull() ?: return null
        return match.copy(
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl ?: match.thumbnailUrl,
            durationText = song.durationText ?: match.durationText,
            artists = song.artists.ifEmpty { match.artists },
            albumName = song.albumName ?: match.albumName,
            playbackSource = "YouTube (SoundCloud fallback)",
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    /** VK metadata remains visible while an exact playable copy is selected. */
    suspend fun resolveVkSourceSong(song: Song): Song? {
        if (!song.isVkSourceTrack) return song
        val match = ExternalPlaylistImporter.match(
            BasicExternalPlaylistTrack(
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationMs = song.durationMillis(),
                coverUrl = song.thumbnailUrl,
            ),
        ) ?: return null
        return match.copy(
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl ?: match.thumbnailUrl,
            durationText = song.durationText ?: match.durationText,
            albumName = song.albumName ?: match.albumName,
            playbackSource = "VK Music fallback",
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    private fun tasteListenerKey(): String? {
        if (!_signedIn.value) return null
        val account = _activeAccountId.value ?: "legacy:${tasteDigest(authStore.cookie.orEmpty())}"
        return "$account:${_activeProfileId.value ?: "default"}"
    }

    private fun listenerKey(): String? = _activeAccountId.value?.let { accountId ->
        _activeProfileId.value?.let { profileId -> "$accountId:$profileId" }
    }

    private fun clearListenerState(restoreCached: Boolean = false) {
        _account.value = null
        likedSyncJob?.cancel()
        LikeState.clear()
        LikeState.restore(TrackRatingStore.ratings.value.mapValues { it.value.likeStatus })
        _playlistsLoading.value = false
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        ownershipInFlight.clear()
        _songMenu.value = null
        _library.value = UiState.Loading
        _history.value = UiState.Loading
        if (restoreCached) listenerCache[listenerKey()]?.let { cached ->
            _account.value = cached.account
            _library.value = cached.library
            _history.value = cached.history
            _playlists.value = cached.playlists
            _playlistOwned.value = cached.owned
        }
    }

    /**
     * Everything that is "the signed-in listener's", refetched.
     *
     * The scope comes first, and inside one coroutine rather than beside them:
     * which channel the session acts as decides what "the library" and "the
     * history" even refer to, so loading them first and scoping second shows
     * the listener the wrong account's music and then silently disagrees with
     * itself.
     */
    private fun reloadForAccount() {
        viewModelScope.launch {
            Innertube.ensureSessionScope()
            loadHome()
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
    }

    fun signOut() {
        // In a multi-account install, sign out removes only the current Google
        // session and restores another one. A legacy single-account install
        // retains the familiar full sign-out behaviour.
        val current = _activeAccountId.value
        if (current != null && authStore.sessions.size > 1) {
            removeAccount(current)
            return
        }
        authStore.signOut()
        Innertube.cookie = null
        // The mirror image: verdicts reached with a session in hand say nothing
        // about what an anonymous walk will be told, and the clients stood down
        // for refusing the session deserve a fresh hearing without it.
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
        Innertube.selectChannel(null, null)
        _channels.value = emptyList()
        _selectedChannelKey.value = null
        _selectedChannelName.value = null
        _googleAccounts.value = emptyList()
        _activeAccountId.value = null
        _activeProfileId.value = null
        _library.value = UiState.Loading
        // Ratings and playlists belong to the account that just left; keeping
        // them would show the next signed-in user someone else's hearts.
        likedSyncJob?.cancel()
        LikeState.clear()
        LikeState.restore(TrackRatingStore.ratings.value.mapValues { it.value.likeStatus })
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        ownershipInFlight.clear()
        _songMenu.value = null
        loadHome()
    }

    private fun Throwable.friendly(): String = when {
        // Every page that fails while there is no connection fails this way,
        // whatever the socket happened to call it. Saying so once here beats
        // matching on the wording of a dozen different network errors.
        AudioCache.isOffline() -> text(R.string.no_internet_connection)
        message?.contains("resolve host", true) == true ||
            message?.contains("Unable to resolve", true) == true -> text(R.string.no_internet_connection)
        message?.contains("401") == true || message?.contains("403") == true ->
            text(R.string.youtube_request_rejected)
        else -> message ?: text(R.string.something_went_wrong)
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}

/**
 * What one lyrics lookup for the playing track was made under. The order and
 * the syllable preference pick a different answer from the same providers, so
 * a change in either is a new lookup, as switching a source on or off is.
 */
internal fun lyricsLookupKey(
    videoId: String,
    sources: Set<LyricsSource>,
    order: List<LyricsSource>,
    prioritizeSyllableSync: Boolean,
): List<Any> = listOf(videoId, sources, order, prioritizeSyllableSync)
