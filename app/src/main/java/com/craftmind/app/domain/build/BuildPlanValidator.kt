package com.craftmind.app.domain.build

/** Stable structural findings for the future AI-plan validation boundary. */
enum class BuildPlanValidationIssue {
    EMPTY_PLAN_ID,
    UNSUPPORTED_SCHEMA_VERSION,
    MISSING_SOURCE_REQUEST,
    MISSING_PROVIDER_OR_MODEL,
    EMPTY_PLAN,
    TOO_MANY_OPERATIONS,
    DUPLICATE_COMPONENT_ID,
    INVALID_COMPONENT_BOUNDS,
    INVALID_OPERATION_ORDER,
    EMPTY_BLOCK_ID,
    UNKNOWN_COMPONENT,
}

sealed interface BuildPlanValidationResult {
    data class Valid(val plan: ValidatedBuildPlan) : BuildPlanValidationResult
    data class Invalid(val issues: Set<BuildPlanValidationIssue>) : BuildPlanValidationResult
}

/**
 * Contract for validating an AI-produced plan before review or execution. Phase 1 defines the
 * boundary only; there is no AI plan or execution pipeline to validate yet.
 */
fun interface BuildPlanValidator {
    fun validate(plan: BuildPlan): BuildPlanValidationResult
}
