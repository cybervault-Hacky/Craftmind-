package com.craftmind.app.designsystem

/**
 * CraftMind typography tokens (Phase 15 design system).
 *
 * One sans-serif family throughout — the platform default — with a deliberately small scale of eleven roles. Size
 * alone does not create hierarchy here: hierarchy comes from weight, colour token, and spacing, which keeps the UI
 * readable at every text scale and avoids the "many competing font sizes" look.
 *
 * Values are in scale-independent pixels (sp) and are declared in plain Kotlin so the JVM test suite can assert the
 * scale is monotonic, that line height always exceeds font size, and that no role is smaller than the readable floor.
 */
data class CraftMindTypeStyle(
    val sizeSp: Float,
    val lineHeightSp: Float,
    val letterSpacingSp: Float,
    /** CSS-style numeric weight; Compose maps it with [androidx.compose.ui.text.font.FontWeight]. */
    val weight: Int,
    /** Whether the role is rendered upper-case with tracking (eyebrow/section labels only). */
    val uppercase: Boolean = false,
) {
    /** Line height must always exceed the font size so wrapped body text stays readable. */
    val lineHeightRatio: Float
        get() = lineHeightSp / sizeSp
}

/** Every typographic role a screen may use. Nothing outside this enum is allowed. */
enum class CraftMindTypeRole {
    DISPLAY,
    HEADLINE,
    TITLE_LARGE,
    TITLE_MEDIUM,
    TITLE_SMALL,
    BODY_LARGE,
    BODY_MEDIUM,
    BODY_SMALL,
    LABEL_LARGE,
    LABEL_MEDIUM,
    LABEL_SMALL,
    EYEBROW,
}

object CraftMindTypeScale {
    /** Hero statement on Home ("Describe it. Show it. Build it."). */
    val DISPLAY = CraftMindTypeStyle(34f, 40f, -0.8f, 700)

    /** Screen titles. */
    val HEADLINE = CraftMindTypeStyle(26f, 33f, -0.4f, 600)

    /** Card and section titles, dialog titles. */
    val TITLE_LARGE = CraftMindTypeStyle(20f, 27f, 0f, 600)
    val TITLE_MEDIUM = CraftMindTypeStyle(17f, 24f, 0f, 600)
    val TITLE_SMALL = CraftMindTypeStyle(15f, 21f, 0.1f, 600)

    /** Reading text. BODY_LARGE is the default body role; BODY_SMALL is the smallest allowed text role. */
    val BODY_LARGE = CraftMindTypeStyle(16f, 25f, 0f, 400)
    val BODY_MEDIUM = CraftMindTypeStyle(14f, 21f, 0f, 400)
    val BODY_SMALL = CraftMindTypeStyle(13f, 19f, 0.1f, 400)

    /** Buttons, chips, and emphasized metadata. */
    val LABEL_LARGE = CraftMindTypeStyle(14f, 20f, 0.1f, 600)
    val LABEL_MEDIUM = CraftMindTypeStyle(13f, 18f, 0.2f, 600)
    val LABEL_SMALL = CraftMindTypeStyle(12f, 16f, 0.3f, 500)

    /**
     * Uppercase eyebrow that labels a section ("QUICK START", "VALIDATION"). Never used for body copy, and never
     * smaller than [MINIMUM_READABLE_SP] — tracked small caps at 11sp fail the readability floor this scale promises.
     */
    val EYEBROW = CraftMindTypeStyle(12f, 16f, 1.2f, 600, uppercase = true)

    /** Smallest text size the design system permits anywhere in the app. */
    const val MINIMUM_READABLE_SP = 12f

    /** Resolves a role to its style; the single lookup every screen uses. */
    fun styleFor(role: CraftMindTypeRole): CraftMindTypeStyle = when (role) {
        CraftMindTypeRole.DISPLAY -> DISPLAY
        CraftMindTypeRole.HEADLINE -> HEADLINE
        CraftMindTypeRole.TITLE_LARGE -> TITLE_LARGE
        CraftMindTypeRole.TITLE_MEDIUM -> TITLE_MEDIUM
        CraftMindTypeRole.TITLE_SMALL -> TITLE_SMALL
        CraftMindTypeRole.BODY_LARGE -> BODY_LARGE
        CraftMindTypeRole.BODY_MEDIUM -> BODY_MEDIUM
        CraftMindTypeRole.BODY_SMALL -> BODY_SMALL
        CraftMindTypeRole.LABEL_LARGE -> LABEL_LARGE
        CraftMindTypeRole.LABEL_MEDIUM -> LABEL_MEDIUM
        CraftMindTypeRole.LABEL_SMALL -> LABEL_SMALL
        CraftMindTypeRole.EYEBROW -> EYEBROW
    }

    /** Every role/style pair, ordered largest to smallest, for tests and for documentation generation. */
    val ALL: List<Pair<CraftMindTypeRole, CraftMindTypeStyle>> = CraftMindTypeRole.entries
        .map { it to styleFor(it) }
        .sortedByDescending { it.second.sizeSp }
}
