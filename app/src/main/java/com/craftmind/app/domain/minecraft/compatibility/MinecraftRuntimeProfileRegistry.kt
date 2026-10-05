package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Central source of compatibility identities shipped by this build: exactly one production Java profile plus the
 * declared legacy/experimental contracts in [LegacyRuntimeProfileRegistry] and the Bedrock contract in
 * [BedrockRuntimeProfileRegistry]. Other versions, loaders, and release channels stay unsupported until a
 * concrete adapter is implemented and independently verified; nothing here is classified as legacy automatically.
 */
object MinecraftRuntimeProfileRegistry {
    val javaFabric1201 = SupportedMinecraftRuntimeDescriptor(
        adapterId = MinecraftAdapterId("java-fabric-1.20.1"),
        edition = MinecraftEdition.JAVA,
        version = MinecraftVersion.parse("1.20.1"),
        loader = MinecraftLoader.FABRIC,
        loaderVersion = "0.16.10",
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        bridgeVersion = "1.2.0",
        javaRuntimeRequirement = JavaRuntimeRequirement(
            requiredMajor = 17,
            minimumSupportedMajor = 17,
            maximumSupportedMajor = 17,
        ),
        requiredFabricApiVersion = "0.92.2+1.20.1",
        supportStatus = MinecraftCompatibilityStatus.SUPPORTED,
        releaseChannel = MinecraftVersionChannel.RELEASE,
        /*
         * CERTIFIED here records that this registry entry is CraftMind's shipped production Java target
         * (Phases 2-9). It is not a new Phase-12 runtime test: legacy and experimental contracts below that rung
         * can never claim SUPPORTED, and the registry rejects a SUPPORTED profile without a supporting rung.
         */
        runtimeCertification = MinecraftRuntimeCertification.CERTIFIED,
        limitations = emptySet(),
        // The production Fabric bridge validates every requested block/state server-side during preflight; there
        // is no app-side substitution, so the app-side catalog stays empty and unused.
        blockStateSupportRevision = MinecraftTargetBlockStateCatalog.SERVER_VALIDATED_REVISION,
        contentValidationMode = MinecraftContentValidationMode.SERVER_SIDE_VALIDATION,
        maximumValidatedOperations = BuildPlanLimits.MAX_OPERATIONS,
        maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
        maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
        maximumDimensions = MinecraftDimensionLimits(
            width = BuildPlanLimits.MAX_BUILD_WIDTH,
            height = BuildPlanLimits.MAX_BUILD_HEIGHT,
            depth = BuildPlanLimits.MAX_BUILD_DEPTH,
        ),
    )

    private val profiles = listOf(javaFabric1201)

    fun allProfiles(): List<SupportedMinecraftRuntimeDescriptor> = profiles.toList()

    fun profile(adapterId: MinecraftAdapterId): SupportedMinecraftRuntimeDescriptor? =
        profiles.firstOrNull { it.adapterId == adapterId }
}
