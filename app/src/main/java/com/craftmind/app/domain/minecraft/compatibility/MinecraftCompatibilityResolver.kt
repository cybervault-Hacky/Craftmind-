package com.craftmind.app.domain.minecraft.compatibility

/** Resolves one exact registered adapter; it never chooses a nearest version or falls through to another edition. */
class MinecraftCompatibilityResolver(
    private val registry: MinecraftAdapterRegistry,
) {
    fun resolve(plan: com.craftmind.app.domain.buildplan.BuildPlan, runtime: MinecraftRuntimeDescriptor): MinecraftCompatibilityResult =
        resolve(runtime, BuildPlanRequirements.from(plan))

    fun resolveRuntime(runtime: MinecraftRuntimeDescriptor): MinecraftCompatibilityResult =
        resolve(runtime, BuildPlanRequirements.runtimeExecution)

    fun resolve(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult {
        val editionHasRegisteredAdapter = registry.allAdapters().any { adapter ->
            adapter.supportedRuntimeDescriptors.any { it.edition == runtime.edition }
        }
        if (runtime.edition in setOf(MinecraftEdition.BEDROCK, MinecraftEdition.LEGACY) && !editionHasRegisteredAdapter) {
            val reason = if (runtime.edition == MinecraftEdition.BEDROCK) {
                "No Bedrock adapter is registered. Bedrock is not routed through a Java/Fabric adapter."
            } else {
                "No Legacy-edition adapter is registered."
            }
            return unresolved(MinecraftCompatibilityStatus.UNSUPPORTED, requirements, listOf(reason))
        }

        val incomplete = incompleteRuntimeReasons(runtime)
        if (incomplete.isNotEmpty()) {
            return unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = incomplete,
            )
        }

        val matches = registry.compatibilityChecks(runtime, requirements)
        if (matches.size == 1) return matches.single()
        if (matches.size > 1) {
            return unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = listOf("More than one registered adapter claims this exact runtime; adapter selection is blocked."),
            )
        }

        val reason = when (runtime.edition) {
            MinecraftEdition.BEDROCK -> "No Bedrock adapter is registered. Bedrock is not routed through a Java/Fabric adapter."
            MinecraftEdition.LEGACY -> "No Legacy-edition adapter is registered."
            MinecraftEdition.JAVA -> "No registered adapter exactly matches this Java edition version, loader, bridge, and protocol profile."
            MinecraftEdition.UNKNOWN -> "The runtime edition is unknown."
        }
        return unresolved(
            status = if (runtime.edition == MinecraftEdition.UNKNOWN) MinecraftCompatibilityStatus.UNKNOWN
            else MinecraftCompatibilityStatus.UNSUPPORTED,
            requirements = requirements,
            reasons = listOf(reason),
        )
    }

    fun adapter(adapterId: MinecraftAdapterId?): MinecraftAdapter? = registry.adapter(adapterId)

    fun registeredAdapters(): List<MinecraftAdapter> = registry.allAdapters()

    private fun incompleteRuntimeReasons(runtime: MinecraftRuntimeDescriptor): List<String> = buildList {
        if (runtime.edition == MinecraftEdition.UNKNOWN) add("Edition could not be determined safely.")
        if (!runtime.version.isKnown) add("Minecraft version is missing or unrecognized.")
        if (runtime.loader == MinecraftLoader.UNKNOWN) add("Loader is missing or unrecognized.")
        if (runtime.bridgeProtocolVersion == null || runtime.bridgeProtocolVersion <= 0) {
            add("Bridge protocol version is missing or invalid.")
        }
        if (!SAFE_VERSION_TOKEN.matches(runtime.bridgeVersion.orEmpty()) ||
            runtime.bridgeVersion?.equals("unknown", ignoreCase = true) == true) {
            add("Bridge version is missing or unrecognized.")
        }
        if (!SAFE_VERSION_TOKEN.matches(runtime.loaderVersion.orEmpty()) ||
            runtime.loaderVersion?.equals("unknown", ignoreCase = true) == true) {
            add("Loader version is missing or unrecognized.")
        }
        if (runtime.javaRuntimeMajor != null && runtime.javaRuntimeMajor <= 0) {
            add("Reported server Java version is invalid.")
        }
    }

    private fun unresolved(
        status: MinecraftCompatibilityStatus,
        requirements: BuildPlanRequirements,
        reasons: List<String>,
    ) = MinecraftCompatibilityResult(
        status = status,
        adapterId = null,
        capabilities = emptySet(),
        missingCapabilities = requirements.requiredCapabilities,
        reasons = reasons,
        warnings = emptyList(),
        limits = MinecraftCompatibilityLimits(
            maximumValidatedOperations = null,
            maximumRequestBytes = null,
            maximumDimensions = null,
            javaToolchainMajor = null,
        ),
        planWithinLimits = false,
    )

    private companion object {
        val SAFE_VERSION_TOKEN = Regex("[A-Za-z0-9._+-]{1,64}")
    }
}

/** Stable UI/error mapping; detailed reasons and capability gaps remain available on the result. */
fun MinecraftCompatibilityResult.failureReasonCode(): String = when {
    status == MinecraftCompatibilityStatus.UNKNOWN -> "BRIDGE_COMPATIBILITY_UNKNOWN"
    status == MinecraftCompatibilityStatus.UNSUPPORTED -> "BRIDGE_RUNTIME_UNSUPPORTED"
    status == MinecraftCompatibilityStatus.EXPERIMENTAL -> "BRIDGE_RUNTIME_EXPERIMENTAL"
    !planWithinLimits -> "LIMIT_EXCEEDED"
    missingCapabilities.any {
        it in setOf(
            MinecraftCapability.WORLD_ACCESS,
            MinecraftCapability.BUILD_EXECUTION,
            MinecraftCapability.BLOCK_PLACEMENT,
            MinecraftCapability.CANCELLATION,
            MinecraftCapability.ORIGIN_RESOLUTION,
            MinecraftCapability.BUILD_PLAN_V2,
        )
    } -> "CONSTRUCTION_DISABLED"
    missingCapabilities.isNotEmpty() -> "BRIDGE_CAPABILITIES_UNSUPPORTED"
    else -> "BUILD_PLAN_NOT_EXECUTABLE"
}
