package com.craftmind.app.domain.buildplan

object BuildPlanTestFixtures {
    fun semanticPlan(
        planId: String = "plan_demo",
        title: String = "Courtyard home",
        generatedAt: Long = 1_700_000_000_000,
    ): BuildPlan {
        val dimensions = BuildDimensions(width = 8, height = 6, depth = 8)
        return BuildPlan(
            planId = planId,
            metadata = BuildPlanMetadata(
                schemaVersion = BuildPlanLimits.CURRENT_SCHEMA_VERSION,
                sourceRequestId = "request-original",
                providerId = "google",
                modelId = "gemini-test",
                title = title,
                summary = "A compact courtyard home with a raised roof.",
                generatedAtEpochMillis = generatedAt,
                dimensions = dimensions,
                intent = BuildIntent(
                    structureType = "courtyard home",
                    style = "modern",
                    approximateScale = "small",
                    floorCount = 1,
                    rooms = listOf("courtyard"),
                    specialFeatures = listOf("raised roof"),
                    materials = listOf("stone", "oak"),
                    environment = null,
                    constraints = listOf("compact footprint"),
                ),
            ),
            originStrategy = BuildOriginStrategy.CENTERED_GROUND,
            components = listOf(
                BuildPlanComponent(
                    componentId = "house",
                    type = BuildComponentType.BUILDING,
                    name = "Main house",
                    purpose = "Foundation and lower walls of the home.",
                    bounds = BlockBounds(BlockPosition(0, 0, 0), BuildDimensions(8, 6, 8)),
                    parentComponentId = null,
                    constructionOrder = 0,
                ),
                BuildPlanComponent(
                    componentId = "roof",
                    type = BuildComponentType.ROOF,
                    name = "Raised roof",
                    purpose = "Raised oak roof above the main house.",
                    bounds = BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(8, 2, 8)),
                    parentComponentId = "house",
                    constructionOrder = 1,
                ),
            ),
            operations = listOf(
                operation(0, "minecraft:stone", 0, 0, 0, "house"),
                operation(1, "minecraft:stone", 1, 0, 0, "house"),
                operation(2, "minecraft:oak_planks", 0, 4, 0, "roof"),
                operation(3, "minecraft:oak_planks", 1, 4, 0, "roof"),
            ),
            status = BuildStatus.READY,
        )
    }

    fun legacyPlan(): BuildPlan {
        val semantic = semanticPlan()
        return semantic.copy(
            metadata = semantic.metadata.copy(
                schemaVersion = BuildPlanLimits.LEGACY_SCHEMA_VERSION,
                intent = null,
            ),
            components = semantic.components.map { component ->
                component.copy(
                    type = BuildComponentType.UNSPECIFIED,
                    bounds = null,
                    parentComponentId = null,
                    constructionOrder = 0,
                )
            },
        )
    }

    fun record(
        plan: BuildPlan = semanticPlan(),
        buildId: String = "build-demo",
        version: Int = 1,
        recordId: String = "$buildId-v$version",
        savedAt: Long = 1_700_000_000_100 + version,
        parentRecordId: String? = null,
        changeSummary: String? = null,
        diff: BuildDiff? = null,
        restoredFromVersion: Int? = null,
    ) = LocalBuildRecord(
        recordId = recordId,
        plan = plan,
        request = BuildRequestSnapshot(prompt = "Build a compact courtyard home"),
        savedAtEpochMillis = savedAt,
        buildId = buildId,
        version = version,
        parentRecordId = parentRecordId,
        refinementInstruction = null,
        changeSummary = changeSummary,
        diff = diff,
        restoredFromVersion = restoredFromVersion,
    )

    fun validated(plan: BuildPlan = semanticPlan()): ValidatedBuildPlan = when (
        val result = DefaultBuildPlanValidator().validate(plan)
    ) {
        is BuildPlanValidationResult.Valid -> result.plan
        is BuildPlanValidationResult.Invalid -> error("Test fixture is invalid: ${result.issues}")
    }

    fun diff(
        before: BuildPlan,
        after: BuildPlan,
        summary: String = "Updated plan",
        targets: List<String> = listOf("roof"),
        preserved: List<String> = listOf("house"),
        instruction: String = "Change the roof",
    ): BuildDiff = BuildDiffCalculator().compare(
        before = before,
        after = after,
        summary = summary,
        targetComponentIds = targets,
        preservedComponentIds = preserved,
        instruction = instruction,
    )

    fun operation(
        sequence: Int,
        blockId: String,
        x: Int,
        y: Int,
        z: Int,
        componentId: String,
    ) = BuildPlanOperation(
        sequence = sequence,
        kind = BuildPlanOperationKind.PLACE_BLOCK,
        blockId = blockId,
        position = BlockPosition(x, y, z),
        componentId = componentId,
    )
}
