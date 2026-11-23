package com.rentalx.playa

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The accent the interface should wear, from the three things that can ask for one.
 *
 * A colour picked by hand wins outright: it is an explicit decision, and the icon
 * is a preset. Otherwise the icon's own accent is used while [followsIcon] is on,
 * and null — "no opinion" — lets the theme keep its current preset.
 */
fun resolvedAccent(manual: Long?, followsIcon: Boolean, iconAccent: Long?): Long? = when {
    manual != null -> manual
    followsIcon -> iconAccent
    else -> null
}

/** A group of icons that belong to one drawing: the grid's headings. */enum class IconFamily(val labelRes: Int) {
    Modern(R.string.icon_family_modern),
    Glass(R.string.icon_family_glass),
    Neon(R.string.icon_family_neon),
    Legacy(R.string.icon_family_legacy),
}

/**
 * The home-screen icon choices. Each is an `<activity-alias>` in the manifest
 * carrying the launcher intent filter; exactly one is enabled at a time.
 *
 * The glass icons are adaptive icons whose foreground already holds the finished
 * icon in its own shape (Apple squircle, Samsung squircle or Pixel circle), so
 * [previewRes] — that foreground — shows the variant as designed rather than
 * through the device's launcher mask. The first two keep yesterday's alias
 * names so a choice made before the update stays enabled.
 *
 * [accent] is the colour the icon is drawn in, offered to the theme when the
 * listener asks for the interface to follow the icon (issue #44). Null means the
 * icon has no opinion — the monochrome marks are grey by definition — and the
 * theme keeps whatever accent it had.
 */
