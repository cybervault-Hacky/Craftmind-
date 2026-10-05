package com.craftmind.app.domain.buildplan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Validated user input to the generation pipeline. */
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
    /** Null is valid only for plans created before semantic BuildPlan v2. */
    val intent: BuildIntent? = null,
)

/** AI-derived description of the user's construction intent; no app-side structure templates. */
@Serializable
data class BuildIntent(
    val structureType: String,
    val style: String? = null,
    val approximateScale: String? = null,
    val floorCount: Int? = null,
    val rooms: List<String> = emptyList(),
    val specialFeatures: List<String> = emptyList(),
    val materials: List<String> = emptyList(),
    val environment: String? = null,
    val constraints: List<String> = emptyList(),
)

@Serializable
enum class BuildComponentType {
    /** Honest marker used when reading Phase 2 v1 records that had no semantic type field. */
    UNSPECIFIED,
    BUILDING,
    FOUNDATION,
    FLOOR,
    ROOM,
    ROOF,
    INTERIOR_FEATURE,
    EXTERIOR_FEATURE,
    LANDSCAPE,
    UTILITY,
    DECORATION,
}

@Serializable
data class BuildPlanComponent(
    val componentId: String,
    val name: String,
    val purpose: String,
    val bounds: BlockBounds? = null,
    /** Defaults keep Phase 2 v1 local records readable; v2 validation requires semantic values. */
    val type: BuildComponentType = BuildComponentType.UNSPECIFIED,
    val parentComponentId: String? = null,
    val constructionOrder: Int = 0,
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

/** Required semantic component record used by the v2 provider wire schema. */
@Serializable
data class AiBuildComponentDocument(
    val componentId: String,
    val type: BuildComponentType,
    val name: String,
    val purpose: String,
    val bounds: BlockBounds,
    val parentComponentId: String?,
    val constructionOrder: Int,
) {
    fun toDomain(): BuildPlanComponent = BuildPlanComponent(
        componentId = componentId,
        name = name,
        purpose = purpose,
        bounds = bounds,
        type = type,
        parentComponentId = parentComponentId,
        constructionOrder = constructionOrder,
    )
}

/** All fields are required on the v2 wire; nullable fields must be present with null when unused. */
@Serializable
data class AiBuildIntentDocument(
    val structureType: String,
    val style: String?,
    val approximateScale: String?,
    val floorCount: Int?,
    val rooms: List<String>,
    val specialFeatures: List<String>,
    val materials: List<String>,
    val environment: String?,
    val constraints: List<String>,
) {
    fun toDomain(): BuildIntent = BuildIntent(
        structureType = structureType,
        style = style,
        approximateScale = approximateScale,
        floorCount = floorCount,
        rooms = rooms,
        specialFeatures = specialFeatures,
        materials = materials,
        environment = environment,
        constraints = constraints,
    )
}

/** Versioned BuildPlan v2 document returned by a provider before trusted metadata is added. */
@Serializable
data class AiBuildPlanDocument(
    val schemaVersion: Int,
    @SerialName("buildId") val buildId: String,
    val title: String,
    val description: String,
    val dimensions: BuildDimensions,
    val originStrategy: BuildOriginStrategy,
    val intent: AiBuildIntentDocument,
    val components: List<AiBuildComponentDocument>,
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

/** Compact semantic edit document. Unmentioned components and their blocks remain unchanged. */
@Serializable
data class AiBuildEditDocument(
    val schemaVersion: Int,
    val editSummary: String,
    val targetComponentIds: List<String>,
    val preservedComponentIds: List<String>,
    val removedComponentIds: List<String>,
    val upsertComponents: List<AiBuildComponentDocument>,
    val replacementOperations: List<AiComponentOperationSetDocument>,
    val title: String? = null,
    val description: String? = null,
    val dimensions: BuildDimensions? = null,
    val intent: AiBuildIntentDocument? = null,
)

@Serializable
data class AiComponentOperationSetDocument(
    val componentId: String,
    val operations: List<AiBlockOperationDocument>,
)

/** A validated BuildPlan wrapper can only be created after central validation succeeds. */
data class ValidatedBuildPlan internal constructor(val plan: BuildPlan)

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
        intent = intent.toDomain(),
    ),
    originStrategy = originStrategy,
    components = components.map(AiBuildComponentDocument::toDomain),
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
