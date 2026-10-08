package com.craftmind.app.designsystem

/**
 * CraftMind motion tokens (Phase 15 design system).
 *
 * Motion in CraftMind is a *state transition cue*, never decoration and never a substitute for progress. Every
 * duration here is short enough that it cannot feel like waiting, and every animated value corresponds to real
 * application state changing. There are no looping, floating, glowing, or particle effects anywhere in the app, and
 * no animation fabricates progress: a generation stage advances only when [com.craftmind.app.domain.ai.AiGenerationStage]
 * advances.
 *
 * Reduced motion is honoured by collapsing every duration to [INSTANT] — see [CraftMindMotionTokens.scaleFor].
 */
object CraftMindMotionTokens {
    /** No animation at all: used when the user prefers reduced motion, or for state that must appear instantly. */
    const val INSTANT = 0

    /** Selection feedback, chip and toggle state, focus rings. */
    const val FAST = 120

    /** Standard transition: screen cross-fade, expand/collapse, card appearance. */
    const val BASE = 200

    /** Larger surface transitions: dialogs, the review sheet, navigation. */
    const val SLOW = 280

    /** Longest duration used anywhere; anything longer would feel like blocking. */
    const val MAXIMUM = 320

    /** Determinate progress indicator period (a real request in flight, driven by real state). */
    const val INDETERMINATE_PERIOD = 1_600

    /** Cubic ease-out control points: quick departure, gentle arrival. */
    val EASE_STANDARD = listOf(0.2f, 0.0f, 0.0f, 1.0f)

    /** Cubic ease-in control points for exits only. */
    val EASE_EXIT = listOf(0.3f, 0.0f, 0.8f, 0.4f)

    /** Every duration in ascending order; tests assert no token exceeds [MAXIMUM]. */
    val DURATIONS = listOf(INSTANT, FAST, BASE, SLOW, MAXIMUM)
}

/**
 * Resolved motion values for one composition.
 *
 * [reducedMotion] mirrors `Settings → Accessibility → Remove animations` / `prefers-reduced-motion`. When it is set,
 * every duration becomes [CraftMindMotionTokens.INSTANT] while the state change itself still happens, so nothing is
 * lost except the transition.
 */
data class CraftMindMotion(
    val reducedMotion: Boolean,
    val fast: Int,
    val base: Int,
    val slow: Int,
) {
    /** True when a component should animate at all. */
    val animates: Boolean
        get() = !reducedMotion
}

/** Builds the motion set for a reduced-motion preference. */
fun CraftMindMotionTokens.scaleFor(reducedMotion: Boolean): CraftMindMotion = if (reducedMotion) {
    CraftMindMotion(reducedMotion = true, fast = INSTANT, base = INSTANT, slow = INSTANT)
} else {
    CraftMindMotion(reducedMotion = false, fast = FAST, base = BASE, slow = SLOW)
}
