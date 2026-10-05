package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiModelCapabilities
import com.craftmind.app.domain.ai.AiProviderId
import com.craftmind.app.domain.ai.StructuredOutputMode
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildComponentType
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanContextSerializerTest {
    private val serializer = BuildPlanContextSerializer()
    private val model = AiModel(
        id = "test-model",
        providerId = AiProviderId("test_provider"),
        displayName = "Test model",
        capabilities = AiModelCapabilities(
            textGeneration = true,
            vision = false,
            publicUrlReferences = false,
            structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
            maximumContextTokens = 16_000,
            maximumOutputTokens = 2_000,
        ),
    )

    @Test
    fun retainsOnlyReferencePresenceAndBoundsContextForLegacyPlans() {
        val request = request(
            plan = BuildPlanTestFixtures.legacyPlan(),
            snapshot = BuildRequestSnapshot(
                prompt = "Build a courtyard home",
                imageContentUri = "content://secret/private-photo",
                imageMediaType = "image/png",
                imageDisplayName = "private-photo.png",
                imageSizeBytes = 1_234,
                urlReference = "https://private.example/room-tour",
            ),
            instruction = "Change the roof",
        )

        val result = serializer.serialize(request, model) as BuildPlanContextResult.Ready
        val document = kotlinx.serialization.json.Json.decodeFromString<BuildPlanRefinementContextDocument>(result.context.json)

        assertFalse(result.context.json.contains("content://secret"))
        assertFalse(result.context.json.contains("https://private.example"))
        assertTrue(document.imageReferencePresentButUnavailable)
        assertTrue(document.urlReferencePresentButUnavailable)
        assertEquals(4, document.operationContext.size)
        assertEquals(setOf("house", "roof"), result.context.fullOperationComponentIds)
        assertTrue(document.components.all { it.bounds != null && it.operationsIncludedCompletely })
        assertTrue(document.contextNotice.contains("legacy schema v1"))
    }

    @Test
    fun refinementReceivesSavedTextOnlyVisualNotesWithoutImageUriOrBytes() {
        val analysis = BuildImageAnalysis(
            summary = "A compact stone-like tower with a steep roof.",
            observedDetails = listOf("A tall narrow silhouette and a pointed roof are visible."),
            inferredDetails = listOf("The pale surfaces may represent stone."),
            uncertainties = listOf("The rear elevation and interior are not visible."),
        )
        val source = BuildImageAnalysisSource("google_gemini", "gemini-3.5-flash", analysis)
        val request = request(
            plan = BuildPlanTestFixtures.semanticPlan(),
            snapshot = BuildRequestSnapshot(
                prompt = "Build a compact tower",
                imageContentUri = "content://secret/private-photo",
                imageMediaType = "image/jpeg",
                imageAnalysisSource = source,
            ),
            instruction = "Refine the roof",
        )

        val result = serializer.serialize(request, model) as BuildPlanContextResult.Ready
        val document = kotlinx.serialization.json.Json.decodeFromString<BuildPlanRefinementContextDocument>(result.context.json)

        assertEquals(source, document.imageAnalysisSource)
        assertTrue(document.imageReferencePresentButUnavailable)
        assertTrue(document.contextNotice.contains("text-only visual notes"))
        assertFalse(result.context.json.contains("content://secret"))
        assertFalse(result.context.json.contains("private-photo"))
    }

    @Test
    fun refusesBroadContextWhenAllPlacementsCannotBeIncluded() {
        val dimensions = BuildDimensions(96, 64, 96)
        val operations = (0 until 769).map { index ->
            val x = index % 96
            val z = (index / 96) % 96
            val y = index / (96 * 96)
            BuildPlanOperation(index, BuildPlanOperationKind.PLACE_BLOCK, "minecraft:stone", BlockPosition(x, y, z), componentId = "main")
        }
        val plan = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(dimensions = dimensions),
            components = listOf(
                BuildPlanComponent(
                    componentId = "main",
                    name = "Main structure",
                    purpose = "A large structure",
                    bounds = BlockBounds(BlockPosition(0, 0, 0), dimensions),
                    type = BuildComponentType.BUILDING,
                    constructionOrder = 0,
                ),
            ),
            operations = operations,
        )

        val result = serializer.serialize(request(plan, instruction = "Make the whole build twice as large"), model)
        assertEquals(BuildPlanContextResult.TooLarge, result)
    }

    @Test
    fun refusesComponentReplacementContextThatExceedsOperationBound() {
        val dimensions = BuildDimensions(96, 64, 96)
        val operations = (0 until 769).map { index ->
            val x = index % 96
            val z = (index / 96) % 96
            val y = index / (96 * 96)
            BuildPlanOperation(index, BuildPlanOperationKind.PLACE_BLOCK, "minecraft:stone", BlockPosition(x, y, z), componentId = "tower")
        }
        val plan = BuildPlanTestFixtures.semanticPlan().copy(
            metadata = BuildPlanTestFixtures.semanticPlan().metadata.copy(dimensions = dimensions),
            components = listOf(
                BuildPlanComponent(
                    componentId = "tower",
                    name = "Tower",
                    purpose = "A tall tower",
                    bounds = BlockBounds(BlockPosition(0, 0, 0), dimensions),
                    type = BuildComponentType.BUILDING,
                    constructionOrder = 0,
                ),
            ),
            operations = operations,
        )

        assertEquals(BuildPlanContextResult.TooLarge, serializer.serialize(request(plan, instruction = "Change the tower"), model))
    }

    @Test
    fun respectsSelectedModelsAdvertisedContextLimit() {
        val smallModel = model.copy(capabilities = model.capabilities.copy(maximumContextTokens = 10))
        val result = serializer.serialize(
            request(BuildPlanTestFixtures.semanticPlan(), instruction = "Change the roof"),
            smallModel,
        )
        assertEquals(BuildPlanContextResult.TooLarge, result)
    }

    private fun request(
        plan: BuildPlan,
        snapshot: BuildRequestSnapshot = BuildRequestSnapshot("Build a compact home"),
        instruction: String,
    ) = BuildEditRequest(
        baseRecordId = "build-demo-v1",
        buildId = "build-demo",
        baseVersion = 1,
        basePlan = plan,
        originalRequest = snapshot,
        instruction = instruction,
        createdAtEpochMillis = 1_700_000_000_500,
    )
}
