package com.rentalx.playa.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rentalx.playa.R
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.data.settings.permits
import com.rentalx.playa.data.sources.SourceConfig
import com.rentalx.playa.data.sources.SourceHealth
import com.rentalx.playa.data.sources.SourceKind
import com.rentalx.playa.data.sources.SourceRegistry
import com.rentalx.playa.sharedui.resources.Res as SharedRes
import com.rentalx.playa.sharedui.resources.flashmusic_logo
import com.rentalx.playa.ui.icons.SfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

/**
 * Everything the listener's own server has to say about itself.
 *
 * Opened from its row on the Sources screen, which is why it takes a back arrow
 * rather than a close button: it is a page of that screen, not a dialog over it.
 *
 * The settings here are the ones that actually change what the app asks of the
 * server — where it is, whether it is asked at all, and whether a search spends
 * the one extra request it takes to mark rows the server already holds. What it
 * can serve is stated rather than configured: the tier is chosen per playback by
 * the quality ceilings, which are set once for every source and belong in
 * Settings, not here.
 */
@Composable
internal fun FlashMusicSettingsPage(
    config: SourceConfig,
    contentPadding: PaddingValues,
    onEditAddress: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val crossMatch by AppSettings.flashMusicCrossMatch.collectAsStateWithLifecycle()
    val wifiQuality by AppSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by AppSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by AppSettings.meteredConnection.collectAsStateWithLifecycle()
    // The row the page was opened from is a snapshot; the switch below writes
    // through the registry, so read the live one to stay in step with it.
    val configs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val live = configs.firstOrNull { it.id == config.id } ?: config

    var health by remember(live.baseUrl) { mutableStateOf<SourceHealth?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun check() {
        val source = SourceRegistry.instance(live.id) ?: return
        checking = true
        scope.launch {
            health = withContext(Dispatchers.IO) {
                runCatching { source.health() }
                    .getOrElse { SourceHealth.Unreachable(it.message ?: "Failed") }
            }
            checking = false
        }
    }
    LaunchedEffect(live.id, live.baseUrl) {
        if (live.isComplete) check() else health = null
    }

    val ceiling = if (metered == true) cellularQuality else wifiQuality
    val reached = ceiling.permits(SourceKind.FLASHMUSIC)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        // No back arrow of its own: the page header above already carries one,
        // and two of them one above the other read as a mistake.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(SharedRes.drawable.flashmusic_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = live.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SettingsGroup(header = stringResource(R.string.status)) {
            SettingsRow(
                icon = SfIcons.Cloud,
                title = when {
                    checking -> stringResource(R.string.checking)
                    health is SourceHealth.Ok -> stringResource(R.string.connected)
                    else -> stringResource(R.string.status)
                },
                subtitle = when {
                    !live.isComplete -> stringResource(R.string.source_setup_required)
                    health is SourceHealth.Ok -> (health as SourceHealth.Ok).detail
                    health is SourceHealth.Unreachable -> (health as SourceHealth.Unreachable).reason
                    health is SourceHealth.Rejected -> (health as SourceHealth.Rejected).reason
                    else -> "—"
                },
                onClick = { if (!checking) check() },
            )
            SettingsRow(
                icon = SfIcons.Dns,
                title = stringResource(R.string.flashmusic_address),
                subtitle = live.baseUrl.ifBlank { stringResource(R.string.source_setup_required) },
                onClick = onEditAddress,
            )
            // Same switch as on the row behind this page, written through the
            // registry so both move together.
            SettingsSubRow(
                title = stringResource(R.string.enabled),
                checked = live.enabled,
                onCheckedChange = { SourceRegistry.setEnabled(live.id, it) },
            )
        }

        SettingsGroup(header = stringResource(R.string.source_matching)) {
            SettingsSubRow(
                title = stringResource(R.string.flashmusic_cross_match),
                subtitle = stringResource(R.string.flashmusic_cross_match_subtitle),
                checked = crossMatch,
                onCheckedChange = AppSettings::setFlashMusicCrossMatch,
            )
        }

        SettingsGroup(
            header = stringResource(R.string.audio_quality),
            // The one thing worth saying about the tier ladder: it is not set
            // here, and where it is set is one screen away.
            footer = stringResource(R.string.sources_quality_capped).takeIf { !reached },
        ) {
            SettingsRow(
                icon = SfIcons.LibraryMusic,
                title = stringResource(R.string.lossless),
                // Not a translation: these are the server's own tier names.
                subtitle = "FLAC · Opus 320 · Opus 192",
            )
        }

        RowDivider()
        Text(
            text = stringResource(R.string.sources_order_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
