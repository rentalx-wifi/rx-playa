package com.music.bitchord.data.personalization

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only metadata, ownership and real user actions; never credentials, audio, or feedback tokens. */
@Serializable
internal data class StoredTasteSong(
    val id: String,
    val title: String,
    val artist: String,
    val art: String? = null,
    val duration: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val album: String? = null,
    val localUri: String? = null,
    val artists: List<String> = emptyList(),
) {
    fun song() = Song(id, title, artist, art, duration, artistId,
        artists = artists.map { ArtistRef(it) }, albumId = albumId, albumName = album, localUri = localUri)

    companion object {
        fun of(song: Song) = StoredTasteSong(song.videoId, song.title, song.artist, song.thumbnailUrl,
            song.durationText, song.artistId, song.albumId, song.albumName, song.localUri, song.artists.map { it.name })
    }
}

@Serializable
internal data class TasteImport(
    val browseId: String,
    val title: String,
    val source: HomeSource,
    val atMs: Long,
    val owner: String? = null,
    val songs: List<StoredTasteSong>,
)

@Serializable
internal data class TasteRating(val account: String, val id: String, val status: String, val atMs: Long)

@Serializable
internal data class StoredTasteSignals(
    val imports: List<TasteImport> = emptyList(),
    val ratings: List<TasteRating> = emptyList(),
)

internal object TasteSignalStore {
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(StoredTasteSignals())
    val state = mutable.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("bitchord_taste_signals", Context.MODE_PRIVATE)
        prefs.getString("metadata", null)?.let { raw ->
            runCatching { json.decodeFromString<StoredTasteSignals>(raw) }.getOrNull()?.let { mutable.value = it }
        }
        val loaded = mutable.value
        io.launch {
            state.filter { it !== loaded }.conflate().collect { value ->
                runCatching {
                    prefs.edit().putString("metadata", json.encodeToString(StoredTasteSignals.serializer(), value)).apply()
                }
            }
        }
    }

    @Synchronized
    fun rememberImport(title: String, source: HomeSource, songs: List<Song>, browseId: String, owner: String?) {
        val entry = TasteImport(browseId, title, source, System.currentTimeMillis(), owner, songs.map(StoredTasteSong::of))
        mutable.value = mutable.value.copy(imports = listOf(entry) + mutable.value.imports.filterNot { it.browseId == browseId })
    }

    @Synchronized
    fun forgetImport(browseId: String) {
        mutable.value = mutable.value.copy(imports = mutable.value.imports.filterNot { it.browseId == browseId })
    }

    @Synchronized
    fun renameImport(browseId: String, title: String) {
        mutable.value = mutable.value.copy(imports = mutable.value.imports.map {
            if (it.browseId == browseId) it.copy(title = title) else it
        })
    }

    @Synchronized
    fun rememberRating(account: String?, id: String, status: LikeStatus) {
        if (account == null) return
        val entry = TasteRating(account, id, status.name, System.currentTimeMillis())
        mutable.value = mutable.value.copy(ratings = listOf(entry) + mutable.value.ratings.filterNot {
            it.account == account && it.id == id
        })
    }
}
