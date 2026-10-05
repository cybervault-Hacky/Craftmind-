package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildImageAnalysisParserTest {
    private val parser = BuildImageAnalysisParser()

    @Test
    fun parsesSeparateObservedInferredAndUncertainDetails() {
        val result = parser.parse(
            """{"schemaVersion":1,"summary":"A narrow tower.","observedDetails":["A pointed roof is visible."],"inferredDetails":["The roof may be dark wood."],"uncertainties":["The back wall is hidden."]}""",
        )

        assertEquals(listOf("A pointed roof is visible."), result.observedDetails)
        assertEquals(listOf("The roof may be dark wood."), result.inferredDetails)
        assertEquals(listOf("The back wall is hidden."), result.uncertainties)
    }

    @Test
    fun rejectsUnknownFieldsMalformedSchemaAndOversizedDetailLists() {
        assertEquals(
            AiErrorCode.INVALID_AI_RESPONSE,
            failureCode("""{"schemaVersion":1,"summary":"x","observedDetails":[],"inferredDetails":[],"uncertainties":[],"extra":true}"""),
        )
        assertEquals(
            AiErrorCode.INVALID_AI_RESPONSE,
            failureCode("""{"schemaVersion":2,"summary":"x","observedDetails":[],"inferredDetails":[],"uncertainties":[]}"""),
        )
        val tooMany = (0..16).joinToString(",") { "\"detail-$it\"" }
        assertEquals(
            AiErrorCode.INVALID_AI_RESPONSE,
            failureCode("""{"schemaVersion":1,"summary":"x","observedDetails":[$tooMany],"inferredDetails":[],"uncertainties":[]}"""),
        )
    }

    @Test
    fun rejectsOtherwiseValidNotesThatExceedTheLocalHistoryLimit() {
        val detail = "x".repeat(240)
        val observed = (0 until 16).joinToString(",") { "\"$detail\"" }
        val inferred = (0 until 12).joinToString(",") { "\"$detail\"" }
        val uncertainties = (0 until 12).joinToString(",") { "\"$detail\"" }
        val response = """{"schemaVersion":1,"summary":"x","observedDetails":[$observed],"inferredDetails":[$inferred],"uncertainties":[$uncertainties]}"""

        assertEquals(AiErrorCode.RESPONSE_TOO_LARGE, failureCode(response))
    }

    @Test
    fun historyValidationAlsoBoundsUtf8BytesForMultibyteVisualNotes() {
        val detail = "🧱".repeat(120)
        val analysis = BuildImageAnalysis(
            summary = "A compact pavilion.",
            observedDetails = List(16) { detail },
            inferredDetails = List(12) { detail },
            uncertainties = List(12) { detail },
        )
        assertTrue(analysis.isWellFormed())
        assertFalse(BuildImageAnalysisSource("google_gemini", "gemini-3.5-flash", analysis).isWellFormed())
    }

    @Test
    fun rejectsResponsesThatExceedTheBoundedIntermediateLimit() {
        val error = try {
            parser.parse(" ".repeat(32 * 1024 + 1))
            throw AssertionError("Expected response-size rejection")
        } catch (expected: AiProviderException) {
            expected
        }
        assertEquals(AiErrorCode.RESPONSE_TOO_LARGE, error.failure.code)
        assertTrue(!error.message.orEmpty().contains("{"))
    }

    private fun failureCode(content: String): AiErrorCode = try {
        parser.parse(content)
        throw AssertionError("Expected strict analysis rejection")
    } catch (expected: AiProviderException) {
        expected.failure.code
    }
}
