package com.music.bitchord.data.yandex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.discord.DiscordPresenceCoordinator
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.LinkedHashMap
import kotlin.math.max

/**
 * User-visible foreground synchronization for playback occurring on another
 * Yandex Music device (web player, speaker, desktop client, and so on).
 */
class YandexMusicSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = YandexMusicApi()
    private var engine: Engine? = null
    private var syncJob: Job? = null
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        notificationJob = scope.launch {
            combine(YandexMusicIntegration.state, YandexMusicIntegration.nowPlaying) { state, track ->
                state to track
            }.collect { (state, track) ->
                runCatching {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, notification(state, track))
                }
            }
        }
        scope.launch {
            combine(
                AppSettings.yandexMusicToken,
                AppSettings.yandexMusicSyncEnabled,
            ) { token, enabled -> token.trim().takeIf { enabled && it.isNotEmpty() } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    stopEngine()
                    if (token == null) {
                        YandexMusicIntegration.update(
                            if (AppSettings.yandexMusicToken.value.isBlank()) {
                                YandexMusicSyncStage.DISCONNECTED
                            } else {
                                YandexMusicSyncStage.DISABLED
                            },
                        )
                        stopSelf()
                    } else {
                        syncJob = launch { synchronize(token) }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppSettings.setYandexMusicSyncEnabled(false)
            YandexMusicIntegration.update(YandexMusicSyncStage.DISABLED)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun synchronize(token: String) {
        YandexMusicIntegration.update(YandexMusicSyncStage.CONNECTING)
        val account = loadAccount(token) ?: return
        AppSettings.setYandexMusicAccount(account.displayName, account.hasPlus)
        val newEngine = Engine(token)
        engine = newEngine
        try {
            newEngine.run()
        } catch (e: YnisonUnauthorizedException) {
            authExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            YandexMusicIntegration.update(YandexMusicSyncStage.ERROR)
            newEngine.clear()
        }
    }

    private suspend fun loadAccount(token: String): YandexMusicAccount? {
        var delayMs = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                return api.account(token)
            } catch (_: YandexMusicApiException.Unauthorized) {
                authExpired()
                return null
            } catch (_: YandexMusicApiException.AccessDenied) {
                authExpired()
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                YandexMusicIntegration.update(YandexMusicSyncStage.RECONNECTING)
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        return null
    }

    private fun authExpired() {
        engine?.clear()
        YandexMusicIntegration.update(YandexMusicSyncStage.AUTH_EXPIRED)
        stopSelf()
    }

    private fun stopEngine() {
        syncJob?.cancel()
        syncJob = null
        engine?.clear()
        engine = null
    }

    private inner class Engine(private val token: String) {
        private val cache = object : LinkedHashMap<String, YandexMusicTrack>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, YandexMusicTrack>?): Boolean =
                size > 32
        }
        private var identity: String? = null
        private var latestPlayable: YnisonPlayable? = null
        private var suppressedIdentity: String? = null
        private var metadataJob: Job? = null
        private var expiryJob: Job? = null

        suspend fun run() {
            YandexMusicYnison(token).collect(
                onConnected = {
                    YandexMusicIntegration.update(YandexMusicSyncStage.WAITING)
                },
                onDisconnected = {
                    clearPresence()
                    YandexMusicIntegration.update(YandexMusicSyncStage.RECONNECTING)
                },
                onState = ::onState,
            )
        }

        private suspend fun onState(state: YnisonPlaybackState) {
            val playable = state.playable
            if (playable == null) {
                identity = null
                latestPlayable = null
                suppressedIdentity = null
                metadataJob?.cancel()
                expiryJob?.cancel()
                clearPresence()
                val stage = when (state.suppression) {
                    YnisonSuppression.PAUSED -> YandexMusicSyncStage.PAUSED
                    YnisonSuppression.ADVERTISEMENT, YnisonSuppression.INJECTED_AUDIO ->
                        YandexMusicSyncStage.ADVERTISEMENT
                    else -> YandexMusicSyncStage.WAITING
                }
                YandexMusicIntegration.update(stage)
                return
            }

            val key = "${playable.trackId}:${playable.albumId.orEmpty()}"
            if (key != identity) {
                identity = key
                suppressedIdentity = null
                metadataJob?.cancel()
                expiryJob?.cancel()
            }
            latestPlayable = playable
            if (suppressedIdentity == key) return

            val cached = cache[key]
            if (cached != null) {
                publish(key, cached, playable)
                return
            }
            if (metadataJob?.isActive == true) return
            metadataJob = scope.launch { resolveMetadata(key, playable) }
        }

        private suspend fun resolveMetadata(key: String, initial: YnisonPlayable) {
            var retryMs = 2_000L
            while (currentCoroutineContext().isActive && identity == key) {
                try {
                    val track = api.track(token, initial.trackId, initial.albumId)
                    if (!track.isUsable()) {
                        suppress(key)
                        return
                    }
                    cache[key] = track
                    latestPlayable?.takeIf { identity == key }?.let { publish(key, track, it) }
                    return
                } catch (_: YandexMusicApiException.Unauthorized) {
                    authExpired()
                    return
                } catch (_: YandexMusicApiException.AccessDenied) {
                    suppress(key)
                    return
                } catch (_: YandexMusicApiException.EmptyResult) {
                    suppress(key)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    clearPresence()
                    YandexMusicIntegration.update(YandexMusicSyncStage.RECONNECTING)
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(30_000L)
                } catch (_: Throwable) {
                    suppress(key)
                    return
                }
            }
        }

        private fun suppress(key: String) {
            suppressedIdentity = key
            clearPresence()
            YandexMusicIntegration.update(YandexMusicSyncStage.RESTRICTED)
        }

        private fun publish(key: String, track: YandexMusicTrack, playable: YnisonPlayable) {
            if (identity != key) return
            val duration = max(playable.durationMs, track.durationMs)
            if (duration <= 0L) {
                suppress(key)
                return
            }
            val now = System.currentTimeMillis()
            val position = playable.positionAt(now).coerceAtMost(duration)
            if (position >= duration) {
                clearPresence()
                YandexMusicIntegration.update(YandexMusicSyncStage.WAITING)
                return
            }
            val song = Song(
                videoId = "yandex:${track.id}",
                title = track.title,
                artist = track.artist,
                thumbnailUrl = track.coverUrl ?: playable.fallbackCoverUrl,
                durationText = formatDuration(duration),
                artists = track.artists.map(::ArtistRef),
                albumId = track.albumId,
                albumName = track.albumTitle,
            )
            DiscordPresenceCoordinator.publish(
                DiscordPresenceCoordinator.Source.YANDEX_MUSIC,
                DiscordPresenceCoordinator.Snapshot(
                    song = song,
                    positionMs = position,
                    durationMs = duration,
                    playbackSpeed = playable.playbackSpeed.toFloat(),
                    capturedAtMs = now,
                    listenUrl = track.webUrl(),
                    defaultButton1Text = getString(R.string.yandex_music_listen_button),
                ),
            )
            YandexMusicIntegration.playing(
                YandexMusicNowPlaying(
                    title = track.title,
                    artist = track.artist,
                    album = track.albumTitle,
                    coverUrl = track.coverUrl ?: playable.fallbackCoverUrl,
                    positionMs = position,
                    durationMs = duration,
                ),
            )
            expiryJob?.cancel()
            expiryJob = scope.launch {
                val wallRemaining = ((duration - position) / playable.playbackSpeed).toLong().coerceAtLeast(0L)
                delay(wallRemaining + TRACK_END_GRACE_MS)
                if (identity == key) {
                    clearPresence()
                    YandexMusicIntegration.update(YandexMusicSyncStage.WAITING)
                }
            }
        }

        fun clear() {
            metadataJob?.cancel()
            expiryJob?.cancel()
            clearPresence()
        }

        private fun clearPresence() {
            DiscordPresenceCoordinator.clear(DiscordPresenceCoordinator.Source.YANDEX_MUSIC)
            YandexMusicIntegration.playing(null)
        }
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(YandexMusicIntegration.state.value, YandexMusicIntegration.nowPlaying.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun notification(state: YandexMusicSyncState, track: YandexMusicNowPlaying?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, YandexMusicSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = track?.let { "${it.artist} — ${it.title}" } ?: when (state.stage) {
            YandexMusicSyncStage.CONNECTING -> getString(R.string.yandex_music_status_connecting)
            YandexMusicSyncStage.RECONNECTING -> getString(R.string.yandex_music_status_reconnecting)
            YandexMusicSyncStage.ADVERTISEMENT -> getString(R.string.yandex_music_status_advertisement)
            YandexMusicSyncStage.PAUSED -> getString(R.string.yandex_music_status_paused)
            YandexMusicSyncStage.RESTRICTED -> getString(R.string.yandex_music_status_restricted)
            YandexMusicSyncStage.AUTH_EXPIRED -> getString(R.string.yandex_music_status_auth_expired)
            else -> getString(R.string.yandex_music_status_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(getString(R.string.yandex_music_sync_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.yandex_music_sync_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.yandex_music_sync_channel_description)
                setShowBadge(false)
            },
        )
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        YandexMusicIntegration.update(YandexMusicSyncStage.ERROR)
        stopSelf(startId)
    }

    override fun onDestroy() {
        stopEngine()
        notificationJob?.cancel()
        DiscordPresenceCoordinator.clear(DiscordPresenceCoordinator.Source.YANDEX_MUSIC)
        val stage = YandexMusicIntegration.state.value.stage
        if (stage !in setOf(
                YandexMusicSyncStage.AUTH_EXPIRED,
                YandexMusicSyncStage.DISABLED,
                YandexMusicSyncStage.DISCONNECTED,
                YandexMusicSyncStage.ERROR,
            )
        ) {
            YandexMusicIntegration.update(YandexMusicSyncStage.RECONNECTING)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun formatDuration(milliseconds: Long): String {
        val seconds = milliseconds / 1000L
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private companion object {
        const val CHANNEL_ID = "yandex_music_sync"
        const val NOTIFICATION_ID = 0x8177
        const val ACTION_STOP = "com.music.bitchord.yandex.STOP_SYNC"
        const val TRACK_END_GRACE_MS = 2_000L
    }
}
