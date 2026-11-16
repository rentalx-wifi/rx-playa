package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import com.music.bitchord.ui.theme.LocalNothingStyleEnabled
import com.music.bitchord.ui.theme.NothingCompactShape
import com.music.bitchord.ui.theme.NothingRed

/** Spotify-style horizontally scrolling source chips for the search page. */
@Composable
fun SearchSourceSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nothing = LocalNothingStyleEnabled.current
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(options) { index, label ->
            val selected = index == selectedIndex
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                label = "searchSourceColor",
            )
            Surface(
                shape = if (nothing) NothingCompactShape else RoundedCornerShape(50),
                color = color,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .then(
                        if (nothing) Modifier.border(
                            1.dp,
                            if (selected) NothingRed else MaterialTheme.colorScheme.outlineVariant,
                            NothingCompactShape,
                        ) else Modifier,
                    )
                    .clickable { onSelect(index) },
            ) {
                Text(
                    text = if (nothing) "${(index + 1).toString().padStart(2, '0')}  ${label.uppercase()}" else label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}
