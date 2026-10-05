package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.BuildImageAnalysis
import com.craftmind.app.domain.buildplan.BuildImageAnalysisLimits
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Strict parser for the bounded intermediate image evidence; it never emits or mutates a BuildPlan. */
class BuildImageAnalysisParser(
    private val json: Json = STRICT_JSON,
) {
    fun parse(content: String): BuildImageAnalysis {
        if (content.length > BuildImageAnalysisLimits.MAX_RESPONSE_BYTES) {
            throw failure(AiErrorCode.RESPONSE_TOO_LARGE)
        }
        val responseBytes = content.toByteArray(StandardCharsets.UTF_8)
        if (responseBytes.size > BuildImageAnalysisLimits.MAX_RESPONSE_BYTES) {
            responseBytes.fill(0)
            throw failure(AiErrorCode.RESPONSE_TOO_LARGE)
        }
        responseBytes.fill(0)

        val document = try {
            val root = json.parseToJsonElement(content).jsonObject
            val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (schemaVersion != ANALYSIS_SCHEMA_VERSION) throw failure(AiErrorCode.INVALID_AI_RESPONSE)
            json.decodeFromString<BuildImageAnalysisDocument>(content)
        } catch (error: AiProviderException) {
            throw error
        } catch (_: SerializationException) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        } catch (_: IllegalArgumentException) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        } catch (_: IllegalStateException) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        }

        if (document.schemaVersion != ANALYSIS_SCHEMA_VERSION ||
            document.observedDetails.size > BuildImageAnalysisLimits.MAX_OBSERVED_DETAILS ||
            document.inferredDetails.size > BuildImageAnalysisLimits.MAX_INFERRED_DETAILS ||
            document.uncertainties.size > BuildImageAnalysisLimits.MAX_UNCERTAINTIES
        ) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        }
        val analysis = BuildImageAnalysis(
            summary = document.summary,
            observedDetails = document.observedDetails,
            inferredDetails = document.inferredDetails,
            uncertainties = document.uncertainties,
        )
        if (!analysis.isWellFormed()) throw failure(AiErrorCode.INVALID_AI_RESPONSE)

        if (!analysis.isWithinSavedByteLimit()) throw failure(AiErrorCode.RESPONSE_TOO_LARGE)
        return analysis
    }

    private fun failure(code: AiErrorCode) = AiProviderException(AiFailure(code, retryable = false))

    @Serializable
    private data class BuildImageAnalysisDocument(
        val schemaVersion: Int,
        val summary: String,
        val observedDetails: List<String>,
        val inferredDetails: List<String>,
        val uncertainties: List<String>,
    )

    private companion object {
        const val ANALYSIS_SCHEMA_VERSION = 1
        val STRICT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            allowSpecialFloatingPointValues = false
        }
    }
}
