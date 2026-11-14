package com.flowkey.ime.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Windows app's design tokens (windows/flowclone/ui/theme.py). */
object Tokens {
    val Accent = Color(0xFF4C6FFF)
    val AccentLight = Color(0xFFA9BBFF)
    val Recording = Color(0xFFE5484D)
    val Success = Color(0xFF30A46C)
    val Warning = Color(0xFFF5A524)
}

private val Dark = darkColorScheme(
    primary = Tokens.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF26304F),
    onPrimaryContainer = Tokens.AccentLight,
    secondary = Tokens.AccentLight,
    secondaryContainer = Color(0xFF26304F),
    onSecondaryContainer = Tokens.AccentLight,
    background = Color(0xFF111114),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF111114),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF23232B),
    onSurfaceVariant = Color(0xFF9A9AA5),
    surfaceContainerLowest = Color(0xFF0C0C0F),
    surfaceContainerLow = Color(0xFF16161B),
    surfaceContainer = Color(0xFF1A1A1F),
    surfaceContainerHigh = Color(0xFF1F1F26),
    surfaceContainerHighest = Color(0xFF26262E),
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF2A2A32),
    error = Tokens.Recording,
)

private val Light = lightColorScheme(
    primary = Tokens.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF1B2C80),
    secondary = Color(0xFF3A57E0),
    secondaryContainer = Color(0xFFE3E9FF),
    onSecondaryContainer = Color(0xFF1B2C80),
    background = Color(0xFFF6F6F9),
    onBackground = Color(0xFF16161B),
    surface = Color(0xFFF6F6F9),
    onSurface = Color(0xFF16161B),
    surfaceVariant = Color(0xFFE9E9EF),
    onSurfaceVariant = Color(0xFF62626E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F0F5),
    surfaceContainerHighest = Color(0xFFE9E9EF),
    outline = Color(0xFFC9C9D3),
    outlineVariant = Color(0xFFE2E2E9),
    error = Color(0xFFD13438),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun FlowKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Type,
        shapes = AppShapes,
        content = content,
    )
}
