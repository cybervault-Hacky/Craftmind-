package com.craftmind.app.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftmind.app.domain.settings.ThemeMode

/**
 * CraftMind theme (Phase 15 design system).
 *
 * Everything visual in the app is derived from the plain-Kotlin token objects in this package:
 * [CraftMindColorTokens], [CraftMindSpacing], [CraftMindRadius], [CraftMindElevation], [CraftMindSizing],
 * [CraftMindIconSize], [CraftMindBreakpoints], [CraftMindTypeScale], and [CraftMindMotionTokens]. This file is the
 * only place where those numbers become Compose `Color`, `Dp`, `Shape`, and `TextStyle` values, which is what makes
 * the system enforceable: a screen cannot invent a colour or a corner radius, it can only ask for one.
 *
 * Material 3 is kept as the component substrate (so text scaling, semantics, and platform behaviour stay correct),
 * but its colour, typography, and shape slots are overwritten wholesale by CraftMind tokens.
 */

/** Converts a token ARGB value into a Compose colour. */
fun craftMindColor(argb: Long): Color = Color(argb)

/** Layout dimensions in `Dp`, mirroring [CraftMindSpacing]. */
object CraftMindLayout {
    val xxs: Dp = CraftMindSpacing.XXS.dp
    val xs: Dp = CraftMindSpacing.XS.dp
    val sm: Dp = CraftMindSpacing.SM.dp
    val md: Dp = CraftMindSpacing.MD.dp
    val lg: Dp = CraftMindSpacing.LG.dp
    val xl: Dp = CraftMindSpacing.XL.dp
    val xxl: Dp = CraftMindSpacing.XXL.dp
    val xxxl: Dp = CraftMindSpacing.XXXL.dp

    val cardInset: Dp = CraftMindSpacing.CARD_INSET.dp
    val screenMargin: Dp = CraftMindSpacing.SCREEN_MARGIN.dp
    val screenMarginWide: Dp = CraftMindSpacing.SCREEN_MARGIN_WIDE.dp
    val screenMarginVertical: Dp = CraftMindSpacing.SCREEN_MARGIN_VERTICAL.dp

    val hairline: Dp = CraftMindBorder.HAIRLINE.dp
    val emphasis: Dp = CraftMindBorder.EMPHASIS.dp

    val flat: Dp = CraftMindElevation.FLAT.dp
    val cardElevation: Dp = CraftMindElevation.CARD.dp
    val raised: Dp = CraftMindElevation.RAISED.dp
    val controls: Dp = CraftMindElevation.CONTROLS.dp
    val overlay: Dp = CraftMindElevation.OVERLAY.dp

    val iconXs: Dp = CraftMindIconSize.XS.dp
    val iconSm: Dp = CraftMindIconSize.SM.dp
    val iconMd: Dp = CraftMindIconSize.MD.dp
    val iconLg: Dp = CraftMindIconSize.LG.dp
    val iconXl: Dp = CraftMindIconSize.XL.dp
    val iconFeature: Dp = CraftMindIconSize.FEATURE.dp

    val minTouchTarget: Dp = CraftMindSizing.MIN_TOUCH_TARGET.dp
    val controlHeight: Dp = CraftMindSizing.CONTROL_HEIGHT_MD.dp
    val controlHeightLarge: Dp = CraftMindSizing.CONTROL_HEIGHT_LG.dp
    val compactHeight: Dp = CraftMindSizing.COMPACT_VISUAL_HEIGHT.dp
    val compactTouchPadding: Dp = CraftMindSizing.COMPACT_TOUCH_PADDING.dp
    val navIcon: Dp = CraftMindSizing.NAV_ICON.dp
    val brandMark: Dp = CraftMindSizing.BRAND_MARK.dp
    val thumbnail: Dp = CraftMindSizing.THUMBNAIL.dp
    val progressSmall: Dp = CraftMindSizing.PROGRESS_SM.dp
    val progressMedium: Dp = CraftMindSizing.PROGRESS_MD.dp

    val compactBreakpoint: Dp = CraftMindBreakpoints.COMPACT.dp
    val mediumBreakpoint: Dp = CraftMindBreakpoints.MEDIUM.dp
    val expandedBreakpoint: Dp = CraftMindBreakpoints.EXPANDED.dp
    val contentMaxWidth: Dp = CraftMindBreakpoints.CONTENT_MAX_WIDTH.dp
    val screenMaxWidth: Dp = CraftMindBreakpoints.SCREEN_MAX_WIDTH.dp
    val overlayMaxWidth: Dp = CraftMindBreakpoints.OVERLAY_MAX_WIDTH.dp
}

