package com.music.bitchord.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppFont

// Apple Music's signature red. No longer the primary accent, but kept for the
// spots (Replay's rank badge) that want that specific red regardless of theme.
val AccentRed = Color(0xFFFA2D48)

// The Material 3 skin's own colours, taken from the app the appearance follows
// (Pixel Music): a deep violet ground, a violet primary, pink and orange.
private val PixelPurpleDark = Color(0xFF1E1234)
private val PixelPrimary = Color(0xFFAB47BC)
private val PixelPink = Color(0xFFF06292)
private val PixelOrange = Color(0xFFFF8A65)
private val PixelLightPurple = Color(0xFFE1BEE7)
private val PixelSurface = Color(0xFF2A1F40)
private val PixelLightBackground = Color(0xFFF7F2FF)
private val PixelLightSurface = Color(0xFFFBF8FF)
private val PixelLightSurfaceVariant = Color(0xFFE8DEF9)
private val PixelLightOnSurface = Color(0xFF1E1237)
private val PixelLightOnSurfaceVariant = Color(0xFF4D4165)
private val PixelLightPrimary = Color(0xFF6C4FF5)
private val PixelLightPrimaryContainer = Color(0xFFE3DBFF)
private val PixelLightOnPrimaryContainer = Color(0xFF23005C)
private val PixelLightOutline = Color(0xFF78659A)

private val DarkColors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF0D0D0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
)

private val LightColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF7F7F9),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFE5E5EA),
)




/** SF Pro Display, the face Apple Music itself is set in, drawn a weight lighter. */
val SFProDisplay = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_regular, FontWeight.W500),
    Font(R.font.sf_pro_display_medium, FontWeight.W600),
    Font(R.font.sf_pro_display_semibold, FontWeight.W700),
    Font(R.font.sf_pro_display_bold, FontWeight.W800),
)

/*
 * Letter spacing measured off Apple Music's own screens: the spacing the
 * bundled Display cut needs, at the weight it draws, to set a line to the
 * width Apple sets it. Small sizes open up, large titles close.
 */
private val BitChordTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = 0.05.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = 0.25.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp, letterSpacing = 0.3.sp),
).withFamily(SFProDisplay)

/**
 * SF Pro Display as the app first shipped it, before [SFProDisplay] moved every
 * weight a cut lighter: each weight draws its own cut, Heavy included. Kept for
 * [AppFont.LEGACY].
 */
val SFProDisplayLegacy = FontFamily(
    Font(R.font.sf_pro_display_regular, FontWeight.W400),
    Font(R.font.sf_pro_display_medium, FontWeight.W500),
    Font(R.font.sf_pro_display_semibold, FontWeight.W600),
    Font(R.font.sf_pro_display_bold, FontWeight.W700),
    Font(R.font.sf_pro_display_heavy, FontWeight.W800),
)

// Heavy, tight typography — the original scale [AppFont.LEGACY] restores.
private val LegacyTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 11.sp),
).withFamily(SFProDisplayLegacy)



private val DefaultShapes = Shapes()

/**
 * The skin's own corner scale, taken from the app this appearance follows:
 * 8, 16 and 24 dp, with the expressive extra-large at 28.
 */
private val PixelShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Montserrat, the geometric grotesque the Material 3 skin is set in.
 *
 * One variable file rather than eight cuts: the weight is selected as a font
 * variation, which is how the face was meant to be used and keeps a megabyte of
 * duplicates out of the APK. Bundled under the SIL Open Font License — see
 * `licenses/Montserrat-OFL.txt`.
 */
