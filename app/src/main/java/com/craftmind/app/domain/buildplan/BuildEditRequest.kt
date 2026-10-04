package com.craftmind.app.domain.buildplan

sealed interface BuildEditValidationResult {
    data class Valid(val request: BuildEditRequest) : BuildEditValidationResult
    data class Invalid(val reason: BuildEditValidationError) : BuildEditValidationResult
}

enum class BuildEditValidationError {
    EMPTY_INSTRUCTION,
    INSTRUCTION_TOO_LONG,
    INVALID_BASE_VERSION,
    INVALID_BASE_PLAN,
    LEGACY_PLAN_NOT_UPGRADABLE,
}

/** Immutable refinement input. The base plan is never mutated or persisted as a candidate. */
data class BuildEditRequest(
    val baseRecordId: String,
    val buildId: String,
    val baseVersion: Int,
    val basePlan: BuildPlan,
    val originalRequest: BuildRequestSnapshot,
    val instruction: String,
    val createdAtEpochMillis: Long,
)

class BuildEditRequestValidator(
    private val planValidator: BuildPlanValidator = DefaultBuildPlanValidator(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    fun create(record: LocalBuildRecord, instruction: String): BuildEditValidationResult {
        val normalized = instruction.trim()
        if (normalized.isBlank()) return BuildEditValidationResult.Invalid(BuildEditValidationError.EMPTY_INSTRUCTION)
        if (normalized.length > BuildPlanLimits.MAX_EDIT_INSTRUCTION_LENGTH) {
            return BuildEditValidationResult.Invalid(BuildEditValidationError.INSTRUCTION_TOO_LONG)
        }
        if (record.recordId.isBlank() || record.buildId.isBlank() || record.version < 1) {
            return BuildEditValidationResult.Invalid(BuildEditValidationError.INVALID_BASE_VERSION)
        }
        val plan = record.plan
        if (plan.status != BuildStatus.READY || planValidator.validate(plan) !is BuildPlanValidationResult.Valid) {
            return BuildEditValidationResult.Invalid(BuildEditValidationError.INVALID_BASE_PLAN)
        }
        if (plan.metadata.schemaVersion == BuildPlanLimits.LEGACY_SCHEMA_VERSION) {
            val componentIds = plan.components.mapTo(hashSetOf(), BuildPlanComponent::componentId)
            if (plan.operations.any { operation -> operation.componentId?.let { it !in componentIds } ?: true } ||
                plan.components.any { component -> plan.operations.none { it.componentId == component.componentId } }
            ) {
                return BuildEditValidationResult.Invalid(BuildEditValidationError.LEGACY_PLAN_NOT_UPGRADABLE)
            }
        }
        return BuildEditValidationResult.Valid(
            BuildEditRequest(
                baseRecordId = record.recordId,
                buildId = record.buildId,
                baseVersion = record.version,
                basePlan = plan,
                originalRequest = record.request,
                instruction = normalized,
                createdAtEpochMillis = nowEpochMillis(),
            ),
        )
    }
}
