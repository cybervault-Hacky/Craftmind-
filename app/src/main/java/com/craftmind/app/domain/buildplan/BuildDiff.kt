package com.craftmind.app.domain.buildplan

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class ModifiedBuildComponent(
    val before: BuildPlanComponent,
    val after: BuildPlanComponent,
)

@Serializable
data class ChangedBuildOperation(
    val before: BuildPlanOperation,
    val after: BuildPlanOperation,
)

@Serializable
enum class BuildDiffWarning {
    CHANGES_OUTSIDE_TARGET_COMPONENTS,
    DECLARED_PRESERVED_COMPONENT_CHANGED,
    LARGE_CHANGE_FOR_LOCALIZED_REQUEST,
}

/** A deterministic, position-based comparison; it never asserts that a world was modified. */
@Serializable
data class BuildDiff(
    val summary: String,
    val addedComponents: List<BuildPlanComponent>,
    val removedComponents: List<BuildPlanComponent>,
    val modifiedComponents: List<ModifiedBuildComponent>,
    val addedOperations: List<BuildPlanOperation>,
    val removedOperations: List<BuildPlanOperation>,
    val changedOperations: List<ChangedBuildOperation>,
    val dimensionsBefore: BuildDimensions,
    val dimensionsAfter: BuildDimensions,
    val titleChanged: Boolean = false,
    val descriptionChanged: Boolean = false,
    val intentChanged: Boolean = false,
    val warnings: List<BuildDiffWarning> = emptyList(),
) {
    val operationChangeCount: Int
        get() = addedOperations.size + removedOperations.size + changedOperations.size

    val hasChanges: Boolean
        get() = addedComponents.isNotEmpty() || removedComponents.isNotEmpty() || modifiedComponents.isNotEmpty() ||
            addedOperations.isNotEmpty() || removedOperations.isNotEmpty() || changedOperations.isNotEmpty() ||
            dimensionsBefore != dimensionsAfter || titleChanged || descriptionChanged || intentChanged
}

class BuildDiffCalculator {
    fun compare(
        before: BuildPlan,
        after: BuildPlan,
        summary: String,
        targetComponentIds: List<String> = emptyList(),
        preservedComponentIds: List<String> = emptyList(),
        instruction: String = "",
    ): BuildDiff {
        val beforeComponents = before.components.associateBy { it.componentId }
        val afterComponents = after.components.associateBy { it.componentId }
        val addedComponents = after.components.filter { it.componentId !in beforeComponents }
        val removedComponents = before.components.filter { it.componentId !in afterComponents }
        val modifiedComponents = before.components.mapNotNull { old ->
            val new = afterComponents[old.componentId]
            new?.takeIf { semanticComponent(old) != semanticComponent(it) }?.let { ModifiedBuildComponent(old, it) }
        }

        val beforeOperations = before.operations.associateBy { it.position }
        val afterOperations = after.operations.associateBy { it.position }
        val addedOperations = after.operations.filter { it.position !in beforeOperations }
        val removedOperations = before.operations.filter { it.position !in afterOperations }
        val changedOperations = before.operations.mapNotNull { old ->
            val new = afterOperations[old.position]
            new?.takeIf { operationContent(old) != operationContent(it) }?.let { ChangedBuildOperation(old, it) }
        }

        val touchedIds = buildSet {
            addedComponents.forEach { add(it.componentId) }
            removedComponents.forEach { add(it.componentId) }
            modifiedComponents.forEach { add(it.before.componentId) }
            (addedOperations + removedOperations).mapNotNullTo(this) { it.componentId }
            changedOperations.forEach {
                it.before.componentId?.let(::add)
                it.after.componentId?.let(::add)
            }
        }
        val warnings = buildList {
            val unexpectedTouchedIds = touchedIds.filterNot { id -> isLegacySemanticBackfill(before, after, id) }
            val localTargetHints = inferLocalTargetHints(before, instruction)
            val declaredScopeViolation = targetComponentIds.isNotEmpty() && unexpectedTouchedIds.any { it !in targetComponentIds }
            val localScopeViolation = localTargetHints.isNotEmpty() && unexpectedTouchedIds.any { id ->
                id in beforeComponents && id !in localTargetHints
            }
            if (declaredScopeViolation || localScopeViolation) {
                add(BuildDiffWarning.CHANGES_OUTSIDE_TARGET_COMPONENTS)
            }
            val changedPreserved = preservedComponentIds.any { id ->
                (beforeComponents[id]?.let { old -> afterComponents[id]?.let { semanticComponent(old) != semanticComponent(it) } ?: true } != false &&
                    !isLegacySemanticBackfill(before, after, id)) ||
                    operationsFor(before, id).map(::operationContent) != operationsFor(after, id).map(::operationContent)
            }
            if (changedPreserved) add(BuildDiffWarning.DECLARED_PRESERVED_COMPONENT_CHANGED)

            val localized = LOCALIZED_SCOPE_PATTERN.containsMatchIn(instruction)
            val denominator = maxOf(before.operations.size, after.operations.size)
            if (localized && denominator > 0 && operationChanges(before, after) * 2 > denominator) {
                add(BuildDiffWarning.LARGE_CHANGE_FOR_LOCALIZED_REQUEST)
            }
        }.distinct()

        return BuildDiff(
            summary = summary.take(MAX_DIFF_SUMMARY_LENGTH),
            addedComponents = addedComponents,
            removedComponents = removedComponents,
            modifiedComponents = modifiedComponents,
            addedOperations = addedOperations,
            removedOperations = removedOperations,
            changedOperations = changedOperations,
            dimensionsBefore = before.metadata.dimensions,
            dimensionsAfter = after.metadata.dimensions,
            titleChanged = before.metadata.title != after.metadata.title,
            descriptionChanged = before.metadata.summary != after.metadata.summary,
            intentChanged = before.metadata.intent != after.metadata.intent,
            warnings = warnings,
        )
    }

