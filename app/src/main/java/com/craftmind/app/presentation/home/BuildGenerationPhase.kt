package com.craftmind.app.presentation.home

import com.craftmind.app.domain.ai.AiGenerationStage

/**
 * Generation phases (Phase 15 §6).
 *
 * A phase is a *grouping of real stages*, never an invention: every value below is produced only from state the app
 * actually holds — [AiGenerationStage] from the generation pipeline, the ready/failed/cancelled generation states, or
 * the execution flow while a build runs in Minecraft. There is no timer, no estimated percentage, and no phase that
 * can be shown while nothing is happening.
 *
 * Phases exist so the UI can show a short, stable label ("Designing") next to the honest detail sentence for the exact
 * stage ("Generating the BuildPlan v2 with the same selected provider/model…").
 */
enum class BuildGenerationPhase(
    /** Short label shown in the progress track. */
    val label: String,
) {
    /** Nothing is running. */
    IDLE("Idle"),

    /** The request, image, or reference URL is being checked and prepared locally. */
    PREPARING("Preparing"),

    /** A visual reference is being fetched within its limits or analyzed by the selected vision model. */
    ANALYZING("Analyzing"),

    /** The provider is generating the BuildPlan. */
    DESIGNING("Designing"),

    /** The returned plan is being parsed and validated locally. */
    VALIDATING("Validating"),

    /** A validated plan is ready to review; nothing has been placed in Minecraft. */
    READY_FOR_REVIEW("Ready for review"),

    /** A preflight-checked plan is being built in Minecraft. */
    BUILDING("Building"),

    /** The attempt failed; the reason is reported from a real error code. */
    FAILED("Failed"),

    /** The user cancelled, or the request was cancelled before any plan existed. */
    CANCELLED("Cancelled"),
}

/** The ordered phases a generation attempt can pass through, used to render the progress track. */
val generationTrackPhases: List<BuildGenerationPhase> = listOf(
    BuildGenerationPhase.PREPARING,
    BuildGenerationPhase.ANALYZING,
    BuildGenerationPhase.DESIGNING,
    BuildGenerationPhase.VALIDATING,
)

/**
 * The track phases that apply to one request.
 *
 * "Analyzing" appears only when a visual reference is attached, because without one that phase can never occur —
 * showing it would be a stage that no real state backs.
 */
fun generationTrackFor(hasVisualReference: Boolean): List<BuildGenerationPhase> =
    if (hasVisualReference) generationTrackPhases else generationTrackPhases.filter { it != BuildGenerationPhase.ANALYZING }

/** Maps a real pipeline stage onto its phase. */
fun AiGenerationStage.phase(): BuildGenerationPhase = when (this) {
    AiGenerationStage.VALIDATING_REQUEST -> BuildGenerationPhase.PREPARING
    AiGenerationStage.PREPARING_IMAGE_LOCALLY -> BuildGenerationPhase.PREPARING
    AiGenerationStage.VALIDATING_REFERENCE_URL -> BuildGenerationPhase.PREPARING
    AiGenerationStage.RESOLVING_PUBLIC_VIDEO_REFERENCE -> BuildGenerationPhase.ANALYZING
    AiGenerationStage.EXTRACTING_VIDEO_FRAMES -> BuildGenerationPhase.ANALYZING
    AiGenerationStage.ANALYZING_IMAGE_WITH_SELECTED_MODEL -> BuildGenerationPhase.ANALYZING
    AiGenerationStage.ANALYZING_VIDEO_FRAMES_WITH_SELECTED_MODEL -> BuildGenerationPhase.ANALYZING
    AiGenerationStage.GENERATING_BUILD_PLAN -> BuildGenerationPhase.DESIGNING
    AiGenerationStage.VALIDATING_BUILD_PLAN -> BuildGenerationPhase.VALIDATING
}

/** Where a phase sits in the applicable track: -1 when it is not part of the track. */
fun BuildGenerationPhase.trackIndex(track: List<BuildGenerationPhase>): Int = track.indexOf(this)

/** Render state of one step in the generation track. */
enum class GenerationTrackStepState {
    /** The app is in this phase right now. */
    ACTIVE,

    /** A real stage in this phase has already been passed. */
    COMPLETE,

    /** Not reached yet. */
    PENDING,

    /** This phase cannot occur for the current request, so it is not shown at all. */
    NOT_APPLICABLE,
}

/**
 * Derives the state of one track step from the real current phase.
 *
 * Nothing here can invent progress: a step is [GenerationTrackStepState.COMPLETE] only when the current phase is
 * later in the same track, and [GenerationTrackStepState.ACTIVE] only when it *is* the current phase. A ready plan
 * completes every applicable step because validation genuinely finished.
 */
fun generationTrackStepState(
    step: BuildGenerationPhase,
    current: BuildGenerationPhase,
    track: List<BuildGenerationPhase>,
): GenerationTrackStepState {
    val stepIndex = track.indexOf(step)
    if (stepIndex < 0) return GenerationTrackStepState.NOT_APPLICABLE
    if (current == BuildGenerationPhase.READY_FOR_REVIEW) return GenerationTrackStepState.COMPLETE
    val currentIndex = track.indexOf(current)
    return when {
        currentIndex == stepIndex -> GenerationTrackStepState.ACTIVE
        currentIndex > stepIndex -> GenerationTrackStepState.COMPLETE
        else -> GenerationTrackStepState.PENDING
    }
}
