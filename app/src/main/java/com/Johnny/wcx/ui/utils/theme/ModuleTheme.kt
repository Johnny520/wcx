package com.Johnny.wcx.ui.utils.theme

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Theme for the module's OWN UI (settings page + module dialogs). Wraps content in BOTH a Material 3
 * [MaterialExpressiveTheme] and a miuix [MiuixTheme] driven by [ThemeSettings], so components from
 * either design system share one accent:
 *
 * - custom color OFF → each system's default palette (miuix blue / the bundled Material scheme);
 * - custom color ON → the palette style + color spec generated from the user's custom seed
 *   ([SeedResolver.customSeed]: wallpaper accent when 动态壁纸取色 is on, else the chosen seed color).
 *
 * Re-themes live: every [ThemeSettings] value is observable, so a settings row change recomposes.
 *
 * NEVER CALL THIS INSIDE MODULE APP.
 */
@Composable
fun ModuleTheme(
    darkTheme: Boolean = ThemeSettings.themeMode.resolve(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // ---- miuix ----
    val controller = if (!ThemeSettings.customColor) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            isDark = darkTheme,
        )
    } else {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = Color(SeedResolver.customSeed(context, darkTheme)),
            colorSpec = ThemeSettings.effectiveColorSpec.miuix,
            paletteStyle = ThemeSettings.paletteStyle.miuix,
            isDark = darkTheme,
        )
    }

    // ---- Material 3 ----
    val materialScheme = if (!ThemeSettings.customColor) {
        if (darkTheme) darkScheme else lightScheme
    } else {
        SeedResolver.materialScheme(SeedResolver.customSeed(context, darkTheme), darkTheme)
    }

    MaterialExpressiveTheme(
        colorScheme = materialScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        MiuixTheme(controller = controller) {
            CompositionLocalProvider(
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
            ) {
                content()
            }
        }
    }
}
