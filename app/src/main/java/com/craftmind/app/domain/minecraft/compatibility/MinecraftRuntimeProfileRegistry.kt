package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Central source of compatibility identities shipped by this build. This intentionally contains exactly
 * one production profile; other versions/loaders stay unsupported until a concrete adapter is implemented
 * and independently verified.
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
