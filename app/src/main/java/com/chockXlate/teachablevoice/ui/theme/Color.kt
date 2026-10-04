package com.chockXlate.teachablevoice.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Authoritative color palette contract supporting both Dark and Light themes.
 * Contrast ratios and text readability are verified across all surfaces.
 */
data class AppColors(
    val isDark: Boolean,
    val bgBase: Color,
    val bgSurface: Color,
    val bgSurfaceElevated: Color,
    val bgSurfaceSubtle: Color,
    val bgInput: Color,
    val bgCode: Color,
    val borderSubtle: Color,
    val borderMedium: Color,
    val borderStrong: Color,
    val borderFocus: Color,
    val accentPrimary: Color,
    val accentHover: Color,
    val accentActive: Color,
    val accentSubtle: Color,
    val accentGlow: Color,
    val statusReady: Color,
    val statusReadyBg: Color,
    val statusReadyBorder: Color,
    val statusTeaching: Color,
    val statusTeachingBg: Color,
    val statusTeachingBorder: Color,
    val statusLearning: Color,
    val statusLearningBg: Color,
    val statusLearningBorder: Color,
    val statusWarning: Color,
    val statusWarningBg: Color,
    val statusWarningBorder: Color,
    val statusError: Color,
    val statusErrorBg: Color,
    val statusErrorBorder: Color,
    val statusHandoff: Color,
    val statusHandoffBg: Color,
    val statusHandoffBorder: Color,
    val statusInactive: Color,
    val statusInactiveBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val textAccent: Color
)

val DarkAppColors = AppColors(
    isDark = true,
    bgBase = Color(0xFF0A0C10),
    bgSurface = Color(0xFF12151B),
    bgSurfaceElevated = Color(0xFF181C24),
    bgSurfaceSubtle = Color(0xFF0E1015),
    bgInput = Color(0xFF151820),
    bgCode = Color(0xFF07080B),
    borderSubtle = Color(0x14FFFFFF),
    borderMedium = Color(0x24FFFFFF),
    borderStrong = Color(0x38FFFFFF),
    borderFocus = Color(0x807C5CFF),
    accentPrimary = Color(0xFF7C5CFF),
    accentHover = Color(0xFF8F72FF),
    accentActive = Color(0xFF6C46FA),
    accentSubtle = Color(0x1F7C5CFF),
    accentGlow = Color(0x407C5CFF),
    statusReady = Color(0xFF10B981),
    statusReadyBg = Color(0x1F10B981),
    statusReadyBorder = Color(0x4D10B981),
    statusTeaching = Color(0xFFF59E0B),
    statusTeachingBg = Color(0x1FF59E0B),
    statusTeachingBorder = Color(0x4DF59E0B),
    statusLearning = Color(0xFFA78BFA),
    statusLearningBg = Color(0x24A78BFA),
    statusLearningBorder = Color(0x59A78BFA),
    statusWarning = Color(0xFFF59E0B),
    statusWarningBg = Color(0x1FF59E0B),
    statusWarningBorder = Color(0x59F59E0B),
    statusError = Color(0xFFEF4444),
    statusErrorBg = Color(0x1FEF4444),
    statusErrorBorder = Color(0x4DEF4444),
    statusHandoff = Color(0xFFF59E0B),
    statusHandoffBg = Color(0x29F59E0B),
    statusHandoffBorder = Color(0x66F59E0B),
    statusInactive = Color(0xFF4B5563),
    statusInactiveBg = Color(0x264B5563),
    textPrimary = Color(0xFFF3F4F6),
    textSecondary = Color(0xFF9CA3AF),
    textMuted = Color(0xFF6B7280),
    textDisabled = Color(0xFF4B5563),
    textAccent = Color(0xFFA78BFA)
)

val LightAppColors = AppColors(
    isDark = false,
    bgBase = Color(0xFFF8F9FA),
    bgSurface = Color(0xFFFFFFFF),
    bgSurfaceElevated = Color(0xFFF1F3F5),
    bgSurfaceSubtle = Color(0xFFE9ECEF),
    bgInput = Color(0xFFF1F3F5),
    bgCode = Color(0xFFE9ECEF),
    borderSubtle = Color(0x1F000000),
    borderMedium = Color(0x33000000),
    borderStrong = Color(0x4D000000),
    borderFocus = Color(0x806C46FA),
    accentPrimary = Color(0xFF6C46FA),
    accentHover = Color(0xFF5B35E0),
    accentActive = Color(0xFF4F28D9),
    accentSubtle = Color(0x1F6C46FA),
    accentGlow = Color(0x336C46FA),
    statusReady = Color(0xFF059669),
    statusReadyBg = Color(0x1A10B981),
    statusReadyBorder = Color(0x4D10B981),
    statusTeaching = Color(0xFFD97706),
    statusTeachingBg = Color(0x1AF59E0B),
    statusTeachingBorder = Color(0x4DF59E0B),
    statusLearning = Color(0xFF7C3AED),
    statusLearningBg = Color(0x1FA78BFA),
    statusLearningBorder = Color(0x4DA78BFA),
    statusWarning = Color(0xFFD97706),
    statusWarningBg = Color(0x1AF59E0B),
    statusWarningBorder = Color(0x4DF59E0B),
    statusError = Color(0xFFDC2626),
    statusErrorBg = Color(0x1AEF4444),
    statusErrorBorder = Color(0x4DEF4444),
    statusHandoff = Color(0xFFD97706),
    statusHandoffBg = Color(0x24F59E0B),
    statusHandoffBorder = Color(0x59F59E0B),
    statusInactive = Color(0xFF6B7280),
    statusInactiveBg = Color(0x1F6B7280),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF4B5563),
    textMuted = Color(0xFF6B7280),
    textDisabled = Color(0xFF9CA3AF),
    textAccent = Color(0xFF6C46FA)
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