@OptIn(ExperimentalTextApi::class)
private val PixelFont = FontFamily(
    listOf(
        FontWeight.Thin,
        FontWeight.ExtraLight,
        FontWeight.Light,
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
        FontWeight.ExtraBold,
        FontWeight.Black,
        FontWeight(950),
    ).map { weight ->
        Font(
            resId = R.font.montserrat_variable,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

/**
 * The skin's type scale: Montserrat throughout, with the expressive headings
 * this appearance is known for — heavy, tight and much larger than Material's
 * baseline at the top of the scale.
 */
private val PixelTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 40.sp, letterSpacing = (-1.0).sp, lineHeight = 44.sp),
    displayMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 34.sp, letterSpacing = (-0.6).sp, lineHeight = 38.sp),
    displaySmall = TextStyle(fontWeight = FontWeight.W700, fontSize = 30.sp, letterSpacing = (-0.4).sp, lineHeight = 34.sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.5).sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.W700, fontSize = 24.sp, letterSpacing = (-0.3).sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 21.sp, letterSpacing = (-0.2).sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.1).sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = 0.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.W600, fontSize = 14.sp, letterSpacing = 0.1.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.W400, fontSize = 16.sp, letterSpacing = 0.15.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.W400, fontSize = 14.sp, letterSpacing = 0.2.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.W400, fontSize = 12.sp, letterSpacing = 0.3.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.W600, fontSize = 14.sp, letterSpacing = 0.1.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.W600, fontSize = 12.sp, letterSpacing = 0.3.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.W500, fontSize = 11.sp, letterSpacing = 0.4.sp, lineHeight = 16.sp),
).withFamily(PixelFont)

/**
 * Which component language the app draws itself in.
 *
 * A skin, and deliberately only a skin: both styles read the same screens,
 * state and navigation, and the primitives they share — switches, the floating
 * bar, rows — are the whole of the difference. `Classic` stays the default and
 * every existing listener keeps it until they ask otherwise.
 */
enum class UiStyle(val key: String, val display: String) {
    CLASSIC("classic", "RX"),
    MATERIAL3("material3", "Material 3");

    companion object {
        fun of(key: String?): UiStyle = entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}

/** Applies [family] to every style in the scale, so nothing is left on Roboto. */
private fun Typography.withFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * The accent a listener picked for the ordinary themes.
 *
 * Nothing mode keeps its own red: that appearance is a costume, and letting a
 * violet accent into it would just look broken. Every other theme takes the
 * accent as its primary role, with a container tint and an on-colour chosen by
 * luminance so text stays legible on both pale and saturated accents.
 */
enum class AppAccent(val key: String, val display: String, val color: Color) {
    DEFAULT("default", "Default", Color.Unspecified),
    CRIMSON("crimson", "Crimson", Color(0xFFD71921)),
    AZURE("azure", "Azure", Color(0xFF3D7BFF)),
    EMERALD("emerald", "Emerald", Color(0xFF18C07A)),
    AMBER("amber", "Amber", Color(0xFFFFB020)),
    VIOLET("violet", "Violet", Color(0xFFA066FF));

