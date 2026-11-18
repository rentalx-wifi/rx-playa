package com.music.bitchord.data.personalization

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PersonalWavePlanner
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What RX Wave has already served, so that pressing the pill again is a
 * different station.
 *
 * Without this the station was a pure function of the likes: the same seed
 * radios answered the same rows, the planner scored them the same way, and the
 * queue was refilled with the tracks that had just finished. The listener reads
 * that as "it plays the same thing in a circle" — rule 11 in
 * [PersonalWavePlanner] is written against this memory.
 *
 * Entries expire after [COOLDOWN_MS] rather than for good: with a few hundred
 * likes the pool would otherwise run dry and the station would start short.
 */
internal object WaveMemory {

    @Serializable
    private data class Entry(val id: String, val titleKey: String, val seconds: Int, val atMs: Long)

    /** How long a served track stays out of the wave. */
    const val COOLDOWN_MS = 72L * 60L * 60L * 1000L

    private const val MAX_ENTRIES = 600
    private const val KEY_DATA = "served"
    private const val PREFS = "rx_wave_memory"

    /** The two ways a recording is recognised: by upload id and by master. */
    class Served(
        val videoIds: Set<String>,
        val recordings: Set<PersonalWavePlanner.RecordingKey>,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null
    private val entries = ArrayList<Entry>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        entries.clear()
        val restored = runCatching {
            json.decodeFromString(ListSerializer(Entry.serializer()), store.getString(KEY_DATA, null) ?: "[]")
        }.getOrDefault(emptyList())
        entries.addAll(restored)
    }

    /** Everything still in its cooldown, for the planner's rule 11. */
    @Synchronized
    fun recentlyServed(nowMs: Long = System.currentTimeMillis()): Served {
        if (prefs == null) return Served(emptySet(), emptySet())
        prune(nowMs)
        val ids = HashSet<String>(entries.size)
        val recordings = HashSet<PersonalWavePlanner.RecordingKey>(entries.size)
        entries.forEach {
            ids += it.id
            recordings += PersonalWavePlanner.RecordingKey(it.titleKey, it.seconds)
        }
        return Served(ids, recordings)
    }

    /** Records one station, so the next press can leave these tracks out. */
    @Synchronized
    fun remember(songs: List<Song>, nowMs: Long = System.currentTimeMillis()) {
        if (prefs == null || songs.isEmpty()) return
        prune(nowMs)
        val known = entries.mapTo(HashSet()) { it.id }
        songs.forEach { song ->
            if (song.videoId.isBlank() || !known.add(song.videoId)) return@forEach
            val key = PersonalWavePlanner.recordingKey(song)
            entries += Entry(song.videoId, key.title, key.seconds, nowMs)
        }
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - COOLDOWN_MS
        entries.removeAll { it.atMs < cutoff }
    }

    private fun persist() {
        val encoded = runCatching {
            json.encodeToString(ListSerializer(Entry.serializer()), entries.toList())
        }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY_DATA, encoded)?.apply()
    }
}
