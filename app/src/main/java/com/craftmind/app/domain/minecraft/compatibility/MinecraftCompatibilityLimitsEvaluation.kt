package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Shared limit evaluation used by every runtime adapter so that Java and Bedrock cannot drift apart.
 *
 * Effective limits are the smaller of the adapter profile's configured ceiling and the authenticated bridge's
 * reported value. A missing or unusable reported limit is never replaced by a default; it fails closed.
 */
internal object MinecraftCompatibilityLimitsEvaluation {
    class Evaluation(
        val limits: MinecraftCompatibilityLimits,
        val withinLimits: Boolean,
        val reasons: List<String>,
        val reasonCodes: Set<MinecraftCompatibilityReasonCode>,
    )

    @Suppress("LongParameterList")
    fun evaluate(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
        maximumValidatedOperations: Int,
        maximumRequestBytes: Int,
        maximumOperationsPerTick: Int,
        maximumExecutionSeconds: Int,
        maximumDimensions: MinecraftDimensionLimits,
        javaRuntimeRequirement: JavaRuntimeRequirement? = null,
    ): Evaluation {
        val operationLimit = runtime.maximumValidatedOperations?.coerceAtMost(maximumValidatedOperations)
        val requestByteLimit = runtime.maximumRequestBytes?.coerceAtMost(maximumRequestBytes)
        val operationsPerTick = runtime.maximumOperationsPerTick?.coerceAtMost(maximumOperationsPerTick)
        val executionSeconds = runtime.maximumExecutionSeconds?.coerceAtMost(maximumExecutionSeconds)
        val limits = MinecraftCompatibilityLimits(
            maximumValidatedOperations = operationLimit,
            maximumRequestBytes = requestByteLimit,
            maximumDimensions = maximumDimensions,
            javaRuntimeRequirement = javaRuntimeRequirement,
            maximumOperationsPerTick = operationsPerTick,
            maximumExecutionSeconds = executionSeconds,
        )

        val reasons = mutableListOf<String>()
        val reasonCodes = linkedSetOf<MinecraftCompatibilityReasonCode>()
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
                schemaVersion !in runtime.supportedBuildPlanSchemaVersions
            ) {
                withinLimits = false
                reasonCodes += MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA
                reasons += "BuildPlan schema $schemaVersion is not supported by this adapter and authenticated bridge."
            }
        }
        requirements.dimensions?.let { requested ->
            if (requested.width !in 1..maximumDimensions.width || requested.height !in 1..maximumDimensions.height ||
                requested.depth !in 1..maximumDimensions.depth
            ) {
                withinLimits = false
                reasons += "The plan dimensions exceed this adapter's configured maximum."
            }
        }
        if (!withinLimits) reasonCodes += MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED

        return Evaluation(limits, withinLimits, reasons, reasonCodes)
    }
}
