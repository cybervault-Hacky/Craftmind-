package com.craftmind.app.domain.model

/** A single user-supplied input. References remain local until a provider explicitly supports them. */
sealed interface BuildInput

sealed interface ReferenceInput : BuildInput

data class TextInput(val text: String) : BuildInput

data class ImageReference(
    val uri: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
) : ReferenceInput

data class UrlReference(val normalizedUrl: String) : ReferenceInput

data class BuildRequest(val inputs: List<BuildInput>) {
    val text: TextInput? get() = inputs.filterIsInstance<TextInput>().singleOrNull()
    val imageReferences: List<ImageReference> get() = inputs.filterIsInstance<ImageReference>()
    val urlReferences: List<UrlReference> get() = inputs.filterIsInstance<UrlReference>()
    val hasInput: Boolean get() = inputs.isNotEmpty()
}

data class BlockCoordinate(val x: Int, val y: Int, val z: Int)

data class BuildDimensions(val width: Int, val length: Int, val height: Int)

data class BlockState(
    val identifier: String,
    val properties: Map<String, String> = emptyMap(),
)

data class MaterialRequirement(
    val blockIdentifier: String,
    val count: Int,
)

data class BuildPlanStep(
    val id: String,
    val title: String,
    val description: String,
)

data class BlockOperation(
    val sequence: Int,
    /** Coordinates are local to the plan: x=[0,width), y=[0,height), z=[0,length). */
    val position: BlockCoordinate,
    val block: BlockState,
    val stepId: String,
    val rotationDegrees: Int? = null,
    val dependsOnSequences: List<Int> = emptyList(),
)

/** Parsed provider output. This remains untrusted until [BuildPlanValidator] accepts it. */
data class BuildPlanDraft(
    val schemaVersion: Int,
    val title: String,
    val style: String,
    val dimensions: BuildDimensions,
    val origin: BlockCoordinate,
    val materials: List<MaterialRequirement>,
    val steps: List<BuildPlanStep>,
    val operations: List<BlockOperation>,
    val estimatedOperationCount: Int,
)

data class BuildPlan(
    val id: String,
    val schemaVersion: Int,
    val title: String,
    val style: String,
    val dimensions: BuildDimensions,
    val origin: BlockCoordinate,
    val materials: List<MaterialRequirement>,
    val steps: List<BuildPlanStep>,
    val operations: List<BlockOperation>,
    val estimatedOperationCount: Int,
    val generatedAtEpochMillis: Long,
    val generator: PlanGenerator,
)

data class PlanGenerator(
    val providerId: String,
    val modelId: String,
)

enum class BuildResultStatus {
    PLAN_READY_FOR_REVIEW,
}

data class BuildResult(
    val buildId: String,
    val plan: BuildPlan,
    val status: BuildResultStatus = BuildResultStatus.PLAN_READY_FOR_REVIEW,
)

enum class BuildPhase {
    QUEUED,
    VALIDATING_REQUEST,
    GENERATING,
    VALIDATING_PLAN,
    PLAN_READY,
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
)

data class WorldInfo(
    val worldName: String,
    val gameVersion: String,
    val capabilities: Set<String> = emptySet(),
)

enum class BuildPlanValidationIssue {
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_PLAN_ID,
    INVALID_GENERATOR,
    EMPTY_TITLE,
    TITLE_TOO_LONG,
    EMPTY_STYLE,
    STYLE_TOO_LONG,
    INVALID_DIMENSIONS,
    PLAN_VOLUME_TOO_LARGE,
    INVALID_ORIGIN,
    INVALID_MATERIAL_COUNT,
    TOO_MANY_MATERIAL_TYPES,
    UNSUPPORTED_MATERIAL,
    DUPLICATE_MATERIAL,
    MATERIAL_TOTAL_MISMATCH,
    INVALID_STEP_COUNT,
    INVALID_STEP,
    DUPLICATE_STEP_ID,
    TOO_MANY_OPERATIONS,
    EMPTY_OPERATIONS,
    INVALID_OPERATION_ORDER,
    OPERATION_OUT_OF_BOUNDS,
    DUPLICATE_COORDINATE,
    UNSUPPORTED_BLOCK,
    INVALID_BLOCK_PROPERTIES,
    INVALID_ROTATION,
    INVALID_STEP_REFERENCE,
    INVALID_DEPENDENCY,
    OPERATION_COUNT_MISMATCH,
}

enum class BuildErrorCode {
    INVALID_INPUT,
    UNSUPPORTED_REFERENCE,
    MISSING_PROVIDER,
    UNSUPPORTED_PROVIDER,
    MISSING_MODEL,
    UNSUPPORTED_MODEL,
    MISSING_API_KEY,
    INVALID_API_KEY,
    PROVIDER_UNAVAILABLE,
    NO_INTERNET,
    REQUEST_TIMED_OUT,
    RATE_LIMITED,
    PROVIDER_REJECTED_REQUEST,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE,
    BUILD_PLAN_REJECTED,
    CREDENTIAL_STORAGE_FAILED,
    CANCELLED,
    UNKNOWN,
}

data class BuildError(
    val code: BuildErrorCode,
    /** Only safe, localized presentation metadata belongs here; never raw provider text or secrets. */
    val retryable: Boolean = false,
    val validationIssue: BuildPlanValidationIssue? = null,
)
