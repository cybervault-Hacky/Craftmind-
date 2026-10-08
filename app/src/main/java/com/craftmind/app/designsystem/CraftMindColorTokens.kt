package com.craftmind.app.designsystem

/**
 * CraftMind colour tokens (Phase 15 design system).
 *
 * These are the single source of truth for every colour in the app. They are declared as ARGB `Long` values in
 * plain Kotlin — deliberately free of `androidx.compose.*` imports — so that [CraftMindTheme] can derive Compose
 * `Color` instances from them *and* the JVM test suite can verify the contrast, tone, and completeness rules that
 * the design system promises. A screen must never hard-code a colour literal; it asks for a token by role.
 *
 * The palette keeps CraftMind's existing identity: a deep forest-green brand colour and a warm sand accent, on a
 * cool off-white surface. It reads as an AI product first (calm, high contrast, generous whitespace) with only a
 * subtle structural nod to Minecraft geometry through the spacing and radius scales — never through textures,
 * pixel art, or neon glow.
 */
data class CraftMindColorTokens(
    /** App canvas behind every screen. */
    val background: Long,
    val onBackground: Long,
    /** Raised cards and sheets. */
    val surface: Long,
    val onSurface: Long,
    /** Slightly recessed surface used for inset panels, code/detail wells, and preview rows. */
    val surfaceSubtle: Long,
    val onSurfaceSubtle: Long,
    /** Chips, thumbnails, and other tinted fills. */
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    /** Secondary text. Must still meet the AA body-text contrast ratio on [surface]. */
    val onSurfaceMuted: Long,
    /** Brand colour: the primary action, selected navigation, and links. */
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    /** Warm sand accent used for secondary emphasis and metadata. */
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    /** Cool teal accent used for technical/runtime detail. */
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    /** Hairlines around cards and fields. */
    val outline: Long,
    val outlineVariant: Long,
    val divider: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val success: Long,
    val onSuccess: Long,
    val successContainer: Long,
    val onSuccessContainer: Long,
    val warning: Long,
    val onWarning: Long,
    val warningContainer: Long,
    val onWarningContainer: Long,
    val info: Long,
    val onInfo: Long,
    val infoContainer: Long,
    val onInfoContainer: Long,
    /** Scrim behind dialogs and the review sheet. */
    val scrim: Long,
)

/** Light theme. Every foreground/background pair used for text is asserted AA-compliant by the design-system tests. */
val LightCraftMindColors = CraftMindColorTokens(
    background = 0xFFF4F6F4,
    onBackground = 0xFF141A16,
    surface = 0xFFFFFFFF,
    onSurface = 0xFF141A16,
    surfaceSubtle = 0xFFEFF3EF,
    onSurfaceSubtle = 0xFF141A16,
    surfaceVariant = 0xFFE7EDE8,
    onSurfaceVariant = 0xFF435046,
    onSurfaceMuted = 0xFF5B685E,
    primary = 0xFF2C5342,
    onPrimary = 0xFFFFFFFF,
    primaryContainer = 0xFFD8E8DC,
    onPrimaryContainer = 0xFF0E2A1B,
    secondary = 0xFF6E5940,
    onSecondary = 0xFFFFFFFF,
    secondaryContainer = 0xFFF2E7D3,
    onSecondaryContainer = 0xFF2E2110,
    tertiary = 0xFF3F6469,
    onTertiary = 0xFFFFFFFF,
    tertiaryContainer = 0xFFDCEAEC,
    onTertiaryContainer = 0xFF0F2F33,
    outline = 0xFF77847A,
    outlineVariant = 0xFFD6DED8,
    divider = 0xFFE4EAE5,
    error = 0xFFA32219,
    onError = 0xFFFFFFFF,
    errorContainer = 0xFFF9DEDC,
    onErrorContainer = 0xFF3F0D0A,
    success = 0xFF2A6641,
    onSuccess = 0xFFFFFFFF,
    successContainer = 0xFFDCEBDF,
    onSuccessContainer = 0xFF0F2E1B,
    warning = 0xFF805300,
    onWarning = 0xFFFFFFFF,
    warningContainer = 0xFFFAEACB,
    onWarningContainer = 0xFF382400,
    info = 0xFF2A5675,
    onInfo = 0xFFFFFFFF,
    infoContainer = 0xFFDEEAF2,
    onInfoContainer = 0xFF0E2739,
    scrim = 0xFF0B110D,
)

