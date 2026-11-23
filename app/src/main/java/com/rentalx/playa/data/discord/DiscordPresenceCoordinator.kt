package com.rentalx.playa.data.discord

import android.content.Context
import com.rentalx.playa.data.TrackLog
import com.rentalx.playa.data.model.Song
import com.rentalx.playa.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One owner for Discord presence, regardless of where a track is playing.
 *
 * Playa playback has priority over a remotely observed Yandex Music track.
 * Keeping one Discord gateway connection prevents the two sources racing and
 * leaving an older network request visible on the profile.
 */
object DiscordPresenceCoordinator {
    enum class Source { PLAYA, YANDEX_MUSIC }

    data class Snapshot(
        val song: Song,
        val positionMs: Long,
        val durationMs: Long,
        val playbackSpeed: Float = 1f,
        val capturedAtMs: Long = System.currentTimeMillis(),
        val listenUrl: String = DiscordRPC.watchUrl(song),
        val defaultButton1Text: String = DiscordRPC.DEFAULT_BUTTON_1,
        val audioQuality: String? = null,
    ) {
        fun currentPosition(nowMs: Long): Long {
            val elapsed = ((nowMs - capturedAtMs).coerceAtLeast(0L) * playbackSpeed).toLong()
            val current = (positionMs + elapsed).coerceAtLeast(0L)
            return if (durationMs > 0L) current.coerceAtMost(durationMs) else current
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sources = mutableMapOf<Source, Snapshot>()
    private var appContext: Context? = null
    @Volatile
    private var rpc: DiscordRPC? = null
    private var updateJob: Job? = null
    private val updateMutex = Mutex()
    @Volatile
    private var updateGeneration = 0L
    private var presenceUp = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        scope.launch {
            combine(AppSettings.discordToken, AppSettings.discordRpcEnabled) { token, enabled ->
                token.takeIf { enabled && it.isNotBlank() }
            }.distinctUntilChanged().collect { token ->
                replaceConnection(token)
            }
        }

        scope.launch {
            combine<Any, List<Any>>(
                AppSettings.discordUseDetails,
                AppSettings.discordStatus,
                AppSettings.discordActivityType,
                AppSettings.discordActivityName,
                AppSettings.discordButton1Text,
                AppSettings.discordButton1Visible,
                AppSettings.discordButton2Text,
                AppSettings.discordButton2Visible,
            ) { it.toList() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refresh() }
        }

        scope.launch {
            AppSettings.meteredConnection.drop(1).collect { metered ->
                if (metered == null) return@collect
                val current = rpc ?: return@collect
                withContext(Dispatchers.IO) { runCatching { current.wakeUp() } }
                if (presenceUp) refresh()
            }
        }
    }

    fun publish(source: Source, snapshot: Snapshot) {
        scope.launch {
            val before = effective()
            sources[source] = snapshot
            val after = effective()
            // A Yandex update while Playa itself is playing is retained as a
            // fallback, but must not overwrite the higher-priority activity.
            if (source == Source.PLAYA || before != after) refresh()
        }
    }

    fun clear(source: Source) {
        scope.launch {
            val before = effective()
            sources.remove(source)
            if (before != effective()) refresh()
        }
    }

    private fun effective(): Snapshot? = sources[Source.PLAYA] ?: sources[Source.YANDEX_MUSIC]

    private suspend fun replaceConnection(token: String?) {
        updateGeneration++
        updateJob?.cancel()
        val old = rpc
        rpc = null
        if (old != null) {
            val wasUp = presenceUp
            presenceUp = false
            withContext(Dispatchers.IO + NonCancellable) {
                withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) {
                    if (wasUp) runCatching { old.close() }
                }
                runCatching { old.closeRPC() }
            }
        }
        if (token == null) return
        val context = appContext ?: return
        rpc = DiscordRPC(context, token)
        // A new gateway hears this session's current card first, whatever the
        // window said about the old one.
        lastPushedKey = null
        lastPushAtMs = 0L
        refresh()
    }

