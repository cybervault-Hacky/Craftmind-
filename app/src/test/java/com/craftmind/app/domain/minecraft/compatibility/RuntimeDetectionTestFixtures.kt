package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Phase 13 fixtures: authoritative runtime reports exactly as an authenticated protocol-v2 bridge sends them.
 *
 * Every value here is a *reported fact* of the fixture bridge, never a CraftMind guess. Capability-dependent facts
 * (`worldAccess`, dimension/world session) are derived from the reported capability set so a fixture is internally
 * coherent by construction; the forged-capability cases build a descriptor directly instead.
 */
object RuntimeDetectionTestFixtures {
    const val APP_VERSION = "1.0.0"
    const val SESSION_ID = "session-01"
    const val BRIDGE_ID = "bridge-0123456789abcdef0123456789abcdef"
    const val AUTHENTICATED_AT = 1_700_000_000_000L
    const val JAVA_BRIDGE_VERSION = "1.2.0"
    const val DIMENSION_ID = "minecraft:overworld"
    const val WORLD_SESSION_ID = "world-session-1"

    val IDENTITY_FINGERPRINT = "00".repeat(32)

    val FULL_CAPABILITIES: Set<MinecraftCapability> = setOf(
        MinecraftCapability.WORLD_ACCESS,
        MinecraftCapability.WORLD_VALIDATION,
        MinecraftCapability.BUILD_EXECUTION,
        MinecraftCapability.BLOCK_PLACEMENT,
        MinecraftCapability.BLOCK_STATE_SUPPORT,
        MinecraftCapability.ORIGIN_RESOLUTION,
        MinecraftCapability.STRUCTURE_BATCHING,
        MinecraftCapability.PROGRESS_REPORTING,
        MinecraftCapability.BUILD_STATUS,
        MinecraftCapability.CANCELLATION,
        MinecraftCapability.BUILD_PLAN_V2,
    )

    /**
     * A coherent report where the operator selected an origin but construction is disabled: the runtime facts stay
     * complete, so detection succeeds and only the missing execution capabilities block a build.
     */
    val ORIGIN_WITHOUT_CONSTRUCTION_CAPABILITIES: Set<MinecraftCapability> = setOf(
        MinecraftCapability.WORLD_ACCESS,
        MinecraftCapability.WORLD_VALIDATION,
        MinecraftCapability.ORIGIN_RESOLUTION,
        MinecraftCapability.BLOCK_STATE_SUPPORT,
        MinecraftCapability.BUILD_PLAN_V2,
    )

    /** The exact production Java runtime: 1.20.1, Java 17, Fabric 0.16.10, Fabric API 0.92.2+1.20.1, Bridge 1.2.0. */
    fun javaProductionSnapshot(
        minecraftVersion: String = "1.20.1",
        loaderName: String = "Fabric",
        loaderVersion: String = "0.16.10",
        fabricApiVersion: String? = "0.92.2+1.20.1",
        javaRuntimeMajor: Int? = 17,
        bridgeVersion: String = JAVA_BRIDGE_VERSION,
        protocolVersion: Int = BridgeProtocol.VERSION,
        clientAppVersion: String? = APP_VERSION,
        capabilities: Set<MinecraftCapability> = FULL_CAPABILITIES,
        schemaVersions: List<Int> = listOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maximumValidatedOperations: Int = BridgeProtocol.MAX_OPERATIONS,
        maximumRequestBytes: Int = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick: Int = 32,
        maximumExecutionSeconds: Int = 300,
    ): BridgeCapabilitiesSnapshot = coherentSnapshot(
        capabilities = capabilities,
        editionName = "java",
        minecraftVersion = minecraftVersion,
        javaRuntimeMajor = javaRuntimeMajor,
        loaderName = loaderName,
        loaderVersion = loaderVersion,
        fabricApiVersion = fabricApiVersion,
        bridgeVersion = bridgeVersion,
        protocolVersion = protocolVersion,
        clientAppVersion = clientAppVersion,
        schemaVersions = schemaVersions,
        maximumValidatedOperations = maximumValidatedOperations,
        maximumRequestBytes = maximumRequestBytes,
        maximumOperationsPerTick = maximumOperationsPerTick,
        maximumExecutionSeconds = maximumExecutionSeconds,
    )