/** Corner radii as ready-to-use shapes, mirroring [CraftMindRadius]. */
object CraftMindShapes {
    val xs: Shape = RoundedCornerShape(CraftMindRadius.XS.dp)
    val sm: Shape = RoundedCornerShape(CraftMindRadius.SM.dp)
    val md: Shape = RoundedCornerShape(CraftMindRadius.MD.dp)
    val lg: Shape = RoundedCornerShape(CraftMindRadius.LG.dp)
    val xl: Shape = RoundedCornerShape(CraftMindRadius.XL.dp)
    val pill: Shape = RoundedCornerShape(CraftMindRadius.PILL.dp)
    val circle: Shape = CircleShape

    /** The Material 3 shape scale, so framework components inherit the same corner language. */
    val materialShapes = Shapes(
        extraSmall = xs,
        small = sm,
        medium = md,
        large = lg,
        extraLarge = xl,
    )
}

private fun CraftMindTypeStyle.textStyle(align: TextAlign? = null): TextStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight(weight),
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
    letterSpacing = letterSpacingSp.sp,
    textAlign = align,
)

/** Text styles per design-system role, mirroring [CraftMindTypeScale]. */
object CraftMindType {
    val display: TextStyle = CraftMindTypeScale.DISPLAY.textStyle()
    val headline: TextStyle = CraftMindTypeScale.HEADLINE.textStyle()
    val titleLarge: TextStyle = CraftMindTypeScale.TITLE_LARGE.textStyle()
    val titleMedium: TextStyle = CraftMindTypeScale.TITLE_MEDIUM.textStyle()
    val titleSmall: TextStyle = CraftMindTypeScale.TITLE_SMALL.textStyle()
    val bodyLarge: TextStyle = CraftMindTypeScale.BODY_LARGE.textStyle()
    val bodyMedium: TextStyle = CraftMindTypeScale.BODY_MEDIUM.textStyle()
    val bodySmall: TextStyle = CraftMindTypeScale.BODY_SMALL.textStyle()
    val labelLarge: TextStyle = CraftMindTypeScale.LABEL_LARGE.textStyle()
    val labelMedium: TextStyle = CraftMindTypeScale.LABEL_MEDIUM.textStyle()
    val labelSmall: TextStyle = CraftMindTypeScale.LABEL_SMALL.textStyle()
    val eyebrow: TextStyle = CraftMindTypeScale.EYEBROW.textStyle()

    /** A style resolved by role, for components that take a [CraftMindTypeRole] parameter. */
    fun styleFor(role: CraftMindTypeRole): TextStyle = when (role) {
        CraftMindTypeRole.DISPLAY -> display
        CraftMindTypeRole.HEADLINE -> headline
        CraftMindTypeRole.TITLE_LARGE -> titleLarge
        CraftMindTypeRole.TITLE_MEDIUM -> titleMedium
        CraftMindTypeRole.TITLE_SMALL -> titleSmall
        CraftMindTypeRole.BODY_LARGE -> bodyLarge
        CraftMindTypeRole.BODY_MEDIUM -> bodyMedium
        CraftMindTypeRole.BODY_SMALL -> bodySmall
        CraftMindTypeRole.LABEL_LARGE -> labelLarge
        CraftMindTypeRole.LABEL_MEDIUM -> labelMedium
        CraftMindTypeRole.LABEL_SMALL -> labelSmall
        CraftMindTypeRole.EYEBROW -> eyebrow
    }

    /** The Material 3 typography scale, mapped from the same eleven roles. */
    val materialTypography = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = headline,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = titleLarge,
        titleLarge = titleLarge,
        titleMedium = titleMedium,
        titleSmall = titleSmall,
        bodyLarge = bodyLarge,
        bodyMedium = bodyMedium,
        bodySmall = bodySmall,
        labelLarge = labelLarge,
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}

/** Resolved motion for the current composition; every animated value must come from here. */
val LocalCraftMindMotion = staticCompositionLocalOf { CraftMindMotionTokens.scaleFor(reducedMotion = false) }

/** Active colour tokens for the current composition, so non-Material code can read them directly. */
val LocalCraftMindColors = staticCompositionLocalOf { LightCraftMindColors }

/** True when the active palette is the dark one. */
val LocalCraftMindIsDark = staticCompositionLocalOf { false }

