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
)

@Serializable
data class LocalBuildRecord(
    val recordId: String,
    val plan: BuildPlan,
    val request: BuildRequestSnapshot,
    val savedAtEpochMillis: Long,
)

interface LocalBuildRepository {
    val records: StateFlow<List<LocalBuildRecord>>
    suspend fun load()
    suspend fun save(plan: ValidatedBuildPlan, request: BuildRequest)
}

class BuildRepositoryException : Exception("Local build records could not be read or saved")
