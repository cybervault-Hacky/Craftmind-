package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildStatus
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPlanParserTest {
    private val parser = BuildPlanParser(DefaultBuildPlanValidator())
    private val request = BuildRequest(
        requestId = "request-test",
        prompt = "A small stone garden pavilion",
        imageReference = null,
        urlReference = null,
        createdAtEpochMillis = 123L,
    )

    @Test
    fun parsesStrictVersionedDocumentAndAddsTrustedRequestAndProviderMetadata() {
        val validated = parser.parse(validDocument(), request, "google_gemini", "gemini-test", 456L)
        val plan = validated.plan

        assertEquals("generated-garden", plan.planId)
        assertEquals("request-test", plan.metadata.sourceRequestId)
        assertEquals("google_gemini", plan.metadata.providerId)
        assertEquals("gemini-test", plan.metadata.modelId)
        assertEquals(456L, plan.metadata.generatedAtEpochMillis)
        assertEquals(BuildStatus.READY, plan.status)
        assertEquals(BuildPlanLimits.CURRENT_SCHEMA_VERSION, plan.metadata.schemaVersion)
        assertEquals("pavilion", plan.metadata.intent?.structureType)
        assertEquals("BUILDING", plan.components.single().type.name)
        assertEquals(2, plan.operations.size)
        assertEquals(0, plan.operations.first().sequence)
    }

    @Test
    fun rejectsUnknownFieldsRatherThanSilentlyIgnoringThem() {
        val response = validDocument().replace("\"title\":", "\"unexpected\":true,\"title\":")
        assertFailure(AiErrorCode.INVALID_AI_RESPONSE, response)
    }

    @Test
    fun rejectsMissingRequiredOperationFields() {
        val response = validDocument().replace("\"blockState\": {}, ", "")
        assertFailure(AiErrorCode.INVALID_AI_RESPONSE, response)
    }

    @Test
    fun rejectsMalformedJsonAndMarkdownFences() {
        assertFailure(AiErrorCode.INVALID_AI_RESPONSE, "not json")
        assertFailure(AiErrorCode.INVALID_AI_RESPONSE, "```json\n${validDocument()}\n```")
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val response = validDocument().replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")
        assertFailure(AiErrorCode.UNSUPPORTED_SCHEMA_VERSION, response)
    }

    @Test
    fun rejectsUnsupportedBlocksAndOutOfBoundsCoordinates() {
        val unsafeBlock = validDocument().replace("minecraft:stone", "minecraft:command_block")
        assertFailure(AiErrorCode.INVALID_BUILD_PLAN, unsafeBlock)
        val unsafePosition = validDocument().replace("\"x\": 1, \"y\": 0, \"z\": 0", "\"x\": 99, \"y\": 0, \"z\": 0")
        assertFailure(AiErrorCode.INVALID_BUILD_PLAN, unsafePosition)
    }

    @Test
    fun rejectsDuplicatePlacementsAndInvalidBlockStates() {
        val duplicate = validDocument().replace(
            "\"x\": 1, \"y\": 0, \"z\": 0",
            "\"x\": 0, \"y\": 0, \"z\": 0",
        )
        assertFailure(AiErrorCode.INVALID_BUILD_PLAN, duplicate)
        val invalidState = validDocument().replace("\"blockState\": {}", "\"blockState\": {\"facing\":\"up\"}")
        assertFailure(AiErrorCode.INVALID_BUILD_PLAN, invalidState)
    }

    @Test
    fun rejectsResponseOverCentralByteLimitBeforeDecoding() {
        assertFailure(
            AiErrorCode.RESPONSE_TOO_LARGE,
            " ".repeat(BuildPlanLimits.MAX_RESPONSE_BYTES + 1),
        )
    }

    private fun assertFailure(code: AiErrorCode, response: String) {
        val error = try {
            parser.parse(response, request, "google_gemini", "gemini-test", 456L)
            throw AssertionError("Expected response to be rejected")
        } catch (expected: AiProviderException) {
            expected
        }
        assertEquals(code, error.failure.code)
        assertTrue(!error.failure.retryable)
    }

    private fun validDocument(): String = """
        {
          "schemaVersion": 2,
          "buildId": "generated-garden",
          "title": "Stone Garden Pavilion",
          "description": "A compact open pavilion with a stone floor and a low garden wall.",
          "dimensions": {"width": 8, "height": 5, "depth": 8},
          "originStrategy": "CENTERED_GROUND",
          "intent": {"structureType":"pavilion", "style":"stone", "approximateScale":"small", "floorCount":1, "rooms":[], "specialFeatures":[], "materials":["stone"], "environment":"garden", "constraints":[]},
          "components": [
            {"componentId": "main", "type":"BUILDING", "name": "Pavilion", "purpose": "Covered gathering area", "bounds":{"origin":{"x":0,"y":0,"z":0},"dimensions":{"width":8,"height":5,"depth":8}}, "parentComponentId":null, "constructionOrder":0}
          ],
          "operations": [
            {"x": 0, "y": 0, "z": 0, "blockId": "minecraft:stone", "blockState": {}, "componentId": "main"},
            {"x": 1, "y": 0, "z": 0, "blockId": "minecraft:stone", "blockState": {}, "componentId": "main"}
          ]
        }
    """.trimIndent()
}
