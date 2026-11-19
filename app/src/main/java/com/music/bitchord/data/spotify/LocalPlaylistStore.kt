package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.HomeSource
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.playback.contextEntryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    /** Legacy imports did not retain their provider and remain honestly labelled Local. */
    val source: HomeSource = HomeSource.LOCAL,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                        isVideoOrigin = sObj.optBoolean("isVideoOrigin", false),
                        playbackSource = sObj.optString("playbackSource", null)
                            .takeIf { !it.isNullOrBlank() && it != "null" },
                        playbackSourceType = sObj.optString("playbackSourceType", null)
                            .takeIf { !it.isNullOrBlank() && it != "null" }
                            ?.let { runCatching { PlaybackSourceType.valueOf(it) }.getOrNull() },
                        playbackSourceId = sObj.optString("playbackSourceId", null)
                            .takeIf { !it.isNullOrBlank() && it != "null" },
                    )
                    songs.add(song)
                }
                val source = runCatching { HomeSource.valueOf(obj.optString("source", "LOCAL")) }
                    .getOrDefault(HomeSource.LOCAL)
                list.add(LocalPlaylist(id, title, songs, source))
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>, source: HomeSource = HomeSource.LOCAL): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs, source = source)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    /**
     * Appends [songs] to the local playlist addressed by [id], skipping what is
     * already in it.
     *
     * [id] is accepted in every shape a row hands one back: the store's own id,
     * the `local:playlist:` browse id of a card, or a bare `VL` id. Returns how
     * many rows were actually added, so a caller can tell "added" from "was
     * already there" without reading the playlist back.
     */
    fun addSongs(id: String, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val clean = id.removePrefix("local:playlist:").removePrefix("VL")
        val index = _playlists.value.indexOfFirst {
            it.id == clean || it.browseId == id || it.id == id
        }
        if (index < 0) return 0
        val playlist = _playlists.value[index]
        val known = playlist.songs.mapTo(HashSet()) { it.videoId }
        val fresh = songs.filter { known.add(it.videoId) }
        if (fresh.isEmpty()) return 0
        val updated = _playlists.value.toMutableList()
        updated[index] = playlist.copy(songs = playlist.songs + fresh)
        _playlists.value = updated
        persist(updated)
        return fresh.size
    }

    /** Adds imported playlists, keeping this device's own ones untouched. */
    fun importAll(imported: List<LocalPlaylist>) {
        if (imported.isEmpty()) return
        val byId = _playlists.value.associateBy { it.id }.toMutableMap()
        imported.forEach { byId[it.id] = it }
        _playlists.value = byId.values.sortedBy { it.title.lowercase() }
        persist(_playlists.value)
    }

    /**
     * Takes a track out of a playlist kept on this device.
     *
     * Its own operation rather than a rewrite of the whole playlist, because
     * this is what the "remove from this playlist" row calls: a playlist made
     * without an account had no way to lose a track at all, which is what the
     * complaints were about.
     *
     * The match is by [Song.contextEntryKey], so a row that is in the list
     * twice under two ids loses only the one that was tapped.
     */
    fun removeSong(id: String, song: Song): Boolean {
        val clean = id.removePrefix("local:playlist:").removePrefix("VL")
        val index = _playlists.value.indexOfFirst {
            it.id == clean || it.browseId == id || it.id == id
        }
        if (index < 0) return false
        val playlist = _playlists.value[index]
        val key = song.contextEntryKey()
        val remaining = playlist.songs.filterNot {
            it.videoId == song.videoId || it.contextEntryKey() == key
        }
        if (remaining.size == playlist.songs.size) return false
        val updated = _playlists.value.toMutableList()
        updated[index] = playlist.copy(songs = remaining)
        _playlists.value = updated
        persist(updated)
        return true
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                pObj.put("source", playlist.source.name)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    sObj.put("isVideoOrigin", song.isVideoOrigin)
                    sObj.put("playbackSource", song.playbackSource)
                    sObj.put("playbackSourceType", song.playbackSourceType?.name)
                    sObj.put("playbackSourceId", song.playbackSourceId)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