    private fun refresh() {
        val currentRpc = rpc ?: return
        val snapshot = effective()
        val generation = ++updateGeneration
        updateJob?.cancel()
        if (snapshot == null) {
            if (!presenceUp) return
            presenceUp = false
            // The next card is a new one whatever it says: a track replayed
            // after its card closed must not wait out the old window.
            lastPushedKey = null
            lastPushAtMs = 0L
            updateJob = scope.launch(Dispatchers.IO) {
                updateMutex.withLock {
                    if (generation == updateGeneration && currentRpc === rpc) {
                        runCatching { currentRpc.close() }
                    }
                }
            }
            return
        }

        val now = System.currentTimeMillis()
        val position = snapshot.currentPosition(now)
        // A stale fallback must never resurrect a card after its own track has
        // ended while Playa playback had priority over it.
        if (snapshot.durationMs > 0L && position >= snapshot.durationMs) {
            val staleSource = sources.entries.firstOrNull { it.value === snapshot }?.key
            if (staleSource != null) sources.remove(staleSource)
            refresh()
            return
        }

        presenceUp = true
        val key = presenceKey(snapshot)
        if (!shouldPushPresence(now, lastPushAtMs, key, lastPushedKey, MIN_PUSH_INTERVAL_MS)) {
            // Coalesced, not dropped: the card carries a position, and the one
            // that goes out when the window ends carries the latest of it.
            if (pendingPush?.isActive != true) {
                val wait = (MIN_PUSH_INTERVAL_MS - (now - lastPushAtMs)).coerceAtLeast(0L)
                pendingPush = scope.launch {
                    delay(wait)
                    refresh()
                }
            }
            return
        }
        lastPushAtMs = now
        lastPushedKey = key
        updateJob = scope.launch(Dispatchers.IO) {
            updateMutex.withLock {
                if (generation != updateGeneration || currentRpc !== rpc) {
                    return@withLock
                }
                currentRpc.updateSong(
                    song = snapshot.song,
                    currentPlaybackTimeMillis = position,
                    durationMillis = snapshot.durationMs,
                    playbackSpeed = snapshot.playbackSpeed,
                    useDetails = AppSettings.discordUseDetails.value,
                    status = AppSettings.discordStatus.value,
                    button1Text = AppSettings.discordButton1Text.value,
                    button1Visible = AppSettings.discordButton1Visible.value,
                    button2Text = AppSettings.discordButton2Text.value,
                    button2Visible = AppSettings.discordButton2Visible.value,
                    activityType = AppSettings.discordActivityType.value,
                    activityName = AppSettings.discordActivityName.value,
                    audioQuality = snapshot.audioQuality,
                    listenUrl = snapshot.listenUrl,
                    defaultButton1Text = snapshot.defaultButton1Text,
                ).onFailure {
                    TrackLog.d("Playa", "Discord presence failed: ${it.message}", about = snapshot.song.videoId)
                }
            }
        }
    }

    /**
     * What the card would say, apart from where in the track it is.
     *
     * The position is deliberately absent: it moves every state frame, and
     * resending the card for it is what tripped Discord's rate limit. A new
     * track, a new setting or a new source all change this key and go out at
     * once; the same card again waits for [MIN_PUSH_INTERVAL_MS].
     */
    private fun presenceKey(snapshot: Snapshot): String = buildString {
        append(snapshot.song.videoId).append('|')
        append(snapshot.durationMs).append('|')
        append(snapshot.playbackSpeed).append('|')
        append(snapshot.audioQuality).append('|')
        append(snapshot.listenUrl).append('|')
        append(AppSettings.discordUseDetails.value).append('|')
        append(AppSettings.discordStatus.value).append('|')
        append(AppSettings.discordActivityType.value).append('|')
        append(AppSettings.discordActivityName.value).append('|')
        append(AppSettings.discordButton1Text.value).append('|')
        append(AppSettings.discordButton1Visible.value).append('|')
        append(AppSettings.discordButton2Text.value).append('|')
        append(AppSettings.discordButton2Visible.value)
    }

    private var lastPushAtMs = 0L
    private var lastPushedKey: String? = null

    /** A card held back by the window, waiting to go out once it ends. */
    private var pendingPush: Job? = null

    private const val MIN_PUSH_INTERVAL_MS = 15_000L

    private const val TEARDOWN_TIMEOUT_MS = 3_000L
}

/**
 * Whether a presence card may go out now.
 *
 * A different card goes out at once; the same one again waits for the window.
 * Discord rate-limits presence updates, and an update per Yandex state frame
 * got the card stalled or dropped instead of moving.
 */
internal fun shouldPushPresence(
    nowMs: Long,
    lastPushAtMs: Long,
    key: String,
    lastKey: String?,
    windowMs: Long,
): Boolean = key != lastKey || nowMs - lastPushAtMs >= windowMs
