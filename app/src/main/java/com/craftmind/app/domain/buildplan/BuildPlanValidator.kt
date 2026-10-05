package com.craftmind.app.domain.buildplan

/** Central limits apply to provider output, local history, edits, and the future bridge boundary. */
object BuildPlanLimits {
    const val LEGACY_SCHEMA_VERSION = 1
    const val CURRENT_SCHEMA_VERSION = 2
    const val EDIT_SCHEMA_VERSION = 1
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
    const val MAX_BUILD_INTENT_TEXT_LENGTH = 100
    const val MAX_BUILD_INTENT_ITEMS = 24
    const val MAX_BUILD_INTENT_ITEM_LENGTH = 80
    const val MAX_BLOCK_STATE_PROPERTIES = 8
    const val MAX_BLOCK_STATE_VALUE_LENGTH = 32
    const val MAX_EDIT_INSTRUCTION_LENGTH = 800
    const val MAX_REFINEMENT_CONTEXT_BYTES = 96 * 1024
    const val MAX_CONTEXT_OPERATIONS = 768
    const val MAX_CONTEXT_SAMPLE_OPERATIONS_PER_COMPONENT = 2
    const val MAX_REVISIONS_PER_BUILD = 32
    const val MAX_BUILDS_IN_HISTORY = 20
    const val MAX_HISTORY_FILE_BYTES = 20 * 1024 * 1024
}

enum class BuildPlanValidationIssue {
    EMPTY_PLAN_ID,
    INVALID_PLAN_ID,
    UNSUPPORTED_SCHEMA_VERSION,
    MISSING_SOURCE_REQUEST,
    MISSING_PROVIDER_OR_MODEL,
    INVALID_TITLE_OR_DESCRIPTION,
    INVALID_INTENT,
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
    OPERATION_OUTSIDE_COMPONENT_BOUNDS,
    INVALID_COMPONENT_PARENT,
    COMPONENT_PARENT_CYCLE,
    INVALID_CONSTRUCTION_ORDER,
    MISSING_COMPONENT_OPERATIONS,
    INVALID_OPERATION_ORDER,
    UNSUPPORTED_OPERATION,
    INVALID_PLAN_STATUS,
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

/** Strict, deterministic validation; provider output is rejected rather than repaired. */
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
        val isLegacy = metadata.schemaVersion == BuildPlanLimits.LEGACY_SCHEMA_VERSION
        val isCurrent = metadata.schemaVersion == BuildPlanLimits.CURRENT_SCHEMA_VERSION

        when {
            plan.planId.isBlank() -> issues += BuildPlanValidationIssue.EMPTY_PLAN_ID
            plan.planId.length > BuildPlanLimits.MAX_BUILD_ID_LENGTH || !BUILD_ID_PATTERN.matches(plan.planId) ->
                issues += BuildPlanValidationIssue.INVALID_PLAN_ID
        }
        if (!isLegacy && !isCurrent) issues += BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION
        if (metadata.sourceRequestId.isBlank()) issues += BuildPlanValidationIssue.MISSING_SOURCE_REQUEST
        if (metadata.providerId.isBlank() || metadata.modelId.isBlank()) {
            issues += BuildPlanValidationIssue.MISSING_PROVIDER_OR_MODEL
        }
        if (metadata.title.isBlank() || metadata.title.length > BuildPlanLimits.MAX_TITLE_LENGTH ||
            metadata.summary.isBlank() || metadata.summary.length > BuildPlanLimits.MAX_DESCRIPTION_LENGTH
        ) {
            issues += BuildPlanValidationIssue.INVALID_TITLE_OR_DESCRIPTION
        }
        if (plan.status !in setOf(BuildStatus.DRAFT, BuildStatus.READY)) {
            issues += BuildPlanValidationIssue.INVALID_PLAN_STATUS
        }
        if (metadata.generatedAtEpochMillis <= 0L) issues += BuildPlanValidationIssue.INVALID_TITLE_OR_DESCRIPTION