    /** A declared legacy contract runtime: Java 1.7.10 / Forge 10.13.4.1614 on Java 8, legacy bridge contract. */
    fun legacyForge1710Snapshot(
        javaRuntimeMajor: Int? = 8,
        loaderVersion: String = "10.13.4.1614",
        bridgeVersion: String = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
        capabilities: Set<MinecraftCapability> = FULL_CAPABILITIES,
    ): BridgeCapabilitiesSnapshot = legacySnapshot(
        minecraftVersion = "1.7.10",
        loaderVersion = loaderVersion,
        javaRuntimeMajor = javaRuntimeMajor,
        bridgeVersion = bridgeVersion,
        capabilities = capabilities,
    )

    /** A declared legacy contract runtime: Java 1.12.2 / Forge 14.23.5.2859 on Java 8, legacy bridge contract. */
    fun legacyForge1122Snapshot(
        javaRuntimeMajor: Int? = 8,
        loaderVersion: String = "14.23.5.2859",
        capabilities: Set<MinecraftCapability> = FULL_CAPABILITIES,
    ): BridgeCapabilitiesSnapshot = legacySnapshot(
        minecraftVersion = "1.12.2",
        loaderVersion = loaderVersion,
        javaRuntimeMajor = javaRuntimeMajor,
        bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
        capabilities = capabilities,
    )

    private fun legacySnapshot(
        minecraftVersion: String,
        loaderVersion: String,
        javaRuntimeMajor: Int?,
        bridgeVersion: String,
        capabilities: Set<MinecraftCapability>,
    ): BridgeCapabilitiesSnapshot = coherentSnapshot(
        capabilities = capabilities,
        editionName = "java",
        minecraftVersion = minecraftVersion,
        javaRuntimeMajor = javaRuntimeMajor,
        loaderName = "Forge",
        loaderVersion = loaderVersion,
        fabricApiVersion = null,
        bridgeVersion = bridgeVersion,
        limitations = LegacyRuntimeProfileRegistry.declaredLimitations(),
    )

    /** A Bedrock runtime as an authoritative Bedrock bridge would report it: no JVM, no loader, no Fabric API. */
    fun bedrockSnapshot(
        minecraftVersion: String = "1.21.60",
        platform: MinecraftRuntimePlatform = MinecraftRuntimePlatform.DEDICATED_SERVER,
        platformVersion: String? = "1.21.60.3",
        bridgeVersion: String = BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION,
        protocolVersion: Int = BridgeProtocol.VERSION,
        capabilities: Set<MinecraftCapability> = FULL_CAPABILITIES,
    ): BridgeCapabilitiesSnapshot = coherentSnapshot(
        capabilities = capabilities,
        editionName = "bedrock",
        minecraftVersion = minecraftVersion,
        javaRuntimeMajor = null,
        loaderName = "Bedrock Native",
        loaderVersion = null,
        fabricApiVersion = null,
        bridgeVersion = bridgeVersion,
        protocolVersion = protocolVersion,
        platform = platform,
        platformVersion = platformVersion,
        limitations = BedrockRuntimeProfileRegistry.bedrockBridgeContract.limitations,
    )

    /** A pre-release-family runtime (snapshot, beta, or alpha identifier) reported by an authoritative bridge. */
    fun preReleaseFamilySnapshot(
        minecraftVersion: String,
        loaderName: String = "Vanilla",
        loaderVersion: String = "1.0.0",
        javaRuntimeMajor: Int? = 17,
    ): BridgeCapabilitiesSnapshot = coherentSnapshot(
        capabilities = FULL_CAPABILITIES,
        editionName = "java",
        minecraftVersion = minecraftVersion,
        javaRuntimeMajor = javaRuntimeMajor,
        loaderName = loaderName,
        loaderVersion = loaderVersion,
        fabricApiVersion = null,
        bridgeVersion = JAVA_BRIDGE_VERSION,
        limitations = setOf(MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED),
    )

