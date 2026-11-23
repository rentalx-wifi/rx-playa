package com.rentalx.playa.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rentalx.playa.data.settings.AppSettings
import com.rentalx.playa.ui.haptics.Haptic
import com.rentalx.playa.ui.haptics.rememberHaptics
import com.rentalx.playa.ui.theme.NothingCompactShape
import com.rentalx.playa.ui.theme.NothingPanelShape
import com.rentalx.playa.ui.theme.LocalMaterial3StyleEnabled
import com.rentalx.playa.ui.theme.LocalNothingStyleEnabled
import com.rentalx.playa.ui.theme.NothingRed
import com.rentalx.playa.ui.theme.nothingPanelChrome
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.abs
import kotlin.math.roundToInt

data class BottomTab(
    val label: String,
    val icon: ImageVector,
)


/** Room the pill keeps from its own edge for the content. */
internal val PILL_INSET = 4.dp

/** Side gutter of the floating bar on a normal-width screen. */
internal val NAV_BAR_GUTTER = 19.dp

/** How far above the system inset the bar floats. */
internal val NAV_BAR_LIFT = 6.dp

/** A gesture bar's inset, kept even when the system inset reads zero. */
private val GESTURE_BAR_FLOOR = 15.dp

internal val floatingBarInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets(bottom = GESTURE_BAR_FLOOR))

/** Vertical padding inside a tab; the bar is icons only now. */
internal val TAB_VERTICAL_PADDING = 12.dp

/** A tab's glyph box. */
internal val TAB_ICON_SIZE = 26.dp

/** The spring the selection indicator and the tab glyphs both travel on. */
internal val GlassSpring: androidx.compose.animation.core.SpringSpec<Float> =
    androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 420f)

/** Widths of the bar's slots, recomputed only when the row or tab count changes. */
private class Slots(rowWidth: Int, private val count: Int, gapPx: Float, density: Density) {
    val slotPx: Float
    val stepPx: Float
    val gap: androidx.compose.ui.unit.Dp

    init {
        gap = with(density) { gapPx.toDp() }
        slotPx = if (rowWidth > 0 && count > 0) (rowWidth - gapPx * (count - 1)) / count else 0f
        stepPx = slotPx + gapPx
    }

    val usable: Boolean get() = slotPx > 0f
}

/** How far the indicator stretches along its travel, and how much it squashes. */
private const val STRETCH = 0.16f
private const val SQUASH = 0.22f

