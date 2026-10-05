package com.craftmind.app.domain.minecraft

import com.craftmind.app.domain.buildplan.BlockPosition
import com.craftmind.app.domain.buildplan.ReviewedBuildPlan
import kotlinx.coroutines.flow.StateFlow

/** A bridge session contains connection metadata only; pairing tokens are transient and not modeled as settings. */
data class MinecraftConnection(
    val endpoint: String,
    val transport: MinecraftTransport,
    val worldId: String? = null,
)

enum class MinecraftTransport {
    LOCAL,
    LAN,
    COMPANION,
    OTHER,
}

enum class MinecraftBridgeStatus {
    DISCONNECTED,
    PAIRING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

sealed interface MinecraftConnectionState {
    val status: MinecraftBridgeStatus

    data object Disconnected : MinecraftConnectionState {
        override val status = MinecraftBridgeStatus.DISCONNECTED
    }

    data object Pairing : MinecraftConnectionState {
        override val status = MinecraftBridgeStatus.PAIRING
    }

    data object Connecting : MinecraftConnectionState {
        override val status = MinecraftBridgeStatus.CONNECTING
    }

    data class Connected(val connection: MinecraftConnection) : MinecraftConnectionState {
        override val status = MinecraftBridgeStatus.CONNECTED
    }

    data class Error(val reasonCode: String) : MinecraftConnectionState {
        override val status = MinecraftBridgeStatus.ERROR
    }
}

data class PairingSession(
    val sessionId: String,
    val expiresAtEpochMillis: Long,
)

data class MinecraftCapabilities(
    val supportsWorldOrigin: Boolean,
    val supportsBlockPlacement: Boolean,
    val supportsCancellation: Boolean,
    val supportedBlockIds: Set<String> = emptySet(),
)

data class MinecraftExecutionRequest(
    val reviewedPlan: ReviewedBuildPlan,
    val worldOrigin: BlockPosition,
)

data class MinecraftExecutionResult(
    val executionId: String,
    val status: MinecraftExecutionStatus,
    val placedOperationCount: Int,
    val totalOperationCount: Int,
    val reasonCode: String? = null,
)

enum class MinecraftExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

sealed interface MinecraftBridgeResult<out T> {
    data class Success<T>(val value: T) : MinecraftBridgeResult<T>
    data class Failure(val reasonCode: String) : MinecraftBridgeResult<Nothing>
}

/**
 * Legacy platform-independent execution abstraction retained for a future construction phase. Phase 4
 * pairing/session transport lives in BridgePairing.kt; it deliberately does not implement these
 * world-reading or construction methods.
 */
interface MinecraftBridge {
    val connectionState: StateFlow<MinecraftConnectionState>

    suspend fun pair(): MinecraftBridgeResult<PairingSession>
    suspend fun connect(connection: MinecraftConnection): MinecraftBridgeResult<Unit>
    suspend fun disconnect()
    suspend fun status(): MinecraftBridgeStatus
    suspend fun getCapabilities(): MinecraftBridgeResult<MinecraftCapabilities>
    suspend fun getWorldOrigin(): MinecraftBridgeResult<BlockPosition>
    suspend fun executeBuild(request: MinecraftExecutionRequest): MinecraftBridgeResult<MinecraftExecutionResult>
    suspend fun cancelBuild(executionId: String): MinecraftBridgeResult<Unit>
}

sealed interface BuildExecutionState {
    data object Idle : BuildExecutionState
    data class AwaitingConfirmation(val planId: String) : BuildExecutionState
    data class Running(
        val executionId: String,
        val completedOperations: Int,
        val totalOperations: Int,
    ) : BuildExecutionState

    data class Completed(val executionId: String) : BuildExecutionState
    data class Failed(val executionId: String?, val reasonCode: String) : BuildExecutionState
    data class Cancelled(val executionId: String) : BuildExecutionState
}
