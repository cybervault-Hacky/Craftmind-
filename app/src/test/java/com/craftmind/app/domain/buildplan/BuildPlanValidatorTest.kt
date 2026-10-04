package com.craftmind.app.domain.buildplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanValidatorTest {
    private val validator = DefaultBuildPlanValidator()

    @Test
    fun validatesBlockRegistryAndRealStateValues() {
        assertTrue(MinecraftBlockCatalog.supports("minecraft:stone"))
        assertTrue(MinecraftBlockCatalog.supports("minecraft:oak_stairs"))
        assertTrue(
            MinecraftBlockCatalog.validState(
                "minecraft:oak_stairs",
                mapOf("facing" to "east", "half" to "bottom", "shape" to "outer_left", "waterlogged" to "false"),
            ),
        )
        assertTrue(!MinecraftBlockCatalog.validState("minecraft:oak_stairs", mapOf("facing" to "up")))
        assertTrue(!MinecraftBlockCatalog.supports("minecraft:command_block"))
        assertTrue(!MinecraftBlockCatalog.supports("minecraft:bamboo_leaves"))
    }

    @Test
    fun returnsReadyOnlyForAPlanWithValidCoordinatesAndBlockState() {
        val result = validator.validate(validPlan())
        assertTrue(result is BuildPlanValidationResult.Valid)
        assertEquals(BuildStatus.READY, (result as BuildPlanValidationResult.Valid).plan.plan.status)
    }

    @Test
    fun rejectsDuplicateCoordinatesUnknownComponentsAndBadBlockState() {
        val plan = validPlan().copy(
            operations = listOf(
                validPlan().operations.first(),
                validPlan().operations.first().copy(
                    sequence = 1,
                    blockState = mapOf("facing" to "north"),
                    componentId = "missing",
                ),
            ),
        )
        val result = validator.validate(plan) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.DUPLICATE_COORDINATE in result.issues)
        assertTrue(BuildPlanValidationIssue.INVALID_BLOCK_STATE in result.issues)
        assertTrue(BuildPlanValidationIssue.UNKNOWN_COMPONENT in result.issues)
    }

    @Test
    fun rejectsOutOfRangeCoordinatesAndExcessiveDimensions() {
        val invalidCoordinate = validPlan().copy(
            operations = listOf(validPlan().operations.first().copy(position = BlockPosition(8, 0, 0))),
        )
        val coordinateResult = validator.validate(invalidCoordinate) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.INVALID_COORDINATE in coordinateResult.issues)

        val oversized = validPlan().copy(
            metadata = validPlan().metadata.copy(dimensions = BuildDimensions(BuildPlanLimits.MAX_BUILD_WIDTH + 1, 5, 5)),
        )
        val sizeResult = validator.validate(oversized) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.BUILD_TOO_WIDE in sizeResult.issues)
    }

    @Test
    fun rejectsUnsupportedOperationsAndNonSequentialOrdering() {
        val plan = validPlan().copy(
            operations = listOf(validPlan().operations.first().copy(sequence = 1, kind = BuildPlanOperationKind.REMOVE_BLOCK)),
        )
        val result = validator.validate(plan) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.INVALID_OPERATION_ORDER in result.issues)
        assertTrue(BuildPlanValidationIssue.UNSUPPORTED_OPERATION in result.issues)
    }

    private fun validPlan() = BuildPlan(
        planId = "garden-plan",
        metadata = BuildPlanMetadata(
            schemaVersion = BuildPlanLimits.CURRENT_SCHEMA_VERSION,
            sourceRequestId = "request-1",
            providerId = "google_gemini",
            modelId = "gemini-test",
            title = "Small garden pavilion",
            summary = "A stone floor for a quiet garden pavilion.",
            generatedAtEpochMillis = 1_700_000_000_000,
            dimensions = BuildDimensions(8, 5, 8),
        ),
        originStrategy = BuildOriginStrategy.CENTERED_GROUND,
        components = listOf(BuildPlanComponent("main", "Pavilion", "Covered gathering area")),
        operations = listOf(
            BuildPlanOperation(
                sequence = 0,
                kind = BuildPlanOperationKind.PLACE_BLOCK,
                blockId = "minecraft:stone",
                position = BlockPosition(0, 0, 0),
                componentId = "main",
            ),
        ),
        status = BuildStatus.DRAFT,
    )
}
