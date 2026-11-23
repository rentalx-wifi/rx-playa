package com.rentalx.playa.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

/**
 * How a feed item arrives, leaves and moves.
 *
 * The home feed swaps a skeleton for a real shelf the instant its request lands,
 * and with no item animation that swap is a cut: the headings and titles under
 * the thumb change without anything moving into place, which reads as a jolt.
 * A fade in, a fade out and a spring on the placement turn the same swap into
 * the page settling, which is what is actually happening.
 *
 * Short fades on purpose — 240 ms in, 140 ms out. Anything longer and a quick
 * scroll starts overtaking its own content, and a fast fling shows rows fading
 * in after they have already passed.
 */
fun LazyItemScope.feedItem(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
    fadeOutSpec = tween(durationMillis = 140),
    placementSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    ),
)
