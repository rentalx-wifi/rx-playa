package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.HomeSource

/** Provenance, not an audio-quality or direct-streaming promise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeSourceBadges(sources: Set<HomeSource>, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    val ordered = remember(sources) { sources.sortedBy { it.ordinal } }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ordered.take(3).forEach { source ->
            val color = when (source) {
                HomeSource.YOUTUBE -> Color(0xFFE74C4C)
                HomeSource.YANDEX -> Color(0xFFB88700)
                HomeSource.SPOTIFY -> Color(0xFF168A45)
                HomeSource.SOUNDCLOUD -> Color(0xFFDB6B16)
                HomeSource.VK -> Color(0xFF2688EB)
                HomeSource.LOCAL -> Color(0xFF3874CC)
                HomeSource.ADDONS -> Color(0xFF8D5BB5)
            }
            Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
                Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (source) {
                            HomeSource.YOUTUBE -> Icons.Rounded.PlayCircle
                            HomeSource.SOUNDCLOUD -> Icons.Rounded.Cloud
                            HomeSource.LOCAL -> Icons.Rounded.Folder
                            HomeSource.ADDONS -> Icons.Rounded.Extension
                            else -> Icons.Rounded.MusicNote
                        },
                        contentDescription = source.label,
                        tint = color,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(source.badge, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        if (ordered.size > 3) Text("+${ordered.size - 3}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
