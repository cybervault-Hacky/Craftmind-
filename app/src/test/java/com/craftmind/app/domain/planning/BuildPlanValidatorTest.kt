package com.craftmind.app.domain.planning

import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.ai.ProviderId
import com.craftmind.app.domain.model.BlockCoordinate
import com.craftmind.app.domain.model.BlockOperation
import com.craftmind.app.domain.model.BlockState
import com.craftmind.app.domain.model.BuildDimensions
import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildPlanStep
import com.craftmind.app.domain.model.BuildPlanValidationIssue
import com.craftmind.app.domain.model.MaterialRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanValidatorTest {
    private val validator = BuildPlanValidator()

    @Test
    fun validDraftProducesNormalizedPlanWithGeneratorMetadata() {
        val result = validator.validate(validDraft(), "plan-1", 1_700_000_000_000, ProviderId.OPENAI.value, "gpt-4o-mini")

        assertTrue(result is BuildPlanValidationResult.Valid)
        val plan = (result as BuildPlanValidationResult.Valid).plan
        assertEquals("Small stone hut", plan.title)
        assertEquals(1, plan.operations.size)
        assertEquals(ProviderId.OPENAI.value, plan.generator.providerId)
        assertEquals("gpt-4o-mini", plan.generator.modelId)
    }

    @Test
    fun rejectsUnsupportedSchemaAndOutOfBoundsCoordinates() {
        val wrongVersion = validDraft().copy(schemaVersion = 2)
        assertEquals(
            BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION,
            (validate(wrongVersion) as BuildPlanValidationResult.Invalid).issue,
        )
        val outOfBounds = validDraft().copy(
            operations = listOf(validOperation().copy(position = BlockCoordinate(1, 0, 0))),
        )
        assertEquals(
            BuildPlanValidationIssue.OPERATION_OUT_OF_BOUNDS,
            (validate(outOfBounds) as BuildPlanValidationResult.Invalid).issue,
        )
    }

    @Test
    fun rejectsDuplicateCoordinatesUnsupportedBlocksAndMaterialMismatches() {
        val duplicate = validDraft().copy(
            dimensions = BuildDimensions(2, 1, 1),
            materials = listOf(MaterialRequirement("minecraft:stone", 2)),
            operations = listOf(
                validOperation(),
                validOperation().copy(sequence = 1),
            ),
            estimatedOperationCount = 2,
        )
        assertEquals(
            BuildPlanValidationIssue.DUPLICATE_COORDINATE,
            (validate(duplicate) as BuildPlanValidationResult.Invalid).issue,
        )

        val unsupported = validDraft().copy(
            materials = listOf(MaterialRequirement("minecraft:command_block", 1)),
            operations = listOf(validOperation().copy(block = BlockState("minecraft:command_block"))),
        )
        assertEquals(
            BuildPlanValidationIssue.UNSUPPORTED_MATERIAL,
            (validate(unsupported) as BuildPlanValidationResult.Invalid).issue,
        )

        val mismatch = validDraft().copy(materials = listOf(MaterialRequirement("minecraft:oak_planks", 1)))
        assertEquals(
            BuildPlanValidationIssue.MATERIAL_TOTAL_MISMATCH,
            (validate(mismatch) as BuildPlanValidationResult.Invalid).issue,
        )
    }

    @Test
    fun rejectsInvalidOperationOrderAndForwardDependencies() {
        val wrongSequence = validDraft().copy(operations = listOf(validOperation().copy(sequence = 5)))
        assertEquals(
            BuildPlanValidationIssue.INVALID_OPERATION_ORDER,
            (validate(wrongSequence) as BuildPlanValidationResult.Invalid).issue,
        )

        val forwardDependency = validDraft().copy(
            operations = listOf(validOperation().copy(dependsOnSequences = listOf(0))),
        )
        assertEquals(
            BuildPlanValidationIssue.INVALID_DEPENDENCY,
            (validate(forwardDependency) as BuildPlanValidationResult.Invalid).issue,
        )
    }

    @Test
    fun rejectsOversizedDimensionsAndTooManyOperations() {
        val largeDimensions = validDraft().copy(dimensions = BuildDimensions(128, 128, 96))
        assertEquals(
            BuildPlanValidationIssue.PLAN_VOLUME_TOO_LARGE,
            (validate(largeDimensions) as BuildPlanValidationResult.Invalid).issue,
        )

        val tooManyOperations = validDraft().copy(
            dimensions = BuildDimensions(1, 1, 1),
            operations = List(BuildLimits.MAX_OPERATION_COUNT + 1) { index ->
                validOperation().copy(sequence = index)
            },
            estimatedOperationCount = BuildLimits.MAX_OPERATION_COUNT + 1,
        )
        assertEquals(
            BuildPlanValidationIssue.TOO_MANY_OPERATIONS,
            (validate(tooManyOperations) as BuildPlanValidationResult.Invalid).issue,
        )
    }

    private fun validate(draft: BuildPlanDraft) =
        validator.validate(draft, "plan-test", 1L, ProviderId.OPENAI.value, "gpt-4o-mini")

    private fun validDraft() = BuildPlanDraft(
        schemaVersion = BuildLimits.PLAN_SCHEMA_VERSION,
        title = " Small stone hut ",
        style = "rustic",
        dimensions = BuildDimensions(1, 1, 1),
        origin = BlockCoordinate(0, 64, 0),
        materials = listOf(MaterialRequirement("minecraft:stone", 1)),
        steps = listOf(BuildPlanStep("foundation", "Foundation", "Place the base")),
        operations = listOf(validOperation()),
        estimatedOperationCount = 1,
    )

    private fun validOperation() = BlockOperation(
        sequence = 0,
        position = BlockCoordinate(0, 0, 0),
        block = BlockState("minecraft:stone"),
        stepId = "foundation",
    )
}
