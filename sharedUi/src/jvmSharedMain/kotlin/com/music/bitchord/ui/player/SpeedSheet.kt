package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.keep_pitch
import com.music.bitchord.sharedui.resources.keep_pitch_subtitle
import com.music.bitchord.sharedui.resources.playback_speed
import com.music.bitchord.sharedui.resources.playback_speed_subtitle
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How fast the music runs, and whether the pitch comes with it.
 *
 * Two things in one drawer because they are one decision: a slowed version of a
 * track *is* its tempo with the pitch dragged along, and the switch is the only
 * difference between that and a podcast at 1.25x. The presets are the ones
 * people ask for by name — 0.8x is the slowed edit, 1.25x the sped-up one — and
 * the slider is for everything between them.
 */
@Composable
internal fun SpeedSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speed by PlayerSettings.playbackSpeed.collectAsStateWithLifecycle()
    val pitchCorrection by PlayerSettings.pitchCorrection.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(Res.string.playback_speed),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SPEED_PRESETS) { preset ->
                SpeedChip(
                    label = formatSpeed(preset),
                    selected = kotlin.math.abs(preset - speed) < SPEED_STEP / 2,
                    onClick = {
                        haptics.play(Haptic.Select)
                        PlayerSettings.setPlaybackSpeed(preset)
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatSpeed(SPEED_MIN),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.45f),
            )
            ThinSlider(
                value = (speed - SPEED_MIN) / (SPEED_MAX - SPEED_MIN),
                onValueChange = { fraction ->
                    PlayerSettings.setPlaybackSpeed(quantize(SPEED_MIN + fraction * (SPEED_MAX - SPEED_MIN)))
                },
                idleHeight = 6.dp,
                activeHeight = 10.dp,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(
                text = formatSpeed(SPEED_MAX),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.45f),
            )
        }
        Text(
            text = stringResource(Res.string.playback_speed_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )

        Spacer(Modifier.height(16.dp))
        KeepPitchRow(
            checked = pitchCorrection,
            onCheckedChange = {
                haptics.play(Haptic.ToggleOn)
                PlayerSettings.setPitchCorrection(it)
            },
        )
    }
}

/**
 * The one switch that turns a tempo change into a slowed edit: with it off the
 * rate is applied to the samples, so the voice drops with the track.
 */
@Composable
private fun KeepPitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SHEET_ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Speed,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.keep_pitch),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.keep_pitch_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LocalPlayerTint.current.copy(alpha = 0.9f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.8f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        modifier = Modifier
            .clip(SHEET_ROW_SHAPE)
            .background(Color.White.copy(alpha = if (selected) 0.16f else 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** "0.8×", "1×", "1.25×" — a whole number never carries its decimals. */
fun formatSpeed(speed: Float): String {
    val text = if (speed % 1f == 0f) {
        speed.roundToInt().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "$text×"
}

/** The slider's steps, so a drag does not leave the player at 1.0374x. */
private fun quantize(value: Float): Float {
    val steps = ((value - SPEED_MIN) / SPEED_STEP).roundToInt()
    return (SPEED_MIN + steps * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)
}

private val SHEET_ROW_SHAPE = RoundedCornerShape(16.dp)

/** The two the formats are named after, and the ones podcasts are listened at. */
val SPEED_PRESETS = listOf(0.5f, 0.75f, 0.8f, 1f, 1.25f, 1.5f, 2f)

/** The same presets as they are written on a chip. */
val SPEED_PRESET_LABELS = SPEED_PRESETS.map(::formatSpeed)
private const val SPEED_MIN = 0.5f
private const val SPEED_MAX = 2f
private const val SPEED_STEP = 0.05f
