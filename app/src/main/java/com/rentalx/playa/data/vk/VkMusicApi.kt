package com.rentalx.playa.data.vk

import com.rentalx.playa.BuildConfig
import com.rentalx.playa.data.Http
import com.rentalx.playa.data.model.PlaybackSourceType
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val VK_TRACK_PREFIX = "vk-audio:"
internal val Song.isVkSourceTrack: Boolean get() = videoId.startsWith(VK_TRACK_PREFIX)

internal data class VkMusicTrack(
    val ownerId: String,
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int,
    val artworkUrl: String?,
) {
    fun song() = Song(
        videoId = "$VK_TRACK_PREFIX$ownerId:$id",
        title = title,
        artist = artist,
        thumbnailUrl = artworkUrl,
        durationText = durationSeconds.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
        playbackSource = "VK Music",
        playbackSourceType = PlaybackSourceType.SEARCH,
        playbackSourceId = "$ownerId:$id",
    )
}

/** Activated only after VK explicitly approves the application's audio scope. */
object VkMusicApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    val available: Boolean
        get() = AppSettings.vkAccessToken.value.isNotBlank()

    suspend fun search(query: String, limit: Int = 50): List<Song> {
        if (!available || query.isBlank()) return emptyList()
        val token = VkIdAuth.accessToken() ?: return emptyList()
        val url = "https://api.vk.com/method/audio.search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("count", limit.coerceIn(1, 200).toString())
            .addQueryParameter("auto_complete", "1")
            .addQueryParameter("sort", "2")
            .addQueryParameter("access_token", token)
            .addQueryParameter("v", "5.199")
            .build()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("VK Music search failed (${response.code})")
                parseVkMusicSearch(json.parseToJsonElement(response.body.string()).jsonObject)
                    .take(limit)
                    .map(VkMusicTrack::song)
            }
        }
    }
}

internal fun parseVkMusicSearch(root: JsonObject): List<VkMusicTrack> {
    (root["error"] as? JsonObject)?.let { error ->
        throw IOException(error.text("error_msg") ?: "VK Music API rejected the request")
    }
    val response = root["response"] as? JsonObject ?: return emptyList()
    return (response["items"] as? JsonArray).orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.text("id") ?: return@mapNotNull null
        val owner = row.text("owner_id") ?: return@mapNotNull null
        val title = row.text("title")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val artist = row.text("artist")?.takeIf(String::isNotBlank) ?: "VK Music"
        val album = row["album"] as? JsonObject
        val thumb = album?.get("thumb") as? JsonObject
        VkMusicTrack(
            ownerId = owner,
            id = id,
            title = title,
            artist = artist,
            durationSeconds = (row["duration"] as? JsonPrimitive)?.intOrNull ?: 0,
            artworkUrl = listOf("photo_1200", "photo_600", "photo_300", "photo_68")
                .firstNotNullOfOrNull { thumb?.text(it) },
        )
    }
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }
