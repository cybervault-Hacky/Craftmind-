package com.craftmind.app.domain.buildplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanEditApplierTest {
    private val applier = BuildPlanEditApplier()

    @Test
    fun addingOneComponentLeavesUnmentionedSemanticComponentsAndOperationsUntouched() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val edit = AiBuildEditDocument(
            schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
            editSummary = "Added a small patio",
            targetComponentIds = listOf("patio"),
            preservedComponentIds = listOf("house", "roof"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    componentId = "patio",
                    type = BuildComponentType.EXTERIOR_FEATURE,
                    name = "Patio",
                    purpose = "A small stone patio beside the house.",
                    bounds = BlockBounds(BlockPosition(2, 1, 2), BuildDimensions(1, 1, 1)),
                    parentComponentId = "house",
                    constructionOrder = 2,
                ),
            ),
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    componentId = "patio",
                    operations = listOf(AiBlockOperationDocument(2, 1, 2, "minecraft:stone_bricks", emptyMap(), "patio")),
                ),
            ),
        )

        val candidate = applied(base, edit, setOf("house", "roof"))
        val validation = DefaultBuildPlanValidator().validate(candidate)
        assertTrue("Candidate must pass central validation: $validation", validation is BuildPlanValidationResult.Valid)
        assertEquals(base.components[0], candidate.components[0])
        assertEquals(base.components[1], candidate.components[1])
        assertEquals(base.operations, candidate.operations.take(base.operations.size))
        assertEquals("patio", candidate.operations.last().componentId)
        assertEquals(BuildPlanLimits.CURRENT_SCHEMA_VERSION, candidate.metadata.schemaVersion)

        val diff = BuildDiffCalculator().compare(
            before = base,
            after = candidate,
            summary = edit.editSummary,
            targetComponentIds = edit.targetComponentIds,
            preservedComponentIds = edit.preservedComponentIds,
            instruction = "Add a small patio",
        )
        assertEquals(listOf("patio"), diff.addedComponents.map(BuildPlanComponent::componentId))
        assertTrue(diff.warnings.isEmpty())
    }

    @Test
    fun replacingTargetOperationsPreservesAllOtherPlacementsExactly() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val roof = base.components.last()
        val edit = AiBuildEditDocument(
            schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
            editSummary = "Changed the raised roof to brick",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    componentId = roof.componentId,
                    type = roof.type,
                    name = roof.name,
                    purpose = "Raised brick roof above the main house.",
                    bounds = roof.bounds!!,
                    parentComponentId = roof.parentComponentId,
                    constructionOrder = roof.constructionOrder,
                ),
            ),
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    componentId = "roof",
                    operations = listOf(
                        AiBlockOperationDocument(0, 4, 0, "minecraft:bricks", emptyMap(), "roof"),
                        AiBlockOperationDocument(1, 4, 0, "minecraft:oak_planks", emptyMap(), "roof"),
                    ),
                ),
            ),
        )

        val candidate = applied(base, edit, setOf("roof"))

        assertEquals(base.operations.take(2), candidate.operations.take(2))
        assertEquals("minecraft:bricks", candidate.operations[2].blockId)
        assertEquals(1, candidate.components[1].constructionOrder)
        // Construction order is validated as a dense 0..n range, so the existing roof remains second.
    }

    @Test
    fun rejectsReplacementWhenCompleteComponentContextWasNotSent() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val edit = AiBuildEditDocument(
            schemaVersion = 1,
            editSummary = "Changed the roof",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(base.components.last().let { roof ->
                AiBuildComponentDocument(
                    roof.componentId, roof.type, roof.name, roof.purpose, roof.bounds!!,
                    roof.parentComponentId, roof.constructionOrder,
                )
            }),
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    "roof",
                    listOf(AiBlockOperationDocument(0, 4, 0, "minecraft:bricks", emptyMap(), "roof")),
                ),
            ),
        )

        val result = applier.apply(base, edit, "google", "gemini-test", 1_700_000_000_500, emptySet())
        assertEquals(
            BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.REPLACEMENT_CONTEXT_UNAVAILABLE),
            result,
        )
    }

    @Test
    fun upgradesLegacyV1DuringRealRefinementAndPreservesUntargetedPlacements() {
        val base = BuildPlanTestFixtures.legacyPlan()
        val edit = AiBuildEditDocument(
            schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
            editSummary = "Refined the roof and added semantic details",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    componentId = "house",
                    type = BuildComponentType.BUILDING,
                    name = "Main house",
                    purpose = "Foundation and lower walls of the home.",
                    bounds = BlockBounds(BlockPosition(0, 0, 0), BuildDimensions(2, 1, 1)),
                    parentComponentId = null,
                    constructionOrder = 0,
                ),
                AiBuildComponentDocument(
                    componentId = "roof",
                    type = BuildComponentType.ROOF,
                    name = "Raised roof",
                    purpose = "Raised brick roof above the main house.",
                    bounds = BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(2, 1, 1)),
                    parentComponentId = null,
                    constructionOrder = 1,
                ),
            ),
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    "roof",
                    listOf(
                        AiBlockOperationDocument(0, 4, 0, "minecraft:bricks", emptyMap(), "roof"),
                        AiBlockOperationDocument(1, 4, 0, "minecraft:oak_planks", emptyMap(), "roof"),
                    ),
                ),
            ),
            intent = AiBuildIntentDocument(
                structureType = "courtyard home",
                style = "modern",
                approximateScale = "small",
                floorCount = 1,
                rooms = listOf("courtyard"),
                specialFeatures = listOf("raised roof"),
                materials = listOf("stone", "brick", "oak"),
                environment = "garden",
                constraints = listOf("compact footprint"),
            ),
        )

        val candidate = applied(base, edit, setOf("house", "roof"))
        val validation = DefaultBuildPlanValidator().validate(candidate)
        assertTrue("Upgraded candidate must pass central validation: $validation", validation is BuildPlanValidationResult.Valid)
        assertEquals(BuildPlanLimits.CURRENT_SCHEMA_VERSION, candidate.metadata.schemaVersion)
        assertEquals("courtyard home", candidate.metadata.intent?.structureType)
        assertEquals(base.operations.take(2), candidate.operations.take(2))
        assertEquals(BuildComponentType.BUILDING, candidate.components.first().type)
        val diff = BuildPlanTestFixtures.diff(
            base,
            candidate,
            targets = listOf("roof"),
            preserved = listOf("house"),
        )
        assertFalse(diff.warnings.contains(BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS))
        assertFalse(diff.warnings.contains(BuildDiffWarning.DECLARED_PRESERVED_COMPONENT_CHANGED))
    }

    @Test
    fun rejectsCandidateThatWouldExceedTotalPlacementLimit() {
        val dimensions = BuildDimensions(96, 64, 96)
        val operations = (0 until BuildPlanLimits.MAX_OPERATIONS).map { index ->
            val x = index % 96
            val z = (index / 96) % 96
            val y = index / (96 * 96)
            BuildPlanTestFixtures.operation(index, "minecraft:stone", x, y, z, "main")
        }
        val base = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(dimensions = dimensions),
            components = listOf(
                BuildPlanComponent(
                    "main", "Main house", "A large house", BlockBounds(BlockPosition(0, 0, 0), dimensions),
                    BuildComponentType.BUILDING, null, 0,
                ),
            ),
            operations = operations,
        )
        val edit = AiBuildEditDocument(
            schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
            editSummary = "Added a garden marker",
            targetComponentIds = listOf("garden"),
            preservedComponentIds = listOf("main"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    "garden", BuildComponentType.LANDSCAPE, "Garden marker", "A small garden marker.",
                    BlockBounds(BlockPosition(0, 0, 43), BuildDimensions(1, 1, 1)), "main", 1,
                ),
            ),
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    "garden",
                    listOf(AiBlockOperationDocument(0, 0, 43, "minecraft:stone_bricks", emptyMap(), "garden")),
                ),
            ),
        )

        val result = applier.apply(base, edit, "google", "gemini-test", 1_700_000_000_500, setOf("main"))
        assertEquals(BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.RESULTING_PLAN_TOO_LARGE), result)
    }

    @Test
    fun refusesPartialLegacySemanticBackfillInsteadOfSilentlyRepairingIt() {
        val base = BuildPlanTestFixtures.legacyPlan()
        val partial = AiBuildEditDocument(
            schemaVersion = 1,
            editSummary = "Changed the roof",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            removedComponentIds = emptyList(),
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    "roof", BuildComponentType.ROOF, "Raised roof", "Raised roof.",
                    BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(2, 1, 1)), null, 1,
                ),
            ),
            replacementOperations = emptyList(),
            intent = BuildPlanTestFixtures.semanticPlan().metadata.intent?.let { intent ->
                AiBuildIntentDocument(
                    intent.structureType, intent.style, intent.approximateScale, intent.floorCount,
                    intent.rooms, intent.specialFeatures, intent.materials, intent.environment, intent.constraints,
                )
            },
        )

        assertTrue(
            applier.apply(base, partial, "google", "gemini-test", 1_700_000_000_500, setOf("house", "roof"))
                is BuildPlanEditApplyResult.Invalid,
        )
    }

    private fun applied(base: BuildPlan, edit: AiBuildEditDocument, fullContext: Set<String>): BuildPlan =
        when (val result = applier.apply(base, edit, "google", "gemini-test", 1_700_000_000_500, fullContext)) {
            is BuildPlanEditApplyResult.Applied -> result.candidate
            is BuildPlanEditApplyResult.Invalid -> error("Expected a patch to apply, got ${result.error}")
        }
}