    /** Wraps a snapshot in the only input detection accepts: an authenticated report bound to a session. */
    @Suppress("LongParameterList")
    fun report(
        snapshot: BridgeCapabilitiesSnapshot,
        sessionId: String? = SESSION_ID,
        requestedAppVersion: String? = APP_VERSION,
        authenticated: Boolean = true,
        authenticatedAtEpochMillis: Long? = AUTHENTICATED_AT,
        expectedBridgeId: String? = BRIDGE_ID,
        expectedIdentityFingerprint: String? = IDENTITY_FINGERPRINT,
        reportedBytes: Int? = null,
    ): AuthenticatedMinecraftRuntimeReport = AuthenticatedMinecraftRuntimeReport.fromSnapshot(
        snapshot = snapshot,
        sessionId = sessionId,
        requestedAppVersion = requestedAppVersion,
        authenticated = authenticated,
        authenticatedAtEpochMillis = authenticatedAtEpochMillis,
        expectedBridgeId = expectedBridgeId,
        expectedIdentityFingerprint = expectedIdentityFingerprint,
        reportedBytes = reportedBytes,
    )

    @Suppress("LongParameterList")
    private fun coherentSnapshot(
        capabilities: Set<MinecraftCapability>,
        editionName: String,
        minecraftVersion: String,
        javaRuntimeMajor: Int?,
        loaderName: String,
        loaderVersion: String?,
        fabricApiVersion: String?,
        bridgeVersion: String,
        protocolVersion: Int = BridgeProtocol.VERSION,
        clientAppVersion: String? = APP_VERSION,
        schemaVersions: List<Int> = listOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maximumValidatedOperations: Int = BridgeProtocol.MAX_OPERATIONS,
        maximumRequestBytes: Int = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick: Int = 32,
        maximumExecutionSeconds: Int = 300,
        platform: MinecraftRuntimePlatform = MinecraftRuntimePlatform.UNKNOWN,
        platformVersion: String? = null,
        limitations: Set<MinecraftRuntimeLimitation> = emptySet(),
    ): BridgeCapabilitiesSnapshot {
        val worldAccess = MinecraftCapability.WORLD_ACCESS in capabilities
        val originAvailable = MinecraftCapability.ORIGIN_RESOLUTION in capabilities
        return BridgeCapabilitiesSnapshot(
            protocolVersion = protocolVersion,
            bridgeId = BRIDGE_ID,
            identityFingerprint = IDENTITY_FINGERPRINT,
            bridgeVersion = bridgeVersion,
            clientAppVersion = clientAppVersion,
            editionName = editionName,
            minecraftVersion = minecraftVersion,
            javaRuntimeMajor = javaRuntimeMajor,
            loaderName = loaderName,
            loaderVersion = loaderVersion,
            fabricApiVersion = fabricApiVersion,
            platform = platform,
            platformVersion = platformVersion,
            limitations = limitations,
            supportedCapabilities = capabilities,
            worldAccess = worldAccess,
            constructionExecute = MinecraftCapability.BUILD_EXECUTION in capabilities,
            cancellation = MinecraftCapability.CANCELLATION in capabilities,
            maximumValidatedOperations = maximumValidatedOperations,
            maximumRequestBytes = maximumRequestBytes,
            maximumOperationsPerTick = maximumOperationsPerTick,
            maximumExecutionSeconds = maximumExecutionSeconds,
            supportedBuildPlanSchemaVersions = schemaVersions,
            dimensionId = if (originAvailable) DIMENSION_ID else null,
            worldSessionId = if (originAvailable) WORLD_SESSION_ID else null,
        )
    }
}
