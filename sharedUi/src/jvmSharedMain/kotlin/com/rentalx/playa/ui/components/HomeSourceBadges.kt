package com.rentalx.playa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.ContentScale
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
import com.rentalx.playa.data.model.HomeSource
import com.rentalx.playa.sharedui.resources.Res
import com.rentalx.playa.sharedui.resources.flashmusic_logo
import org.jetbrains.compose.resources.painterResource

/** Provenance, not an audio-quality or direct-streaming promise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeSourceBadges(sources: Set<HomeSource>, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    // The listener's own server first, then the platforms in their fixed order:
    // three marks fit, and "this is already yours" is worth more than a fourth
    // platform name — sorted by enum position alone, the one badge that says the
    // track is on their own disk was the first to be cut.
    val ordered = remember(sources) {
        sources.sortedWith(compareBy({ it != HomeSource.FLASHMUSIC }, { it.ordinal }))
    }
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
                HomeSource.FLASHMUSIC -> Color(0xFF2E86E8)
            }
            Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
                Row(Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (source == HomeSource.FLASHMUSIC) {
                        // The logo is the mark people already associate with the
                        // server, so it earns the two glyphs the others spend on
                        // a Material icon. Drawn larger than those — the mark is
                        // a thin bolt and a note, and at 11dp neither reads — but
                        // in a box the same height as the row's text, so a chip
                        // carrying it is not a few dp taller than its neighbours.
                        Image(
                            painter = painterResource(Res.drawable.flashmusic_logo),
                            // The badge's own letters say which source this is;
                            // a description here would be read out twice.
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(13.dp).aspectRatio(1f),
                        )
                    } else {
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
                    }
                    Text(source.badge, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        if (ordered.size > 3) Text("+${ordered.size - 3}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
