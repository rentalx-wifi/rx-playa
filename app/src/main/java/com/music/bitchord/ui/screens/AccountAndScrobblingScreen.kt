package com.music.bitchord.ui.screens

import com.music.bitchord.ui.icons.SfIcons
import com.music.bitchord.auth.clearSpotifyWebSession
import com.music.bitchord.auth.clearYandexMusicWebSession
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.spotify_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import com.music.bitchord.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.soundcloud.SoundCloudOAuth
import com.music.bitchord.data.yandex.YandexMusicIntegration
import com.music.bitchord.data.yandex.YandexMusicSyncStage
import kotlin.math.roundToInt

@Composable
fun AccountAndScrobblingScreen(
    signedIn: Boolean,
    account: Account?,
    channelName: String?,
    onSignIn: () -> Unit,
    onImportGoogleCookie: () -> Unit,
    onSwitchChannel: () -> Unit,
    onSignOut: () -> Unit,
    onOpenListenBrainzLogin: () -> Unit,
    onOpenLastfmLogin: () -> Unit,
    onOpenDiscord: () -> Unit,
    onOpenSpotify: () -> Unit,
    onOpenSoundCloudLogin: () -> Unit,
    onDisconnectSoundCloud: () -> Unit,
    onImportSoundCloudPlaylist: () -> Unit,
    onOpenVkLogin: () -> Unit,
    onOpenVkMusic: () -> Unit,
    onImportVkPlaylist: () -> Unit,
    onDisconnectVk: () -> Unit,
    onOpenYandexMusicLogin: () -> Unit,
    onImportYandexPlaylist: () -> Unit,
    /** Copies a YouTube Music playlist by its link — needs no account. */
    onImportYoutubePlaylist: () -> Unit,
    guestProfile: Boolean,
    onEditGuestProfile: () -> Unit,
    onDeleteGuestProfile: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val lastfmEnabled by AppSettings.lastfmEnabled.collectAsStateWithLifecycle()
    val lastfmUsername by AppSettings.lastfmUsername.collectAsStateWithLifecycle()
    val lastfmSessionKey by AppSettings.lastfmSessionKey.collectAsStateWithLifecycle()
    val lastfmScrobbleEnabled by AppSettings.lastfmScrobbleEnabled.collectAsStateWithLifecycle()
    val lastfmNowPlayingEnabled by AppSettings.lastfmNowPlaying.collectAsStateWithLifecycle()
    val lastfmPrimaryArtistOnly by AppSettings.lastfmPrimaryArtistOnly.collectAsStateWithLifecycle()
    val scrobbleMinDuration by AppSettings.scrobbleMinDuration.collectAsStateWithLifecycle()
    val scrobbleDelayPercent by AppSettings.scrobbleDelayPercent.collectAsStateWithLifecycle()
    val scrobbleDelaySeconds by AppSettings.scrobbleDelaySeconds.collectAsStateWithLifecycle()
    val listenBrainzEnabled by AppSettings.listenBrainzEnabled.collectAsStateWithLifecycle()
    val listenBrainzToken by AppSettings.listenBrainzToken.collectAsStateWithLifecycle()
    val listenBrainzPrimaryArtistOnly by AppSettings.listenBrainzPrimaryArtistOnly.collectAsStateWithLifecycle()
    val discordToken by AppSettings.discordToken.collectAsStateWithLifecycle()
    val discordUsername by AppSettings.discordUsername.collectAsStateWithLifecycle()
    val discordRpcEnabled by AppSettings.discordRpcEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val yandexToken by AppSettings.yandexMusicToken.collectAsStateWithLifecycle()
    val yandexAccount by AppSettings.yandexMusicAccountName.collectAsStateWithLifecycle()
    val yandexHasPlus by AppSettings.yandexMusicHasPlus.collectAsStateWithLifecycle()
    val yandexSyncEnabled by AppSettings.yandexMusicSyncEnabled.collectAsStateWithLifecycle()
    val soundCloudToken by AppSettings.soundCloudAccessToken.collectAsStateWithLifecycle()
    val soundCloudAccount by AppSettings.soundCloudAccountName.collectAsStateWithLifecycle()
    val vkToken by AppSettings.vkAccessToken.collectAsStateWithLifecycle()
    val vkAccount by AppSettings.vkAccountName.collectAsStateWithLifecycle()
    val yandexState by YandexMusicIntegration.state.collectAsStateWithLifecycle()
    val yandexNowPlaying by YandexMusicIntegration.nowPlaying.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var soundCloudLoginAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        soundCloudLoginAvailable = SoundCloudOAuth.isConfigured()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SettingsPageTitle(stringResource(R.string.account_integrations))

        // One card: who is signed in, which profile is listening, and the way
        // out, with the explanation of profiles underneath.
        SettingsGroup(
            footer = when {
                signedIn -> stringResource(R.string.account_profiles_help)
                guestProfile -> stringResource(R.string.guest_profile_help)
                else -> null
            },
            topSpacing = 0.dp,
        ) {
            AccountCard(
                signedIn = signedIn,
                account = account,
                onSignIn = onSignIn,
                onClick = if (guestProfile) onEditGuestProfile else onSwitchChannel,
                guest = guestProfile,
                grouped = true,
            )
            if (signedIn) {
                FullWidthDivider()
                SettingsRow(
                    icon = SfIcons.SwitchAccount,
                    title = stringResource(R.string.listen_as),
                    subtitle = channelName ?: stringResource(R.string.default_youtube_profile),
                    onClick = onSwitchChannel,
                )
                FullWidthDivider()
                DestructiveRow(label = stringResource(R.string.sign_out), onClick = onSignOut)
            } else {
                if (!guestProfile) {
                    FullWidthDivider()
                    SettingsRow(
                        icon = SfIcons.Person,
                        title = stringResource(R.string.guest_profile),
                        subtitle = stringResource(R.string.guest_profile_subtitle),
                        onClick = onEditGuestProfile,
                    )
                } else {
                    FullWidthDivider()
                    SettingsRow(
                        icon = SfIcons.SwitchAccount,
                        title = stringResource(R.string.sign_in_youtube_music),
                        subtitle = stringResource(R.string.tap_to_sign_in_google),
                        onClick = onSignIn,
                    )
                    FullWidthDivider()
                    DestructiveRow(
                        label = stringResource(R.string.delete_guest_profile),
                        onClick = onDeleteGuestProfile,
                    )
                }
            }
            FullWidthDivider()
            SettingsRow(
                icon = SfIcons.SwitchAccount,
                title = stringResource(R.string.google_cookie_import_title),
                subtitle = stringResource(R.string.google_cookie_import_subtitle),
                onClick = onImportGoogleCookie,
            )
            // Works signed out too: a playlist link needs no account, which is
            // exactly why it is here rather than under the account rows.
            FullWidthDivider()
            SettingsRow(
                icon = SfIcons.Download,
                title = stringResource(R.string.youtube_import_title),
                subtitle = stringResource(R.string.youtube_import_description),
                onClick = onImportYoutubePlaylist,
            )
        }

        SettingsGroup(
            header = stringResource(R.string.spotify),
            footer = stringResource(R.string.spotify_connect_subtitle),
        ) {
            SettingsRow(
                iconPainter = painterResource(Res.drawable.spotify_logo),
                title = stringResource(R.string.spotify),
                subtitle = stringResource(
                    if (spotifyConnected.isNotBlank()) R.string.connected else R.string.tap_to_connect,
                ),
                onClick = onOpenSpotify,
            )
            if (spotifyConnected.isNotBlank()) {
                FullWidthDivider()
                DestructiveRow(
                    label = stringResource(R.string.spotify_disconnect),
                    onClick = {
                        clearSpotifyWebSession()
                        AppSettings.setSpotifySpdcToken("")
                    },
                )
            }
        }

        SettingsGroup(
            header = stringResource(R.string.soundcloud),
            footer = stringResource(R.string.soundcloud_import_footer),
        ) {
            // The account line exists only when there is an account: with no
            // token it said "public playlists and liked tracks, without signing
            // in" under the SoundCloud heading, which described the import row
            // below it rather than anything the row itself did.
            if (soundCloudToken.isNotBlank()) {
                SettingsRow(
                    icon = SfIcons.Cloud,
                    title = stringResource(R.string.soundcloud),
                    subtitle = if (soundCloudAccount.isNotBlank()) {
                        soundCloudAccount
                    } else {
                        stringResource(R.string.connected)
                    },
                    onClick = null,
                )
                FullWidthDivider()
            }
            SettingsRow(
                icon = SfIcons.Cloud,
                title = stringResource(R.string.soundcloud_import_title),
                subtitle = stringResource(R.string.soundcloud_import_description),
                onClick = onImportSoundCloudPlaylist,
            )
            if (soundCloudToken.isBlank()) {
                FullWidthDivider()
                SettingsRow(
                    icon = SfIcons.SwitchAccount,
                    title = stringResource(R.string.sign_in),
                    subtitle = if (soundCloudLoginAvailable) null
                    else stringResource(R.string.soundcloud_token_title),
                    onClick = onOpenSoundCloudLogin,
                )
            }
            if (soundCloudToken.isNotBlank()) {
                FullWidthDivider()
                DestructiveRow(
                    label = stringResource(R.string.disconnect),
                    onClick = onDisconnectSoundCloud,
                )
            }
        }

        SettingsGroup(
            header = "VK Music",
            footer = stringResource(R.string.vk_import_subtitle),
        ) {
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = "VK Music",
                subtitle = when {
                    vkToken.isBlank() -> stringResource(R.string.tap_to_connect)
                    vkAccount.isNotBlank() -> vkAccount
                    else -> stringResource(R.string.connected)
                },
                onClick = if (vkToken.isBlank()) onOpenVkLogin else onOpenVkMusic,
            )
            FullWidthDivider()
            SettingsRow(
                icon = SfIcons.Download,
                title = stringResource(R.string.vk_import_title),
                subtitle = stringResource(R.string.vk_import_subtitle),
                onClick = onImportVkPlaylist,
            )
            if (vkToken.isNotBlank()) {
                FullWidthDivider()
                DestructiveRow(
                    label = stringResource(R.string.disconnect),
                    onClick = onDisconnectVk,
                )
            }
        }

        SettingsGroup(
            header = stringResource(R.string.yandex_music),
            footer = stringResource(R.string.yandex_music_integration_footer),
        ) {
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = stringResource(
                    if (yandexToken.isBlank()) R.string.yandex_music_sign_in else R.string.yandex_music,
                ),
                subtitle = when {
                    yandexToken.isBlank() -> stringResource(R.string.tap_to_connect)
                    yandexState.stage == YandexMusicSyncStage.AUTH_EXPIRED ->
                        stringResource(R.string.yandex_music_status_auth_expired)
                    yandexAccount.isNotBlank() -> stringResource(
                        if (yandexHasPlus) R.string.yandex_music_connected_plus
                        else R.string.yandex_music_connected_free,
                        yandexAccount,
                    )
                    else -> stringResource(R.string.connected)
                },
                onClick = {
                    if (yandexToken.isBlank() || yandexState.stage == YandexMusicSyncStage.AUTH_EXPIRED) {
                        onOpenYandexMusicLogin()
                    }
                },
            )
            if (yandexToken.isNotBlank()) {
                FullWidthDivider()
                SettingsRow(
                    icon = SfIcons.GraphicEq,
                    title = stringResource(R.string.yandex_music_sync_presence),
                    subtitle = if (!yandexSyncEnabled) {
                        stringResource(R.string.yandex_music_status_disabled)
                    } else yandexNowPlaying?.let { "${it.artist} — ${it.title}" } ?: when (yandexState.stage) {
                        YandexMusicSyncStage.CONNECTING ->
                            stringResource(R.string.yandex_music_status_connecting)
                        YandexMusicSyncStage.RECONNECTING ->
                            stringResource(R.string.yandex_music_status_reconnecting)
                        YandexMusicSyncStage.PAUSED ->
                            stringResource(R.string.yandex_music_status_paused)
                        YandexMusicSyncStage.ADVERTISEMENT ->
                            stringResource(R.string.yandex_music_status_advertisement)
                        YandexMusicSyncStage.RESTRICTED ->
                            stringResource(R.string.yandex_music_status_restricted)
                        YandexMusicSyncStage.AUTH_EXPIRED ->
                            stringResource(R.string.yandex_music_status_auth_expired)
                        YandexMusicSyncStage.DISABLED ->
                            stringResource(R.string.yandex_music_status_disabled)
                        else -> stringResource(R.string.yandex_music_status_waiting)
                    },
                    trailing = {
                        IosSwitch(
                            checked = yandexSyncEnabled,
                            onCheckedChange = { enabled ->
                                AppSettings.setYandexMusicSyncEnabled(enabled)
                                if (enabled) YandexMusicIntegration.ensureRunning(context)
                                else YandexMusicIntegration.stop(context)
                            },
                        )
                    },
                    onClick = {
                        val enabled = !yandexSyncEnabled
                        AppSettings.setYandexMusicSyncEnabled(enabled)
                        if (enabled) YandexMusicIntegration.ensureRunning(context)
                        else YandexMusicIntegration.stop(context)
                    },
                )
                FullWidthDivider()
                SettingsRow(
                    icon = SfIcons.Download,
                    title = stringResource(R.string.yandex_import_title),
                    subtitle = stringResource(R.string.yandex_import_description),
                    onClick = onImportYandexPlaylist,
                )
                FullWidthDivider()
                DestructiveRow(
                    label = stringResource(R.string.sign_out),
                    onClick = {
                        clearYandexMusicWebSession()
                        AppSettings.clearYandexMusicAccount()
                        YandexMusicIntegration.stop(context)
                    },
                )
            }
        }

        SettingsGroup(
            header = stringResource(R.string.rich_presence),
            footer = stringResource(R.string.rich_presence_footer),
        ) {
            SettingsRow(
                icon = ImageVector.vectorResource(R.drawable.ic_discord),
                title = "Discord",
                subtitle = when {
                    discordToken.isEmpty() -> stringResource(R.string.tap_to_connect)
                    !discordRpcEnabled -> stringResource(R.string.connected_presence_off)
                    discordUsername.isNotEmpty() -> stringResource(R.string.sharing_as, discordUsername)
                    else -> stringResource(R.string.sharing_listens)
                },
                onClick = onOpenDiscord,
            )
        }

        if (AppSettings.scrobblingAvailable) {
            SettingsGroup(
                header = stringResource(R.string.scrobbling),
                footer = stringResource(R.string.scrobbling_footer),
            ) {
                SettingsRow(
                    icon = SfIcons.Cloud,
                    title = "ListenBrainz",
                    subtitle = if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                        stringResource(R.string.connected)
                    } else {
                        stringResource(R.string.enter_token_to_enable)
                    },
                    trailing = {
                        IosSwitch(
                            checked = listenBrainzEnabled,
                            onCheckedChange = { checked ->
                                if (checked && listenBrainzToken.isBlank()) {
                                    onOpenListenBrainzLogin()
                                } else {
                                    AppSettings.setListenBrainzEnabled(checked)
                                }
                            },
                        )
                    },
                    onClick = onOpenListenBrainzLogin,
                )
                if (listenBrainzEnabled && listenBrainzToken.isNotBlank()) {
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.scrobble_primary_artist_only),
                        subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = listenBrainzPrimaryArtistOnly,
                                onCheckedChange = AppSettings::setListenBrainzPrimaryArtistOnly,
                            )
                        },
                        onClick = { AppSettings.setListenBrainzPrimaryArtistOnly(!listenBrainzPrimaryArtistOnly) },
                    )
                }
                RowDivider()
                SettingsRow(
                    icon = SfIcons.History,
                    title = "Last.fm",
                    subtitle = if (lastfmSessionKey.isNotBlank()) {
                        stringResource(R.string.signed_in_as, lastfmUsername)
                    } else {
                        stringResource(R.string.tap_to_sign_in)
                    },
                    trailing = {
                        IosSwitch(
                            checked = lastfmEnabled,
                            onCheckedChange = { checked ->
                                if (checked && lastfmSessionKey.isBlank()) {
                                    onOpenLastfmLogin()
                                } else {
                                    AppSettings.setLastfmEnabled(checked)
                                }
                            },
                        )
                    },
                    onClick = {
                        if (lastfmSessionKey.isNotBlank()) {
                            AppSettings.setLastfmSessionKey("")
                            AppSettings.setLastfmUsername("")
                            AppSettings.setLastfmEnabled(false)
                            AppSettings.setLastfmScrobbleEnabled(false)
                            AppSettings.setLastfmNowPlaying(false)
                        } else {
                            onOpenLastfmLogin()
                        }
                    },
                )
                if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.scrobble_tracks),
                        subtitle = stringResource(R.string.scrobble_tracks_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lastfmScrobbleEnabled,
                                onCheckedChange = AppSettings::setLastfmScrobbleEnabled,
                            )
                        },
                        onClick = { AppSettings.setLastfmScrobbleEnabled(!lastfmScrobbleEnabled) },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.scrobble_primary_artist_only),
                        subtitle = stringResource(R.string.scrobble_primary_artist_only_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lastfmPrimaryArtistOnly,
                                onCheckedChange = AppSettings::setLastfmPrimaryArtistOnly,
                            )
                        },
                        onClick = { AppSettings.setLastfmPrimaryArtistOnly(!lastfmPrimaryArtistOnly) },
                    )
                    RowDivider()
                    SettingsRow(
                        icon = SfIcons.GraphicEq,
                        title = stringResource(R.string.now_playing),
                        subtitle = stringResource(R.string.lastfm_now_playing_subtitle),
                        trailing = {
                            IosSwitch(
                                checked = lastfmNowPlayingEnabled,
                                onCheckedChange = AppSettings::setLastfmNowPlaying,
                            )
                        },
                        onClick = { AppSettings.setLastfmNowPlaying(!lastfmNowPlayingEnabled) },
                    )
                }
            }

            if (lastfmEnabled && lastfmSessionKey.isNotBlank()) {
                SettingsGroup(header = stringResource(R.string.scrobble_timing)) {
                    SliderRow(
                        icon = SfIcons.Tune,
                        title = stringResource(R.string.min_song_duration),
                        subtitle = stringResource(R.string.min_song_duration_subtitle),
                        value = "${scrobbleMinDuration}s",
                        sliderValue = scrobbleMinDuration.toFloat(),
                        onSliderValue = { AppSettings.setScrobbleMinDuration(it.roundToInt()) },
                        valueRange = 15f..120f,
                        steps = 20,
                    )
                    RowDivider()
                    SliderRow(
                        icon = SfIcons.Tune,
                        title = stringResource(R.string.scrobble_delay),
                        subtitle = stringResource(R.string.scrobble_delay_subtitle),
                        value = "${(scrobbleDelayPercent * 100).roundToInt()}%",
                        sliderValue = scrobbleDelayPercent,
                        onSliderValue = { AppSettings.setScrobbleDelayPercent(it) },
                        valueRange = 0.1f..1.0f,
                        steps = 8,
                    )
                    RowDivider()
                    SliderRow(
                        icon = SfIcons.Tune,
                        title = stringResource(R.string.max_delay),
                        subtitle = stringResource(R.string.max_delay_subtitle),
                        value = "${scrobbleDelaySeconds}s",
                        sliderValue = scrobbleDelaySeconds.toFloat(),
                        onSliderValue = { AppSettings.setScrobbleDelaySeconds(it.roundToInt()) },
                        valueRange = 30f..300f,
                        steps = 26,
                    )
                }
            }
        } else {
            // Left in place rather than dropped: a section that simply vanishes
            // reads as a feature that never existed, and this one is coming back.
            // The rows are dimmed and inert via `enabled = false`.
            SettingsGroup(
                header = stringResource(R.string.scrobbling),
                footer = stringResource(R.string.scrobbling_paused_footer),
            ) {
                SettingsRow(
                    icon = SfIcons.Cloud,
                    title = "ListenBrainz",
                    subtitle = stringResource(R.string.back_in_future_version),
                    enabled = false,
                )
                RowDivider()
                SettingsRow(
                    icon = SfIcons.History,
                    title = "Last.fm",
                    subtitle = stringResource(R.string.back_in_future_version),
                    enabled = false,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** A hairline across the whole card, for setting a destructive row apart from the rows above it. */
@Composable
private fun FullWidthDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
}
