package com.craftmind.app.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-system contract tests (Phase 15).
 *
 * The design system is a permanent foundation: later phases add screens on top of it instead of restyling. These
 * tests make the promises in §3 of the Phase 15 brief enforceable — colour pairings clear their WCAG floor, the
 * spacing/radius/elevation/motion scales stay monotonic and bounded, every interactive height satisfies the 48dp
 * touch-target minimum, and no palette token is accidentally transparent.
 */
class CraftMindDesignTokensTest {

    // ------------------------------------------------------------------------------- §3 colour + contrast rules

    @Test
    fun everyDeclaredTextPairingClearsItsWcagFloorInBothPalettes() {
        CraftMindColorPalettes.forEach { palette ->
            palette.contrastPairs().forEach { pair ->
                assertTrue(
                    "${pair.name} measured ${"%.2f".format(pair.measuredRatio)} but needs " +
                        "${pair.requirement.minimumRatio} for ${pair.requirement}",
                    pair.meetsRequirement,
                )
            }
        }
    }

    @Test
    fun thePrimaryReadingPairingAlsoSatisfiesEnhancedContrast() {
        CraftMindColorPalettes.forEach { palette ->
            assertTrue(
                CraftMindContrast.meetsEnhancedTextMinimum(palette.onSurface, palette.surface),
            )
            assertTrue(
                CraftMindContrast.meetsEnhancedTextMinimum(palette.onBackground, palette.background),
            )
        }
    }

    @Test
    fun everySemanticToneIsReadableOnItsOwnContainer() {
        CraftMindColorPalettes.forEach { palette ->
            CraftMindTone.entries.forEach { tone ->
                val colors = palette.toneColors(tone)
                assertTrue(
                    "$tone onContainer/container measured ${"%.2f".format(CraftMindContrast.ratio(colors.onContainer, colors.container))}",
                    CraftMindContrast.meetsTextMinimum(colors.onContainer, colors.container),
                )
                assertTrue(
                    "$tone accent is not a usable non-text indicator",
                    CraftMindContrast.meetsLargeTextAndUiMinimum(colors.accent, colors.container),
                )
            }
        }
    }

    @Test
    fun noPaletteTokenIsTransparentAndNoPaletteRoleIsMissing() {
        val fields = CraftMindColorTokens::class.java.declaredFields.filter { it.type == java.lang.Long.TYPE }
        assertEquals("the token set is the contract; add new roles deliberately", 41, fields.size)
        CraftMindColorPalettes.forEach { palette ->
            fields.forEach { field ->
                field.isAccessible = true
                val argb = field.getLong(palette)
                assertEquals("${field.name} must be an opaque token", 0xFF, ((argb shr 24) and 0xFF).toInt())
            }
        }
        assertEquals(LightCraftMindColors, craftMindColorTokens(darkTheme = false))
        assertEquals(DarkCraftMindColors, craftMindColorTokens(darkTheme = true))
        assertFalse(
            "light and dark must not be the same palette",
            LightCraftMindColors == DarkCraftMindColors,
        )
    }

    @Test
    fun contrastMathematicsMatchesTheWcagReferenceValues() {
        assertEquals(21.0, CraftMindContrast.ratio(0xFFFFFFFF, 0xFF000000), 0.001)
        assertEquals(1.0, CraftMindContrast.ratio(0xFF2C5342, 0xFF2C5342), 0.001)
        // WCAG 2.1 worked example: #767676 on white is 4.54:1, the classic AA grey.
        assertEquals(4.54, CraftMindContrast.ratio(0xFF767676, 0xFFFFFFFF), 0.01)
        assertTrue(CraftMindContrast.meetsTextMinimum(0xFF767676, 0xFFFFFFFF))
        assertFalse(CraftMindContrast.meetsEnhancedTextMinimum(0xFF767676, 0xFFFFFFFF))
        // Translucent foregrounds are composited before measurement, not measured as if opaque.
        val translucent = CraftMindContrast.ratio(0x80FFFFFF, 0xFF000000)
        assertTrue("composited white-on-black must stay light", translucent > 4.0)
        assertTrue(translucent < CraftMindContrast.ratio(0xFFFFFFFF, 0xFF000000))
    }