    companion object {
        fun of(key: String?): AppAccent = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

private fun ColorScheme.withAccent(accent: AppAccent): ColorScheme {
    if (accent == AppAccent.DEFAULT) return this
    val ink = accent.color.contrasting()
    return copy(
        primary = accent.color,
        onPrimary = ink,
        primaryContainer = accent.color.copy(alpha = 0.22f),
        onPrimaryContainer = onBackground,
        secondary = accent.color,
        tertiary = accent.color,
    )
}

private fun Color.contrasting(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.6f) Color.Black else Color.White
}

/**
 * The skin's palette, taken from the app it follows rather than derived from a
 * seed: a deep purple ground, a violet primary, and pink and orange as the
 * second and third accents.
 */
private fun pixelScheme(dark: Boolean, accent: Color?): ColorScheme {
    val base = if (dark) {
        darkColorScheme(
            primary = PixelPrimary,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF5B3475),
            onPrimaryContainer = PixelLightPurple,
            secondary = PixelPink,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF5A2E43),
            onSecondaryContainer = Color(0xFFFFD9E2),
            tertiary = PixelOrange,
            onTertiary = Color.White,
            background = PixelPurpleDark,
            onBackground = Color.White,
            surface = PixelSurface,
            onSurface = PixelLightPurple,
            surfaceVariant = Color(0xFF3A2C4D),
            onSurfaceVariant = Color(0xFFCDB9E0),
            surfaceContainerLowest = Color(0xFF170D28),
            surfaceContainerLow = Color(0xFF221637),
            surfaceContainer = PixelSurface,
            surfaceContainerHigh = Color(0xFF352748),
            surfaceContainerHighest = Color(0xFF402F54),
            outline = Color(0xFF8F7CA8),
            outlineVariant = Color(0xFF4A3A5E),
            error = Color(0xFFFF5252),
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = PixelLightPrimary,
            onPrimary = Color.White,
            primaryContainer = PixelLightPrimaryContainer,
            onPrimaryContainer = PixelLightOnPrimaryContainer,
            secondary = PixelPink,
            onSecondary = Color.White,
            secondaryContainer = PixelPink.copy(alpha = 0.15f),
            onSecondaryContainer = PixelPink.copy(alpha = 0.85f),
            tertiary = PixelOrange,
            onTertiary = Color.Black,
            background = PixelLightBackground,
            onBackground = PixelLightOnSurface,
            surface = PixelLightSurface,
            onSurface = PixelLightOnSurface,
            surfaceVariant = PixelLightSurfaceVariant,
            onSurfaceVariant = PixelLightOnSurfaceVariant,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF3ECFF),
            surfaceContainer = Color(0xFFEDE5FB),
            surfaceContainerHigh = Color(0xFFE7DDF7),
            surfaceContainerHighest = Color(0xFFE1D6F3),
            outline = PixelLightOutline,
            outlineVariant = PixelLightOutline.copy(alpha = 0.6f),
            surfaceTint = PixelLightPrimary,
            error = Color(0xFFD32F2F),
            onError = Color.White,
        )
    }
    if (accent == null) return base
    // A listener's own accent replaces the violet and nothing else: pink and
    // orange stay the second and third roles, which is what keeps the skin
    // recognisable under a colour of someone else's choosing.
    val ink = accent.contrasting()
    return base.copy(
        primary = accent,
        onPrimary = ink,
        primaryContainer = lerp(base.background, accent, if (dark) 0.34f else 0.18f),
        onPrimaryContainer = if (dark) PixelLightPurple else PixelLightOnPrimaryContainer,
    )
}

@Composable
fun BitChordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    font: AppFont = AppFont.DEFAULT,
    accent: AppAccent = AppAccent.DEFAULT,
    liquidGlass: Boolean = false,
    style: UiStyle = UiStyle.CLASSIC,
    materialYou: Boolean = false,
    customAccent: Color? = null,
    content: @Composable () -> Unit,
) {
    val material3 = style == UiStyle.MATERIAL3
    val context = LocalContext.current
    val palette = when {
        !material3 -> if (darkTheme) DarkColors.withAccent(accent) else LightColors.withAccent(accent)
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> pixelScheme(darkTheme, customAccent ?: accent.color.takeIf { accent != AppAccent.DEFAULT })
    }
    CompositionLocalProvider(
        LocalNothingStyleEnabled provides false,
        com.music.bitchord.ui.components.LocalLiquidGlassEnabled provides (liquidGlass && !material3),
        LocalMaterial3StyleEnabled provides material3,
    ) {
        MaterialTheme(
            colorScheme = palette,
            typography = when {
                material3 -> PixelTypography
                font == AppFont.DEFAULT -> BitChordTypography
                else -> LegacyTypography
            },
            shapes = if (material3) PixelShapes else DefaultShapes,
        ) {
            // MaterialTheme provides no content colour of its own, and the app's
            // root is a Box that paints a background rather than a Surface, so
            // every Text that named no colour — the alert cards, the menu rows,
            // most popups — fell back to Compose's default Color.Black. In the
            // dark theme that is black on black: the unreadable text in some
            // popups. onBackground is the right default because it is the colour
            // of the page a popup floats over, and any Surface inside a popup
            // still overrides it for its own content.
            CompositionLocalProvider(
                LocalContentColor provides palette.onBackground,
                content = content,
            )
        }
    }
}

/**
 * Draws the status and navigation bar glyphs dark or light.
 *
 * `enableEdgeToEdge()` decides this from the *system* dark-mode setting, which
 * is the wrong input the moment the in-app theme disagrees with it: Light theme
 * on a phone in dark mode left white icons on a white bar, invisible. The bars
 * have to follow the content the app is actually painting. The player supplies
 * its own stable light-icon value and paints contrast behind it; every other
 * surface follows the theme. Hence a parameter rather than reading it here.
 */
@Composable
fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = findWindow(view) ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = dark
            isAppearanceLightNavigationBars = dark
        }
    }
}
