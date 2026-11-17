package com.music.bitchord.data.discord

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
 * BitChord playback has priority over a remotely observed Yandex Music track.
 * Keeping one Discord gateway connection prevents the two sources racing and
 * leaving an older network request visible on the profile.
 */
object DiscordPresenceCoordinator {
    enum class Source { BITCHORD, YANDEX_MUSIC }

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
            // A Yandex update while BitChord itself is playing is retained as a
            // fallback, but must not overwrite the higher-priority activity.
            if (source == Source.BITCHORD || before != after) refresh()
        }
    }

    fun clear(source: Source) {
        scope.launch {
            val before = effective()
            sources.remove(source)
            if (before != effective()) refresh()
        }
    }

    private fun effective(): Snapshot? = sources[Source.BITCHORD] ?: sources[Source.YANDEX_MUSIC]

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
        // ended while BitChord playback had priority over it.
        if (snapshot.durationMs > 0L && position >= snapshot.durationMs) {
            val staleSource = sources.entries.firstOrNull { it.value === snapshot }?.key
            if (staleSource != null) sources.remove(staleSource)
            refresh()
            return
        }

        presenceUp = true
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
                    TrackLog.d("BitChord", "Discord presence failed: ${it.message}", about = snapshot.song.videoId)
                }
            }
        }
    }

    private const val TEARDOWN_TIMEOUT_MS = 3_000L
}