    // -------------------------------------------------------------------------------- §3 spacing, radius, depth

    @Test
    fun spacingScaleIsMonotonicAndAnchoredOnAFourPointGrid() {
        val scale = CraftMindSpacing.SCALE
        assertEquals(scale.sorted(), scale)
        scale.zipWithNext().forEach { (smaller, larger) -> assertTrue(smaller < larger) }
        scale.filter { it >= CraftMindSpacing.SM }.forEach { value ->
            assertEquals("$value.dp is off the 4dp rhythm", 0f, value % 4f)
        }
        assertTrue(CraftMindSpacing.SCREEN_MARGIN >= CraftMindSpacing.LG)
        assertTrue(CraftMindSpacing.SCREEN_MARGIN_WIDE > CraftMindSpacing.SCREEN_MARGIN)
        assertTrue(CraftMindSpacing.CARD_INSET >= CraftMindSpacing.LG)
    }

    @Test
    fun radiusScaleIsMonotonicAndNeverSquareCutOrFullyPilled() {
        val scale = CraftMindRadius.SCALE
        assertEquals(scale.sorted(), scale)
        scale.zipWithNext().forEach { (smaller, larger) -> assertTrue(smaller < larger) }
        assertTrue(CraftMindRadius.PILL > CraftMindRadius.XL)
        assertTrue("cards keep a soft rectangle, never a hard corner", CraftMindRadius.MD > CraftMindRadius.NONE)
        assertTrue(CraftMindRadius.LG > CraftMindRadius.MD)
    }

    @Test
    fun elevationStaysLowSoSurfacesSeparateByLightnessNotShadow() {
        val scale = CraftMindElevation.SCALE
        assertEquals(scale.sorted(), scale)
        assertTrue(CraftMindElevation.CARD <= 2f)
        assertTrue(CraftMindElevation.RAISED > CraftMindElevation.CARD)
        assertTrue(CraftMindElevation.OVERLAY > CraftMindElevation.CONTROLS)
        assertEquals(CraftMindBorder.HAIRLINE, 1f)
        assertTrue(CraftMindBorder.EMPHASIS > CraftMindBorder.HAIRLINE)
    }

    @Test
    fun iconSizesAreAFixedSetAndNavigationUsesOneOfThem() {
        val scale = CraftMindIconSize.SCALE
        assertEquals(scale.sorted(), scale)
        assertTrue(CraftMindSizing.NAV_ICON in scale)
        assertTrue(CraftMindSizing.PROGRESS_MD >= CraftMindIconSize.SM)
        scale.forEach { assertTrue(it >= CraftMindIconSize.XS) }
    }

    // ------------------------------------------------------------------------------------- §16 touch targets

    @Test
    fun everyInteractiveHeightSatisfiesTheMinimumTouchTarget() {
        assertTrue(CraftMindSizing.MIN_TOUCH_TARGET >= 48f)
        CraftMindSizing.SCALE.forEach { height ->
            assertTrue("$height.dp is below the touch-target minimum", height >= CraftMindSizing.MIN_TOUCH_TARGET)
        }
        assertTrue(CraftMindSizing.CONTROL_HEIGHT_MD >= CraftMindSizing.MIN_TOUCH_TARGET)
        assertTrue(CraftMindSizing.CONTROL_HEIGHT_LG > CraftMindSizing.CONTROL_HEIGHT_MD)
        assertTrue(
            "a compact control still offers a full touch target",
            CraftMindSizing.COMPACT_VISUAL_HEIGHT + CraftMindSizing.COMPACT_TOUCH_PADDING * 2 >=
                CraftMindSizing.MIN_TOUCH_TARGET,
        )
    }

