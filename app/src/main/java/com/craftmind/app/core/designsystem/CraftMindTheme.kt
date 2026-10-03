package com.craftmind.app.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF355A31),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EBCB),
    onPrimaryContainer = Color(0xFF172715),
    secondary = Color(0xFF54684C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E8D8),
    onSecondaryContainer = Color(0xFF1D291A),
    tertiary = Color(0xFF876642),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F2),
    onBackground = Color(0xFF1B2119),
    surface = Color(0xFFF6F7F2),
    onSurface = Color(0xFF1B2119),
    surfaceVariant = Color(0xFFE9EDE5),
    onSurfaceVariant = Color(0xFF485047),
    outline = Color(0xFF798175),
    outlineVariant = Color(0xFFD2D8CD),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7D99E),
    onPrimary = Color(0xFF20331C),
    primaryContainer = Color(0xFF354D2F),
    onPrimaryContainer = Color(0xFFD5EEC3),
    secondary = Color(0xFFBDCBB2),
    onSecondary = Color(0xFF293326),
    secondaryContainer = Color(0xFF3B4637),
    onSecondaryContainer = Color(0xFFD9E6CF),
    tertiary = Color(0xFFE1C29A),
    onTertiary = Color(0xFF3E2D18),
    background = Color(0xFF11140F),
    onBackground = Color(0xFFE3E8DE),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE3E8DE),
    surfaceVariant = Color(0xFF292F27),
    onSurfaceVariant = Color(0xFFC0C9BA),
    outline = Color(0xFF8A9584),
    outlineVariant = Color(0xFF3A4237),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val CraftMindTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1.4).sp,
        ),
        displayMedium = base.displayMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1.1).sp,
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.8).sp,
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        ),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val CraftMindShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(Corners.small),
    small = androidx.compose.foundation.shape.RoundedCornerShape(Corners.small),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(Corners.medium),
    large = androidx.compose.foundation.shape.RoundedCornerShape(Corners.large),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(Corners.extraLarge),
)

@Composable
fun CraftMindTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CraftMindTypography,
        shapes = CraftMindShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
