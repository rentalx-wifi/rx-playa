package com.rentalx.playa.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether shared primitives should draw themselves the Material 3 way.
 *
 * Declared here rather than next to the theme in the app module because the
 * components that have to read it — the shelf cards, the list rows, the section
 * headers — live in `sharedUi`, which the app depends on and not the other way
 * round. The theme in the app provides it; everything below only reads it.
 */
val LocalMaterial3StyleEnabled = staticCompositionLocalOf { false }