    private fun operationChanges(before: BuildPlan, after: BuildPlan): Int {
        val first = before.operations.associateBy { it.position }
        val second = after.operations.associateBy { it.position }
        val removed = first.filter { (position, old) -> second[position]?.let { operationContent(it) != operationContent(old) } ?: true }.size
        val added = second.filter { (position, new) -> first[position]?.let { operationContent(it) != operationContent(new) } ?: true }.size
        return maxOf(removed, added)
    }

    private fun operationsFor(plan: BuildPlan, componentId: String): List<BuildPlanOperation> =
        plan.operations.filter { it.componentId == componentId }.sortedBy { it.sequence }

    /** Cross-checks AI-declared scope against component names/purposes using local text only. */
    private fun inferLocalTargetHints(plan: BuildPlan, instruction: String): Set<String> {
        val requestTerms = tokenize(instruction)
        if (requestTerms.isEmpty()) return emptySet()
        return plan.components.filter { component ->
            tokenize("${component.name} ${component.purpose} ${component.type.name}").any { componentTerm ->
                requestTerms.any { requestTerm ->
                    requestTerm == componentTerm || requestTerm.startsWith(componentTerm) || componentTerm.startsWith(requestTerm)
                }
            }
        }.mapTo(linkedSetOf(), BuildPlanComponent::componentId)
    }

    private fun tokenize(text: String): Set<String> = TARGET_TOKEN_PATTERN.findAll(text.lowercase(Locale.ROOT))
        .map { it.value.removeSuffix("s").removeSuffix("es") }
        .filter { it.length >= 3 }
        .toSet()

    /** Semantic fields filled while upgrading a v1 record do not masquerade as unrelated design edits. */
    private fun isLegacySemanticBackfill(before: BuildPlan, after: BuildPlan, componentId: String): Boolean {
        if (before.metadata.schemaVersion != BuildPlanLimits.LEGACY_SCHEMA_VERSION ||
            after.metadata.schemaVersion != BuildPlanLimits.CURRENT_SCHEMA_VERSION
        ) return false
        val old = before.components.firstOrNull { it.componentId == componentId } ?: return false
        val new = after.components.firstOrNull { it.componentId == componentId } ?: return false
        return old.type == BuildComponentType.UNSPECIFIED && old.parentComponentId == null &&
            old.constructionOrder == 0 && old.name == new.name && old.purpose == new.purpose &&
            (old.bounds == null || old.bounds == new.bounds) &&
            operationsFor(before, componentId).map(::operationContent) == operationsFor(after, componentId).map(::operationContent)
    }

    private fun semanticComponent(component: BuildPlanComponent): List<Any?> = listOf(
        component.componentId,
        component.type,
        component.name,
        component.purpose,
        component.bounds,
        component.parentComponentId,
        component.constructionOrder,
    )

    private fun operationContent(operation: BuildPlanOperation): List<Any?> = listOf(
        operation.kind,
        operation.blockId,
        operation.position,
        operation.blockState.toSortedMap(),
        operation.componentId,
    )

    private companion object {
        const val MAX_DIFF_SUMMARY_LENGTH = 240
        val TARGET_TOKEN_PATTERN = Regex("[a-z0-9]+")
        val LOCALIZED_SCOPE_PATTERN = Regex("\\b(only|just|solely|specifically)\\b", RegexOption.IGNORE_CASE)
    }
}
