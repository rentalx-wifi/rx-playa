package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StoredTrackRating(
    val id: String,
    val status: String,
    val changedAtMs: Long,
    val title: String,
    val artist: String,
    val artwork: String? = null,
    val duration: String? = null,
    val album: String? = null,
    val localUri: String? = null,
    val playbackSource: String? = null,
    val playbackSourceId: String? = null,
) {
    val likeStatus: LikeStatus get() = runCatching { LikeStatus.valueOf(status) }
        .getOrDefault(LikeStatus.INDIFFERENT)

    fun song() = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = artwork,
        durationText = duration,
        albumName = album,
        localUri = localUri,
        playbackSource = playbackSource,
        playbackSourceType = playbackSource?.let { PlaybackSourceType.SEARCH },
        playbackSourceId = playbackSourceId,
    )
}

/** Durable, provider-neutral ratings used by Favorites and personalization. */
object TrackRatingStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<Map<String, StoredTrackRating>>(emptyMap())
    val ratings = mutable.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("rx_music_track_ratings", Context.MODE_PRIVATE)
        val restored = prefs?.getString(KEY_DATA, null)?.let { raw ->
            runCatching { json.decodeFromString<List<StoredTrackRating>>(raw) }.getOrNull()
        }.orEmpty().associateBy(StoredTrackRating::id)
        mutable.value = restored
        LikeState.restore(restored.mapValues { it.value.likeStatus })
    }

    @Synchronized
    fun set(song: Song, status: LikeStatus) {
        val entry = StoredTrackRating(
            id = song.videoId,
            status = status.name,
            changedAtMs = System.currentTimeMillis(),
            title = song.title,
            artist = song.artist,
            artwork = song.thumbnailUrl,
            duration = song.durationText,
            album = song.albumName,
            localUri = song.localUri,
            playbackSource = song.playbackSource,
            playbackSourceId = song.playbackSourceId,
        )
        mutable.value = mutable.value + (song.videoId to entry)
        LikeState.set(song.videoId, status)
        persist()
    }

    @Synchronized
    fun restore(song: Song, previous: LikeStatus, previousEntry: StoredTrackRating?) {
        mutable.value = if (previousEntry == null) mutable.value - song.videoId
        else mutable.value + (song.videoId to previousEntry)
        LikeState.set(song.videoId, previous)
        persist()
    }

    fun entry(id: String): StoredTrackRating? = mutable.value[id]

    fun likedSongs(): List<Song> = mutable.value.values.asSequence()
        .filter { it.likeStatus == LikeStatus.LIKE }
        .sortedByDescending(StoredTrackRating::changedAtMs)
        .map(StoredTrackRating::song)
        .toList()

    fun dislikedSongs(): List<Song> = mutable.value.values.asSequence()
        .filter { it.likeStatus == LikeStatus.DISLIKE }
        .map(StoredTrackRating::song)
        .toList()

    /** Provider ids can differ for the same recording, so title/credit aliases are blocked too. */
    fun isDisliked(song: Song, disliked: List<Song> = dislikedSongs()): Boolean =
        disliked.any { QueueBuilder.isSameRecording(it, song) }

    /** Every stored rating, for a backup that carries likes and dislikes off this device. */
    fun all(): List<StoredTrackRating> = mutable.value.values.toList()

    /** Replaces the whole rating set, as an imported backup does. */
    @Synchronized
    fun replaceAll(entries: List<StoredTrackRating>) {
        mutable.value = entries.associateBy { it.id }
        LikeState.restore(mutable.value.mapValues { it.value.likeStatus })
        persist()
    }

    @Synchronized
    fun importLikes(songs: List<Song>) {
        if (songs.isEmpty()) return
        val next = mutable.value.toMutableMap()
        val now = System.currentTimeMillis()
        songs.forEachIndexed { index, song ->
            val existing = next[song.videoId]
            if (existing?.likeStatus == LikeStatus.DISLIKE || existing?.likeStatus == LikeStatus.INDIFFERENT) return@forEachIndexed
            next[song.videoId] = StoredTrackRating(
                song.videoId, LikeStatus.LIKE.name, existing?.changedAtMs ?: now - index,
                song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName,
                song.localUri, song.playbackSource, song.playbackSourceId,
            )
        }
        mutable.value = next
        LikeState.restore(next.mapValues { it.value.likeStatus })
        persist()
    }

    private fun persist() {
        prefs?.edit()?.putString(KEY_DATA, json.encodeToString(mutable.value.values.toList()))?.apply()
    }

    private const val KEY_DATA = "ratings_v1"
}
