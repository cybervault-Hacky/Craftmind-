package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot

/**
 * The sole Phase 9 runtime adapter. Execution methods deliberately delegate to the existing authenticated,
 * preflight-token-based Fabric bridge repository; this class does not introduce another transport or placement path.
 */
class JavaFabric1201Adapter : MinecraftAdapter {
    override val adapterId = ID

    override val supportedRuntimeDescriptors = listOf(
        SupportedMinecraftRuntimeDescriptor(
            edition = MinecraftEdition.JAVA,
            version = MinecraftVersion.parse("1.20.1"),
            loader = MinecraftLoader.FABRIC,
            loaderVersion = FABRIC_LOADER_VERSION,
            bridgeProtocolVersion = BRIDGE_PROTOCOL_VERSION,
            bridgeVersion = BRIDGE_VERSION,
            requiredPlatformApiVersion = FABRIC_API_VERSION,
            supportStatus = MinecraftCompatibilityStatus.SUPPORTED,
            javaToolchainMajor = JAVA_TOOLCHAIN_MAJOR,
            maximumValidatedOperations = MAXIMUM_OPERATIONS,
            maximumRequestBytes = MAXIMUM_REQUEST_BYTES,
            maximumDimensions = MinecraftDimensionLimits(
                width = BuildPlanLimits.MAX_BUILD_WIDTH,
                height = BuildPlanLimits.MAX_BUILD_HEIGHT,
                depth = BuildPlanLimits.MAX_BUILD_DEPTH,
            ),
        ),
    )

    /** Confirmed adapter-side behavior implemented by the existing Fabric bridge; dynamic abilities come from the server. */
    override val capabilities = setOf(
        MinecraftCapability.BLOCK_STATE_SUPPORT,
        MinecraftCapability.WORLD_VALIDATION,
        MinecraftCapability.STRUCTURE_BATCHING,
        MinecraftCapability.PROGRESS_REPORTING,
        MinecraftCapability.BUILD_STATUS,
    )

    override fun compatibilityCheck(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult? {
        val profile = supportedRuntimeDescriptors.firstOrNull { it.matches(runtime) } ?: return null
        val available = capabilities + runtime.capabilities.filter { it != MinecraftCapability.UNKNOWN }
        val missing = requirements.requiredCapabilities - available
        val reasons = buildList {
            if (missing.isNotEmpty()) {
                add("Runtime is not currently reporting: ${missing.sortedBy(MinecraftCapability::name).joinToString { it.displayName }}.")
            }
        }.toMutableList()
        val warnings = buildList {
            if (runtime.javaRuntimeMajor == null) {
                add("The protocol-v1 bridge does not report the server JVM; this adapter is built for the Java 17 toolchain, but the running JVM was not independently verified.")
            }
            if (profile.requiredPlatformApiVersion != null) {
                add("The bridge mod declares Fabric API ${profile.requiredPlatformApiVersion} as a dependency, but protocol v1 does not report the loaded API version separately.")
            }
            add("The serialized request is measured against the reported byte limit again before preflight.")
        }

        val operationLimit = runtime.maximumValidatedOperations
            ?.coerceAtMost(profile.maximumValidatedOperations)
        val requestByteLimit = runtime.maximumRequestBytes
            ?.coerceAtMost(profile.maximumRequestBytes)
        val limits = MinecraftCompatibilityLimits(
            maximumValidatedOperations = operationLimit,
            maximumRequestBytes = requestByteLimit,
            maximumDimensions = profile.maximumDimensions,
            javaToolchainMajor = profile.javaToolchainMajor,
        )

        var withinLimits = true
        if (operationLimit == null || operationLimit <= 0) {
            withinLimits = false
            reasons += "The authenticated bridge did not provide a usable operation limit."
        }
        if (requestByteLimit == null || requestByteLimit < MINIMUM_REQUEST_BYTES) {
            withinLimits = false
            reasons += "The authenticated bridge did not provide a usable request-byte limit."
        }
        requirements.operationCount?.let { count ->
            if (count <= 0 || operationLimit == null || count > operationLimit) {
                withinLimits = false
                reasons += "The plan operation count exceeds the authenticated bridge limit."
            }
        }
        requirements.schemaVersion?.let { schemaVersion ->
            if (schemaVersion != BuildPlanLimits.CURRENT_SCHEMA_VERSION ||
                schemaVersion !in runtime.supportedBuildPlanSchemaVersions) {
                withinLimits = false
                reasons += "BuildPlan schema $schemaVersion is not supported by this adapter and authenticated bridge."
            }
        }
        requirements.dimensions?.let { requested ->
            val maximum = profile.maximumDimensions
            if (requested.width !in 1..maximum.width || requested.height !in 1..maximum.height ||
                requested.depth !in 1..maximum.depth) {
                withinLimits = false
                reasons += "The plan dimensions exceed this adapter's configured maximum."
            }
        }

        return MinecraftCompatibilityResult(
            status = profile.supportStatus,
            adapterId = adapterId,
            capabilities = available,
            missingCapabilities = missing,
            reasons = reasons.distinct(),
            warnings = warnings,
            limits = limits,
            planWithinLimits = withinLimits,
        )
    }

    override suspend fun preflight(
        bridge: MinecraftBridgePairingRepository,
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionPreview = bridge.prepareExecution(record, executionId)

    override suspend fun execute(
        bridge: MinecraftBridgePairingRepository,
        preview: MinecraftExecutionPreview,
    ): MinecraftExecutionSnapshot = bridge.startExecution(preview)

    override suspend fun cancel(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftCancellationResult = bridge.cancelExecution(executionId)

    override suspend fun status(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftExecutionQueryResult = bridge.queryExecution(executionId)

    companion object {
        val ID = MinecraftAdapterId("java-fabric-1.20.1")
        const val JAVA_TOOLCHAIN_MAJOR = 17
        const val FABRIC_LOADER_VERSION = "0.16.10"
        const val FABRIC_API_VERSION = "0.92.2+1.20.1"
        const val BRIDGE_PROTOCOL_VERSION = 1
        const val BRIDGE_VERSION = "1.1.0"
        const val MAXIMUM_OPERATIONS = BuildPlanLimits.MAX_OPERATIONS
        const val MAXIMUM_REQUEST_BYTES = 1_048_576
        private const val MINIMUM_REQUEST_BYTES = 1_024
    }
}
