package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.AiBlockOperationDocument
import com.craftmind.app.domain.buildplan.AiBuildComponentDocument
import com.craftmind.app.domain.buildplan.AiBuildEditDocument
import com.craftmind.app.domain.buildplan.AiComponentOperationSetDocument
import com.craftmind.app.domain.buildplan.BuildDiffWarning
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildComponentType
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.ai.AiErrorCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanEditParserTest {
    private val parser = BuildPlanEditParser(DefaultBuildPlanValidator())

    @Test
    fun parsesAndValidatesPatchThenReturnsDeterministicDiff() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val response = parser.parse(
            content = encode(edit(base)),
            request = request(base),
            providerId = "google",
            modelId = "gemini-test",
            generatedAtEpochMillis = 1_700_000_000_500,
            componentsWithFullOperationContext = setOf("house", "roof"),
        )

        assertEquals("minecraft:bricks", response.plan.plan.operations[2].blockId)
        assertEquals("minecraft:stone", response.plan.plan.operations[0].blockId)
        assertEquals(1, response.diff.changedOperations.size)
        assertTrue(response.diff.warnings.isEmpty())
        assertEquals("google", response.plan.plan.metadata.providerId)
        assertEquals("gemini-test", response.plan.plan.metadata.modelId)
    }

    @Test
    fun rejectsMalformedUnknownKeyAndUnsupportedEditSchema() {
        assertEquals(AiErrorCode.INVALID_AI_RESPONSE, errorCode("{"))
        val unknownKey = encode(edit(BuildPlanTestFixtures.semanticPlan())).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"surprise\":true")
        assertEquals(AiErrorCode.INVALID_AI_RESPONSE, errorCode(unknownKey))
        val unsupported = encode(edit(BuildPlanTestFixtures.semanticPlan())).replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertEquals(AiErrorCode.UNSUPPORTED_SCHEMA_VERSION, errorCode(unsupported))
    }

    @Test
    fun rejectsInvalidBlockOutputAndPatchThatWouldRequireUnsentOperations() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val unsafe = edit(base).copy(
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    "roof",
                    listOf(AiBlockOperationDocument(0, 4, 0, "minecraft:command_block", emptyMap(), "roof")),
                ),
            ),
        )
        assertEquals(AiErrorCode.INVALID_BUILD_PLAN, errorCode(encode(unsafe)))

        assertEquals(
            AiErrorCode.INVALID_BUILD_EDIT,
            errorCode(encode(edit(base)), fullContext = emptySet()),
        )
    }

    @Test
    fun rejectsNoopOversizedAndInvalidSchemaCandidatesWithTypedErrors() {
        val base = BuildPlanTestFixtures.semanticPlan()
        val noOp = AiBuildEditDocument(
            schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
            editSummary = "No actual change",
            targetComponentIds = listOf("roof"),
            preservedComponentIds = listOf("house"),
            removedComponentIds = emptyList(),
            upsertComponents = emptyList(),
            replacementOperations = emptyList(),
        )
        assertEquals(AiErrorCode.NO_CHANGES_PROPOSED, errorCode(encode(noOp)))
        assertEquals(AiErrorCode.RESPONSE_TOO_LARGE, errorCode(" ".repeat(BuildPlanLimits.MAX_RESPONSE_BYTES + 1)))

        val invalidCandidate = edit(base).copy(
            upsertComponents = listOf(
                AiBuildComponentDocument(
                    "roof", BuildComponentType.ROOF, "Roof", "Updated roof",
                    BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(8, 2, 8)), "house", 1,
                ),
            ),
            replacementOperations = listOf(
                AiComponentOperationSetDocument(
                    "roof",
                    listOf(AiBlockOperationDocument(0, 4, 0, "minecraft:bricks", emptyMap(), "not_roof")),
                ),
            ),
        )
        assertEquals(AiErrorCode.INVALID_BUILD_EDIT, errorCode(encode(invalidCandidate)))
    }

    @Test
    fun schemaV1CandidateIsUpgradedAndCentralValidationAppliesToFullPlan() {
        val base = BuildPlanTestFixtures.legacyPlan()
        val document = legacyUpgradeEdit()
        val response = parser.parse(
            encode(document), request(base), "google", "gemini-test", 1_700_000_000_500, setOf("house", "roof"),
        )
        assertEquals(BuildPlanLimits.CURRENT_SCHEMA_VERSION, response.plan.plan.metadata.schemaVersion)
        assertEquals("courtyard home", response.plan.plan.metadata.intent?.structureType)
        assertTrue(response.diff.modifiedComponents.isNotEmpty())
        assertTrue(response.diff.warnings.none { it == BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS })
    }

    private fun edit(base: com.craftmind.app.domain.buildplan.BuildPlan) = AiBuildEditDocument(
        schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
        editSummary = "Changed the raised roof to brick",
        targetComponentIds = listOf("roof"),
        preservedComponentIds = listOf("house"),
        removedComponentIds = emptyList(),
        upsertComponents = listOf(
            AiBuildComponentDocument(
                "roof", BuildComponentType.ROOF, "Raised roof", "Raised brick roof above the main house.",
                BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(8, 2, 8)), "house", 1,
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
    )

    private fun legacyUpgradeEdit() = AiBuildEditDocument(
        schemaVersion = BuildPlanLimits.EDIT_SCHEMA_VERSION,
        editSummary = "Refined the raised roof",
        targetComponentIds = listOf("roof"),
        preservedComponentIds = listOf("house"),
        removedComponentIds = emptyList(),
        upsertComponents = listOf(
            AiBuildComponentDocument(
                "house", BuildComponentType.BUILDING, "Main house", "Foundation and lower walls of the home.",
                BlockBounds(BlockPosition(0, 0, 0), BuildDimensions(2, 1, 1)), null, 0,
            ),
            AiBuildComponentDocument(
                "roof", BuildComponentType.ROOF, "Raised roof", "Raised brick roof above the main house.",
                BlockBounds(BlockPosition(0, 4, 0), BuildDimensions(2, 1, 1)), null, 1,
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
        intent = com.craftmind.app.domain.buildplan.AiBuildIntentDocument(
            "courtyard home", "modern", "small", 1, listOf("courtyard"), listOf("raised roof"),
            listOf("stone", "brick", "oak"), "garden", listOf("compact footprint"),
        ),
    )

    private fun request(base: com.craftmind.app.domain.buildplan.BuildPlan) = BuildEditRequest(
        baseRecordId = "build-demo-v1",
        buildId = "build-demo",
        baseVersion = 1,
        basePlan = base,
        originalRequest = BuildRequestSnapshot(prompt = "Build a compact courtyard home"),
        instruction = "Change the roof to brick",
        createdAtEpochMillis = 1_700_000_000_400,
    )

    private fun encode(document: AiBuildEditDocument): String = Json.encodeToString(document)

    private fun errorCode(content: String, fullContext: Set<String> = setOf("house", "roof")): AiErrorCode {
        val error = try {
            parser.parse(content, request(BuildPlanTestFixtures.semanticPlan()), "google", "gemini-test", 1_700_000_000_500, fullContext)
            throw AssertionError("Expected a parser failure")
        } catch (expected: AiProviderException) {
            expected
        }
        return error.failure.code
    }
}
