package com.craftmind.app.domain.buildplan

/** Central limits apply to provider output, local storage, and the future execution boundary. */
object BuildPlanLimits {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_BUILD_WIDTH = 96
    const val MAX_BUILD_HEIGHT = 64
    const val MAX_BUILD_DEPTH = 96
    const val MAX_COMPONENTS = 64
    const val MAX_OPERATIONS = 4_096
    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    const val MAX_BUILD_ID_LENGTH = 64
    const val MAX_TITLE_LENGTH = 100
    const val MAX_DESCRIPTION_LENGTH = 1_200
    const val MAX_COMPONENT_NAME_LENGTH = 80
    const val MAX_COMPONENT_PURPOSE_LENGTH = 240
    const val MAX_BLOCK_STATE_PROPERTIES = 8
    const val MAX_BLOCK_STATE_VALUE_LENGTH = 32
}

enum class BuildPlanValidationIssue {
    EMPTY_PLAN_ID,
    INVALID_PLAN_ID,
    UNSUPPORTED_SCHEMA_VERSION,
    MISSING_SOURCE_REQUEST,
    MISSING_PROVIDER_OR_MODEL,
    INVALID_TITLE_OR_DESCRIPTION,
    INVALID_DIMENSIONS,
    BUILD_TOO_WIDE,
    BUILD_TOO_TALL,
    BUILD_TOO_DEEP,
    TOO_MANY_COMPONENTS,
    EMPTY_COMPONENTS,
    TOO_MANY_OPERATIONS,
    EMPTY_OPERATIONS,
    DUPLICATE_COMPONENT_ID,
    INVALID_COMPONENT,
    INVALID_COMPONENT_BOUNDS,
    INVALID_OPERATION_ORDER,
    UNSUPPORTED_OPERATION,
    EMPTY_BLOCK_ID,
    INVALID_BLOCK_ID,
    INVALID_BLOCK_STATE,
    INVALID_COORDINATE,
    DUPLICATE_COORDINATE,
    UNKNOWN_COMPONENT,
}

sealed interface BuildPlanValidationResult {
    data class Valid(val plan: ValidatedBuildPlan) : BuildPlanValidationResult
    data class Invalid(val issues: Set<BuildPlanValidationIssue>) : BuildPlanValidationResult
}

fun interface BuildPlanValidator {
    fun validate(plan: BuildPlan): BuildPlanValidationResult
}

/** Strict, deterministic validation; it does not repair or rewrite AI output. */
class DefaultBuildPlanValidator(
    private val blockCatalog: MinecraftBlockCatalog = MinecraftBlockCatalog,
) : BuildPlanValidator {
    override fun validate(plan: BuildPlan): BuildPlanValidationResult {
        if (plan.components.size > BuildPlanLimits.MAX_COMPONENTS) {
            return BuildPlanValidationResult.Invalid(setOf(BuildPlanValidationIssue.TOO_MANY_COMPONENTS))
        }
        if (plan.operations.size > BuildPlanLimits.MAX_OPERATIONS) {
            return BuildPlanValidationResult.Invalid(setOf(BuildPlanValidationIssue.TOO_MANY_OPERATIONS))
        }
        val issues = linkedSetOf<BuildPlanValidationIssue>()
        val metadata = plan.metadata
        val dimensions = metadata.dimensions

        when {
            plan.planId.isBlank() -> issues += BuildPlanValidationIssue.EMPTY_PLAN_ID
            plan.planId.length > BuildPlanLimits.MAX_BUILD_ID_LENGTH ||
                !BUILD_ID_PATTERN.matches(plan.planId) -> issues += BuildPlanValidationIssue.INVALID_PLAN_ID
        }
        if (metadata.schemaVersion != BuildPlanLimits.CURRENT_SCHEMA_VERSION) {
            issues += BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION
        }
        if (metadata.sourceRequestId.isBlank()) issues += BuildPlanValidationIssue.MISSING_SOURCE_REQUEST
        if (metadata.providerId.isBlank() || metadata.modelId.isBlank()) {
            issues += BuildPlanValidationIssue.MISSING_PROVIDER_OR_MODEL
        }
        if (metadata.title.isBlank() || metadata.title.length > BuildPlanLimits.MAX_TITLE_LENGTH ||
            metadata.summary.isBlank() || metadata.summary.length > BuildPlanLimits.MAX_DESCRIPTION_LENGTH
        ) {
            issues += BuildPlanValidationIssue.INVALID_TITLE_OR_DESCRIPTION
        }

        if (dimensions.width <= 0 || dimensions.height <= 0 || dimensions.depth <= 0) {
            issues += BuildPlanValidationIssue.INVALID_DIMENSIONS
        }
        if (dimensions.width > BuildPlanLimits.MAX_BUILD_WIDTH) issues += BuildPlanValidationIssue.BUILD_TOO_WIDE
        if (dimensions.height > BuildPlanLimits.MAX_BUILD_HEIGHT) issues += BuildPlanValidationIssue.BUILD_TOO_TALL
        if (dimensions.depth > BuildPlanLimits.MAX_BUILD_DEPTH) issues += BuildPlanValidationIssue.BUILD_TOO_DEEP

        if (plan.components.isEmpty()) issues += BuildPlanValidationIssue.EMPTY_COMPONENTS
        if (plan.components.size > BuildPlanLimits.MAX_COMPONENTS) {
            issues += BuildPlanValidationIssue.TOO_MANY_COMPONENTS
        }
        val componentIds = HashSet<String>(plan.components.size)
        plan.components.forEach { component ->
            if (!COMPONENT_ID_PATTERN.matches(component.componentId) ||
                component.name.isBlank() || component.name.length > BuildPlanLimits.MAX_COMPONENT_NAME_LENGTH ||
                component.purpose.isBlank() || component.purpose.length > BuildPlanLimits.MAX_COMPONENT_PURPOSE_LENGTH
            ) {
                issues += BuildPlanValidationIssue.INVALID_COMPONENT
            }
            if (!componentIds.add(component.componentId)) {
                issues += BuildPlanValidationIssue.DUPLICATE_COMPONENT_ID
            }
            component.bounds?.let { bounds ->
                if (!boundsFit(bounds, dimensions)) issues += BuildPlanValidationIssue.INVALID_COMPONENT_BOUNDS
            }
        }

        if (plan.operations.isEmpty()) issues += BuildPlanValidationIssue.EMPTY_OPERATIONS
        if (plan.operations.size > BuildPlanLimits.MAX_OPERATIONS) {
            issues += BuildPlanValidationIssue.TOO_MANY_OPERATIONS
        }
        val coordinates = HashSet<BlockPosition>(minOf(plan.operations.size, BuildPlanLimits.MAX_OPERATIONS))
        plan.operations.forEachIndexed { index, operation ->
            if (operation.sequence != index) issues += BuildPlanValidationIssue.INVALID_OPERATION_ORDER
            if (operation.kind != BuildPlanOperationKind.PLACE_BLOCK) {
                issues += BuildPlanValidationIssue.UNSUPPORTED_OPERATION
            }
            if (operation.blockId.isBlank()) {
                issues += BuildPlanValidationIssue.EMPTY_BLOCK_ID
            } else if (!blockCatalog.supports(operation.blockId)) {
                issues += BuildPlanValidationIssue.INVALID_BLOCK_ID
            }
            if (!blockCatalog.validState(operation.blockId, operation.blockState)) {
                issues += BuildPlanValidationIssue.INVALID_BLOCK_STATE
            }
            if (!coordinateFits(operation.position, dimensions)) {
                issues += BuildPlanValidationIssue.INVALID_COORDINATE
            }
            if (!coordinates.add(operation.position)) {
                issues += BuildPlanValidationIssue.DUPLICATE_COORDINATE
            }
            operation.componentId?.let { componentId ->
                if (componentId !in componentIds) issues += BuildPlanValidationIssue.UNKNOWN_COMPONENT
            }
        }

        return if (issues.isEmpty()) {
            BuildPlanValidationResult.Valid(
                ValidatedBuildPlan(plan.copy(status = BuildStatus.READY)),
            )
        } else {
            BuildPlanValidationResult.Invalid(issues)
        }
    }

    private fun boundsFit(bounds: BlockBounds, dimensions: BuildDimensions): Boolean {
        val size = bounds.dimensions
        val origin = bounds.origin
        return size.width > 0 && size.height > 0 && size.depth > 0 &&
            origin.x >= 0 && origin.y >= 0 && origin.z >= 0 &&
            origin.x.toLong() + size.width <= dimensions.width.toLong() &&
            origin.y.toLong() + size.height <= dimensions.height.toLong() &&
            origin.z.toLong() + size.depth <= dimensions.depth.toLong()
    }

    private fun coordinateFits(position: BlockPosition, dimensions: BuildDimensions): Boolean =
        position.x >= 0 && position.x < dimensions.width &&
            position.y >= 0 && position.y < dimensions.height &&
            position.z >= 0 && position.z < dimensions.depth

    private companion object {
        val BUILD_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,${BuildPlanLimits.MAX_BUILD_ID_LENGTH}}")
        val COMPONENT_ID_PATTERN = Regex("[a-z0-9_-]{1,48}")
    }
}