        if (dimensions.width <= 0 || dimensions.height <= 0 || dimensions.depth <= 0) {
            issues += BuildPlanValidationIssue.INVALID_DIMENSIONS
        }
        if (dimensions.width > BuildPlanLimits.MAX_BUILD_WIDTH) issues += BuildPlanValidationIssue.BUILD_TOO_WIDE
        if (dimensions.height > BuildPlanLimits.MAX_BUILD_HEIGHT) issues += BuildPlanValidationIssue.BUILD_TOO_TALL
        if (dimensions.depth > BuildPlanLimits.MAX_BUILD_DEPTH) issues += BuildPlanValidationIssue.BUILD_TOO_DEEP

        if (isCurrent && metadata.intent == null) issues += BuildPlanValidationIssue.INVALID_INTENT
        metadata.intent?.let { intent ->
            if (!validIntent(intent)) issues += BuildPlanValidationIssue.INVALID_INTENT
        }

        if (plan.components.isEmpty()) issues += BuildPlanValidationIssue.EMPTY_COMPONENTS
        val componentsById = linkedMapOf<String, BuildPlanComponent>()
        val componentIds = HashSet<String>(plan.components.size)
        val componentOrders = HashSet<Int>(plan.components.size)
        plan.components.forEach { component ->
            if (!COMPONENT_ID_PATTERN.matches(component.componentId) ||
                component.name.isBlank() || component.name.length > BuildPlanLimits.MAX_COMPONENT_NAME_LENGTH ||
                component.purpose.isBlank() || component.purpose.length > BuildPlanLimits.MAX_COMPONENT_PURPOSE_LENGTH
            ) {
                issues += BuildPlanValidationIssue.INVALID_COMPONENT
            }
            if (!componentIds.add(component.componentId)) {
                issues += BuildPlanValidationIssue.DUPLICATE_COMPONENT_ID
            } else {
                componentsById[component.componentId] = component
            }
            if (isCurrent) {
                if (component.type == BuildComponentType.UNSPECIFIED || component.bounds == null ||
                    component.constructionOrder !in plan.components.indices ||
                    !componentOrders.add(component.constructionOrder)
                ) {
                    issues += BuildPlanValidationIssue.INVALID_CONSTRUCTION_ORDER
                }
            }
            component.bounds?.let { bounds ->
                if (!boundsFit(bounds, dimensions)) issues += BuildPlanValidationIssue.INVALID_COMPONENT_BOUNDS
            }
        }
        if (isCurrent && componentOrders.size != plan.components.size) {
            issues += BuildPlanValidationIssue.INVALID_CONSTRUCTION_ORDER
        }

        plan.components.forEach { component ->
            val parentId = component.parentComponentId
            if (parentId != null) {
                val parent = componentsById[parentId]
                if (parent == null || parentId == component.componentId ||
                    (isCurrent && parent.constructionOrder >= component.constructionOrder)
                ) {
                    issues += BuildPlanValidationIssue.INVALID_COMPONENT_PARENT
                } else if (isCurrent && parent.bounds != null && component.bounds != null &&
                    !boundsFitWithin(component.bounds, parent.bounds)
                ) {
                    issues += BuildPlanValidationIssue.INVALID_COMPONENT_BOUNDS
                }
            }
        }
        if (hasParentCycle(componentsById)) issues += BuildPlanValidationIssue.COMPONENT_PARENT_CYCLE

