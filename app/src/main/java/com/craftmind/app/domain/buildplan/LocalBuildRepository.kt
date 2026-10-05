package com.craftmind.app.domain.buildplan

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class BuildRequestSnapshot(
    val prompt: String,
    val imageContentUri: String? = null,
    val imageMediaType: String? = null,
    val imageDisplayName: String? = null,
    val imageSizeBytes: Long? = null,
    val urlReference: String? = null,
    /** Text-only analysis and its model source, stored only after a successful initial image analysis. */
    val imageAnalysisSource: BuildImageAnalysisSource? = null,
    /** Bounded text-only evidence from an actually fetched public video; no video/frame bytes are stored. */
    val referenceAnalysisSource: BuildReferenceAnalysisSource? = null,
)

/** One immutable, locally stored version in a build's recoverable history. */
@Serializable
data class LocalBuildRecord(
    val recordId: String,
    val plan: BuildPlan,
    val request: BuildRequestSnapshot,
    val savedAtEpochMillis: Long,
    /** Defaults migrate Phase 2's flat records into independent v1 histories without data loss. */
    val buildId: String = recordId,
    val version: Int = 1,
    val parentRecordId: String? = null,
    val refinementInstruction: String? = null,
    val changeSummary: String? = null,
    val diff: BuildDiff? = null,
    val restoredFromVersion: Int? = null,
)

interface LocalBuildRepository {
    /** All immutable versions, ordered newest first. */
    val records: StateFlow<List<LocalBuildRecord>>
    suspend fun load()
    suspend fun save(plan: ValidatedBuildPlan, request: BuildRequest): LocalBuildRecord =
        save(plan, request, imageAnalysisSource = null, referenceAnalysisSource = null)

    /** Persist both visual provenance channels explicitly; implementations cannot silently discard either. */
    suspend fun save(
        plan: ValidatedBuildPlan,
        request: BuildRequest,
        imageAnalysisSource: BuildImageAnalysisSource?,
    ): LocalBuildRecord = save(plan, request, imageAnalysisSource, referenceAnalysisSource = null)

    suspend fun save(
        plan: ValidatedBuildPlan,
        request: BuildRequest,
        imageAnalysisSource: BuildImageAnalysisSource?,
        referenceAnalysisSource: BuildReferenceAnalysisSource?,
    ): LocalBuildRecord

    suspend fun appendRefinement(
        baseRecordId: String,
        plan: ValidatedBuildPlan,
        request: BuildEditRequest,
        diff: BuildDiff,
    ): LocalBuildRecord
    suspend fun revertTo(buildId: String, targetVersion: Int, expectedCurrentRecordId: String): LocalBuildRecord
}

enum class BuildRepositoryError {
    INVALID_RECORD,
    NOT_FOUND,
    STALE_VERSION,
    HISTORY_LIMIT_REACHED,
    STORAGE_FAILURE,
}

class BuildRepositoryException(
    val error: BuildRepositoryError = BuildRepositoryError.STORAGE_FAILURE,
) : Exception("Local build history operation failed (${error.name})")
