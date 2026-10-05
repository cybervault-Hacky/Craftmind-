package com.craftmind.app.domain.minecraft

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class TrustedMinecraftBridge(
    val host: String,
    val port: Int,
    val tlsFingerprint: String,
    val bridgeId: String,
    val clientId: String,
    val displayName: String,
    val pairedAtEpochMillis: Long,
)

data class BridgeCapabilitiesSnapshot(
    val protocolVersion: Int,
    val bridgeId: String,
    val identityFingerprint: String,
    val bridgeVersion: String,
    val minecraftVersion: String,
    val loaderName: String,
    val loaderVersion: String,
    val worldAccess: Boolean,
    val constructionExecute: Boolean,
    val cancellation: Boolean,
    val maximumValidatedOperations: Int,
    val maximumRequestBytes: Int,
    val supportedBuildPlanSchemaVersions: List<Int>,
    val dimensionId: String? = null,
    val worldSessionId: String? = null,
) {
    /** Typed, fail-safe interpretation of the existing protocol-v1 runtime fields. */
    val runtimeDescriptor: MinecraftRuntimeDescriptor
        get() = MinecraftRuntimeDescriptor.fromBridgeV1(
            bridgeProtocolVersion = protocolVersion,
            bridgeVersion = bridgeVersion,
            minecraftVersion = minecraftVersion,
            loaderName = loaderName,
            loaderVersion = loaderVersion,
            worldAccess = worldAccess,
            constructionExecute = constructionExecute,
            cancellation = cancellation,
            maximumValidatedOperations = maximumValidatedOperations,
            maximumRequestBytes = maximumRequestBytes,
            supportedBuildPlanSchemaVersions = supportedBuildPlanSchemaVersions.toSet(),
            operatorOriginAvailable = dimensionId != null && worldSessionId != null,
        )

    val compatibilityResult: MinecraftCompatibilityResult
        get() = DefaultMinecraftCompatibility.resolver.resolveRuntime(runtimeDescriptor)

    /** Runtime-level execution gate, resolved through the registered adapter rather than scattered version checks. */
    val executionCompatible: Boolean
        get() = compatibilityResult.canExecute
}

sealed interface BridgeConnectionState {
    data object Disconnected : BridgeConnectionState
    data object Connecting : BridgeConnectionState
    data class Connected(
        val bridge: TrustedMinecraftBridge,
        val capabilities: BridgeCapabilitiesSnapshot,
        val authenticatedAtEpochMillis: Long,
    ) : BridgeConnectionState
    data class Error(val reasonCode: String) : BridgeConnectionState
}

interface MinecraftBridgeProfileRepository {
    val profile: Flow<TrustedMinecraftBridge?>
    suspend fun save(profile: TrustedMinecraftBridge)
    suspend fun clear()
}

interface MinecraftBridgePairingRepository {
    val connectionState: StateFlow<BridgeConnectionState>
    val profile: Flow<TrustedMinecraftBridge?>

    /** Performs TLS pin verification, one-time pairing, proof-of-possession, login, and capability verification. */
    suspend fun pair(host: String, port: Int, tlsFingerprint: String, pairingCode: String)

    /** Re-authenticates with the previously paired device key; it never changes the saved TLS pin. */
    suspend fun connect()

    /** Closes the current in-memory bridge session when reachable; local state is cleared either way. */
    suspend fun disconnect()

    /** Refreshes capabilities over the current pinned, authenticated session. */
    suspend fun refreshCapabilities()

    /** Performs an independent bridge preflight for an immutable, locally saved BuildPlan version. */
    suspend fun prepareExecution(record: LocalBuildRecord, executionId: String): MinecraftExecutionPreview

    /** Confirms a server-issued preview. Retries with the same ID/token are idempotent. */
    suspend fun startExecution(preview: MinecraftExecutionPreview): MinecraftExecutionSnapshot

    /** Queries bridge truth; NotFound never implies that a block was placed or that a build failed. */
    suspend fun queryExecution(executionId: String): MinecraftExecutionQueryResult

    /** Requests cancellation of the named execution and returns the server's typed outcome. */
    suspend fun cancelExecution(executionId: String): MinecraftCancellationResult

    /** Revokes this client identity at the trusted bridge and invalidates its active sessions. */
    suspend fun revoke()

    /** Clears only this device's saved profile and private key; it cannot revoke while the bridge is unreachable. */
    suspend fun forgetLocally()
}

class MinecraftBridgeFailure(val reasonCode: String) : Exception(reasonCode)
