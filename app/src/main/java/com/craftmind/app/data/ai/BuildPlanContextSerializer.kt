package com.craftmind.app.data.ai

import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.buildplan.BlockBounds
import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildEditRequest
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Locale

@Serializable
data class BuildPlanContextComponent(
    val componentId: String,
    val type: String,
    val name: String,
    val purpose: String,
    val bounds: com.craftmind.app.domain.buildplan.BlockBounds?,
    val parentComponentId: String?,
    val constructionOrder: Int,
    val operationCount: Int,
    val operationsIncludedCompletely: Boolean,
)

@Serializable
data class BuildPlanRefinementContextDocument(
    val schemaVersion: Int,
    val title: String,
    val description: String,
    val dimensions: com.craftmind.app.domain.buildplan.BuildDimensions,
    val intent: com.craftmind.app.domain.buildplan.BuildIntent?,
    val components: List<BuildPlanContextComponent>,
    val operationContext: List<BuildPlanOperation>,
    val originalWrittenRequest: String,
    val imageReferencePresentButUnavailable: Boolean,
    val imageAnalysisSource: BuildImageAnalysisSource?,
    val urlReferencePresentButUnavailable: Boolean,
    val contextNotice: String,
)

data class SerializedBuildPlanContext(
    val json: String,
    val fullOperationComponentIds: Set<String>,
    val targetComponentHints: List<String>,
    val estimatedInputTokens: Int,
)

sealed interface BuildPlanContextResult {
    data class Ready(val context: SerializedBuildPlanContext) : BuildPlanContextResult
    data object TooLarge : BuildPlanContextResult
}

