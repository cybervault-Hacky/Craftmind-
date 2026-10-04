package com.craftmind.app.domain.buildplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildDiffTest {
    private val calculator = BuildDiffCalculator()

    @Test
    fun comparesComponentAndOperationAddedRemovedAndModifiedSections() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val changed = base.copy(
            metadata = base.metadata.copy(dimensions = BuildDimensions(9, 6, 8)),
            components = base.components.map { component ->
                if (component.componentId == "roof") component.copy(name = "Brick roof", purpose = "A brick roof.") else component
            } + BuildPlanComponent(
                componentId = "garden",
                type = BuildComponentType.LANDSCAPE,
                name = "Garden",
                purpose = "A small garden bed.",
                bounds = BlockBounds(BlockPosition(4, 0, 4), BuildDimensions(1, 1, 1)),
                parentComponentId = null,
                constructionOrder = 2,
            ),
            operations = base.operations.mapIndexed { index, operation ->
                if (index == 2) operation.copy(blockId = "minecraft:bricks") else operation
            } + BuildPlanTestFixtures.operation(4, "minecraft:grass_block", 4, 0, 4, "garden"),
        )
        val result = calculator.compare(base, changed, "Added garden, updated roof")

        assertEquals(listOf("garden"), result.addedComponents.map(BuildPlanComponent::componentId))
        assertEquals(listOf("roof"), result.modifiedComponents.map { it.before.componentId })
        assertEquals(1, result.addedOperations.size)
        assertEquals(1, result.changedOperations.size)
        assertEquals(base.metadata.dimensions, result.dimensionsBefore)
        assertEquals(changed.metadata.dimensions, result.dimensionsAfter)
        assertTrue(result.hasChanges)
    }

    @Test
    fun warnsWhenDeclaredPreservedOrUnrelatedComponentsChange() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val changed = base.copy(
            operations = base.operations.mapIndexed { index, operation ->
                operation.copy(blockId = if (index < 2) "minecraft:stone_bricks" else "minecraft:bricks")
            },
        )
        val diff = calculator.compare(
            before = base,
            after = changed,
            summary = "Localized roof change",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            instruction = "Only change the roof",
        )

        assertTrue(BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS in diff.warnings)
        assertTrue(BuildDiffWarning.DECLARED_PRESERVED_COMPONENT_CHANGED in diff.warnings)
        assertTrue(BuildDiffWarning.LARGE_CHANGE_FOR_LOCALIZED_REQUEST in diff.warnings)

        val overlyBroadDeclaration = calculator.compare(
            before = base,
            after = changed,
            summary = "Reworked the entire plan",
            targetComponentIds = listOf("house", "roof"),
            instruction = "Change the roof to brick",
        )
        assertTrue(BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS in overlyBroadDeclaration.warnings)
    }

    @Test
    fun legacySemanticBackfillDoesNotLookLikeUnrelatedPhysicalDesignChanges() {
        val legacy = BuildPlanTestFixtures.legacyPlan()
        val currentComponents = listOf(
            AiBuildComponentDocument(
                "house", BuildComponentType.BUILDING, "Main house", "Foundation and lower walls of the home.",
                BlockBounds(BlockPosition(0, 0, 0), BuildDimensions(2, 1, 1)), null, 0,
            ).toDomain(),
            AiBuildComponentDocument(
                "roof", BuildComponentType.ROOF, "Raised roof", "Raised roof.",
                BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(2, 1, 1)), null, 1,
            ).toDomain(),
        )
        val current = legacy.copy(
            metadata = legacy.metadata.copy(
                schemaVersion = BuildPlanLimits.CURRENT_SCHEMA_VERSION,
                intent = BuildIntent("courtyard home"),
            ),
            components = currentComponents,
        )
        val diff = calculator.compare(
            legacy,
            current,
            "Semantic upgrade",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            instruction = "Refine the roof",
        )

        assertEquals(2, diff.modifiedComponents.size) // The v1 records had no semantic fields.
        assertFalse(diff.warnings.contains(BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS))
        assertFalse(diff.warnings.contains(BuildDiffWarning.DECLARED_PRESERVED_COMPONENT_CHANGED))
        assertTrue(diff.intentChanged)
    }

    @Test
    fun reportsRemovedComponentsAndOperationsAndNoChangeAccurately() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val withoutRoof = base.copy(
            components = base.components.filterNot { it.componentId == "roof" },
            operations = base.operations.take(2),
        )
        val diff = calculator.compare(base, withoutRoof, "Removed roof", targetComponentIds = listOf("roof"))
        assertEquals(listOf("roof"), diff.removedComponents.map(BuildPlanComponent::componentId))
        assertEquals(2, diff.removedOperations.size)
        assertTrue(diff.hasChanges)

        val noChange = calculator.compare(base, base.copy(metadata = base.metadata.copy(generatedAtEpochMillis = 1_700_000_100_000)), "No-op")
        assertFalse(noChange.hasChanges)
    }
}
