package com.craftmind.app.domain.build

import java.util.UUID

/**
 * User input to the future generation pipeline. References stay local in Phase 1; a request is
 * constructed only for validation and preview and is never sent to a provider.
 */
data class BuildRequest(
    val requestId: String,
    val prompt: String,
    val imageReference: BuildInput.ImageReference?,
    val urlReference: BuildInput.UrlReference?,
    val createdAtEpochMillis: Long,
) {
    /** The provider-facing input sequence, kept independent of any one provider's API format. */
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

/** A normalized draft collected by the composer before request validation. */
data class BuildRequestDraft(
    val prompt: String,
    val imageReference: BuildInput.ImageReference? = null,
    val urlReference: BuildInput.UrlReference? = null,
)

/** Input variants the provider contract can consume. */
sealed interface BuildInput {
    data class Text(val value: String) : BuildInput

    /** Android document URI plus metadata; the file itself is not copied or uploaded in Phase 1. */
    data class ImageReference(
        val contentUri: String,
        val mediaType: String,
        val sizeBytes: Long?,
        val displayName: String? = null,
    ) : BuildInput

    /** A syntactically validated HTTP(S) reference. Phase 1 never fetches this URL. */
    data class UrlReference(val url: String) : BuildInput
}

/** The lifecycle of an AI-produced plan and its later execution. No plan is fabricated in Phase 1. */
enum class BuildStatus {
    DRAFT,
    GENERATING,
    READY,
    BUILDING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class BuildPlan(
    val planId: String,
    val metadata: BuildPlanMetadata,
    val components: List<BuildPlanComponent>,
    val operations: List<BuildPlanOperation>,
    val status: BuildStatus,
)

/** Created by the plan-validation boundary, not by an AI provider or presentation code. */
data class ValidatedBuildPlan internal constructor(val plan: BuildPlan)

/** User review is a distinct gate between validation and a future Minecraft execution request. */
data class ReviewedBuildPlan internal constructor(
    val validatedPlan: ValidatedBuildPlan,
    val reviewedAtEpochMillis: Long,
)

data class BuildPlanMetadata(
    val schemaVersion: Int,
    val sourceRequestId: String,
    val providerId: String,
    val modelId: String,
    val title: String,
    val summary: String,
    val generatedAtEpochMillis: Long,
    val dimensions: BuildDimensions? = null,
)

data class BuildPlanComponent(
    val componentId: String,
    val name: String,
    val purpose: String,
    val bounds: BlockBounds? = null,
)

data class BuildPlanOperation(
    val sequence: Int,
    val kind: BuildPlanOperationKind,
    val blockId: String,
    val position: BlockPosition,
    val componentId: String? = null,
)

enum class BuildPlanOperationKind {
    PLACE_BLOCK,
    REMOVE_BLOCK,
}

data class BlockPosition(val x: Int, val y: Int, val z: Int)

data class BuildDimensions(val width: Int, val height: Int, val depth: Int)

data class BlockBounds(
    val origin: BlockPosition,
    val dimensions: BuildDimensions,
)
