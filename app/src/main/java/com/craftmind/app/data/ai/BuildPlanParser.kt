package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.buildplan.AiBuildPlanDocument
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanValidationIssue
import com.craftmind.app.domain.buildplan.BuildPlanValidator
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import com.craftmind.app.domain.buildplan.toDomainPlan
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

/** Strict JSON parser: unknown fields and malformed structures are errors, never silently repaired. */
class BuildPlanParser(
    private val validator: BuildPlanValidator,
    private val json: Json = STRICT_PLAN_JSON,
) {
    fun parse(
        content: String,
        request: BuildRequest,
        providerId: String,
        modelId: String,
        generatedAtEpochMillis: Long,
    ): ValidatedBuildPlan {
        if (content.toByteArray(StandardCharsets.UTF_8).size > BuildPlanLimits.MAX_RESPONSE_BYTES) {
            throw AiProviderException(AiFailure(AiErrorCode.RESPONSE_TOO_LARGE, retryable = false))
        }

        val document = try {
            json.decodeFromString<AiBuildPlanDocument>(content)
        } catch (_: SerializationException) {
            throw AiProviderException(AiFailure(AiErrorCode.INVALID_AI_RESPONSE, retryable = false))
        } catch (_: IllegalArgumentException) {
            throw AiProviderException(AiFailure(AiErrorCode.INVALID_AI_RESPONSE, retryable = false))
        } catch (_: IllegalStateException) {
            throw AiProviderException(AiFailure(AiErrorCode.INVALID_AI_RESPONSE, retryable = false))
        }

        if (document.schemaVersion != BuildPlanLimits.CURRENT_SCHEMA_VERSION) {
            throw AiProviderException(AiFailure(AiErrorCode.UNSUPPORTED_SCHEMA_VERSION, retryable = false))
        }
        if (document.operations.size > BuildPlanLimits.MAX_OPERATIONS ||
            document.components.size > BuildPlanLimits.MAX_COMPONENTS
        ) {
            throw AiProviderException(AiFailure(AiErrorCode.BUILD_TOO_LARGE, retryable = false))
        }

        val plan = document.toDomainPlan(
            requestId = request.requestId,
            providerId = providerId,
            modelId = modelId,
            generatedAtEpochMillis = generatedAtEpochMillis,
        )
        return when (val result = validator.validate(plan)) {
            is BuildPlanValidationResult.Valid -> result.plan
            is BuildPlanValidationResult.Invalid -> {
                val oversized = result.issues.any { issue ->
                    issue in setOf(
                        BuildPlanValidationIssue.BUILD_TOO_WIDE,
                        BuildPlanValidationIssue.BUILD_TOO_TALL,
                        BuildPlanValidationIssue.BUILD_TOO_DEEP,
                        BuildPlanValidationIssue.TOO_MANY_COMPONENTS,
                        BuildPlanValidationIssue.TOO_MANY_OPERATIONS,
                    )
                }
                val code = if (oversized) AiErrorCode.BUILD_TOO_LARGE else AiErrorCode.INVALID_BUILD_PLAN
                throw AiProviderException(AiFailure(code, retryable = false))
            }
        }
    }

    private companion object {
        val STRICT_PLAN_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            allowSpecialFloatingPointValues = false
        }
    }
}
