package com.rentalx.playa.data.importing

import com.rentalx.playa.data.innertube.StreamResolver
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Public YouTube search used only when YouTube Music returns no acceptable
 * counterpart for an external-service track.
 *
 * Some countries expose ordinary YouTube while the music.youtube.com catalogue
 * is empty or incomplete. The resulting video id still goes through Playa's
 * normal stream resolver; this does not fetch or proxy Spotify/Yandex audio.
 */
internal object PublicYouTubeFallback {
    suspend fun candidates(title: String, artist: String): List<Song> = withContext(Dispatchers.IO) {
        StreamResolver.ensureNewPipeInitialized()
        val target = TrackMatcher.Target(title = title, artist = artist)
        for (query in TrackMatcher.queries(target).take(MAX_QUERIES)) {
            currentCoroutineContext().ensureActive()
            val songs = runCatching {
                ServiceList.YouTube.getSearchExtractor(query).run {
                    fetchPage()
                    initialPage.items.asSequence()
                        .filterIsInstance<StreamInfoItem>()
                        .mapNotNull(::toSong)
                        .distinctBy(Song::videoId)
                        .take(MAX_RESULTS)
                        .toList()
                }
            }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) return@withContext songs
        }
        emptyList()
    }

    private fun toSong(item: StreamInfoItem): Song? {
        val videoId = extractPublicYouTubeVideoId(item.url) ?: return null
        val title = item.name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val artist = item.uploaderName.orEmpty()
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { title.substringBefore(" - ").trim() }
        if (artist.isEmpty()) return null
        return Song(
            videoId = videoId,
            title = title,
            artist = artist,
            thumbnailUrl = item.thumbnails.bestImage(),
            durationText = item.duration.takeIf { it > 0L }?.let(::formatDuration),
            isVideo = true,
            isVideoOrigin = true,
        )
    }

    private fun List<Image>?.bestImage(): String? =
        orEmpty().maxByOrNull { image -> image.width.coerceAtLeast(image.height) }?.url

    private fun formatDuration(seconds: Long): String =
        "%d:%02d".format(seconds / 60L, seconds % 60L)

    private const val MAX_QUERIES = 2
    private const val MAX_RESULTS = 20
}

internal fun extractPublicYouTubeVideoId(rawUrl: String?): String? {
    val url = rawUrl?.toHttpUrlOrNull() ?: return null
    val host = url.host.lowercase()
    val candidate = when {
        host == "youtu.be" -> url.pathSegments.firstOrNull()
        host == "youtube.com" || host.endsWith(".youtube.com") -> when {
            url.encodedPath == "/watch" -> url.queryParameter("v")
            url.pathSegments.firstOrNull() in setOf("shorts", "live", "embed") -> url.pathSegments.getOrNull(1)
            else -> null
        }
        else -> null
    }
    return candidate?.takeIf { YOUTUBE_VIDEO_ID.matches(it) }
}

private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