enum class AppIcon(
    val alias: String,
    val iconRes: Int,
    val labelRes: Int,
    val previewRes: Int? = null,
    val family: IconFamily = IconFamily.Modern,
    val accent: Long? = null,
) {
    Default("LauncherDefault", R.mipmap.ic_rx, R.string.app_icon_default),
    Monochrome("LauncherMonochrome", R.mipmap.ic_monochrome, R.string.app_icon_monochrome),

    LiquidGlass(
        "LauncherGlassLight", R.mipmap.ic_liquid_glass, R.string.app_icon_liquid_glass,
        R.mipmap.ic_liquid_glass_foreground, IconFamily.Glass, 0xFF6EA8FF,
    ),
    LiquidGlassDark(
        "LauncherGlassDark", R.mipmap.ic_liquid_glass_dark, R.string.app_icon_liquid_glass_dark,
        R.mipmap.ic_liquid_glass_dark_foreground, IconFamily.Glass, 0xFF7C8CF8,
    ),
    LiquidGlassClear(
        "LauncherGlassClear", R.mipmap.ic_liquid_glass_clear, R.string.app_icon_liquid_glass_clear,
        R.mipmap.ic_liquid_glass_clear_foreground, IconFamily.Glass, 0xFF55C2E6,
    ),
    LiquidGlassClearDark(
        "LauncherGlassClearDark", R.mipmap.ic_liquid_glass_clear_dark, R.string.app_icon_liquid_glass_clear_dark,
        R.mipmap.ic_liquid_glass_clear_dark_foreground, IconFamily.Glass, 0xFF5AA9D6,
    ),
    SamsungGlass(
        "LauncherSamsungGlass", R.mipmap.ic_samsung_glass, R.string.app_icon_samsung_glass,
        R.mipmap.ic_samsung_glass_foreground, IconFamily.Glass, 0xFF4C7DFF,
    ),
    SamsungGlassDark(
        "LauncherSamsungGlassDark", R.mipmap.ic_samsung_glass_dark, R.string.app_icon_samsung_glass_dark,
        R.mipmap.ic_samsung_glass_dark_foreground, IconFamily.Glass, 0xFF6C8EFF,
    ),
    SamsungGlassClear(
        "LauncherSamsungGlassClear", R.mipmap.ic_samsung_glass_clear, R.string.app_icon_samsung_glass_clear,
        R.mipmap.ic_samsung_glass_clear_foreground, IconFamily.Glass, 0xFF35B7C4,
    ),
    SamsungGlassClearDark(
        "LauncherSamsungGlassClearDark", R.mipmap.ic_samsung_glass_clear_dark, R.string.app_icon_samsung_glass_clear_dark,
        R.mipmap.ic_samsung_glass_clear_dark_foreground, IconFamily.Glass, 0xFF2FA6B3,
    ),
    PixelGlass(
        "LauncherPixelGlass", R.mipmap.ic_pixel_glass, R.string.app_icon_pixel_glass,
        R.mipmap.ic_pixel_glass_foreground, IconFamily.Glass, 0xFF4285F4,
    ),
    PixelGlassDark(
        "LauncherPixelGlassDark", R.mipmap.ic_pixel_glass_dark, R.string.app_icon_pixel_glass_dark,
        R.mipmap.ic_pixel_glass_dark_foreground, IconFamily.Glass, 0xFF6091F7,
    ),
    PixelGlassClear(
        "LauncherPixelGlassClear", R.mipmap.ic_pixel_glass_clear, R.string.app_icon_pixel_glass_clear,
        R.mipmap.ic_pixel_glass_clear_foreground, IconFamily.Glass, 0xFF34A853,
    ),
    PixelGlassClearDark(
        "LauncherPixelGlassClearDark", R.mipmap.ic_pixel_glass_clear_dark, R.string.app_icon_pixel_glass_clear_dark,
        R.mipmap.ic_pixel_glass_clear_dark_foreground, IconFamily.Glass, 0xFF2E9E4C,
    ),

    NeonSilver(
        "LauncherNeonSilver", R.mipmap.ic_neon_silver, R.string.app_icon_neon_silver,
        R.mipmap.ic_neon_silver_foreground, IconFamily.Neon, 0xFFB9C2D0,
    ),
    NeonCyan(
        "LauncherNeonCyan", R.mipmap.ic_neon_cyan, R.string.app_icon_neon_cyan,
        R.mipmap.ic_neon_cyan_foreground, IconFamily.Neon, 0xFF22E3C8,
    ),
    NeonViolet(
        "LauncherNeonViolet", R.mipmap.ic_neon_violet, R.string.app_icon_neon_violet,
        R.mipmap.ic_neon_violet_foreground, IconFamily.Neon, 0xFFA46BFF,
    ),
    SamsungNeonSilver(
        "LauncherSamsungNeonSilver", R.mipmap.ic_samsung_neon_silver, R.string.app_icon_samsung_neon_silver,
        R.mipmap.ic_samsung_neon_silver_foreground, IconFamily.Neon, 0xFFC4CBD8,
    ),
    SamsungNeonCyan(
        "LauncherSamsungNeonCyan", R.mipmap.ic_samsung_neon_cyan, R.string.app_icon_samsung_neon_cyan,
        R.mipmap.ic_samsung_neon_cyan_foreground, IconFamily.Neon, 0xFF19D3C5,
    ),
    SamsungNeonViolet(
        "LauncherSamsungNeonViolet", R.mipmap.ic_samsung_neon_violet, R.string.app_icon_samsung_neon_violet,
        R.mipmap.ic_samsung_neon_violet_foreground, IconFamily.Neon, 0xFF9A6CFF,
    ),
    PixelNeonSilver(
        "LauncherPixelNeonSilver", R.mipmap.ic_pixel_neon_silver, R.string.app_icon_pixel_neon_silver,
        R.mipmap.ic_pixel_neon_silver_foreground, IconFamily.Neon, 0xFFC7CDD6,
    ),
    PixelNeonCyan(
        "LauncherPixelNeonCyan", R.mipmap.ic_pixel_neon_cyan, R.string.app_icon_pixel_neon_cyan,
        R.mipmap.ic_pixel_neon_cyan_foreground, IconFamily.Neon, 0xFF2BD9C6,
    ),
    PixelNeonViolet(
        "LauncherPixelNeonViolet", R.mipmap.ic_pixel_neon_violet, R.string.app_icon_pixel_neon_violet,
        R.mipmap.ic_pixel_neon_violet_foreground, IconFamily.Neon, 0xFFB07CFF,
    ),

    // The mark this app shipped before the RX redesign, kept for the people who
    // asked for it back (issues #46, #47). Square-on-a-square, as launchers of
    // the time drew it: these are not adaptive, and are not meant to be.
    LegacyClassic(
        "LauncherLegacy", R.drawable.ic_rx_legacy, R.string.app_icon_legacy_classic,
        family = IconFamily.Legacy,
    ),
    LegacyDark(
        "LauncherLegacyDark", R.drawable.ic_rx_legacy_dark, R.string.app_icon_legacy_dark,
        family = IconFamily.Legacy,
    ),
    LegacyAccent(
        "LauncherLegacyAccent", R.drawable.ic_rx_legacy_accent, R.string.app_icon_legacy_accent,
        family = IconFamily.Legacy, accent = 0xFF22E3C8,
    );

    private fun component(context: Context) =
        ComponentName(context.packageName, "com.rentalx.playa.$alias")

    companion object {
        /**
         * The family whose colourways the settings screen is showing.
         *
         * One family open at a time, as the issue asked for: a tap on a mark
         * reveals its own colours rather than expanding every group at once.
         */
        fun familyOf(icon: AppIcon): IconFamily = icon.family

        fun inFamily(family: IconFamily): List<AppIcon> = entries.filter { it.family == family }

        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: Default
        }

        /** Enables [icon] before disabling the rest so there is never a moment with no launcher entry. */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
