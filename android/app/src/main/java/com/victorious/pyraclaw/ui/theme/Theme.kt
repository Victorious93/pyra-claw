package com.victorious.pyraclaw.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fleet palette (FLEET-BRIEF rule 1). No white surfaces anywhere. */
object Neon {
    val Bg = Color(0xFF0B0F1A)
    val Surface = Color(0xFF111827)
    val SurfaceHigh = Color(0xFF172036)
    val SurfaceHighest = Color(0xFF1E2942)
    val Outline = Color(0xFF2B3654)
    val Cyan = Color(0xFF00E5FF)
    val Magenta = Color(0xFFFF2BD6)
    val Violet = Color(0xFF9B5CFF)
    val Green = Color(0xFF2BFF9A)
    val Amber = Color(0xFFFFC547)
    val Red = Color(0xFFFF4D6D)
    val Text = Color(0xFFE6EDF7)
    val TextDim = Color(0xFF93A1BC)
    val TextFaint = Color(0xFF5E6B86)

    val CyanMagenta = Brush.linearGradient(listOf(Cyan, Violet, Magenta))
    val CardGlow = Brush.verticalGradient(listOf(SurfaceHigh, Surface))
}

private val Scheme = darkColorScheme(
    primary = Neon.Cyan,
    onPrimary = Color(0xFF00222A),
    primaryContainer = Color(0xFF003C47),
    onPrimaryContainer = Color(0xFFB8F6FF),
    secondary = Neon.Magenta,
    onSecondary = Color(0xFF2E0027),
    secondaryContainer = Color(0xFF0E3A45), // tonal buttons/chips stay cyan-dark
    onSecondaryContainer = Color(0xFFB8F6FF),
    tertiary = Neon.Violet,
    onTertiary = Color(0xFF1C0040),
    tertiaryContainer = Color(0xFF2E1A55),
    onTertiaryContainer = Color(0xFFE5D6FF),
    background = Neon.Bg,
    onBackground = Neon.Text,
    surface = Neon.Bg,
    onSurface = Neon.Text,
    surfaceVariant = Neon.SurfaceHigh,
    onSurfaceVariant = Neon.TextDim,
    surfaceTint = Neon.Cyan,
    inverseSurface = Neon.SurfaceHighest,
    inverseOnSurface = Neon.Text,
    inversePrimary = Neon.Cyan,
    error = Neon.Red,
    onError = Color(0xFF2D0008),
    errorContainer = Color(0xFF4A1020),
    onErrorContainer = Color(0xFFFFD9DF),
    outline = Neon.Outline,
    outlineVariant = Color(0xFF222C45),
    scrim = Color(0xCC05070D),
    surfaceBright = Neon.SurfaceHighest,
    surfaceDim = Neon.Bg,
    surfaceContainerLowest = Color(0xFF080B14),
    surfaceContainerLow = Color(0xFF0E1320),
    surfaceContainer = Neon.Surface,
    surfaceContainerHigh = Neon.SurfaceHigh,
    surfaceContainerHighest = Neon.SurfaceHighest,
)

val Mono = FontFamily.Monospace

private val Type = Typography().let { t ->
    Typography(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge,
        bodyMedium = t.bodyMedium,
        bodySmall = t.bodySmall,
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

/** Small caps-style overline used for section headers. */
val Overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PyraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = AppShapes) {
        // Screens drawn outside a Surface (onboarding, sheets) must not fall back to black text.
        CompositionLocalProvider(LocalContentColor provides Neon.Text, content = content)
    }
}
