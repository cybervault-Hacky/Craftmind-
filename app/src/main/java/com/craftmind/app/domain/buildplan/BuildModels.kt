package com.craftmind.app.domain.buildplan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Validated user input to the generation pipeline. The Phase 2 engine rejects unsupported
 * references before sending anything to a provider; it never silently drops them.
 */
data class BuildRequest(
    val requestId: String,
    val prompt: String,
    val imageReference: BuildInput.ImageReference?,
    val urlReference: BuildInput.UrlReference?,
    val createdAtEpochMillis: Long,
) {
    fun inputs(): List<BuildInput> = buildList {
        add(BuildInput.Text(prompt))
        imageReference?.let { add(it) }
        urlReference?.let { add(it) }
    }

    companion object {
        internal fun create(
            prompt: String,
            imageReference: BuildInput.ImageReference?,
            urlReference: BuildInput.UrlReference?,
            nowEpochMillis: () -> Long,
            requestId: () -> String,
        ): BuildRequest = BuildRequest(
            requestId = requestId(),
            prompt = prompt,
            imageReference = imageReference,
            urlReference = urlReference,
            createdAtEpochMillis = nowEpochMillis(),
        )
    }
}

data class BuildRequestDraft(
    val prompt: String,
    val imageReference: BuildInput.ImageReference? = null,
    val urlReference: BuildInput.UrlReference? = null,
)

sealed interface BuildInput {
    data class Text(val value: String) : BuildInput

    data class ImageReference(
        val contentUri: String,
        val mediaType: String,
        val sizeBytes: Long?,
        val displayName: String? = null,
    ) : BuildInput

    data class UrlReference(val url: String) : BuildInput
}

@Serializable
enum class BuildStatus {
    DRAFT,
    GENERATING,
    READY,
    BUILDING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

@Serializable
data class BuildPlan(
    val planId: String,
    val metadata: BuildPlanMetadata,
    val originStrategy: BuildOriginStrategy,
    val components: List<BuildPlanComponent>,
    val operations: List<BuildPlanOperation>,
    val status: BuildStatus,
)

@Serializable
enum class BuildOriginStrategy {
    CENTERED_GROUND,
    WORLD_ORIGIN,
}

@Serializable
data class BuildPlanMetadata(
    val schemaVersion: Int,
    val sourceRequestId: String,
    val providerId: String,
    val modelId: String,
    val title: String,
    val summary: String,
    val generatedAtEpochMillis: Long,
    val dimensions: BuildDimensions,
)

@Serializable
data class BuildPlanComponent(
    val componentId: String,
    val name: String,
    val purpose: String,
    val bounds: BlockBounds? = null,
)

/** One placement in local, non-negative plan coordinates. */
@Serializable
data class BuildPlanOperation(
    val sequence: Int,
    val kind: BuildPlanOperationKind,
    val blockId: String,
    val position: BlockPosition,
    val blockState: Map<String, String> = emptyMap(),
    val componentId: String? = null,
)

@Serializable
enum class BuildPlanOperationKind {
    PLACE_BLOCK,
    REMOVE_BLOCK,
}

@Serializable
data class BlockPosition(val x: Int, val y: Int, val z: Int)

@Serializable
data class BuildDimensions(val width: Int, val height: Int, val depth: Int)

@Serializable
data class BlockBounds(
    val origin: BlockPosition,
    val dimensions: BuildDimensions,
)

/** Versioned wire document returned verbatim by an AI provider before domain metadata is added. */
@Serializable
data class AiBuildPlanDocument(
    val schemaVersion: Int,
    @SerialName("buildId") val buildId: String,
    val title: String,
    val description: String,
    val dimensions: BuildDimensions,
    val originStrategy: BuildOriginStrategy,
    val components: List<BuildPlanComponent>,
    val operations: List<AiBlockOperationDocument>,
)

@Serializable
data class AiBlockOperationDocument(
    val x: Int,
    val y: Int,
    val z: Int,
    val blockId: String,
    val blockState: Map<String, String>,
    val componentId: String,
)

/** The provider validator creates this wrapper only after a complete plan passes all limits. */
data class ValidatedBuildPlan internal constructor(val plan: BuildPlan)

/** Minecraft execution will require explicit user review of a validated, AI-generated plan. */
data class ReviewedBuildPlan internal constructor(
    val validatedPlan: ValidatedBuildPlan,
    val reviewedAtEpochMillis: Long,
)

internal fun AiBuildPlanDocument.toDomainPlan(
    requestId: String,
    providerId: String,
    modelId: String,
    generatedAtEpochMillis: Long,
): BuildPlan = BuildPlan(
    planId = buildId,
    metadata = BuildPlanMetadata(
        schemaVersion = schemaVersion,
        sourceRequestId = requestId,
        providerId = providerId,
        modelId = modelId,
        title = title,
        summary = description,
        generatedAtEpochMillis = generatedAtEpochMillis,
        dimensions = dimensions,
    ),
    originStrategy = originStrategy,
    components = components,
    operations = operations.mapIndexed { index, operation ->
        BuildPlanOperation(
            sequence = index,
            kind = BuildPlanOperationKind.PLACE_BLOCK,
            blockId = operation.blockId,
            position = BlockPosition(operation.x, operation.y, operation.z),
            blockState = operation.blockState,
            componentId = operation.componentId,
        )
    },
    status = BuildStatus.DRAFT,
)