private fun CraftMindColorTokens.lightScheme() = lightColorScheme(
    primary = craftMindColor(primary),
    onPrimary = craftMindColor(onPrimary),
    primaryContainer = craftMindColor(primaryContainer),
    onPrimaryContainer = craftMindColor(onPrimaryContainer),
    inversePrimary = craftMindColor(DarkCraftMindColors.primary),
    secondary = craftMindColor(secondary),
    onSecondary = craftMindColor(onSecondary),
    secondaryContainer = craftMindColor(secondaryContainer),
    onSecondaryContainer = craftMindColor(onSecondaryContainer),
    tertiary = craftMindColor(tertiary),
    onTertiary = craftMindColor(onTertiary),
    tertiaryContainer = craftMindColor(tertiaryContainer),
    onTertiaryContainer = craftMindColor(onTertiaryContainer),
    background = craftMindColor(background),
    onBackground = craftMindColor(onBackground),
    surface = craftMindColor(surface),
    onSurface = craftMindColor(onSurface),
    surfaceVariant = craftMindColor(surfaceVariant),
    onSurfaceVariant = craftMindColor(onSurfaceVariant),
    surfaceTint = craftMindColor(primary),
    inverseSurface = craftMindColor(onSurface),
    inverseOnSurface = craftMindColor(surface),
    outline = craftMindColor(outline),
    outlineVariant = craftMindColor(outlineVariant),
    error = craftMindColor(error),
    onError = craftMindColor(onError),
    errorContainer = craftMindColor(errorContainer),
    onErrorContainer = craftMindColor(onErrorContainer),
    scrim = craftMindColor(scrim),
)

private fun CraftMindColorTokens.darkScheme() = darkColorScheme(
    primary = craftMindColor(primary),
    onPrimary = craftMindColor(onPrimary),
    primaryContainer = craftMindColor(primaryContainer),
    onPrimaryContainer = craftMindColor(onPrimaryContainer),
    inversePrimary = craftMindColor(LightCraftMindColors.primary),
    secondary = craftMindColor(secondary),
    onSecondary = craftMindColor(onSecondary),
    secondaryContainer = craftMindColor(secondaryContainer),
    onSecondaryContainer = craftMindColor(onSecondaryContainer),
    tertiary = craftMindColor(tertiary),
    onTertiary = craftMindColor(onTertiary),
    tertiaryContainer = craftMindColor(tertiaryContainer),
    onTertiaryContainer = craftMindColor(onTertiaryContainer),
    background = craftMindColor(background),
    onBackground = craftMindColor(onBackground),
    surface = craftMindColor(surface),
    onSurface = craftMindColor(onSurface),
    surfaceVariant = craftMindColor(surfaceVariant),
    onSurfaceVariant = craftMindColor(onSurfaceVariant),
    surfaceTint = craftMindColor(primary),
    inverseSurface = craftMindColor(onSurface),
    inverseOnSurface = craftMindColor(surface),
    outline = craftMindColor(outline),
    outlineVariant = craftMindColor(outlineVariant),
    error = craftMindColor(error),
    onError = craftMindColor(onError),
    errorContainer = craftMindColor(errorContainer),
    onErrorContainer = craftMindColor(onErrorContainer),
    scrim = craftMindColor(scrim),
)

/**
 * Applies the CraftMind design system.
 *
 * @param themeMode persisted user preference (system, light, dark).
 * @param reducedMotion true when the device asks for animations to be removed; every transition collapses to zero
 *   duration while the underlying state change still happens.
 */
@Composable
fun CraftMindTheme(
    themeMode: ThemeMode,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val tokens = craftMindColorTokens(darkTheme)
    val motion = CraftMindMotionTokens.scaleFor(reducedMotion)

    CompositionLocalProvider(
        LocalCraftMindColors provides tokens,
        LocalCraftMindIsDark provides darkTheme,
        LocalCraftMindMotion provides motion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) tokens.darkScheme() else tokens.lightScheme(),
            typography = CraftMindType.materialTypography,
            shapes = CraftMindShapes.materialShapes,
            content = content,
        )
    }
}

/** The active colour tokens. */
val MaterialTheme.craftMindColors: CraftMindColorTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalCraftMindColors.current

/** The resolved motion values; components must not animate longer than these. */
val MaterialTheme.craftMindMotion: CraftMindMotion
    @Composable
    @ReadOnlyComposable
    get() = LocalCraftMindMotion.current

/** Convenience: the hairline border every resting card uses. */
@Composable
@ReadOnlyComposable
fun craftMindCardBorder(): BorderStroke =
    BorderStroke(CraftMindLayout.hairline, MaterialTheme.colorScheme.outlineVariant)

/** Convenience: the hairline border used to separate a section that needs to be slightly stronger. */
@Composable
@ReadOnlyComposable
fun craftMindDividerColor(): Color = MaterialTheme.colorScheme.outlineVariant

/** Converts a type role to its `TextUnit` size, for components that size non-text content from type. */
val CraftMindTypeRole.sizeSp: TextUnit
    get() = CraftMindTypeScale.styleFor(this).sizeSp.sp
