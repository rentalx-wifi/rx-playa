package com.rentalx.playa.data.soundcloud

import com.rentalx.playa.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The small public SoundCloud API surface needed by search, imports and playback.
 *
 * Keeping these calls here is important for restricted networks: metadata and
 * stream resolution go straight to the reachable mobile API instead of first
 * loading the desktop SoundCloud page through NewPipe.
 */
internal object SoundCloudApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = Http.client.newBuilder()
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(sourceUrl: String): JsonObject = requestWithClientId { clientId ->
        API_BASE.toHttpUrl().newBuilder()
            .addPathSegment("resolve")
            .addQueryParameter("url", sourceUrl)
            .addQueryParameter("client_id", clientId)
            .build()
    }.jsonObject

    suspend fun tracks(ids: List<String>): List<JsonObject> {
        if (ids.isEmpty()) return emptyList()
        val response = requestWithClientId { clientId ->
            API_BASE.toHttpUrl().newBuilder()
                .addPathSegment("tracks")
                .addQueryParameter("ids", ids.joinToString(","))
                .addQueryParameter("client_id", clientId)
                .build()
        }
        return (response as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    suspend fun searchTracks(
        query: String,
        offset: Int,
        limit: Int,
        continuationUrl: String? = null,
    ): JsonObject =
        requestWithClientId { clientId ->
            if (continuationUrl != null) {
                require(isTrustedSoundCloudSearchContinuation(continuationUrl)) {
                    "Invalid SoundCloud search continuation"
                }
                continuationUrl.toHttpUrl().newBuilder()
                    .setQueryParameter("client_id", clientId)
                    .build()
            } else {
                API_BASE.toHttpUrl().newBuilder()
                    .addPathSegments("search/tracks")
                    .addQueryParameter("q", query)
                    .addQueryParameter("client_id", clientId)
                    .addQueryParameter("limit", limit.toString())
                    .addQueryParameter("offset", offset.toString())
                    .build()
            }
        }.jsonObject

    suspend fun userCollection(userId: String, collection: String, continuation: String?): JsonObject {
        require(userId.matches(Regex("[0-9]+")) && collection in setOf("playlists", "likes"))
        return requestWithClientId { clientId ->
            if (continuation != null) {
                require(isTrustedSoundCloudUserContinuation(continuation, userId, collection)) {
                    "Invalid SoundCloud profile continuation"
                }
                continuation.toHttpUrl().newBuilder().setQueryParameter("client_id", clientId).build()
            } else {
                API_BASE.toHttpUrl().newBuilder()
                    .addPathSegments("users/$userId/$collection")
                    .addQueryParameter("client_id", clientId)
                    .addQueryParameter("limit", "100")
                    .addQueryParameter("linked_partitioning", "1")
                    .build()
            }
        }.jsonObject
    }

    suspend fun signedStreamUrl(endpoint: String, trackAuthorization: String?): String {
        require(isTrustedSoundCloudApiUrl(endpoint)) { "Invalid SoundCloud media endpoint" }
        val response = requestWithClientId { clientId ->
            endpoint.toHttpUrl().newBuilder()
                .setQueryParameter("client_id", clientId)
                .apply {
                    trackAuthorization?.takeIf(String::isNotBlank)?.let {
                        setQueryParameter("track_authorization", it)
                    }
                }
                .build()
        }.jsonObject
        return response.string("url")
            ?.takeIf(::isTrustedSoundCloudMediaUrl)
            ?: throw IOException("SoundCloud returned an invalid stream URL")
    }

    private suspend fun requestWithClientId(url: (String) -> HttpUrl): JsonElement =
        withContext(Dispatchers.IO) {
            val firstClientId = SoundCloudClientId.value()
            try {
                executeRetried(url(firstClientId))
            } catch (rejected: ClientIdRejectedException) {
                // Public web client ids rotate. Refresh once instead of leaving
                // every SoundCloud feature broken until the process restarts.
                val refreshed = SoundCloudClientId.refresh(firstClientId)
                executeRetried(url(refreshed))
            }
        }

    private suspend fun executeRetried(url: HttpUrl): JsonElement {
        var last: IOException? = null
        repeat(MAX_REQUEST_ATTEMPTS) { attempt ->
            try {
                return execute(url)
            } catch (retryable: RetryableSoundCloudException) {
                last = retryable
                if (attempt + 1 < MAX_REQUEST_ATTEMPTS) delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw last ?: IOException("SoundCloud request failed")
    }

    private fun execute(url: HttpUrl): JsonElement {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw ClientIdRejectedException(response.code)
            }
            if (response.code == 429 || response.code in 500..599) {
                throw RetryableSoundCloudException(response.code)
            }
            if (!response.isSuccessful) {
                throw IOException("SoundCloud request failed (${response.code})")
            }
            val body = response.body.string()
            runCatching { json.parseToJsonElement(body) }
                .getOrElse { throw IOException("SoundCloud returned an invalid response", it) }
        }
    }

    private class ClientIdRejectedException(code: Int) :
        IOException("SoundCloud rejected its public client id ($code)")

    private class RetryableSoundCloudException(code: Int) :
        IOException("SoundCloud temporarily failed ($code)")

    private const val API_BASE = "https://api-v2.soundcloud.com/"
    private const val REQUEST_TIMEOUT_SECONDS = 12L
    private const val MAX_REQUEST_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 250L
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36"
}

internal fun isTrustedSoundCloudApiUrl(raw: String): Boolean =
    trustedHttpsHost(raw) { host ->
        host == "api-v2.soundcloud.com" || host == "api-media.soundcloud.com" ||
            host.endsWith(".api-media.soundcloud.com")
    }

internal fun isTrustedSoundCloudSearchContinuation(raw: String): Boolean {
    val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return false
    return url.isHttps && url.host.equals("api-v2.soundcloud.com", ignoreCase = true) &&
        url.encodedPath == "/search/tracks"
}

internal fun isTrustedSoundCloudUserContinuation(raw: String, userId: String, collection: String): Boolean {
    val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return false
    return userId.matches(Regex("[0-9]+")) && collection in setOf("playlists", "likes") &&
        url.isHttps && url.host == "api-v2.soundcloud.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath == "/users/$userId/$collection"
}

internal fun isTrustedSoundCloudMediaUrl(raw: String): Boolean =
    trustedHttpsHost(raw) { host ->
        host == "soundcloud.cloud" || host.endsWith(".soundcloud.cloud") ||
            host == "sndcdn.com" || host.endsWith(".sndcdn.com") ||
            host == "soundcloud.com" || host.endsWith(".soundcloud.com")
    }

private inline fun trustedHttpsHost(raw: String, accept: (String) -> Boolean): Boolean {
    val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return false
    return url.isHttps && accept(url.host.lowercase())
}

private fun JsonObject.string(name: String): String? =
    this[name]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?.takeUnless { it == "null" }
