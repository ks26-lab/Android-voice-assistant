package com.chockXlate.teachablevoice.ui.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Lightweight, non-destructive theme preference manager.
 * Persists user preference cleanly without triggering activity destruction or resetting application state.
 */
object ThemePreferences {
    private const val PREFS_NAME = "teachable_voice_theme_prefs"
    private const val KEY_DARK_MODE = "is_dark_mode"

    private var sharedPreferences: SharedPreferences? = null

    var isDarkMode by mutableStateOf(true)
        internal set

    fun init(context: Context) {
        if (sharedPreferences == null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPreferences = prefs
            isDarkMode = prefs.getBoolean(KEY_DARK_MODE, true)
        }
    }

    fun toggleTheme() {
        val newMode = !isDarkMode
        isDarkMode = newMode
        sharedPreferences?.edit()?.putBoolean(KEY_DARK_MODE, newMode)?.apply()
    }

    fun setDarkMode(dark: Boolean) {
        if (isDarkMode != dark) {
            isDarkMode = dark
            sharedPreferences?.edit()?.putBoolean(KEY_DARK_MODE, dark)?.apply()
        }
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    onPrimary = Color(0xFFF3F4F6),
    primaryContainer = Color(0x1F7C5CFF),
    onPrimaryContainer = Color(0xFFA78BFA),
    secondary = Color(0xFF9CA3AF),
    onSecondary = Color(0xFF0A0C10),
    background = Color(0xFF0A0C10),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF12151B),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF181C24),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0x24FFFFFF),
    outlineVariant = Color(0x14FFFFFF)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6C46FA),
    onPrimary = Color.White,
    primaryContainer = Color(0x1F6C46FA),
    onPrimaryContainer = Color(0xFF4F28D9),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0x33000000),
    outlineVariant = Color(0x1F000000)
)

@Composable
fun TeachableVoiceTheme(
    darkTheme: Boolean = ThemePreferences.isDarkMode,
    content: @Composable () -> Unit
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = appColors.bgBase.toArgb()
                window.navigationBarColor = appColors.bgBase.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
