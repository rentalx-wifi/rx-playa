package com.rentalx.playa.data.yandex

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rentalx.playa.data.discord.DiscordPresenceCoordinator
import com.rentalx.playa.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class YandexMusicSyncStage {
    DISCONNECTED,
    DISABLED,
    CONNECTING,
    RECONNECTING,
    WAITING,
    PLAYING,
    PAUSED,
    ADVERTISEMENT,
    RESTRICTED,
    AUTH_EXPIRED,
    ERROR,
}

data class YandexMusicNowPlaying(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
)

data class YandexMusicSyncState(
    val stage: YandexMusicSyncStage,
    val detail: String? = null,
)

/** Process-wide observable state and lifecycle entry point for the integration. */
object YandexMusicIntegration {
    private val _state = MutableStateFlow(YandexMusicSyncState(YandexMusicSyncStage.DISCONNECTED))
    val state = _state.asStateFlow()

    private val _nowPlaying = MutableStateFlow<YandexMusicNowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    fun ensureRunning(context: Context) {
        if (AppSettings.yandexMusicToken.value.isBlank() || !AppSettings.yandexMusicSyncEnabled.value) {
            stop(context)
            return
        }
        runCatching {
            // A plain service, not a foreground one: nothing here is worth a
            // permanent notification until a track actually starts, and
            // `startForegroundService` would demand one within seconds. The
            // service promotes itself the moment the engine has something to
            // report — see the state collector in its onCreate.
            context.applicationContext.startService(
                Intent(context.applicationContext, YandexMusicSyncService::class.java),
            )
        }.onFailure {
            update(YandexMusicSyncStage.ERROR)
        }
    }

    fun stop(context: Context) {
        context.applicationContext.stopService(Intent(context, YandexMusicSyncService::class.java))
        DiscordPresenceCoordinator.clear(DiscordPresenceCoordinator.Source.YANDEX_MUSIC)
        _nowPlaying.value = null
        _state.value = YandexMusicSyncState(
            if (AppSettings.yandexMusicToken.value.isBlank()) {
                YandexMusicSyncStage.DISCONNECTED
            } else {
                YandexMusicSyncStage.DISABLED
            },
        )
    }

    internal fun update(stage: YandexMusicSyncStage, detail: String? = null) {
        _state.value = YandexMusicSyncState(stage, detail)
    }

    internal fun playing(value: YandexMusicNowPlaying?) {
        _nowPlaying.value = value
        if (value != null) update(YandexMusicSyncStage.PLAYING, "${value.artist} — ${value.title}")
    }
}
