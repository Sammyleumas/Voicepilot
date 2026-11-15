package com.voicepilot.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepilot.core.settings.ThemePreference

/** Brand palette. Accents are used sparingly: one primary violet, one secondary blue. */
object VpColors {
    val Ink = Color(0xFF0B0B12)
    val Surface = Color(0xFF13131D)
    val SurfaceRaised = Color(0xFF1B1B28)
    val Outline = Color(0xFF2A2A3A)
    val Violet = Color(0xFF7C5CFF)
    val VioletSoft = Color(0xFFB4A3FF)
    val Blue = Color(0xFF4C8DFF)
    val TextHigh = Color(0xFFF2F1F7)
    val TextMid = Color(0xFFA9A7B8)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF6B6B)
}

/** Semantic colours not covered by Material's scheme. */
@Immutable
data class StatusColors(val success: Color, val warning: Color, val danger: Color, val listening: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(VpColors.Success, VpColors.Warning, VpColors.Danger, VpColors.Violet)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = VpColors.Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2160),
    onPrimaryContainer = VpColors.VioletSoft,
    secondary = VpColors.Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF16284A),
    onSecondaryContainer = Color(0xFFBBD2FF),
    background = VpColors.Ink,
    onBackground = VpColors.TextHigh,
    surface = VpColors.Ink,
    onSurface = VpColors.TextHigh,
    surfaceVariant = VpColors.Surface,
    onSurfaceVariant = VpColors.TextMid,
    surfaceContainerLowest = VpColors.Ink,
    surfaceContainerLow = Color(0xFF101018),
    surfaceContainer = VpColors.Surface,
    surfaceContainerHigh = VpColors.SurfaceRaised,
    surfaceContainerHighest = Color(0xFF232332),
    outline = VpColors.Outline,
    outlineVariant = Color(0xFF20202D),
    error = VpColors.Danger,
    onError = Color.Black,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF5B3DF5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E0FF),
    onPrimaryContainer = Color(0xFF22106B),
    secondary = Color(0xFF2F6FE0),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF15151C),
    surface = Color(0xFFF7F7FA),
    onSurface = Color(0xFF15151C),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5B5A68),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0EFF5),
    surfaceContainerHighest = Color(0xFFE8E7EF),
    outline = Color(0xFFD9D8E2),
    outlineVariant = Color(0xFFE8E7EF),
    error = Color(0xFFD03A3A),
)

private val VpTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

private val VpShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing scale used across screens. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 20.dp
}

@Composable
fun VoicePilotTheme(preference: ThemePreference = ThemePreference.DARK, content: @Composable () -> Unit) {
    val dark = when (preference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = VpTypography,
        shapes = VpShapes,
        content = content,
    )
}
