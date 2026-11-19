package com.music.bitchord.data.importing

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import java.net.URI

/**
 * A playlist moved over from YouTube Music itself.
 *
 * Every other import has to find each track a second time, on another
 * catalogue, and that matching is where rows go wrong. This one does not have
 * that problem: the rows already are YouTube videos, so the playlist is copied
 * as it is and every row plays. What it does need is the paging — a playlist
 * answers with one screenful and a continuation token, and importing only the
 * first page is how a 300-track playlist became 25.
 */
object YouTubeMusicPlaylistImport {

    data class Imported(val title: String, val songs: List<Song>)

    /** How many continuation round trips one import may spend. */
    private const val MAX_PAGES = 100

    private val BARE_ID = Regex("""[A-Za-z0-9_-]{10,}""")

    /**
     * The browse id a link names, or null when it names no playlist.
     *
     * Accepts what somebody actually copies out of the address bar: a playlist
     * page, a watch link that carries `list=`, or the bare id.
     */
    fun browseIdOf(link: String): String? {
        val text = link.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://") && !text.contains("youtube", ignoreCase = true)) {
            return text.takeIf { BARE_ID.matches(it) }?.let(::normalize)
        }
        val uri = runCatching { URI(text) }.getOrNull() ?: runCatching { URI("https://$text") }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        if (host != "youtu.be" && !host.endsWith("youtube.com")) return null
        val list = uri?.rawQuery.orEmpty()
            .split('&')
            .firstOrNull { it.startsWith("list=") }
            ?.substringAfter("list=")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return normalize(list)
    }

    private fun normalize(id: String): String = if (id.startsWith("VL")) id else "VL$id"

    /**
     * Reads the whole playlist, following the continuation to its end.
     *
     * [onProgress] is told the running count so the dialog can say something
     * better than a spinner while a long playlist is read.
     */
    suspend fun load(link: String, onProgress: (Int) -> Unit = {}): Imported {
        val browseId = browseIdOf(link) ?: error("not a playlist link")
        val first = YtMusicRepository.browseSongs(browseId).getOrElse { throw it }
        val songs = LinkedHashMap<String, Song>()
        first.songs.forEach { songs.putIfAbsent(it.videoId, it) }
        onProgress(songs.size)
        var token = first.continuation
        var pages = 1
        while (token != null && pages < MAX_PAGES) {
            val next = YtMusicRepository.moreSongs(token).getOrNull() ?: break
            val added = next.songs.count { songs.putIfAbsent(it.videoId, it) == null }
            if (added > 0) onProgress(songs.size)
            token = next.continuation
            pages++
            // A page that adds nothing and hands back the same token would loop
            // forever; the count check above is the cheap stop for that.
            if (added == 0 && next.continuation == null) break
        }
        if (songs.isEmpty()) error("empty playlist")
        val title = first.header?.title?.takeIf { it.isNotBlank() } ?: "YouTube Music"
        return Imported(title, songs.values.toList())
    }
}