/** Dark theme. Surfaces stay green-tinted rather than pure black so elevation reads through lightness, not glow. */
val DarkCraftMindColors = CraftMindColorTokens(
    background = 0xFF0F1511,
    onBackground = 0xFFE2E9E3,
    surface = 0xFF18211B,
    onSurface = 0xFFE2E9E3,
    surfaceSubtle = 0xFF131B16,
    onSurfaceSubtle = 0xFFE2E9E3,
    surfaceVariant = 0xFF26332B,
    onSurfaceVariant = 0xFFC2CFC4,
    onSurfaceMuted = 0xFFA6B3A8,
    primary = 0xFFA6D0B2,
    onPrimary = 0xFF143222,
    primaryContainer = 0xFF2B4D39,
    onPrimaryContainer = 0xFFD3ECDA,
    secondary = 0xFFE2C9A2,
    onSecondary = 0xFF3D2E18,
    secondaryContainer = 0xFF57462E,
    onSecondaryContainer = 0xFFF5E4CA,
    tertiary = 0xFFA6CFD3,
    onTertiary = 0xFF153539,
    tertiaryContainer = 0xFF2A464A,
    onTertiaryContainer = 0xFFD5E9EB,
    outline = 0xFF8B998E,
    outlineVariant = 0xFF3B493F,
    divider = 0xFF2A372E,
    error = 0xFFFFB4AB,
    onError = 0xFF680004,
    errorContainer = 0xFF8C1A11,
    onErrorContainer = 0xFFFFDAD5,
    success = 0xFF93D5A6,
    onSuccess = 0xFF0E2C19,
    successContainer = 0xFF1F3D2A,
    onSuccessContainer = 0xFFD8F0DE,
    warning = 0xFFF3CA7C,
    onWarning = 0xFF3D2700,
    warningContainer = 0xFF4A3410,
    onWarningContainer = 0xFFF8E7C6,
    info = 0xFF9EC9E7,
    onInfo = 0xFF0E2B3D,
    infoContainer = 0xFF1D3446,
    onInfoContainer = 0xFFD8E8F4,
    scrim = 0xFF000000,
)

/** Resolves the active palette for a resolved dark/light decision. */
fun craftMindColorTokens(darkTheme: Boolean): CraftMindColorTokens =
    if (darkTheme) DarkCraftMindColors else LightCraftMindColors

/**
 * Semantic tone of a badge, notice, or status line.
 *
 * Tones express *meaning*, never decoration: [POSITIVE] is reserved for real success/compatible/certified state,
 * [NEGATIVE] for real errors and blocked state, [CAUTION] for experimental or partially supported state,
 * [INFORMATIVE] for neutral explanation, and [BRAND] for the primary brand accent.
 */
enum class CraftMindTone {
    NEUTRAL,
    BRAND,
    POSITIVE,
    CAUTION,
    NEGATIVE,
    INFORMATIVE,
}

/** The three colours a toned surface needs: its fill, its readable content colour, and its emphasis accent. */
data class CraftMindToneColors(
    val container: Long,
    val onContainer: Long,
    val accent: Long,
)

/** Maps a semantic tone onto the active palette. Every returned pair meets the design-system contrast floor. */
fun CraftMindColorTokens.toneColors(tone: CraftMindTone): CraftMindToneColors = when (tone) {
    CraftMindTone.NEUTRAL -> CraftMindToneColors(surfaceVariant, onSurfaceVariant, outline)
    CraftMindTone.BRAND -> CraftMindToneColors(primaryContainer, onPrimaryContainer, primary)
    CraftMindTone.POSITIVE -> CraftMindToneColors(successContainer, onSuccessContainer, success)
    CraftMindTone.CAUTION -> CraftMindToneColors(warningContainer, onWarningContainer, warning)
    CraftMindTone.NEGATIVE -> CraftMindToneColors(errorContainer, onErrorContainer, error)
    CraftMindTone.INFORMATIVE -> CraftMindToneColors(infoContainer, onInfoContainer, info)
}

/** Every palette, so tests can assert the same rules hold for light and dark without listing them twice. */
val CraftMindColorPalettes: List<CraftMindColorTokens> = listOf(LightCraftMindColors, DarkCraftMindColors)
