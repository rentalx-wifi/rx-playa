package com.rentalx.playa.data.yandex

import com.rentalx.playa.BuildConfig
import com.rentalx.playa.data.Http
import com.rentalx.playa.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random

internal enum class YnisonSuppression {
    NONE,
    NO_ACTIVE_DEVICE,
    PAUSED,
    INJECTED_AUDIO,
    ADVERTISEMENT,
    EMPTY_QUEUE,
    NOT_A_TRACK,
}

internal data class YnisonPlayable(
    val trackId: String,
    val albumId: String?,
    val fallbackTitle: String,
    val fallbackCoverUrl: String?,
    val progressMs: Long,
    val durationMs: Long,
    val paused: Boolean,
    val playbackSpeed: Double,
    val statusTimestampMs: Long,
) {
    fun positionAt(nowMs: Long): Long {
        val elapsed = if (!paused && statusTimestampMs > 0L) {
            (max(0L, nowMs - statusTimestampMs) * playbackSpeed).toLong()
        } else {
            0L
        }
        val position = (progressMs + elapsed).coerceAtLeast(0L)
        return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
    }
}

internal data class YnisonPlaybackState(
    val playable: YnisonPlayable?,
    val suppression: YnisonSuppression,
)

internal class YnisonUnauthorizedException : IOException("Ynison rejected the OAuth token")

private class YnisonServerException(
    message: String,
    val backoffMs: List<Long> = emptyList(),
    val goAwaySeconds: Long? = null,
) : IOException(message)

/**
 * Small Ynison remote-controller client.
 *
 * Ynison is the live player-state protocol used by Yandex Music itself. The
 * older REST queue API has no reliable pause clock and may describe the last
 * queue long after playback ended, so it cannot drive an honest presence.
 */
