package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * The sole production runtime adapter. Execution still delegates to the existing authenticated,
 * preflight-token-based Fabric bridge repository; no alternate transport or placement path is added.
 */
class JavaFabric1201Adapter : MinecraftAdapter {
    override val adapterId = ID
    override val supportedRuntimeDescriptors = listOf(MinecraftRuntimeProfileRegistry.javaFabric1201)

    override fun compatibilityCheck(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult? {
        val profile = supportedRuntimeDescriptors.firstOrNull { it.matches(runtime) } ?: return null
        val available = runtime.capabilities.filterTo(linkedSetOf()) { it != MinecraftCapability.UNKNOWN }
        val missing = requirements.requiredCapabilities - available
        val reasons = mutableListOf<String>()
        val reasonCodes = linkedSetOf<MinecraftCompatibilityReasonCode>()
        if (missing.isNotEmpty()) {
            reasonCodes += MinecraftCompatibilityReasonCode.MISSING_CAPABILITY
            reasons += "The authenticated bridge did not report: ${missing.sortedBy(MinecraftCapability::name).joinToString { it.displayName }}."
        }

        val operationLimit = runtime.maximumValidatedOperations
            ?.coerceAtMost(profile.maximumValidatedOperations)
        val requestByteLimit = runtime.maximumRequestBytes
            ?.coerceAtMost(profile.maximumRequestBytes)
        val operationsPerTick = runtime.maximumOperationsPerTick
            ?.coerceAtMost(profile.maximumOperationsPerTick)
        val executionSeconds = runtime.maximumExecutionSeconds
            ?.coerceAtMost(profile.maximumExecutionSeconds)
        val limits = MinecraftCompatibilityLimits(
            maximumValidatedOperations = operationLimit,
            maximumRequestBytes = requestByteLimit,
            maximumDimensions = profile.maximumDimensions,
            javaRuntimeRequirement = profile.javaRuntimeRequirement,
            maximumOperationsPerTick = operationsPerTick,
            maximumExecutionSeconds = executionSeconds,
        )

        var withinLimits = true
        if (operationLimit == null || operationLimit <= 0) {
            withinLimits = false
            reasons += "The authenticated bridge did not provide a usable operation limit."
        }
        if (requestByteLimit == null || requestByteLimit < BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES) {
            withinLimits = false
            reasons += "The authenticated bridge did not provide a usable request-byte limit."
        }
        if (operationsPerTick == null || operationsPerTick <= 0) {
            withinLimits = false
            reasons += "The authenticated bridge did not provide a usable per-tick operation limit."
        }
        if (executionSeconds == null || executionSeconds <= 0) {
            withinLimits = false
            reasons += "The authenticated bridge did not provide a usable execution-time limit."
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
                reasonCodes += MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA
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
        if (!withinLimits) reasonCodes += MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED

        return MinecraftCompatibilityResult(
            status = profile.supportStatus,
            adapterId = adapterId,
            capabilities = available,
            missingCapabilities = missing,
            reasons = reasons.distinct(),
            warnings = listOf(
                "The server validates each requested block and state during preflight. Unsupported content is rejected; CraftMind does not substitute blocks or change an approved plan.",
                "The serialized request is measured against the authenticated byte limit again before preflight.",
            ),
            limits = limits,
            planWithinLimits = withinLimits,
            reasonCodes = reasonCodes,
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
        val ID = MinecraftRuntimeProfileRegistry.javaFabric1201.adapterId
    }
}
