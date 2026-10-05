package com.craftmind.app.domain.minecraft

import com.craftmind.app.domain.buildplan.BlockPosition
import kotlinx.serialization.Serializable

/** One server-issued, expiring preview. The token exists only in memory until the user confirms. */
data class MinecraftExecutionPreview(
    val executionId: String,
    val preflightToken: String,
    val planRecordId: String,
    val planVersion: Int,
    val planTitle: String,
    val dimensionId: String,
    val worldSessionId: String,
    val resolvedOrigin: BlockPosition,
    val originStrategy: String,
    val operationCount: Int,
    val createdAtEpochMillis: Long,
    val eventSequence: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
enum class MinecraftExecutionPhase {
    PREPARED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** Locally stored execution facts, independent of immutable LocalBuildRecord plan versions. */
@Serializable
data class LocalBuildExecutionRecord(
    val executionId: String,
    val buildId: String,
    val planRecordId: String,
    val planVersion: Int,
    val bridgeId: String,
    val phase: MinecraftExecutionPhase,
    val completedOperations: Int,
    val totalOperations: Int,
    val eventSequence: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val dimensionId: String,
    val worldSessionId: String,
    val resolvedOrigin: BlockPosition,
    val reasonCode: String? = null,
    val failedOperationIndex: Int? = null,
)

/** Current authenticated bridge snapshot. Counts and terminal state are server-reported only. */
data class MinecraftExecutionSnapshot(
    val executionId: String,
    val buildId: String,
    val planRecordId: String,
    val planVersion: Int,
    val phase: MinecraftExecutionPhase,
    val completedOperations: Int,
    val totalOperations: Int,
    val eventSequence: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val dimensionId: String,
    val worldSessionId: String,
    val resolvedOrigin: BlockPosition,
    val reasonCode: String?,
    val failedOperationIndex: Int?,
)

sealed interface MinecraftExecutionQueryResult {
    data class Found(val snapshot: MinecraftExecutionSnapshot) : MinecraftExecutionQueryResult
    data object NotFound : MinecraftExecutionQueryResult
}

data class MinecraftCancellationResult(
    val outcome: String,
    val phase: MinecraftExecutionPhase?,
    val reasonCode: String?,
)

interface LocalBuildExecutionRepository {
    val records: kotlinx.coroutines.flow.StateFlow<List<LocalBuildExecutionRecord>>
    suspend fun load()
    suspend fun save(record: LocalBuildExecutionRecord)
}
