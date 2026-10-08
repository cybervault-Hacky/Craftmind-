package com.craftmind.app.designsystem

/**
 * CraftMind layout tokens (Phase 15 design system): spacing, corner radius, elevation, icon sizing, touch targets,
 * and content widths.
 *
 * Plain Kotlin with no `androidx.compose.*` imports: values are declared in density-independent pixels (dp) and
 * Compose code converts them with `.dp` at the single point where a modifier is built. Screens must use these
 * scales instead of ad-hoc numbers so that rhythm, density, and corner language stay identical everywhere and can
 * be extended by later phases without inventing new values.
 */
object CraftMindSpacing {
    /** Hairline gap inside a dense row (badge to label, icon to text). */
    const val XXS = 2f

    /** Tight gap between related text lines. */
    const val XS = 4f

    /** Gap between a label and its control. */
    const val SM = 8f

    /** Default gap between related elements inside a card. */
    const val MD = 12f

    /** Gap between sections inside a card. */
    const val LG = 16f

    /** Gap between cards on a screen. */
    const val XL = 24f

    /** Gap between major screen regions. */
    const val XXL = 32f

    /** Generous separation used only on wide layouts. */
    const val XXXL = 48f

    /** Standard inset padding of a card body. */
    const val CARD_INSET = 20f

    /** Standard horizontal screen margin on phones. */
    const val SCREEN_MARGIN = 20f

    /** Standard horizontal screen margin on tablets and larger phones in landscape. */
    const val SCREEN_MARGIN_WIDE = 32f

    /** Standard vertical screen margin. */
    const val SCREEN_MARGIN_VERTICAL = 24f

    /** Every spacing value in ascending order; tests assert the scale is monotonic and gap-free in intent. */
    val SCALE = listOf(XXS, XS, SM, MD, LG, XL, XXL, XXXL)
}

/**
 * Corner radius scale.
 *
 * The language is "soft rectangle": small radii on dense chips and fields, medium on cards, large on sheets and the
 * review surface. Nothing is a pill except status badges, and nothing is square-cut, which keeps the interface calm
 * without looking like a blocky game UI.
 */
object CraftMindRadius {
    const val NONE = 0f

    /** Badges, small chips, thumbnails. */
    const val XS = 6f

    /** Text fields, buttons, list rows. */
    const val SM = 10f

    /** Standard cards. */
    const val MD = 14f

    /** Prominent cards, dialogs, grouped sections. */
    const val LG = 18f

    /** Bottom sheets and the full-screen review surface. */
    const val XL = 24f

    /** Pill, used only for status badges. */
    const val PILL = 999f

    val SCALE = listOf(NONE, XS, SM, MD, LG, XL)
}

/**
 * Elevation scale, expressed in dp of shadow.
 *
 * CraftMind separates surfaces mainly through lightness and a hairline border rather than heavy shadows, so
 * elevation stays low: resting cards are barely lifted, and only overlays (dialogs, the review sheet) float.
 */
object CraftMindElevation {
    const val FLAT = 0f
    const val CARD = 1f
    const val RAISED = 2f
    const val CONTROLS = 3f
    const val OVERLAY = 8f

    val SCALE = listOf(FLAT, CARD, RAISED, CONTROLS, OVERLAY)
}

/** Hairline widths. CraftMind uses a 1dp border or divider and nothing heavier. */
object CraftMindBorder {
    const val HAIRLINE = 1f
    const val EMPHASIS = 2f
}

/** Icon sizing scale. Icons are always one of these sizes so optical weight stays consistent. */
object CraftMindIconSize {
    const val XS = 14f
    const val SM = 16f
    const val MD = 20f
    const val LG = 24f
    const val XL = 32f
    const val FEATURE = 48f

    val SCALE = listOf(XS, SM, MD, LG, XL, FEATURE)
}

/**
 * Touch target and component sizing rules (accessibility).
 *
 * [MIN_TOUCH_TARGET] is the Android accessibility minimum of 48dp; every interactive component in the design system
 * enforces it, including icon-only buttons and chips.
 */
object CraftMindSizing {
    /** Android accessibility minimum; no interactive component may offer less than this. */
    const val MIN_TOUCH_TARGET = 48f

    /** Standard control height: buttons, text fields, selectable rows, navigation items. */
    const val CONTROL_HEIGHT_MD = 48f

    /** Primary call to action, used once per screen at most. */
    const val CONTROL_HEIGHT_LG = 56f

    /**
     * Visual height of a compact control (chip, badge with an action). The *touch* target is still
     * [MIN_TOUCH_TARGET] because the component adds [COMPACT_TOUCH_PADDING] above and below its visible bounds.
     */
    const val COMPACT_VISUAL_HEIGHT = 40f
    const val COMPACT_TOUCH_PADDING = 4f

    const val NAV_ICON = 24f
    const val BRAND_MARK = 44f
    const val THUMBNAIL = 56f
    const val PROGRESS_SM = 16f
    const val PROGRESS_MD = 22f

    /** Heights a control may have; all of them satisfy [MIN_TOUCH_TARGET]. */
    val SCALE = listOf(
        COMPACT_VISUAL_HEIGHT + COMPACT_TOUCH_PADDING * 2,
        CONTROL_HEIGHT_MD,
        CONTROL_HEIGHT_LG,
    )
}

/**
 * Responsive layout breakpoints (dp of available width).
 *
 * CraftMind composes responsively instead of shipping separate layouts: below [COMPACT] everything is a single
 * column; from [MEDIUM] content is capped in width and centred; from [EXPANDED] the app shows a navigation rail and
 * may use two columns.
 */
object CraftMindBreakpoints {
    const val COMPACT = 600f
    const val MEDIUM = 840f
    const val EXPANDED = 1200f

    /** Maximum readable measure for text columns; longer lines hurt comprehension. */
    const val CONTENT_MAX_WIDTH = 720f

    /** Maximum width of a screen's content area on large screens. */
    const val SCREEN_MAX_WIDTH = 1040f

    /** Maximum width of a dialog or the review sheet. */
    const val OVERLAY_MAX_WIDTH = 900f
}
