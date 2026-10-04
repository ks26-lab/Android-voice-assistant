package com.chockXlate.teachablevoice.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ColorAccentPrimary,
    onPrimary = ColorTextPrimary,
    primaryContainer = ColorAccentSubtle,
    onPrimaryContainer = ColorTextAccent,
    secondary = ColorTextSecondary,
    onSecondary = ColorBgBase,
    background = ColorBgBase,
    onBackground = ColorTextPrimary,
    surface = ColorBgSurface,
    onSurface = ColorTextPrimary,
    surfaceVariant = ColorBgSurfaceElevated,
    onSurfaceVariant = ColorTextSecondary,
    outline = ColorBorderMedium,
    outlineVariant = ColorBorderSubtle
)

@Composable
fun TeachableVoiceTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = ColorBgBase.toArgb()
                window.navigationBarColor = ColorBgBase.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
