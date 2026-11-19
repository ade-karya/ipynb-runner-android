package com.adekarya.ipynbrunner.ui.theme

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8A2FF),
    onPrimary = Color(0xFF201047),
    primaryContainer = Color(0xFF3A2A6B),
    onPrimaryContainer = Color(0xFFE8DFFF),
    secondary = Color(0xFF7EE7F5),
    onSecondary = Color(0xFF002F36),
    secondaryContainer = Color(0xFF16404A),
    onSecondaryContainer = Color(0xFFB6F3FB),
    tertiary = Color(0xFFFFD27D),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF52400F),
    onTertiaryContainer = Color(0xFFFFE8B3),
    background = Color(0xFF12111C),
    onBackground = Color(0xFFF3F0FF),
    surface = Color(0xFF12111C),
    onSurface = Color(0xFFF3F0FF),
    surfaceVariant = Color(0xFF29263A),
    onSurfaceVariant = Color(0xFFCBC5DF),
    surfaceTint = Color(0xFFB8A2FF),
    outline = Color(0xFF5C5773),
    outlineVariant = Color(0xFF353147),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE9E3F8),
    inverseOnSurface = Color(0xFF302D41),
    inversePrimary = Color(0xFF6C4FD1),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6442C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1F0A52),
    secondary = Color(0xFF006874),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF97F0FF),
    onSecondaryContainer = Color(0xFF001F24),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFCFAFF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFCFAFF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE7E1F2),
    onSurfaceVariant = Color(0xFF484457),
    surfaceTint = Color(0xFF6442C9),
    outline = Color(0xFF797386),
    outlineVariant = Color(0xFFCBC4D9),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF312F37),
    inverseOnSurface = Color(0xFFF4EFF7),
    inversePrimary = Color(0xFFCDBDFF),
    scrim = Color(0xFF000000),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

@Composable
fun NotebookRunnerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