        if (plan.operations.isEmpty()) issues += BuildPlanValidationIssue.EMPTY_OPERATIONS
        val coordinates = HashSet<BlockPosition>(minOf(plan.operations.size, BuildPlanLimits.MAX_OPERATIONS))
        val operationCounts = mutableMapOf<String, Int>()
        var previousConstructionOrder = -1
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
            val componentId = operation.componentId
            if (componentId == null || componentId !in componentIds) {
                if (componentId != null) issues += BuildPlanValidationIssue.UNKNOWN_COMPONENT
                if (isCurrent) issues += BuildPlanValidationIssue.UNKNOWN_COMPONENT
            } else {
                operationCounts[componentId] = (operationCounts[componentId] ?: 0) + 1
                if (isCurrent) {
                    val component = componentsById[componentId]
                    val order = component?.constructionOrder ?: -1
                    if (order < previousConstructionOrder) issues += BuildPlanValidationIssue.INVALID_OPERATION_ORDER
                    previousConstructionOrder = order
                    if (component?.bounds?.let { bounds -> !positionWithin(operation.position, bounds) } == true) {
                        issues += BuildPlanValidationIssue.OPERATION_OUTSIDE_COMPONENT_BOUNDS
                    }
                }
            }
        }
        if (isCurrent && plan.components.any { (operationCounts[it.componentId] ?: 0) == 0 }) {
            issues += BuildPlanValidationIssue.MISSING_COMPONENT_OPERATIONS
        }

        return if (issues.isEmpty()) {
            BuildPlanValidationResult.Valid(ValidatedBuildPlan(plan.copy(status = BuildStatus.READY)))
        } else {
            BuildPlanValidationResult.Invalid(issues)
        }
    }

    private fun validIntent(intent: BuildIntent): Boolean =
        intent.structureType.isNotBlank() && intent.structureType.length <= BuildPlanLimits.MAX_BUILD_INTENT_TEXT_LENGTH &&
            listOfNotNull(intent.style, intent.approximateScale, intent.environment)
                .all { it.isNotBlank() && it.length <= BuildPlanLimits.MAX_BUILD_INTENT_TEXT_LENGTH } &&
            (intent.floorCount == null || intent.floorCount in 1..BuildPlanLimits.MAX_BUILD_HEIGHT) &&
            listOf(intent.rooms, intent.specialFeatures, intent.materials, intent.constraints).all { values ->
                values.size <= BuildPlanLimits.MAX_BUILD_INTENT_ITEMS && values.all { value ->
                    value.isNotBlank() && value.length <= BuildPlanLimits.MAX_BUILD_INTENT_ITEM_LENGTH
                }
            }

    private fun hasParentCycle(components: Map<String, BuildPlanComponent>): Boolean {
        components.keys.forEach { start ->
            val seen = mutableSetOf<String>()
            var current: String? = start
            while (current != null) {
                if (!seen.add(current)) return true
                current = components[current]?.parentComponentId
            }
        }
        return false
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

    private fun boundsFitWithin(child: BlockBounds, parent: BlockBounds): Boolean =
        child.origin.x >= parent.origin.x && child.origin.y >= parent.origin.y && child.origin.z >= parent.origin.z &&
            child.origin.x.toLong() + child.dimensions.width <= parent.origin.x.toLong() + parent.dimensions.width &&
            child.origin.y.toLong() + child.dimensions.height <= parent.origin.y.toLong() + parent.dimensions.height &&
            child.origin.z.toLong() + child.dimensions.depth <= parent.origin.z.toLong() + parent.dimensions.depth

    private fun coordinateFits(position: BlockPosition, dimensions: BuildDimensions): Boolean =
        position.x >= 0 && position.x < dimensions.width &&
            position.y >= 0 && position.y < dimensions.height &&
            position.z >= 0 && position.z < dimensions.depth

    private fun positionWithin(position: BlockPosition, bounds: BlockBounds): Boolean =
        position.x >= bounds.origin.x && position.x.toLong() < bounds.origin.x.toLong() + bounds.dimensions.width &&
            position.y >= bounds.origin.y && position.y.toLong() < bounds.origin.y.toLong() + bounds.dimensions.height &&
            position.z >= bounds.origin.z && position.z.toLong() < bounds.origin.z.toLong() + bounds.dimensions.depth

    private companion object {
        val BUILD_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,${BuildPlanLimits.MAX_BUILD_ID_LENGTH}}")
        val COMPONENT_ID_PATTERN = Regex("[a-z0-9_-]{1,48}")
    }
}
