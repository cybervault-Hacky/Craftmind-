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
    fun rejectsMalformedSemanticIntentComponentBoundsAndOperationOutsideComponent() {
        val valid = BuildPlanTestFixtures.semanticPlan()
        val missingIntent = valid.copy(metadata = valid.metadata.copy(intent = null))
        val intentResult = validator.validate(missingIntent) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.INVALID_INTENT in intentResult.issues)

        val badParent = valid.copy(components = valid.components.map { component ->
            if (component.componentId == "roof") component.copy(parentComponentId = "missing") else component
        })
        val parentResult = validator.validate(badParent) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.INVALID_COMPONENT_PARENT in parentResult.issues)

        val outsideBounds = valid.copy(
            operations = valid.operations.mapIndexed { index, operation ->
                if (index == 2) operation.copy(position = BlockPosition(7, 0, 7)) else operation
            },
        )
        val boundsResult = validator.validate(outsideBounds) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.OPERATION_OUTSIDE_COMPONENT_BOUNDS in boundsResult.issues)
    }

    @Test
    fun rejectsV2PlansWithoutOperationsForEachComponentOrInConstructionOrder() {
        val valid = BuildPlanTestFixtures.semanticPlan()
        val missingComponentOperations = valid.copy(operations = valid.operations.take(2))
        val missingResult = validator.validate(missingComponentOperations) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.MISSING_COMPONENT_OPERATIONS in missingResult.issues)

        val wrongOrder = valid.copy(operations = listOf(valid.operations[2], valid.operations[0], valid.operations[1], valid.operations[3])
            .mapIndexed { index, operation -> operation.copy(sequence = index) })
        val orderResult = validator.validate(wrongOrder) as BuildPlanValidationResult.Invalid
        assertTrue(BuildPlanValidationIssue.INVALID_OPERATION_ORDER in orderResult.issues)
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
            intent = BuildIntent(
                structureType = "pavilion",
                style = "stone",
                approximateScale = "small",
                floorCount = 1,
                rooms = emptyList(),
                specialFeatures = emptyList(),
                materials = listOf("stone"),
                environment = "garden",
                constraints = emptyList(),
            ),
        ),
        originStrategy = BuildOriginStrategy.CENTERED_GROUND,
        components = listOf(
            BuildPlanComponent(
                componentId = "main",
                name = "Pavilion",
                purpose = "Covered gathering area",
                bounds = BlockBounds(BlockPosition(0, 0, 0), BuildDimensions(8, 5, 8)),
                type = BuildComponentType.BUILDING,
                parentComponentId = null,
                constructionOrder = 0,
            ),
        ),
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
