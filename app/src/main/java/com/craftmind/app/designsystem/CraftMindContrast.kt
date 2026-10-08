package com.craftmind.app.designsystem

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 contrast utilities (Phase 15 design system).
 *
 * The design system's accessibility promise is checkable, not aspirational: these pure functions compute the same
 * contrast ratio an accessibility audit would, and `CraftMindDesignTokensTest` asserts that every text pairing used
 * in both palettes clears the required floor. Any future colour change that drops a pairing below AA fails the build.
 */
object CraftMindContrast {
    /** WCAG AA floor for body text (all text below 18sp / 14sp bold). */
    const val AA_TEXT = 4.5

    /** WCAG AA floor for large text (≥ 18sp regular or ≥ 14sp bold) and for meaningful UI components. */
    const val AA_LARGE_TEXT_AND_UI = 3.0

    /** WCAG AAA floor, used for the primary reading pair only. */
    const val AAA_TEXT = 7.0

    /**
     * Floor for purely decorative separations (surface tints, hairline dividers). They must be perceptible but never
     * carry meaning alone; screens pair them with text, colour tokens, or icons that do.
     */
    const val MINIMUM_PERCEPTIBLE = 1.05

    /**
     * Contrast ratio of two ARGB colours, from 1.0 (identical) to 21.0 (black on white).
     * Alpha is composited over the background first so translucent tokens are measured as they are actually drawn.
     */
    fun ratio(foregroundArgb: Long, backgroundArgb: Long): Double {
        val composite = compositeOver(foregroundArgb, backgroundArgb)
        val foregroundLuminance = relativeLuminance(composite)
        val backgroundLuminance = relativeLuminance(backgroundArgb)
        val lighter = max(foregroundLuminance, backgroundLuminance)
        val darker = min(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** True when the pair may be used for any text, including small body copy. */
    fun meetsTextMinimum(foregroundArgb: Long, backgroundArgb: Long): Boolean =
        ratio(foregroundArgb, backgroundArgb) >= AA_TEXT

    /** True when the pair may be used for large text, icons, borders, and other meaningful UI components. */
    fun meetsLargeTextAndUiMinimum(foregroundArgb: Long, backgroundArgb: Long): Boolean =
        ratio(foregroundArgb, backgroundArgb) >= AA_LARGE_TEXT_AND_UI

    /** True when the pair also satisfies the stricter AAA floor. */
    fun meetsEnhancedTextMinimum(foregroundArgb: Long, backgroundArgb: Long): Boolean =
        ratio(foregroundArgb, backgroundArgb) >= AAA_TEXT

    /** Relative luminance of an ARGB colour per WCAG 2.1. */
    fun relativeLuminance(argb: Long): Double {
        val red = linearize(channel(argb, 16))
        val green = linearize(channel(argb, 8))
        val blue = linearize(channel(argb, 0))
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /** Alpha of an ARGB colour in 0..1. */
    fun alpha(argb: Long): Double = channel(argb, 24) / 255.0

    private fun channel(argb: Long, shift: Int): Int = ((argb shr shift) and 0xFF).toInt()

    private fun linearize(value: Int): Double {
        val normalized = value / 255.0
        return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }

    /** Composites [foregroundArgb] over [backgroundArgb] using source-over alpha blending. */
    private fun compositeOver(foregroundArgb: Long, backgroundArgb: Long): Long {
        val foregroundAlpha = alpha(foregroundArgb)
        if (foregroundAlpha >= 1.0) return foregroundArgb and 0xFFFFFF
        val backgroundAlpha = alpha(backgroundArgb)
        val resultAlpha = foregroundAlpha + backgroundAlpha * (1.0 - foregroundAlpha)
        if (resultAlpha <= 0.0) return 0x000000
        fun blend(shift: Int): Int {
            val foregroundChannel = channel(foregroundArgb, shift)
            val backgroundChannel = channel(backgroundArgb, shift)
            val blended = (foregroundChannel * foregroundAlpha +
                backgroundChannel * backgroundAlpha * (1.0 - foregroundAlpha)) / resultAlpha
            return blended.toInt().coerceIn(0, 255)
        }
        return (blend(16).toLong() shl 16) or (blend(8).toLong() shl 8) or blend(0).toLong()
    }
}

/**
 * The WCAG floor a pairing must clear.
 *
 * [TEXT] covers every pairing that carries readable copy, including small body text, so it uses the strict 4.5 floor.
 * [UI_COMPONENT] covers meaningful non-text contrast: control borders, icons, and the accent of a selected item,
 * where WCAG 1.4.11 allows 3.0. [DECORATIVE] covers surface tints and hairline dividers that separate content but
 * never encode state on their own — they still have to be perceptible, and no screen may use them as the only
 * indication of anything.
 */
enum class CraftMindContrastRequirement(val minimumRatio: Double) {
    TEXT(CraftMindContrast.AA_TEXT),
    UI_COMPONENT(CraftMindContrast.AA_LARGE_TEXT_AND_UI),
    DECORATIVE(CraftMindContrast.MINIMUM_PERCEPTIBLE),
}

/**
 * A named foreground/background pairing the design system guarantees.
 *
 * Pairings are declared here so the contrast floor is enforced by tests instead of by convention: a screen may render
 * text with any listed pairing, and adding a new colour combination means adding it to [contrastPairs].
 */
data class CraftMindContrastPair(
    val name: String,
    val foreground: Long,
    val background: Long,
    val requirement: CraftMindContrastRequirement = CraftMindContrastRequirement.TEXT,
) {
    val measuredRatio: Double
        get() = CraftMindContrast.ratio(foreground, background)

    val meetsRequirement: Boolean
        get() = measuredRatio >= requirement.minimumRatio
}

/**
 * Every text pairing the app is allowed to render, for both palettes.
 *
 * Screens compose text from these pairings only. A new colour combination requires adding it here, which is what
 * makes the contrast floor enforceable instead of documented.
 */
fun CraftMindColorTokens.contrastPairs(): List<CraftMindContrastPair> = listOf(
    CraftMindContrastPair("onBackground/background", onBackground, background),
    CraftMindContrastPair("onSurface/surface", onSurface, surface),
    CraftMindContrastPair("onSurfaceVariant/surface", onSurfaceVariant, surface),
    CraftMindContrastPair("onSurfaceMuted/surface", onSurfaceMuted, surface),
    CraftMindContrastPair("onSurfaceVariant/surfaceSubtle", onSurfaceVariant, surfaceSubtle),
    CraftMindContrastPair("onSurfaceSubtle/surfaceSubtle", onSurfaceSubtle, surfaceSubtle),
    CraftMindContrastPair("onSurfaceVariant/background", onSurfaceVariant, background),
    CraftMindContrastPair("onSurfaceMuted/background", onSurfaceMuted, background),
    CraftMindContrastPair("primary/surface", primary, surface),
    CraftMindContrastPair("primary/background", primary, background),
    CraftMindContrastPair("onPrimary/primary", onPrimary, primary),
    CraftMindContrastPair("onPrimaryContainer/primaryContainer", onPrimaryContainer, primaryContainer),
    CraftMindContrastPair("primary/primaryContainer", primary, primaryContainer),
    CraftMindContrastPair("onSecondary/secondary", onSecondary, secondary),
    CraftMindContrastPair("onSecondaryContainer/secondaryContainer", onSecondaryContainer, secondaryContainer),
    CraftMindContrastPair("onTertiary/tertiary", onTertiary, tertiary),
    CraftMindContrastPair("onTertiaryContainer/tertiaryContainer", onTertiaryContainer, tertiaryContainer),
    CraftMindContrastPair("error/surface", error, surface),
    CraftMindContrastPair("error/background", error, background),
    CraftMindContrastPair("onError/error", onError, error),
    CraftMindContrastPair("onErrorContainer/errorContainer", onErrorContainer, errorContainer),
    CraftMindContrastPair("onSuccess/success", onSuccess, success),
    CraftMindContrastPair("onSuccessContainer/successContainer", onSuccessContainer, successContainer),
    CraftMindContrastPair("onWarning/warning", onWarning, warning),
    CraftMindContrastPair("onWarningContainer/warningContainer", onWarningContainer, warningContainer),
    CraftMindContrastPair("onInfo/info", onInfo, info),
    CraftMindContrastPair("onInfoContainer/infoContainer", onInfoContainer, infoContainer),
    // Non-text pairings that carry meaning: control borders and accent colours need the 3.0 UI-component floor.
    CraftMindContrastPair(
        "outline/surface",
        outline,
        surface,
        CraftMindContrastRequirement.UI_COMPONENT,
    ),
    CraftMindContrastPair("success/surface", success, surface, CraftMindContrastRequirement.UI_COMPONENT),
    CraftMindContrastPair("warning/surface", warning, surface, CraftMindContrastRequirement.UI_COMPONENT),
    CraftMindContrastPair("info/surface", info, surface, CraftMindContrastRequirement.UI_COMPONENT),
    CraftMindContrastPair("secondary/surface", secondary, surface, CraftMindContrastRequirement.UI_COMPONENT),
    CraftMindContrastPair("tertiary/surface", tertiary, surface, CraftMindContrastRequirement.UI_COMPONENT),
    // Decorative separations only: never the sole indicator of state, and never used for text.
    CraftMindContrastPair("outlineVariant/surface", outlineVariant, surface, CraftMindContrastRequirement.DECORATIVE),
    CraftMindContrastPair("divider/surface", divider, surface, CraftMindContrastRequirement.DECORATIVE),
    CraftMindContrastPair("surface/background", surface, background, CraftMindContrastRequirement.DECORATIVE),
    CraftMindContrastPair("surfaceVariant/surface", surfaceVariant, surface, CraftMindContrastRequirement.DECORATIVE),
    CraftMindContrastPair("surfaceSubtle/surface", surfaceSubtle, surface, CraftMindContrastRequirement.DECORATIVE),
)