internal class YandexMusicYnison(
    private val token: String,
    client: OkHttpClient = Http.client,
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val deviceId = MessageDigest.getInstance("SHA-256")
        .digest("playa-yandex-ynison:$token".toByteArray())
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    suspend fun collect(
        onConnected: () -> Unit,
        onDisconnected: (Throwable) -> Unit,
        onState: suspend (YnisonPlaybackState) -> Unit,
    ) {
        var attempt = 0
        var serverBackoff = DEFAULT_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            // A socket that died because the device has no network is not
            // something to retry: under Doze that was a wake-up every thirty
            // seconds until morning, for a connection nothing could carry.
            if (attempt > 0) awaitNetwork()
            var receivedState = false
            try {
                runConnection {
                    if (!receivedState) onConnected()
                    receivedState = true
                    attempt = 0
                    onState(it)
                }
                throw IOException("Ynison closed the state connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: YnisonUnauthorizedException) {
                throw e
            } catch (e: YnisonServerException) {
                if (e.goAwaySeconds != null) {
                    onDisconnected(e)
                    delay(e.goAwaySeconds.coerceAtLeast(1L) * 1000L)
                    continue
                }
                if (e.backoffMs.isNotEmpty()) serverBackoff = e.backoffMs
                onDisconnected(e)
            } catch (e: Throwable) {
                onDisconnected(e)
            }

            attempt++
            // Grows past the server's own hint instead of sitting on its last
            // value. That hint used to become the ceiling for the rest of the
            // session: four failures and every retry after them was thirty
            // seconds apart, however long the outage lasted.
            val hint = serverBackoff[(attempt - 1).coerceAtMost(serverBackoff.lastIndex)]
            val grown = 1_000L shl (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
            delay(minOf(maxOf(hint, grown), MAX_BACKOFF_MS) + Random.nextLong(0L, 251L))
        }
    }

    /**
     * Suspends until the device has a network again, when it has none.
     *
     * The reading is the one the Wi-Fi-only download gate uses: null exactly
     * when there is no active network. Waiting on it rather than polling means
     * a night without a signal costs one wake-up, on the callback that says the
     * network is back, instead of a reconnect per backoff step.
     */
    private suspend fun awaitNetwork() {
        if (AppSettings.meteredConnection.value != null) return
        // Bounded: a device whose connectivity was never observed leaves this
        // null with a network up, and waiting on it forever would be a sync that
        // never comes back. Fifteen minutes is the longest a retry waits anyway.
        withTimeoutOrNull(MAX_BACKOFF_MS) { AppSettings.meteredConnection.first { it != null } }
    }

    private suspend fun runConnection(onState: suspend (YnisonPlaybackState) -> Unit) {
        val redirectSocket = openSocket(REDIRECT_URL, client, subprotocol(ticket = null, sessionId = null))
        val redirectFrame = redirectSocket.receiveRedirect()
        parseServerError(redirectFrame)?.let { throw it }
        val redirect = parseYnisonRedirect(redirectFrame)
        val pingSeconds = redirect.keepAliveSeconds.coerceIn(5L, 60L)
        val stateClient = client.newBuilder().pingInterval(pingSeconds, TimeUnit.SECONDS).build()
        val stateSocket = openSocket(
            redirect.stateUrl,
            stateClient,
            subprotocol(redirect.ticket, redirect.sessionId),
        )
        try {
            if (!stateSocket.send(fullStateRequest())) throw IOException("Ynison socket closed before registration")
            while (currentCoroutineContext().isActive) {
                val frame = stateSocket.receiveText()
                parseServerError(frame)?.let { throw it }
                onState(parseYnisonPlaybackState(frame))
            }
        } finally {
            stateSocket.close()
        }
    }

    private suspend fun openSocket(url: HttpUrl, socketClient: OkHttpClient, protocols: String): YnisonSocketConnection {
        val events = Channel<YnisonSocketEvent>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(url)
            .header("Origin", "https://music.yandex.ru")
            .header("Authorization", "OAuth $token")
            .header("Sec-WebSocket-Protocol", protocols)
            .build()
        val socket = socketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(YnisonSocketEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(YnisonSocketEvent.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(YnisonSocketEvent.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code in setOf(401, 403)) {
                    events.trySend(YnisonSocketEvent.Failed(YnisonUnauthorizedException()))
                } else {
                    events.trySend(YnisonSocketEvent.Failed(t))
                }
            }
        })
        val connection = YnisonSocketConnection(socket, events)
        connection.awaitOpen()
        return connection
    }

    private fun subprotocol(ticket: String?, sessionId: Long?): String {
        val innerInfo = buildJsonObject {
            put("app_name", "RX Playa")
            put("type", "1")
        }.toString()
        val descriptor = buildJsonObject {
            put("Ynison-Device-Id", deviceId)
            put("Ynison-Device-Info", innerInfo)
            if (ticket != null && sessionId != null) {
                put("Ynison-Redirect-Ticket", ticket)
                put("Ynison-Session-Id", sessionId.toString())
            }
        }.toString()
        val encoded = URLEncoder.encode(descriptor, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "Bearer, v2, $encoded"
    }

    private fun fullStateRequest(): String = buildJsonObject {
        put("update_full_state", buildJsonObject {
            put("player_state", buildJsonObject {
                put("status", buildJsonObject {
                    put("progress_ms", "0")
                    put("duration_ms", "0")
                    put("paused", true)
                    put("playback_speed", 1.0)
                    put("version", version(timestampMs = 0L))
                })
                put("player_queue", buildJsonObject {
                    put("entity_id", "")
                    put("entity_type", "VARIOUS")
                    put("current_playable_index", -1)
                    put("options", buildJsonObject { put("repeat_mode", "NONE") })
                    put("version", version(timestampMs = 0L))
                    put("entity_context", "BASED_ON_ENTITY_BY_DEFAULT")
                    put("from_optional", "")
                })
            })
            put("device", buildJsonObject {
                put("capabilities", buildJsonObject {
                    put("can_be_player", false)
                    put("can_be_remote_controller", true)
                    put("volume_granularity", 0)
                })
                put("info", buildJsonObject {
                    put("device_id", deviceId)
                    put("type", "WEB")
                    put("title", "RX Playa")
                    put("app_name", "playa")
                    put("app_version", BuildConfig.VERSION_NAME)
                })
                put("volume_info", buildJsonObject { put("volume", 0.0) })
            })
            put("is_currently_active", false)
        })
        put("player_action_timestamp_ms", "0")
        put("rid", UUID.randomUUID().toString())
        put("activity_interception_type", "DO_NOT_INTERCEPT_BY_DEFAULT")
    }.toString()

    private fun version(timestampMs: Long): JsonObject = buildJsonObject {
        put("device_id", deviceId)
        put("version", "0")
        put("timestamp_ms", timestampMs.toString())
    }

    private fun parseServerError(frame: String): YnisonServerException? {
        val root = runCatching { parseObject(frame) }.getOrNull() ?: return null
        val error = root.objectAt("error") ?: return null
        val details = error.objectAt("details", "extra_headers", "extraHeaders") ?: JsonObject(emptyMap())
        val grpc = error.longAt("grpc_code", "grpcCode")
        val http = error.longAt("http_code", "httpCode")
        if (grpc in setOf(7L, 16L) || http in setOf(401L, 403L)) throw YnisonUnauthorizedException()
        val backoff = details.stringAt("ynison-backoff-millis")
            ?.split(':')?.mapNotNull(String::toLongOrNull).orEmpty()
        val goAway = details.stringAt("ynison-go-away-for-seconds")?.toLongOrNull()
        return YnisonServerException(
            message = error.stringAt("message") ?: "Ynison server error",
            backoffMs = backoff,
            goAwaySeconds = goAway,
        )
    }

    private companion object {
        val REDIRECT_URL =
            "https://ynison.music.yandex.ru/redirector.YnisonRedirectService/GetRedirectToYnison".toHttpUrl()
        val DEFAULT_BACKOFF_MS = listOf(0L, 1_000L, 5_000L, 30_000L)

        /** The longest a reconnect waits, so a dead night is a few tries, not thousands. */
        const val MAX_BACKOFF_MS = 900_000L

        /** 1 s doubling: 1, 2, 4, … 1024 s, which [MAX_BACKOFF_MS] then caps. */
        const val MAX_BACKOFF_SHIFT = 10
    }
}

internal data class YnisonRedirect(
    val stateUrl: HttpUrl,
    val ticket: String,
    val sessionId: Long,
    val keepAliveSeconds: Long,
)

internal fun parseYnisonRedirect(frame: String): YnisonRedirect {
    val root = parseObject(frame)
    val stateUrl = parseYnisonStateUrl(root.stringAt("host").orEmpty())
    val ticket = root.stringAt("redirect_ticket", "redirectTicket")?.trim().orEmpty()
    val session = root.longAt("session_id", "sessionId") ?: 0L
    if (ticket.isBlank() || session == 0L) {
        throw IOException("Ynison redirect response was incomplete or unsafe")
    }
    val keepAlive = root.objectAt("keep_alive_params", "keepAliveParams")
        ?.longAt("keep_alive_time_seconds", "keepAliveTimeSeconds")
        ?: DEFAULT_PING_SECONDS
    return YnisonRedirect(stateUrl, ticket, session, keepAlive)
}

private fun parseYnisonStateUrl(authority: String): HttpUrl {
    val parts = YNISON_AUTHORITY.matchEntire(authority)
        ?: throw IOException("Ynison redirect host was unsafe")
    val host = parts.groupValues[1].lowercase(Locale.ROOT)
    val portText = parts.groupValues[2]
    val port = if (portText.isEmpty()) 443 else portText.toIntOrNull()
    if (host.length > 253 || host.split('.').any { !DNS_LABEL.matches(it) } ||
        !(host == "music.yandex.ru" || host.endsWith(".music.yandex.ru")) ||
        port == null || port !in 1..65535
    ) {
        throw IOException("Ynison redirect host was unsafe")
    }
    // OkHttp uses HTTPS HttpUrls for WSS. Build from parsed components so the
    // authority cannot be reinterpreted as userinfo, an escape, or another URL.
    return HttpUrl.Builder()
        .scheme("https")
        .host(host)
        .port(port)
        .addPathSegments(STATE_SERVICE)
        .build()
}

internal fun parseYnisonPlaybackState(frame: String): YnisonPlaybackState {
    val root = parseObject(frame)
    val player = root.objectAt("player_state", "playerState")
        ?: return YnisonPlaybackState(null, YnisonSuppression.EMPTY_QUEUE)
    val activeDevice = root.stringAt("active_device_id_optional", "activeDeviceIdOptional")
    if (activeDevice.isNullOrBlank()) {
        return YnisonPlaybackState(null, YnisonSuppression.NO_ACTIVE_DEVICE)
    }

    val injection = player.objectAt("player_queue_inject_optional", "playerQueueInjectOptional")
    val injectedPlayable = injection?.objectAt("playable")
    val injectedType = injectedPlayable?.stringAt("playable_type", "playableType").orEmpty()
    val injectionPresent = injectedPlayable != null && (
        !injectedPlayable.stringAt("playable_id", "playableId").isNullOrBlank() ||
            injectedType.isNotBlank()
        )
    if (injectionPresent) {
        val advertisement = injectedType.equals("AD", true) || injectedType.equals("PREROLL", true) ||
            injectedType == "2" || injectedType == "3"
        return YnisonPlaybackState(
            null,
            if (advertisement) YnisonSuppression.ADVERTISEMENT else YnisonSuppression.INJECTED_AUDIO,
        )
    }

    val status = player.objectAt("status")
        ?: return YnisonPlaybackState(null, YnisonSuppression.PAUSED)
    val paused = status.booleanAt("paused") ?: true
    if (paused) return YnisonPlaybackState(null, YnisonSuppression.PAUSED)

    val queue = player.objectAt("player_queue", "playerQueue")
        ?: return YnisonPlaybackState(null, YnisonSuppression.EMPTY_QUEUE)
    val index = queue.longAt("current_playable_index", "currentPlayableIndex")?.toInt() ?: -1
    val list = queue.arrayAt("playable_list", "playableList")
        ?: return YnisonPlaybackState(null, YnisonSuppression.EMPTY_QUEUE)
    val playable = list.getOrNull(index) as? JsonObject
        ?: return YnisonPlaybackState(null, YnisonSuppression.EMPTY_QUEUE)
    val type = playable.stringAt("playable_type", "playableType").orEmpty()
    if (!type.equals("TRACK", true) && type != "1") {
        return YnisonPlaybackState(null, YnisonSuppression.NOT_A_TRACK)
    }
    val trackId = playable.stringAt("playable_id", "playableId")?.takeIf { it.isNotBlank() }
        ?: return YnisonPlaybackState(null, YnisonSuppression.EMPTY_QUEUE)
    val version = status.objectAt("version")
    return YnisonPlaybackState(
        playable = YnisonPlayable(
            trackId = trackId,
            albumId = playable.stringAt("album_id_optional", "albumIdOptional"),
            fallbackTitle = playable.stringAt("title").orEmpty(),
            fallbackCoverUrl = playable.stringAt("cover_url_optional", "coverUrlOptional").toArtworkUrl(),
            progressMs = status.longAt("progress_ms", "progressMs") ?: 0L,
            durationMs = status.longAt("duration_ms", "durationMs") ?: 0L,
            paused = false,
            playbackSpeed = (status.doubleAt("playback_speed", "playbackSpeed") ?: 1.0)
                .takeIf { it > 0.0 } ?: 1.0,
            statusTimestampMs = version?.longAt("timestamp_ms", "timestampMs") ?: 0L,
        ),
        suppression = YnisonSuppression.NONE,
    )
}

private fun parseObject(raw: String): JsonObject = try {
    JSON.parseToJsonElement(raw).jsonObject
} catch (e: Throwable) {
    throw IOException("Ynison returned malformed JSON", e)
}

internal sealed interface YnisonSocketEvent {
    data object Open : YnisonSocketEvent
    data class Text(val value: String) : YnisonSocketEvent
    data class Closed(val code: Int, val reason: String) : YnisonSocketEvent
    data class Failed(val error: Throwable) : YnisonSocketEvent
}

internal class YnisonSocketConnection(
    private val socket: WebSocket,
    private val events: Channel<YnisonSocketEvent>,
) {
    suspend fun awaitOpen() {
        try {
            awaitYnisonHandshake {
                when (val event = events.receive()) {
                    YnisonSocketEvent.Open -> Unit
                    is YnisonSocketEvent.Failed -> throw event.error
                    is YnisonSocketEvent.Closed -> throw IOException("WebSocket closed during handshake (${event.code})")
                    is YnisonSocketEvent.Text -> throw IOException("WebSocket sent data before opening")
                }
            }
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    suspend fun receiveRedirect(): String = try {
        awaitYnisonHandshake { receiveText() }
    } finally {
        close()
    }

    suspend fun receiveText(): String {
        while (true) {
            when (val event = events.receive()) {
                YnisonSocketEvent.Open -> Unit
                is YnisonSocketEvent.Text -> return event.value
                is YnisonSocketEvent.Failed -> throw event.error
                is YnisonSocketEvent.Closed -> throw IOException("WebSocket closed (${event.code}): ${event.reason}")
            }
        }
    }

    fun send(text: String): Boolean = socket.send(text)

    fun close() {
        events.cancel()
        // A close frame cannot release a connection whose HTTP upgrade stalled.
        socket.cancel()
    }
}

private suspend fun <T : Any> awaitYnisonHandshake(block: suspend () -> T): T {
    val result = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { block() }
    if (result != null) return result
    // withTimeoutOrNull handles only its own deadline; parent cancellation wins.
    currentCoroutineContext().ensureActive()
    throw IOException("Ynison handshake timed out")
}

private const val STATE_SERVICE = "ynison_state.YnisonStateService/PutYnisonState"
private const val HANDSHAKE_TIMEOUT_MS = 10_000L
private const val DEFAULT_PING_SECONDS = 20L
private val YNISON_AUTHORITY = Regex("""([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?""")
private val DNS_LABEL = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?""")
private val JSON = Json { ignoreUnknownKeys = true }