    // ----------------------------------------------------------------------------------------- §18 breakpoints

    @Test
    fun breakpointsAndContentWidthsOrderSensibly() {
        assertTrue(CraftMindBreakpoints.COMPACT < CraftMindBreakpoints.MEDIUM)
        assertTrue(CraftMindBreakpoints.MEDIUM < CraftMindBreakpoints.EXPANDED)
        assertTrue(CraftMindBreakpoints.CONTENT_MAX_WIDTH <= CraftMindBreakpoints.OVERLAY_MAX_WIDTH)
        assertTrue(CraftMindBreakpoints.OVERLAY_MAX_WIDTH <= CraftMindBreakpoints.SCREEN_MAX_WIDTH)
    }

    // -------------------------------------------------------------------------------- §3 typography + motion

    @Test
    fun typeScaleIsReadableOrderedAndWeightedDeliberately() {
        val allowedWeights = setOf(400, 500, 600, 700)
        CraftMindTypeScale.ALL.forEach { (role, style) ->
            assertTrue("$role is below the readable floor", style.sizeSp >= CraftMindTypeScale.MINIMUM_READABLE_SP)
            assertTrue("$role line height must exceed its size", style.lineHeightSp > style.sizeSp)
            assertTrue("$role uses an undeclared weight ${style.weight}", style.weight in allowedWeights)
            assertTrue("$role line height is excessive", style.lineHeightRatio <= 1.7f)
        }
        assertEquals(CraftMindTypeRole.DISPLAY, CraftMindTypeScale.ALL.first().first)
        assertEquals(CraftMindTypeRole.EYEBROW, CraftMindTypeScale.ALL.last().first)
        CraftMindTypeRole.entries.forEach { role ->
            assertEquals(
                "eyebrow is the only upper-case role",
                role == CraftMindTypeRole.EYEBROW,
                CraftMindTypeScale.styleFor(role).uppercase,
            )
        }
        // Body roles stay regular weight; emphasis comes from colour and size, not from bolding paragraphs.
        assertEquals(400, CraftMindTypeScale.BODY_LARGE.weight)
        assertEquals(400, CraftMindTypeScale.BODY_MEDIUM.weight)
        assertEquals(400, CraftMindTypeScale.BODY_SMALL.weight)
        assertTrue(CraftMindTypeScale.HEADLINE.sizeSp > CraftMindTypeScale.TITLE_LARGE.sizeSp)
        assertTrue(CraftMindTypeScale.TITLE_LARGE.sizeSp > CraftMindTypeScale.BODY_LARGE.sizeSp)
    }

    @Test
    fun motionIsShortPurposefulAndCollapsesUnderReducedMotion() {
        val durations = CraftMindMotionTokens.DURATIONS
        assertEquals(durations.sorted(), durations)
        durations.forEach { duration ->
            assertTrue("$duration ms exceeds the maximum allowed transition", duration <= CraftMindMotionTokens.MAXIMUM)
        }
        assertEquals(0, CraftMindMotionTokens.INSTANT)
        assertTrue(CraftMindMotionTokens.MAXIMUM <= 400)
        assertEquals(4, CraftMindMotionTokens.EASE_STANDARD.size)
        assertEquals(4, CraftMindMotionTokens.EASE_EXIT.size)

        val full = CraftMindMotionTokens.scaleFor(reducedMotion = false)
        assertTrue(full.animates)
        assertEquals(CraftMindMotionTokens.FAST, full.fast)
        assertEquals(CraftMindMotionTokens.BASE, full.base)
        assertEquals(CraftMindMotionTokens.SLOW, full.slow)

        val reduced = CraftMindMotionTokens.scaleFor(reducedMotion = true)
        assertFalse(reduced.animates)
        assertEquals(0, reduced.fast)
        assertEquals(0, reduced.base)
        assertEquals(0, reduced.slow)
    }
}
