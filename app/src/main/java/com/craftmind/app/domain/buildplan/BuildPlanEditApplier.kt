package com.craftmind.app.domain.buildplan

enum class BuildPlanEditApplyError {
    INVALID_SUMMARY,
    INVALID_COMPONENT_REFERENCE,
    INVALID_COMPONENT_SET,
    REPLACEMENT_CONTEXT_UNAVAILABLE,
    INVALID_REPLACEMENT_OPERATIONS,
    RESULTING_PLAN_TOO_LARGE,
}

sealed interface BuildPlanEditApplyResult {
    data class Applied(val candidate: BuildPlan) : BuildPlanEditApplyResult
    data class Invalid(val error: BuildPlanEditApplyError) : BuildPlanEditApplyResult
}

/** Applies an AI-authored component patch to an immutable base, preserving all unmentioned data. */
class BuildPlanEditApplier {
    fun apply(
        base: BuildPlan,
        edit: AiBuildEditDocument,
        providerId: String,
        modelId: String,
        generatedAtEpochMillis: Long,
        componentsWithFullOperationContext: Set<String>,
    ): BuildPlanEditApplyResult {
        if (edit.editSummary.isBlank() || edit.editSummary.length > MAX_EDIT_SUMMARY_LENGTH) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_SUMMARY)
        }
        if (edit.targetComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            edit.preservedComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            edit.removedComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            edit.upsertComponents.size > BuildPlanLimits.MAX_COMPONENTS ||
            edit.replacementOperations.size > BuildPlanLimits.MAX_COMPONENTS
        ) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_COMPONENT_SET)
        }

        val baseById = base.components.associateBy(BuildPlanComponent::componentId)
        val upgradingLegacy = base.metadata.schemaVersion == BuildPlanLimits.LEGACY_SCHEMA_VERSION
        val targetIds = edit.targetComponentIds
        val preservedIds = edit.preservedComponentIds
        val removedIds = edit.removedComponentIds
        val upsertIds = edit.upsertComponents.map(AiBuildComponentDocument::componentId)
        val replacementIds = edit.replacementOperations.map(AiComponentOperationSetDocument::componentId)
        if (targetIds.isEmpty() || !targetIds.all(COMPONENT_ID_PATTERN::matches) ||
            targetIds.distinct().size != targetIds.size ||
            preservedIds.distinct().size != preservedIds.size ||
            removedIds.distinct().size != removedIds.size ||
            upsertIds.distinct().size != upsertIds.size ||
            replacementIds.distinct().size != replacementIds.size ||
            targetIds.any { it !in baseById && it !in upsertIds } ||
            preservedIds.any { it !in baseById || it in targetIds } ||
            removedIds.any { it !in baseById || it !in targetIds } ||
            upsertIds.any { it !in targetIds && !(upgradingLegacy && it in baseById) } ||
            replacementIds.any { it !in targetIds }
        ) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_COMPONENT_REFERENCE)
        }
        if (removedIds.any { it in upsertIds } || removedIds.any { it in replacementIds }) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_COMPONENT_SET)
        }
        if (upgradingLegacy) {
            val retainedLegacyIds = baseById.keys - removedIds.toSet()
            val untouchedLegacyIds = retainedLegacyIds - targetIds.toSet()
            val semanticUpserts = edit.upsertComponents.associateBy(AiBuildComponentDocument::componentId)
            if (edit.intent == null ||
                retainedLegacyIds.any { it !in upsertIds } ||
                untouchedLegacyIds.any { it !in preservedIds } ||
                untouchedLegacyIds.any { id ->
                    val previous = baseById.getValue(id)
                    val updated = semanticUpserts[id]?.toDomain()
                    updated == null || updated.name != previous.name || updated.purpose != previous.purpose
                } ||
                base.operations.any { it.componentId == null }
            ) {
                return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_COMPONENT_SET)
            }
        }

        val resultingComponentIds = (baseById.keys - removedIds.toSet()) + upsertIds
        if (resultingComponentIds.size > BuildPlanLimits.MAX_COMPONENTS ||
            resultingComponentIds.size != resultingComponentIds.distinct().size
        ) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_COMPONENT_SET)
        }
        if (edit.replacementOperations.any { replacement ->
                replacement.componentId !in resultingComponentIds ||
                    replacement.operations.isEmpty() ||
                    replacement.operations.size > BuildPlanLimits.MAX_OPERATIONS ||
                    replacement.operations.any { operation -> operation.componentId != replacement.componentId }
            }
        ) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_REPLACEMENT_OPERATIONS)
        }
        val newComponentIds = upsertIds.filter { it !in baseById }
        val attemptedExistingReplacement = replacementIds.filter { it in baseById }
        if (attemptedExistingReplacement.any { it !in componentsWithFullOperationContext }) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.REPLACEMENT_CONTEXT_UNAVAILABLE)
        }
        if (newComponentIds.any { it !in replacementIds }) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_REPLACEMENT_OPERATIONS)
        }
        if (edit.title != null && (edit.title.isBlank() || edit.title.length > BuildPlanLimits.MAX_TITLE_LENGTH) ||
            edit.description != null && (edit.description.isBlank() || edit.description.length > BuildPlanLimits.MAX_DESCRIPTION_LENGTH)
        ) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.INVALID_SUMMARY)
        }

        val upserts = edit.upsertComponents.associate { it.componentId to it.toDomain() }
        val mergedComponents = base.components.filterNot { it.componentId in removedIds }
            .map { old -> upserts[old.componentId] ?: old }
            .toMutableList()
        edit.upsertComponents.filter { it.componentId !in baseById }.forEach { mergedComponents += it.toDomain() }

        val replacementMap = edit.replacementOperations.associate { replacement ->
            replacement.componentId to replacement.operations.map { operation -> operation.toDomain() }
        }
        val mergedOperations = if (base.metadata.schemaVersion == BuildPlanLimits.CURRENT_SCHEMA_VERSION || upgradingLegacy) {
            val orderedComponents = mergedComponents.sortedBy(BuildPlanComponent::constructionOrder)
            orderedComponents.flatMap { component ->
                replacementMap[component.componentId] ?: base.operations
                    .filter { it.componentId == component.componentId }
                    .map { it.copy(sequence = 0) }
            }
        } else {
            val emittedReplacementIds = mutableSetOf<String>()
            buildList {
                base.operations.forEach { operation ->
                    val id = operation.componentId
                    if (id == null || id !in removedIds) {
                        if (id != null && id in replacementMap) {
                            if (emittedReplacementIds.add(id)) addAll(replacementMap.getValue(id))
                        } else {
                            add(operation)
                        }
                    }
                }
                replacementMap.forEach { (id, operations) ->
                    if (emittedReplacementIds.add(id)) addAll(operations)
                }
            }
        }
        if (mergedOperations.size > BuildPlanLimits.MAX_OPERATIONS) {
            return BuildPlanEditApplyResult.Invalid(BuildPlanEditApplyError.RESULTING_PLAN_TOO_LARGE)
        }

        val title = edit.title ?: base.metadata.title
        val description = edit.description ?: base.metadata.summary
        val dimensions = edit.dimensions ?: base.metadata.dimensions
        val candidate = BuildPlan(
            planId = base.planId,
            metadata = base.metadata.copy(
                schemaVersion = if (upgradingLegacy) BuildPlanLimits.CURRENT_SCHEMA_VERSION else base.metadata.schemaVersion,
                providerId = providerId,
                modelId = modelId,
                title = title,
                summary = description,
                generatedAtEpochMillis = generatedAtEpochMillis,
                dimensions = dimensions,
                intent = edit.intent?.toDomain() ?: base.metadata.intent,
            ),
            originStrategy = base.originStrategy,
            components = mergedComponents,
            operations = mergedOperations.mapIndexed { index, operation -> operation.copy(sequence = index) },
            status = BuildStatus.DRAFT,
        )
        return BuildPlanEditApplyResult.Applied(candidate)
    }

    private fun AiBlockOperationDocument.toDomain() = BuildPlanOperation(
        sequence = 0,
        kind = BuildPlanOperationKind.PLACE_BLOCK,
        blockId = blockId,
        position = BlockPosition(x, y, z),
        blockState = blockState,
        componentId = componentId,
    )

    private companion object {
        const val MAX_EDIT_SUMMARY_LENGTH = 240
        val COMPONENT_ID_PATTERN = Regex("[a-z0-9_-]{1,48}")
    }
}