/** Creates a bounded, disclosure-safe summary rather than sending an unbounded placement list. */
class BuildPlanContextSerializer(
    private val json: Json = Json { encodeDefaults = true },
) {
    fun serialize(request: BuildEditRequest, model: AiModel): BuildPlanContextResult {
        val plan = request.basePlan
        val operationsByComponent = plan.operations.groupBy { it.componentId }
        val hints = findTargetHints(plan, request.instruction)
        val allOperationsFit = plan.operations.size <= BuildPlanLimits.MAX_CONTEXT_OPERATIONS
        val broadEdit = BROAD_SCOPE_PATTERN.containsMatchIn(request.instruction)
        if (!allOperationsFit && broadEdit && hints.isEmpty()) return BuildPlanContextResult.TooLarge

        val fullyIncludedIds = when {
            allOperationsFit -> plan.components.mapTo(linkedSetOf()) { it.componentId }
            hints.isNotEmpty() -> hints.toSet()
            else -> emptySet()
        }
        val fullCount = fullyIncludedIds.sumOf { operationsByComponent[it].orEmpty().size }
        if (fullCount > BuildPlanLimits.MAX_CONTEXT_OPERATIONS) return BuildPlanContextResult.TooLarge

        val includedOperations = if (allOperationsFit) {
            plan.operations
        } else {
            val selected = mutableListOf<BuildPlanOperation>()
            plan.components.forEach { component ->
                val componentOperations = operationsByComponent[component.componentId].orEmpty().sortedBy { it.sequence }
                if (component.componentId in fullyIncludedIds) {
                    selected += componentOperations
                } else {
                    selected += componentOperations.take(BuildPlanLimits.MAX_CONTEXT_SAMPLE_OPERATIONS_PER_COMPONENT)
                }
            }
            selected.sortedBy(BuildPlanOperation::sequence)
        }

        val components = plan.components.map { component ->
            BuildPlanContextComponent(
                componentId = component.componentId,
                type = component.type.name,
                name = component.name,
                purpose = component.purpose,
                bounds = component.bounds ?: operationsByComponent[component.componentId].orEmpty().let(::derivedBounds),
                parentComponentId = component.parentComponentId,
                constructionOrder = component.constructionOrder,
                operationCount = operationsByComponent[component.componentId].orEmpty().size,
                operationsIncludedCompletely = component.componentId in fullyIncludedIds,
            )
        }
        val document = BuildPlanRefinementContextDocument(
            schemaVersion = plan.metadata.schemaVersion,
            title = plan.metadata.title,
            description = plan.metadata.summary,
            dimensions = plan.metadata.dimensions,
            intent = plan.metadata.intent,
            components = components,
            operationContext = includedOperations,
            originalWrittenRequest = request.originalRequest.prompt,
            imageReferencePresentButUnavailable = request.originalRequest.imageContentUri != null,
            imageAnalysisSource = request.originalRequest.imageAnalysisSource,
            urlReferencePresentButUnavailable = request.originalRequest.urlReference != null,
            contextNotice = buildList {
                add(if (allOperationsFit) {
                    "All existing placements are included."
                } else {
                    "Some operation details are samples only. Do not replace operations for a component unless its full operations are included. Unmentioned operations are retained locally."
                })
                if (request.originalRequest.imageContentUri != null) {
                    add(if (request.originalRequest.imageAnalysisSource != null) {
                        "The original image bytes are not included in refinement. Only the saved text-only visual notes are present; observed details may be wrong and inferred details are uncertain."
                    } else {
                        "The original image bytes and any visual analysis are unavailable to refinement. Do not claim to have seen the image."
                    })
                }
                if (request.originalRequest.urlReference != null) {
                    add("The original URL was not fetched; do not claim to know its contents.")
                }
                if (plan.metadata.schemaVersion == BuildPlanLimits.LEGACY_SCHEMA_VERSION) {
                    add("This legacy schema has no semantic intent or component categories. Its component bounds are derived locally from saved placements. Upgrade it to the current schema by returning semantic metadata for every retained component, but preserve untargeted names, purposes, and placements.")
                }
            }.joinToString(" "),
        )
        val serialized = json.encodeToString(document)
        val bytes = serialized.toByteArray(StandardCharsets.UTF_8)
        val estimatedTokens = (bytes.size + 2) / 3 + FIXED_PROMPT_TOKEN_BUDGET
        if (bytes.size > BuildPlanLimits.MAX_REFINEMENT_CONTEXT_BYTES ||
            (model.capabilities.maximumContextTokens != null && estimatedTokens > model.capabilities.maximumContextTokens)
        ) {
            return BuildPlanContextResult.TooLarge
        }
        return BuildPlanContextResult.Ready(
            SerializedBuildPlanContext(
                json = serialized,
                fullOperationComponentIds = fullyIncludedIds,
                targetComponentHints = hints,
                estimatedInputTokens = estimatedTokens,
            ),
        )
    }

    private fun findTargetHints(plan: BuildPlan, instruction: String): List<String> {
        val requestTerms = tokenize(instruction)
        if (requestTerms.isEmpty()) return emptyList()
        return plan.components.filter { component ->
            val terms = tokenize("${component.name} ${component.purpose} ${component.type.name}")
            terms.any { componentTerm -> requestTerms.any { requestTerm ->
                requestTerm == componentTerm || requestTerm.startsWith(componentTerm) || componentTerm.startsWith(requestTerm)
            } }
        }.map(BuildPlanComponent::componentId)
    }

    private fun tokenize(text: String): Set<String> = TOKEN_PATTERN.findAll(text.lowercase(Locale.ROOT))
        .map { match -> match.value.removeSuffix("s").removeSuffix("es") }
        .filter { it.length >= 3 }
        .toSet()

    private fun derivedBounds(operations: List<BuildPlanOperation>): BlockBounds? {
        if (operations.isEmpty()) return null
        val minX = operations.minOf { it.position.x }
        val minY = operations.minOf { it.position.y }
        val minZ = operations.minOf { it.position.z }
        val maxX = operations.maxOf { it.position.x }
        val maxY = operations.maxOf { it.position.y }
        val maxZ = operations.maxOf { it.position.z }
        return BlockBounds(
            origin = BlockPosition(minX, minY, minZ),
            dimensions = BuildDimensions(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1),
        )
    }

    private companion object {
        const val FIXED_PROMPT_TOKEN_BUDGET = 2_000
        val TOKEN_PATTERN = Regex("[a-z0-9]+")
        val BROAD_SCOPE_PATTERN = Regex(
            "\\b(entire build|whole build|whole structure|everything|all components|across the whole|" +
                "make it twice as large|double the size|add another floor|add a floor|another floor|" +
                "increase the overall|scale the whole)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
