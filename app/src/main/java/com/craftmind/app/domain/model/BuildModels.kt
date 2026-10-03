package com.craftmind.app.domain.model

import java.time.Instant

/** Inputs are local references until a later phase explicitly introduces processing. */
sealed interface ReferenceInput {
    data class Image(
        val uri: String,
        val mimeType: String,
        val displayName: String,
        val sizeBytes: Long,
    ) : ReferenceInput

    data class Url(val normalizedUrl: String) : ReferenceInput
}

data class BuildRequest(
    val prompt: String? = null,
    val references: List<ReferenceInput> = emptyList(),
) {
    val hasInput: Boolean
        get() = !prompt.isNullOrBlank() || references.isNotEmpty()
}

/** Integer world coordinates are relative to a future bridge-defined origin. */
data class BlockCoordinate(val x: Int, val y: Int, val z: Int)

data class BlockDimensions(val width: Int, val height: Int, val depth: Int)

data class BuildBounds(val min: BlockCoordinate, val max: BlockCoordinate)

data class BlockState(
    /** A namespaced material identifier, e.g. "minecraft:oak_planks". */
    val identifier: String,
    val properties: Map<String, String> = emptyMap(),
)

data class BuildStructure(
    val id: String,
    val name: String,
    val bounds: BuildBounds,
    val dependsOn: Set<String> = emptySet(),
)

data class BlockOperation(
    val sequence: Int,
    val position: BlockCoordinate,
    val block: BlockState,
    val structureId: String? = null,
    val dependsOnSequences: Set<Int> = emptySet(),
)

data class BuildPlan(
    val id: String,
    val title: String,
    val dimensions: BlockDimensions,
    val structures: List<BuildStructure>,
    val operations: List<BlockOperation>,
    val createdAt: Instant? = null,
)

data class WorldInfo(
    val worldName: String,
    val gameVersion: String,
    val capabilities: Set<String> = emptySet(),
)

data class BuildResult(
    val buildId: String,
    val plan: BuildPlan,
    val completedAt: Instant? = null,
)

enum class BuildPhase {
    QUEUED,
    ANALYZING_REQUEST,
    PROCESSING_REFERENCES,
    PLANNING,
    VALIDATING,
    READY,
    CONNECTING,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class BuildProgress(
    val phase: BuildPhase,
    val completedOperations: Int? = null,
    val totalOperations: Int? = null,
    val message: String? = null,
)

enum class BuildErrorCode {
    INVALID_INPUT,
    MISSING_CONFIGURATION,
    AI_UNAVAILABLE,
    REFERENCE_UNAVAILABLE,
    MINECRAFT_UNAVAILABLE,
    BUILD_REJECTED,
    BUILD_FAILED,
    CANCELLED,
    UNEXPECTED,
}

data class BuildError(
    val code: BuildErrorCode,
    /** A safe, user-facing summary. Never include credentials or raw request data. */
    val message: String,
    val retryable: Boolean = false,
)
