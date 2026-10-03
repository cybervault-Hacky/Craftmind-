package com.craftmind.app.minecraft

import com.craftmind.app.domain.model.BlockOperation
import com.craftmind.app.domain.model.BuildError
import com.craftmind.app.domain.model.BuildPlan
import com.craftmind.app.domain.model.BuildProgress
import com.craftmind.app.domain.model.BuildResult
import com.craftmind.app.domain.model.WorldInfo
import kotlinx.coroutines.flow.Flow

sealed interface MinecraftBuildEvent {
    data class Progress(val value: BuildProgress) : MinecraftBuildEvent
    data class Completed(val result: BuildResult) : MinecraftBuildEvent
    data class Failed(val error: BuildError) : MinecraftBuildEvent
    data object Cancelled : MinecraftBuildEvent
}

/** Platform-neutral boundary for a future Minecraft-side companion or supported API. */
interface MinecraftBridge {
    suspend fun connect(): Result<WorldInfo>
    suspend fun disconnect(): Result<Unit>
    suspend fun getWorldInfo(): Result<WorldInfo>
    suspend fun validateBuild(plan: BuildPlan): Result<BuildValidation>
    fun executeBuild(plan: BuildPlan): Flow<MinecraftBuildEvent>
    suspend fun cancelBuild(buildId: String): Result<Unit>
    fun observeBuildProgress(buildId: String): Flow<BuildProgress>
}

data class BuildValidation(
    val accepted: Boolean,
    val issues: List<String> = emptyList(),
    val operationCount: Int = 0,
)

/** Optional lower-level command port for bridges that execute individual operations. */
interface BlockOperationExecutor {
    suspend fun execute(operation: BlockOperation): Result<Unit>
}
