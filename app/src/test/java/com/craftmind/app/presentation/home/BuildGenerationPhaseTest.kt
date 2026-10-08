package com.craftmind.app.presentation.home

import com.craftmind.app.domain.ai.AiGenerationStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generation phase contract (Phase 15 §6).
 *
 * The progress track may only ever show what the pipeline really reported: a phase that cannot occur for the current
 * request is not drawn, a step is complete only after a later phase is active, and a ready plan is the only state
 * that completes every step.
 */
class BuildGenerationPhaseTest {

    @Test
    fun everyRealStageMapsToATrackPhase() {
        AiGenerationStage.entries.forEach { stage ->
            val phase = stage.phase()
            assertTrue("$stage mapped to $phase, which is not part of the track", phase in generationTrackPhases)
        }
    }

    @Test
    fun theTrackOrderMatchesTheRealPipelineOrder() {
        assertEquals(
            listOf(
                BuildGenerationPhase.PREPARING,
                BuildGenerationPhase.ANALYZING,
                BuildGenerationPhase.DESIGNING,
                BuildGenerationPhase.VALIDATING,
            ),
            generationTrackPhases,
        )
        // Generating the plan can only be followed by validating it, and reference work only by generation.
        assertTrue(
            AiGenerationStage.GENERATING_BUILD_PLAN.phase().trackIndex(generationTrackPhases) <
                AiGenerationStage.VALIDATING_BUILD_PLAN.phase().trackIndex(generationTrackPhases),
        )
        assertTrue(
            AiGenerationStage.VALIDATING_REQUEST.phase().trackIndex(generationTrackPhases) <
                AiGenerationStage.EXTRACTING_VIDEO_FRAMES.phase().trackIndex(generationTrackPhases),
        )
    }

    @Test
    fun analyzingIsOnlyShownWhenAVisualReferenceExists() {
        val textOnly = generationTrackFor(hasVisualReference = false)
        assertFalse(
            "a text-only request can never reach Analyzing, so it must not be drawn",
            BuildGenerationPhase.ANALYZING in textOnly,
        )
        assertEquals(
            listOf(BuildGenerationPhase.PREPARING, BuildGenerationPhase.DESIGNING, BuildGenerationPhase.VALIDATING),
            textOnly,
        )
        assertEquals(generationTrackPhases, generationTrackFor(hasVisualReference = true))
    }

    @Test
    fun stepStatesFollowTheRealPhaseAndNeverRunAhead() {
        val track = generationTrackPhases
        val current = BuildGenerationPhase.DESIGNING
        assertEquals(
            GenerationTrackStepState.COMPLETE,
            generationTrackStepState(BuildGenerationPhase.PREPARING, current, track),
        )
        assertEquals(
            GenerationTrackStepState.COMPLETE,
            generationTrackStepState(BuildGenerationPhase.ANALYZING, current, track),
        )
        assertEquals(
            GenerationTrackStepState.ACTIVE,
            generationTrackStepState(BuildGenerationPhase.DESIGNING, current, track),
        )
        assertEquals(
            GenerationTrackStepState.PENDING,
            generationTrackStepState(BuildGenerationPhase.VALIDATING, current, track),
        )
        // Exactly one step is ever active.
        assertEquals(1, track.count { generationTrackStepState(it, current, track) == GenerationTrackStepState.ACTIVE })
    }

    @Test
    fun onlyAReadyPlanCompletesEveryStep() {
        val track = generationTrackPhases
        track.forEach { step ->
            assertEquals(
                GenerationTrackStepState.COMPLETE,
                generationTrackStepState(step, BuildGenerationPhase.READY_FOR_REVIEW, track),
            )
        }
        // A phase that is not part of the applicable track is never shown as progress.
        assertEquals(
            GenerationTrackStepState.NOT_APPLICABLE,
            generationTrackStepState(
                BuildGenerationPhase.ANALYZING,
                BuildGenerationPhase.DESIGNING,
                generationTrackFor(hasVisualReference = false),
            ),
        )
    }

    @Test
    fun nonProgressPhasesAreNeverPartOfTheTrack() {
        listOf(
            BuildGenerationPhase.IDLE,
            BuildGenerationPhase.READY_FOR_REVIEW,
            BuildGenerationPhase.BUILDING,
            BuildGenerationPhase.FAILED,
            BuildGenerationPhase.CANCELLED,
        ).forEach { phase ->
            assertFalse("$phase must not be a track step", phase in generationTrackPhases)
            assertEquals(-1, phase.trackIndex(generationTrackPhases))
        }
    }

    @Test
    fun everyPhaseHasAShortHumanLabel() {
        BuildGenerationPhase.entries.forEach { phase ->
            assertTrue("$phase needs a label", phase.label.isNotBlank())
            assertTrue("$phase label must fit a track step", phase.label.length <= 18)
            assertFalse("$phase label must not be the enum name", phase.label == phase.name)
        }
        assertEquals("Ready for review", BuildGenerationPhase.READY_FOR_REVIEW.label)
        assertEquals("Building", BuildGenerationPhase.BUILDING.label)
    }
}
