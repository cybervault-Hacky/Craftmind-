package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot

/**
 * The production Java Edition adapter for the exact 1.20.1/Fabric profile. Execution still delegates to the
 * existing authenticated, preflight-token-based Fabric bridge repository; no alternate transport or placement
 * path is added. Limit evaluation is shared with the Bedrock adapter so both editions stay identical.
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

        val evaluation = MinecraftCompatibilityLimitsEvaluation.evaluate(
            runtime = runtime,
            requirements = requirements,
            maximumValidatedOperations = profile.maximumValidatedOperations,
            maximumRequestBytes = profile.maximumRequestBytes,
            maximumOperationsPerTick = profile.maximumOperationsPerTick,
            maximumExecutionSeconds = profile.maximumExecutionSeconds,
            maximumDimensions = profile.maximumDimensions,
            javaRuntimeRequirement = profile.javaRuntimeRequirement,
        )
        reasons += evaluation.reasons
        reasonCodes += evaluation.reasonCodes

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
            limits = evaluation.limits,
            planWithinLimits = evaluation.withinLimits,
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
