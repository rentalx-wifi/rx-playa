package com.music.bitchord.data.soundcloud

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounded, parallel replacement for NewPipe's unbounded sequential SoundCloud JS scan. */
internal object SoundCloudClientId {
    private val lock = Mutex()
    private val client = Http.client.newBuilder()
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun ensureInstalled() {
        if (installedClientId() != null) return
        lock.withLock {
            if (installedClientId() != null) return
            val id = extract() ?: throw IOException("SoundCloud client id is unavailable")
            install(id)
        }
    }

    suspend fun value(): String {
        ensureInstalled()
        return installedClientId() ?: throw IOException("SoundCloud client id is unavailable")
    }

    /** Refreshes an id rejected by SoundCloud without racing another request's refresh. */
    suspend fun refresh(rejectedValue: String): String = lock.withLock {
        val current = installedClientId()
        if (current != null && current != rejectedValue) return@withLock current
        clearInstalledClientId()
        val id = extract() ?: throw IOException("SoundCloud client id is unavailable")
        install(id)
        id
    }

    private suspend fun extract(): String? = withContext(Dispatchers.IO) {
        for (homepageUrl in HOMEPAGES) {
            val homepage = runCatching { execute(homepageUrl) }.getOrNull() ?: continue
            // The official mobile shell currently embeds this directly and is
            // reachable on networks where the desktop host's TLS is filtered.
            soundCloudClientId(homepage)?.let { return@withContext it }
            val scripts = soundCloudScriptUrls(homepage).takeLast(MAX_SCRIPT_CANDIDATES).reversed()
            val fromScripts = supervisorScope {
                scripts.map { url ->
                    async(Dispatchers.IO) {
                        runCatching {
                            soundCloudClientId(
                                execute(url, range = "bytes=0-$SCRIPT_RANGE_END"),
                            )
                        }.getOrNull()
                    }
                }.awaitAll().firstNotNullOfOrNull { it }
            }
            if (fromScripts != null) return@withContext fromScripts
        }
        null
    }

    private fun execute(url: String, range: String? = null): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { range?.let { header("Range", it) } }
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("SoundCloud request failed (${response.code})")
            response.body.string()
        }
    }

    private fun installedClientId(): String? = runCatching {
        clientIdField().get(null) as? String
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun install(value: String) {
        runCatching { clientIdField().set(null, value) }
            .getOrElse { throw IOException("Could not initialize SoundCloud", it) }
    }

    private fun clearInstalledClientId() {
        runCatching { clientIdField().set(null, null) }
            .getOrElse { throw IOException("Could not refresh SoundCloud", it) }
    }

    private fun clientIdField() = Class.forName(SOUNDCLOUD_HELPER)
        .getDeclaredField("clientId")
        .apply { isAccessible = true }

    private const val SOUNDCLOUD_HELPER =
        "org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper"
    private val HOMEPAGES = listOf("https://m.soundcloud.com/", "https://soundcloud.com/")
    private const val REQUEST_TIMEOUT_SECONDS = 5L
    private const val MAX_SCRIPT_CANDIDATES = 10
    private const val SCRIPT_RANGE_END = 100_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
}

internal fun soundCloudScriptUrls(html: String): List<String> =
    Jsoup.parse(html, "https://soundcloud.com/")
        .select("script[src*=sndcdn.com/][src$=.js]")
        .mapNotNull { element -> element.absUrl("src").takeIf(String::isNotBlank) }
        .distinct()

internal fun soundCloudClientId(script: String): String? =
    SOUNDCLOUD_CLIENT_ID_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(script)?.groupValues?.getOrNull(1)
    }

private val SOUNDCLOUD_CLIENT_ID_PATTERNS = listOf(
    Regex("""["']clientId["']\s*:\s*["']([A-Za-z0-9_-]{20,})["']"""),
    Regex("""client_id\s*:\s*["']([A-Za-z0-9_-]{20,})["']"""),
    Regex("""client_id%3D([A-Za-z0-9_-]{20,})"""),
    Regex("""client_id=([A-Za-z0-9_-]{20,})"""),
)
