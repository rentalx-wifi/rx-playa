package com.rentalx.playa.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithCache
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Whether the lightweight Nothing-inspired appearance is active. */
val LocalNothingStyleEnabled = staticCompositionLocalOf { false }


val NothingPanelShape = RoundedCornerShape(22.dp)
val NothingCompactShape = RoundedCornerShape(12.dp)
val NothingRed = Color(0xFFD71921)
val NothingRedBright = Color(0xFFFF3344)
val NothingWhite = Color(0xFFF2F2F0)
val NothingBlack = Color(0xFF070707)
val NothingSurface = Color(0xFF101010)
val NothingSurfaceRaised = Color(0xFF181818)

/** Short pulse envelope: no work remains after the 520 ms transition. */
internal fun nothingTransitionEnvelope(progress: Float): Float {
    val p = progress.coerceIn(0f, 1f)
    return sin((p * PI).toFloat()).coerceIn(0f, 1f)
}

internal fun nothingTransitionProgress(progress: Float): Float =
    CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f).transform(progress.coerceIn(0f, 1f))

/**
 * A compact centre pulse for appearance changes.
 *
 * It never clips, scales or captures the application surface. Animation state
 * is consumed by one bounded blur layer
 * and one draw layer, leaving playback and page composition untouched.
 */
@Composable
fun NothingStyleTransitionHost(
    enabled: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val phase = remember { Animatable(1f) }
    var previous by remember { mutableStateOf<Boolean?>(null) }
    var active by remember { mutableStateOf(false) }
    val awaiting = previous != null && previous != enabled && !reduceMotion
    val visible = active || awaiting

    LaunchedEffect(enabled, reduceMotion) {
        val old = previous
        previous = enabled
        if (old == null || old == enabled || reduceMotion) {
            active = false
            phase.snapTo(1f)
        } else {
            phase.snapTo(0f)
            active = true
            phase.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 520,
                    easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f),
                ),
            )
            active = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
        if (visible) {
            // The expensive blur is restricted to a 112dp glow for half a
            // second, never a RenderEffect over the full application surface.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(112.dp)
                    .graphicsLayer {
                        val p = if (awaiting) 0f else phase.value
                        val energy = nothingTransitionEnvelope(p)
                        alpha = energy * 0.32f
                        scaleX = 0.55f + nothingTransitionProgress(p) * 1.2f
                        scaleY = scaleX
                    }
                    .blur(18.dp)
                    .background(NothingRed, CircleShape),
            )
            NothingTransitionPulse(
                phase = phase,
                awaiting = awaiting,
                entering = enabled,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Two dotted rings and a centre glyph, deliberately under thirty primitives. */
@Composable
private fun NothingTransitionPulse(
    phase: Animatable<Float, *>,
    awaiting: Boolean,
    entering: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val raw = if (awaiting) 0f else phase.value.coerceIn(0f, 1f)
        val progress = nothingTransitionProgress(raw)
        val energy = nothingTransitionEnvelope(raw)
        val centre = Offset(size.width / 2f, size.height / 2f)
        val direction = if (entering) 1f else -1f

        repeat(2) { ring ->
            val count = if (ring == 0) 8 else 16
            val radius = (28 + ring * 18).dp.toPx() * (0.7f + progress * 0.55f)
            repeat(count) dot@{ index ->
                if ((index + ring) % 5 == 0) return@dot
                val angle = index.toFloat() / count.toFloat() * (PI * 2.0) +
                    raw * direction * (0.45f + ring * 0.2f)
                drawCircle(
                    color = if (ring == 0) NothingWhite.copy(alpha = energy)
                    else NothingRed.copy(alpha = energy * 0.9f),
                    radius = (if (ring == 0) 2.2f else 1.8f).dp.toPx(),
                    center = Offset(
                        centre.x + cos(angle).toFloat() * radius,
                        centre.y + sin(angle).toFloat() * radius,
                    ),
                )
            }
        }
        drawCircle(
            color = NothingRed.copy(alpha = energy),
            radius = (10 + progress * 5).dp.toPx(),
            center = centre,
        )
        drawCircle(
            color = NothingWhite.copy(alpha = energy),
            radius = (18 + progress * 7).dp.toPx(),
            center = centre,
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

/** One cached path of dots behind content; no persistent foreground overlay. */
fun Modifier.nothingDotBackground(enabled: Boolean): Modifier {
    if (!enabled) return this
    return drawWithCache {
        val dotRadius = 1.25.dp.toPx()
        val gap = 9.dp.toPx()
        val right = size.width - 18.dp.toPx()
        val top = 28.dp.toPx()
        val dots = Path().apply {
            repeat(5) { row ->
                repeat(7) { column ->
                    val x = right - column * gap
                    val y = top + row * gap
                    addOval(Rect(x - dotRadius, y - dotRadius, x + dotRadius, y + dotRadius))
                }
            }
        }
        onDrawBehind {
            drawPath(dots, NothingWhite.copy(alpha = 0.07f))
            drawCircle(
                color = NothingRed.copy(alpha = 0.65f),
                radius = dotRadius,
                center = Offset(right, top),
            )
        }
    }
}

/**
 * Nothing-style hardware frame: four pairs of registration marks and one red
 * status pixel.
 *
 * The marks deliberately do not trace a second closed contour. Every host
 * surface already establishes its own edge with its own border and corner
 * radius, so an independently placed inset rectangle used to float inside that
 * edge at a different radius and was painted over children - it read as a
 * doubled, misaligned outline. Marks now sit on the straight run of each edge,
 * past the corner arc, and are drawn behind content instead of over it.
 *
 * Draw-only and cached with the surface size, so it adds no layout nodes and no
 * per-frame composition work.
 */
fun Modifier.nothingPanelChrome(
    enabled: Boolean,
    accent: Color = NothingRed,
    cornerRadius: Dp = 22.dp,
): Modifier {
    if (!enabled) return this
    return drawWithCache {
        val edge = 1.dp.toPx()
        val inset = 6.dp.toPx()
        val mark = 8.dp.toPx()
        val subtle = NothingWhite.copy(alpha = 0.1f)
        val focus = accent.copy(alpha = 0.9f)
        val dot = 2.dp.toPx()

        val left = inset
        val top = inset
        val right = (size.width - inset).coerceAtLeast(left)
        val bottom = (size.height - inset).coerceAtLeast(top)
        // Straight run of each edge begins past the corner arc.
        val dx = cornerRadius.toPx().coerceAtMost((right - left) / 2f)
        val dy = cornerRadius.toPx().coerceAtMost((bottom - top) / 2f)

        val segments = ArrayList<Pair<Offset, Offset>>(4)
        fun horizontal(y: Float, from: Float, to: Float) {
            if (to - from > 0f) segments.add(Offset(from, y) to Offset(to, y))
        }
        fun vertical(x: Float, from: Float, to: Float) {
            if (to - from > 0f) segments.add(Offset(x, from) to Offset(x, to))
        }

        horizontal(top, left + dx, left + dx + mark)
        vertical(left, top + dy, top + dy + mark)
        horizontal(top, right - dx - mark, right - dx)
        vertical(right, top + dy, top + dy + mark)
        horizontal(bottom, left + dx, left + dx + mark)
        vertical(left, bottom - dy - mark, bottom - dy)
        horizontal(bottom, right - dx - mark, right - dx)
        vertical(right, bottom - dy - mark, bottom - dy)

        onDrawBehind {
            segments.forEach { (start, end) -> drawLine(subtle, start, end, edge) }
            drawCircle(focus, dot, Offset(right - 6.dp.toPx(), top + 6.dp.toPx()))
        }
    }
}
