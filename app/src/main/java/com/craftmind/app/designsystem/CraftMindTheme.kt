package com.craftmind.app.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftmind.app.domain.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF315846),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E9DC),
    onPrimaryContainer = Color(0xFF102B1C),
    secondary = Color(0xFF766044),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2E7D3),
    onSecondaryContainer = Color(0xFF302311),
    tertiary = Color(0xFF496D72),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7F5),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFE9EEEA),
    onSurfaceVariant = Color(0xFF4D5A51),
    outline = Color(0xFF7B887E),
    outlineVariant = Color(0xFFD5DDD7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA6D0B2),
    onPrimary = Color(0xFF153323),
    primaryContainer = Color(0xFF2B4D39),
    onPrimaryContainer = Color(0xFFD0EBD5),
    secondary = Color(0xFFE0C69F),
    onSecondary = Color(0xFF3E2F19),
    secondaryContainer = Color(0xFF57462E),
    onSecondaryContainer = Color(0xFFF4E3C9),
    tertiary = Color(0xFFA4CDD1),
    onTertiary = Color(0xFF16363A),
    background = Color(0xFF111713),
    onBackground = Color(0xFFE2E9E3),
    surface = Color(0xFF18211B),
    onSurface = Color(0xFFE2E9E3),
    surfaceVariant = Color(0xFF26332B),
    onSurfaceVariant = Color(0xFFC0CDC2),
    outline = Color(0xFF89978C),
    outlineVariant = Color(0xFF3C4A40),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val CraftMindShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val CraftMindTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 54.sp,
        letterSpacing = (-1.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
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
        lineHeight = 20.sp,
    ),
)

@Composable
fun CraftMindTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CraftMindTypography,
        shapes = CraftMindShapes,
        content = content,
    )
}