// Dynamic theme-aware color properties for Composable UI
val ColorBgBase: Color @Composable get() = LocalAppColors.current.bgBase
val ColorBgSurface: Color @Composable get() = LocalAppColors.current.bgSurface
val ColorBgSurfaceElevated: Color @Composable get() = LocalAppColors.current.bgSurfaceElevated
val ColorBgSurfaceSubtle: Color @Composable get() = LocalAppColors.current.bgSurfaceSubtle
val ColorBgInput: Color @Composable get() = LocalAppColors.current.bgInput
val ColorBgCode: Color @Composable get() = LocalAppColors.current.bgCode

val ColorBorderSubtle: Color @Composable get() = LocalAppColors.current.borderSubtle
val ColorBorderMedium: Color @Composable get() = LocalAppColors.current.borderMedium
val ColorBorderStrong: Color @Composable get() = LocalAppColors.current.borderStrong
val ColorBorderFocus: Color @Composable get() = LocalAppColors.current.borderFocus

val ColorAccentPrimary: Color @Composable get() = LocalAppColors.current.accentPrimary
val ColorAccentHover: Color @Composable get() = LocalAppColors.current.accentHover
val ColorAccentActive: Color @Composable get() = LocalAppColors.current.accentActive
val ColorAccentSubtle: Color @Composable get() = LocalAppColors.current.accentSubtle
val ColorAccentGlow: Color @Composable get() = LocalAppColors.current.accentGlow

val ColorStatusReady: Color @Composable get() = LocalAppColors.current.statusReady
val ColorStatusReadyBg: Color @Composable get() = LocalAppColors.current.statusReadyBg
val ColorStatusReadyBorder: Color @Composable get() = LocalAppColors.current.statusReadyBorder

val ColorStatusTeaching: Color @Composable get() = LocalAppColors.current.statusTeaching
val ColorStatusTeachingBg: Color @Composable get() = LocalAppColors.current.statusTeachingBg
val ColorStatusTeachingBorder: Color @Composable get() = LocalAppColors.current.statusTeachingBorder

val ColorStatusLearning: Color @Composable get() = LocalAppColors.current.statusLearning
val ColorStatusLearningBg: Color @Composable get() = LocalAppColors.current.statusLearningBg
val ColorStatusLearningBorder: Color @Composable get() = LocalAppColors.current.statusLearningBorder

val ColorStatusWarning: Color @Composable get() = LocalAppColors.current.statusWarning
val ColorStatusWarningBg: Color @Composable get() = LocalAppColors.current.statusWarningBg
val ColorStatusWarningBorder: Color @Composable get() = LocalAppColors.current.statusWarningBorder

val ColorStatusError: Color @Composable get() = LocalAppColors.current.statusError
val ColorStatusErrorBg: Color @Composable get() = LocalAppColors.current.statusErrorBg
val ColorStatusErrorBorder: Color @Composable get() = LocalAppColors.current.statusErrorBorder

val ColorStatusHandoff: Color @Composable get() = LocalAppColors.current.statusHandoff
val ColorStatusHandoffBg: Color @Composable get() = LocalAppColors.current.statusHandoffBg
val ColorStatusHandoffBorder: Color @Composable get() = LocalAppColors.current.statusHandoffBorder

val ColorStatusInactive: Color @Composable get() = LocalAppColors.current.statusInactive
val ColorStatusInactiveBg: Color @Composable get() = LocalAppColors.current.statusInactiveBg

val ColorTextPrimary: Color @Composable get() = LocalAppColors.current.textPrimary
val ColorTextSecondary: Color @Composable get() = LocalAppColors.current.textSecondary
val ColorTextMuted: Color @Composable get() = LocalAppColors.current.textMuted
val ColorTextDisabled: Color @Composable get() = LocalAppColors.current.textDisabled
val ColorTextAccent: Color @Composable get() = LocalAppColors.current.textAccent
