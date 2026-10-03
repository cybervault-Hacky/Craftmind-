package com.craftmind.app.domain.planning

import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.model.BlockCoordinate
import com.craftmind.app.domain.model.BuildPlan
import com.craftmind.app.domain.model.BuildPlanDraft
import com.craftmind.app.domain.model.BuildPlanValidationIssue
import com.craftmind.app.domain.model.PlanGenerator

sealed interface BuildPlanValidationResult {
    data class Valid(val plan: BuildPlan) : BuildPlanValidationResult
    data class Invalid(val issue: BuildPlanValidationIssue) : BuildPlanValidationResult
}

class BuildPlanValidator {
    fun validate(
        draft: BuildPlanDraft,
        planId: String,
        generatedAtEpochMillis: Long,
        providerId: String,
        modelId: String,
    ): BuildPlanValidationResult {
        if (draft.schemaVersion != BuildLimits.PLAN_SCHEMA_VERSION) {
            return invalid(BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION)
        }
        if (planId.isBlank() || planId.length > BuildLimits.MAX_PLAN_ID_CHARACTERS) {
            return invalid(BuildPlanValidationIssue.INVALID_PLAN_ID)
        }
        if (providerId.isBlank() || providerId.length > BuildLimits.MAX_PROVIDER_ID_CHARACTERS ||
            !providerId.matches(Regex("[a-z][a-z0-9_-]{0,${BuildLimits.MAX_PROVIDER_ID_CHARACTERS - 1}}"))
        ) {
            return invalid(BuildPlanValidationIssue.INVALID_GENERATOR)
        }
        if (!isValidModelId(modelId)) return invalid(BuildPlanValidationIssue.INVALID_GENERATOR)
        if (draft.title.isBlank()) return invalid(BuildPlanValidationIssue.EMPTY_TITLE)
        if (draft.title.length > BuildLimits.MAX_PLAN_TITLE_CHARACTERS) {
            return invalid(BuildPlanValidationIssue.TITLE_TOO_LONG)
        }
        if (draft.style.isBlank()) return invalid(BuildPlanValidationIssue.EMPTY_STYLE)
        if (draft.style.length > BuildLimits.MAX_STYLE_CHARACTERS) {
            return invalid(BuildPlanValidationIssue.STYLE_TOO_LONG)
        }

        val dimensions = draft.dimensions
        if (dimensions.width !in 1..BuildLimits.MAX_WIDTH ||
            dimensions.length !in 1..BuildLimits.MAX_LENGTH ||
            dimensions.height !in 1..BuildLimits.MAX_HEIGHT
        ) {
            return invalid(BuildPlanValidationIssue.INVALID_DIMENSIONS)
        }
        val volume = dimensions.width.toLong() * dimensions.length * dimensions.height
        if (volume > BuildLimits.MAX_BOUNDS_VOLUME) {
            return invalid(BuildPlanValidationIssue.PLAN_VOLUME_TOO_LARGE)
        }
        if (!isValidOrigin(draft.origin)) return invalid(BuildPlanValidationIssue.INVALID_ORIGIN)

        if (draft.materials.isEmpty()) return invalid(BuildPlanValidationIssue.INVALID_MATERIAL_COUNT)
        if (draft.materials.size > BuildLimits.MAX_MATERIAL_TYPES) {
            return invalid(BuildPlanValidationIssue.TOO_MANY_MATERIAL_TYPES)
        }
        val materialCounts = LinkedHashMap<String, Int>()
        for (material in draft.materials) {
            if (material.count <= 0 || material.count > BuildLimits.MAX_OPERATION_COUNT) {
                return invalid(BuildPlanValidationIssue.INVALID_MATERIAL_COUNT)
            }
            if (material.blockIdentifier !in SupportedMaterials.blockIdentifiers) {
                return invalid(BuildPlanValidationIssue.UNSUPPORTED_MATERIAL)
            }
            if (materialCounts.put(material.blockIdentifier, material.count) != null) {
                return invalid(BuildPlanValidationIssue.DUPLICATE_MATERIAL)
            }
        }

        if (draft.steps.isEmpty() || draft.steps.size > BuildLimits.MAX_PLAN_STEPS) {
            return invalid(BuildPlanValidationIssue.INVALID_STEP_COUNT)
        }
        val stepIds = HashSet<String>(draft.steps.size)
        for (step in draft.steps) {
            if (!step.id.matches(Regex("[a-z][a-z0-9_-]{0,${BuildLimits.MAX_STEP_ID_CHARACTERS - 1}}")) ||
                step.title.isBlank() || step.title.length > BuildLimits.MAX_STEP_TITLE_CHARACTERS ||
                step.description.isBlank() || step.description.length > BuildLimits.MAX_STEP_DESCRIPTION_CHARACTERS
            ) {
                return invalid(BuildPlanValidationIssue.INVALID_STEP)
            }
            if (!stepIds.add(step.id)) return invalid(BuildPlanValidationIssue.DUPLICATE_STEP_ID)
        }

        if (draft.operations.isEmpty()) return invalid(BuildPlanValidationIssue.EMPTY_OPERATIONS)
        if (draft.operations.size > BuildLimits.MAX_OPERATION_COUNT) {
            return invalid(BuildPlanValidationIssue.TOO_MANY_OPERATIONS)
        }
        if (draft.estimatedOperationCount != draft.operations.size) {
            return invalid(BuildPlanValidationIssue.OPERATION_COUNT_MISMATCH)
        }

        val occupied = HashSet<BlockCoordinate>(draft.operations.size)
        val actualMaterialCounts = HashMap<String, Int>()
        draft.operations.forEachIndexed { expectedSequence, operation ->
            if (operation.sequence != expectedSequence) {
                return invalid(BuildPlanValidationIssue.INVALID_OPERATION_ORDER)
            }
            val point = operation.position
            if (point.x !in 0 until dimensions.width ||
                point.y !in 0 until dimensions.height ||
                point.z !in 0 until dimensions.length
            ) {
                return invalid(BuildPlanValidationIssue.OPERATION_OUT_OF_BOUNDS)
            }
            if (!occupied.add(point)) return invalid(BuildPlanValidationIssue.DUPLICATE_COORDINATE)
            if (operation.block.identifier !in SupportedMaterials.blockIdentifiers) {
                return invalid(BuildPlanValidationIssue.UNSUPPORTED_BLOCK)
            }
            if (!isValidBlockProperties(operation.block.properties)) {
                return invalid(BuildPlanValidationIssue.INVALID_BLOCK_PROPERTIES)
            }
            if (operation.rotationDegrees?.let { it !in 0..359 || it % 90 != 0 } == true) {
                return invalid(BuildPlanValidationIssue.INVALID_ROTATION)
            }
            if (operation.stepId !in stepIds) return invalid(BuildPlanValidationIssue.INVALID_STEP_REFERENCE)
            if (operation.dependsOnSequences.size > BuildLimits.MAX_DEPENDENCIES_PER_OPERATION ||
                operation.dependsOnSequences.size != operation.dependsOnSequences.toSet().size ||
                operation.dependsOnSequences.any { it < 0 || it >= operation.sequence }
            ) {
                return invalid(BuildPlanValidationIssue.INVALID_DEPENDENCY)
            }
            actualMaterialCounts[operation.block.identifier] =
                (actualMaterialCounts[operation.block.identifier] ?: 0) + 1
        }

        if (materialCounts != actualMaterialCounts) {
            return invalid(BuildPlanValidationIssue.MATERIAL_TOTAL_MISMATCH)
        }

        val normalizedMaterials = draft.materials.sortedBy { it.blockIdentifier }
        val normalizedSteps = draft.steps.toList()
        val normalizedOperations = draft.operations.map { operation ->
            operation.copy(
                block = operation.block.copy(properties = operation.block.properties.toSortedMap()),
                dependsOnSequences = operation.dependsOnSequences.sorted(),
            )
        }
        return BuildPlanValidationResult.Valid(
            BuildPlan(
                id = planId,
                schemaVersion = draft.schemaVersion,
                title = draft.title.trim(),
                style = draft.style.trim(),
                dimensions = dimensions,
                origin = draft.origin,
                materials = normalizedMaterials,
                steps = normalizedSteps,
                operations = normalizedOperations,
                estimatedOperationCount = draft.estimatedOperationCount,
                generatedAtEpochMillis = generatedAtEpochMillis,
                generator = PlanGenerator(providerId, modelId),
            ),
        )
    }

    private fun isValidOrigin(origin: BlockCoordinate): Boolean =
        origin.x in -30_000_000..30_000_000 &&
            origin.z in -30_000_000..30_000_000 &&
            origin.y in -64..1_024

    private fun isValidBlockProperties(properties: Map<String, String>): Boolean {
        if (properties.size > BuildLimits.MAX_BLOCK_PROPERTIES) return false
        return properties.all { (key, value) ->
            key.matches(Regex("[a-z][a-z0-9_]{0,${BuildLimits.MAX_BLOCK_PROPERTY_NAME_CHARACTERS - 1}}")) &&
                value.isNotBlank() &&
                value.length <= BuildLimits.MAX_PROPERTY_VALUE_CHARACTERS &&
                value.matches(Regex("[A-Za-z0-9_-]+"))
        }
    }

    private fun isValidModelId(value: String): Boolean =
        value.length in 1..BuildLimits.MAX_MODEL_ID_CHARACTERS &&
            value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))

    private fun invalid(issue: BuildPlanValidationIssue) = BuildPlanValidationResult.Invalid(issue)
}
