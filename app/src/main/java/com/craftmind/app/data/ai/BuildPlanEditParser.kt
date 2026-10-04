package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiFailure
import com.craftmind.app.domain.ai.AiProviderException
import com.craftmind.app.domain.ai.AiRefinementResponse
import com.craftmind.app.domain.buildplan.AiBuildEditDocument
import com.craftmind.app.domain.buildplan.BuildDiffCalculator
import com.craftmind.app.domain.buildplan.BuildPlanEditApplier
import com.craftmind.app.domain.buildplan.BuildPlanEditApplyResult
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanValidationIssue
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.BuildPlanValidator
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.ValidatedBuildPlan
import com.craftmind.app.domain.ai.AiUsage
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Strict parser for AI-authored patches; the base plan is never mutated by parsing. */
class BuildPlanEditParser(
    private val validator: BuildPlanValidator,
    private val applier: BuildPlanEditApplier = BuildPlanEditApplier(),
    private val diffCalculator: BuildDiffCalculator = BuildDiffCalculator(),
    private val json: Json = STRICT_EDIT_JSON,
) {
    fun parse(
        content: String,
        request: BuildEditRequest,
        providerId: String,
        modelId: String,
        generatedAtEpochMillis: Long,
        componentsWithFullOperationContext: Set<String>,
        usage: AiUsage? = null,
    ): AiRefinementResponse {
        if (content.toByteArray(StandardCharsets.UTF_8).size > BuildPlanLimits.MAX_RESPONSE_BYTES) {
            throw failure(AiErrorCode.RESPONSE_TOO_LARGE)
        }
        val schemaVersion = try {
            json.parseToJsonElement(content).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        } ?: throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        if (schemaVersion != BuildPlanLimits.EDIT_SCHEMA_VERSION) {
            throw failure(AiErrorCode.UNSUPPORTED_SCHEMA_VERSION)
        }

        val document = try {
            json.decodeFromString<AiBuildEditDocument>(content)
        } catch (_: SerializationException) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        } catch (_: IllegalArgumentException) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        } catch (_: IllegalStateException) {
            throw failure(AiErrorCode.INVALID_AI_RESPONSE)
        }
        if (document.upsertComponents.size > BuildPlanLimits.MAX_COMPONENTS ||
            document.removedComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            document.targetComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            document.preservedComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            document.replacementOperations.size > BuildPlanLimits.MAX_COMPONENTS ||
            document.replacementOperations.sumOf { it.operations.size.toLong() } > BuildPlanLimits.MAX_OPERATIONS.toLong()
        ) {
            throw failure(AiErrorCode.BUILD_TOO_LARGE)
        }

        val candidate = when (
            val result = applier.apply(
                base = request.basePlan,
                edit = document,
                providerId = providerId,
                modelId = modelId,
                generatedAtEpochMillis = generatedAtEpochMillis,
                componentsWithFullOperationContext = componentsWithFullOperationContext,
            )
        ) {
            is BuildPlanEditApplyResult.Applied -> result.candidate
            is BuildPlanEditApplyResult.Invalid -> throw failure(
                when {
                    result.error == com.craftmind.app.domain.buildplan.BuildPlanEditApplyError.RESULTING_PLAN_TOO_LARGE -> AiErrorCode.BUILD_TOO_LARGE
                    result.error == com.craftmind.app.domain.buildplan.BuildPlanEditApplyError.INVALID_REPLACEMENT_OPERATIONS &&
                        document.replacementOperations.sumOf { it.operations.size } > BuildPlanLimits.MAX_OPERATIONS -> AiErrorCode.BUILD_TOO_LARGE
                    else -> AiErrorCode.INVALID_BUILD_EDIT
                },
            )
        }

        val validated = when (val result = validator.validate(candidate)) {
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
                throw failure(if (oversized) AiErrorCode.BUILD_TOO_LARGE else AiErrorCode.INVALID_BUILD_PLAN)
            }
        }
        val diff = diffCalculator.compare(
            before = request.basePlan,
            after = validated.plan,
            summary = document.editSummary,
            targetComponentIds = document.targetComponentIds,
            preservedComponentIds = document.preservedComponentIds,
            instruction = request.instruction,
        )
        if (!diff.hasChanges) throw failure(AiErrorCode.NO_CHANGES_PROPOSED)
        return AiRefinementResponse(validated, diff, usage)
    }

    private fun failure(code: AiErrorCode) = AiProviderException(AiFailure(code, retryable = false))

    private companion object {
        val STRICT_EDIT_JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
            allowSpecialFloatingPointValues = false
        }
    }
}