private val TAB_SLOT_GAP = 6.dp

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FloatingBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    if (LocalMaterial3StyleEnabled.current) {
        Material3NavigationBar(tabs, selectedIndex, onTabSelected, modifier)
        return
    }
    val nothing = LocalNothingStyleEnabled.current
    val pillShape = remember(nothing) {
        if (nothing) NothingPanelShape else RoundedCornerShape(percent = 50)
    }
    val container = MaterialTheme.colorScheme.surface
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    // The glass settle is exactly the motion "reduce animation" promises to
    // drop — snapping both the indicator's travel and the glyph's pop to
    // their target leaves the tap itself instant rather than eased.
    val glassSpec = when {
        reduceAnimation -> snap<Float>()
        nothing -> androidx.compose.animation.core.tween<Float>(180)
        else -> GlassSpring
    }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)

    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val slots = Slots(rowSize.width, tabs.size, with(density) { TAB_SLOT_GAP.toPx() }, density)

    // The drag below runs in one coroutine for the life of the bar, so it
    // reads everything that can change under it through these. Read plainly,
    // each would stay what it was on the first touch.
    val currentSlots by rememberUpdatedState(slots)
    val currentLastIndex by rememberUpdatedState(tabs.lastIndex)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    val pillTargetPx = if (slots.usable) selectedIndex * slots.stepPx + dragOffset else 0f

    val animatedPillOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = pillTargetPx,
        animationSpec = glassSpec,
        label = "pillOffset",
    )

    // How much of a tab's stride is still ahead of the indicator: 0 at rest,
    // toward 1 in the middle of a move or under a drag that has run away from
    // it. The stretch below is a function of this and nothing else, which is
    // what keeps it honest — the shape can only be deformed while it is
    // actually behind where it is going.
    val lag = if (slots.usable) {
        (abs(pillTargetPx - animatedPillOffset) / slots.stepPx).coerceIn(0f, 1f)
    } else {
        0f
    }

    var lastHapticTab by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(selectedIndex) {
        dragOffset = 0f
        lastHapticTab = selectedIndex
    }

    // A fixed 19dp gutter ate 38dp of a 360dp screen and left the five slots
    // cramped; small phones get a narrower one so the capsule still fits.
    val compactWidth = LocalConfiguration.current.screenWidthDp < 380
    val barGutter = if (compactWidth) 10.dp else NAV_BAR_GUTTER
    Box(
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(horizontal = barGutter)
            .padding(bottom = NAV_BAR_LIFT)
            .fillMaxWidth()
            .clip(pillShape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(container)
                } else if (nothing) {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = barFrost(container, alpha = 0.58f),
                    )
                } else if (useGlass) {
                    Modifier.liquidGlass(shape = pillShape)
                } else {
                    Modifier.optimizedHazeEffect(
                        state = hazeState,
                        style = barFrost(container, alpha = 0.7f),
                    )
                },
            )
            .border(
                width = if (nothing) 1.dp else GLASS_EDGE_WIDTH,
                color = if (nothing) NothingRed.copy(alpha = 0.42f) else GLASS_EDGE_COLOR,
                shape = pillShape,
            )
            .nothingPanelChrome(nothing)
            .padding(horizontal = PILL_INSET, vertical = PILL_INSET),
    ) {
        if (slots.usable) {
            Box(
                modifier = Modifier
                    .width(with(density) { slots.slotPx.toDp() })
                    .height(with(density) { rowSize.height.toDp() })
                    .graphicsLayer {
                        translationX = animatedPillOffset
                        // Around its own centre, so the indicator draws out
                        // both ways rather than growing a tail off one edge —
                        // a leading edge that ran ahead of the glyph it is
                        // meant to be behind would read as two things moving,
                        // not one thing stretching.
                        scaleX = 1f + lag * STRETCH
                        scaleY = 1f - lag * STRETCH * SQUASH
                    }
                    .clip(if (nothing) NothingCompactShape else pillShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (nothing) 0.18f else 0.14f)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .pointerInput(Unit) {
                    // Every drag measures from the tab selected when it starts.
                    // A stale index here measured them all from the tab the bar
                    // first opened on: a drag right of it still landed, while
                    // one to the left clamped back onto it and read as no change.
                    var totalDrag = 0f
                    var dragStartIndex = currentSelectedIndex
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDrag = 0f
                            dragStartIndex = currentSelectedIndex
                        },
                        onDragCancel = {
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            val step = currentSlots.stepPx
                            if (currentSlots.usable && step > 0f) {
                                val absoluteIndexFloat = (dragStartIndex + totalDrag / step)
                                    .coerceIn(0f, currentLastIndex.toFloat())
                                val newIndex = absoluteIndexFloat.roundToInt()
                                if (newIndex != currentSelectedIndex) {
                                    // Hold the indicator at the released cell
                                    // until navigation publishes the new index.
                                    // Resetting first made it visibly jump back
                                    // under load before travelling forward again.
                                    dragOffset = (newIndex - currentSelectedIndex) * step
                                    currentOnTabSelected(newIndex)
                                } else {
                                    dragOffset = 0f
                                }
                            }
                        },
                        onHorizontalDrag = { _, delta ->
                            val step = currentSlots.stepPx
                            if (!currentSlots.usable || step <= 0f) return@detectHorizontalDragGestures
                            totalDrag += delta
                            val absoluteIndexFloat = (dragStartIndex + totalDrag / step)
                                .coerceIn(0f, currentLastIndex.toFloat())
                            // Never past the first or the last slot, even when
                            // the finger keeps going: the indicator used to run
                            // off the end of the bar and hang there.
                            dragOffset = absoluteIndexFloat * step - currentSelectedIndex * step

                            val approxTab = absoluteIndexFloat.roundToInt()
                            if (approxTab != lastHapticTab) {
                                haptics.play(Haptic.Tick)
                                lastHapticTab = approxTab
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(TAB_SLOT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A real glass pill samples whatever artwork is behind it, not the
            // theme's surface color, so a fixed onSurfaceVariant gray can lose
            // contrast against it. Glass mode reads luminance off the surface
            // color instead and picks pure black or white, same as the tint
            // Echo's floating nav bar uses for its own liquid glass.
            val glassTint = glassContentColor()
            val adaptiveTint = when {
                nothing -> MaterialTheme.colorScheme.primary
                useGlass -> glassTint
                else -> null
            }
            tabs.forEachIndexed { index, tab ->
                BottomBarItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    glassSpec = glassSpec,
                    selectedTint = adaptiveTint,
                    unselectedTint = if (nothing) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        adaptiveTint?.copy(alpha = 0.65f)
                    },
                    nothingStyle = nothing,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    tab: BottomTab,
    selected: Boolean,
    glassSpec: androidx.compose.animation.core.AnimationSpec<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Overrides the theme's primary/onSurfaceVariant tint — see the glass branch above. */
    selectedTint: Color? = null,
    unselectedTint: Color? = null,
    nothingStyle: Boolean = false,
) {
    // The same spring the indicator rides, so the glyph arriving and the glass
    // arriving are one movement rather than two that nearly agree.
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            selected && nothingStyle -> 1.03f
            selected -> 1.08f
            else -> 1f
        },
        animationSpec = glassSpec,
        label = "tabScale",
    )
    val haptics = rememberHaptics()
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) {
            selectedTint ?: MaterialTheme.colorScheme.primary
        } else {
            unselectedTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "tabTint",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // The outer bar already clips its chrome; a capsule per slot clipped label corners.
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (!selected) haptics.play(Haptic.Select)
                onClick()
            }
            .semantics { this.selected = selected }
            .padding(vertical = TAB_VERTICAL_PADDING),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier
                .size(TAB_ICON_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/**
 * The skin's navigation bar: a compact floating slab of icons.
 *
 * Icons only, no labels. Five labels under five icons in a 64dp bar is a wall
 * of 11sp text that still has to be shrunk to fit "Библиотека", and the app
 * this skin follows draws the compact bar instead — the current tab is named by
 * the pill behind its icon and by the page itself.
 */
@Composable
private fun Material3NavigationBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                // The same 24dp as the tonal cards the rest of the skin draws,
                // and no border or shadow: the page's own surfaces have none,
                // and a hairline here was the one edge in the app that had one.
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val indicator by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent,
                    animationSpec = tween(160),
                    label = "navIndicator",
                )
                val iconTint by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(160),
                    label = "navIconTint",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onTabSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 52.dp, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(indicator),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Smallest a fitted bar label may be drawn before it stops being readable. */
private const val MIN_LABEL_SP = 9f
