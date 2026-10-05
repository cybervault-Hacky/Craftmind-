package com.craftmind.app.domain.minecraft

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform
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

/**
 * Authenticated runtime report of one pinned CraftMind bridge.
 *
 * Java Edition and Bedrock Edition share this type because they share the bridge protocol, authentication,
 * capability negotiation, limits, and execution lifecycle. Edition-specific runtime facts stay explicit and
 * nullable instead of being defaulted: a Bedrock report carries no JVM, loader, or Fabric API values, and a Java
 * report carries no Bedrock platform.
 */
data class BridgeCapabilitiesSnapshot(
    val protocolVersion: Int,
    val bridgeId: String,
    val identityFingerprint: String,
    val bridgeVersion: String,
    /** Version supplied by this Android app and echoed in the authenticated capability response. */
    val clientAppVersion: String?,
    val editionName: String,
    val minecraftVersion: String,
    /** Null unless the reported runtime actually runs a JVM. */
    val javaRuntimeMajor: Int?,
    val loaderName: String,
    /** Null for runtimes without a loader concept, including Bedrock. */
    val loaderVersion: String?,
    val fabricApiVersion: String?,
    /** Bedrock runtime host reported by a Bedrock bridge; [MinecraftRuntimePlatform.UNKNOWN] for Java. */
    val platform: MinecraftRuntimePlatform = MinecraftRuntimePlatform.UNKNOWN,
    /** Bedrock host/runtime version where safely reported; null otherwise. */
    val platformVersion: String? = null,
    /** Integration limitations declared by the reported runtime; only Bedrock reports them. */
    val limitations: Set<BedrockRuntimeLimitation> = emptySet(),
    val supportedCapabilities: Set<MinecraftCapability>,
    val worldAccess: Boolean,
    val constructionExecute: Boolean,
    val cancellation: Boolean,
    val maximumValidatedOperations: Int,
    val maximumRequestBytes: Int,
    val maximumOperationsPerTick: Int,
    val maximumExecutionSeconds: Int,
    val supportedBuildPlanSchemaVersions: List<Int>,
    val dimensionId: String? = null,
    val worldSessionId: String? = null,
) {
    /** Typed, fail-closed interpretation of the authenticated protocol-v2 runtime report. */
    val runtimeDescriptor: MinecraftRuntimeDescriptor
        get() = MinecraftRuntimeDescriptor.fromBridgeV2(
            appVersion = clientAppVersion,
            editionName = editionName,
            bridgeProtocolVersion = protocolVersion,
            bridgeVersion = bridgeVersion,
            minecraftVersion = minecraftVersion,
            javaRuntimeMajor = javaRuntimeMajor,
            loaderName = loaderName,
            loaderVersion = loaderVersion,
            fabricApiVersion = fabricApiVersion,
            reportedCapabilities = supportedCapabilities,
            maximumValidatedOperations = maximumValidatedOperations,
            maximumRequestBytes = maximumRequestBytes,
            maximumOperationsPerTick = maximumOperationsPerTick,
            maximumExecutionSeconds = maximumExecutionSeconds,
            supportedBuildPlanSchemaVersions = supportedBuildPlanSchemaVersions.toSet(),
            worldAvailable = worldAccess,
            operatorOriginAvailable = dimensionId != null && worldSessionId != null,
            platform = platform,
            platformVersion = platformVersion,
            limitations = limitations,
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

data class MinecraftBlockRejectionDetails(
    val operationIndex: Int,
    val blockId: String,
    val unsupportedStateProperties: List<String> = emptyList(),
)

class MinecraftBridgeFailure(
    val reasonCode: String,
    val safeMessage: String? = null,
    val blockRejection: MinecraftBlockRejectionDetails? = null,
) : Exception(safeMessage ?: reasonCode)
