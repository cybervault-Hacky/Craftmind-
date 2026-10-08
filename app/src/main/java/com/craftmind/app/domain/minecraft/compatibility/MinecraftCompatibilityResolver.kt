package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlan

/**
 * Resolves one exact registered profile; it never chooses a nearest version or falls through to another edition.
 *
 * Phase 13 keeps a single resolution path: descriptor validation comes from the shared
 * [MinecraftRuntimeDescriptorValidation] rules and adapter matching comes from the shared
 * [MinecraftAdapterSelector], so runtime detection, adapter selection, and compatibility resolution cannot drift.
 */
class MinecraftCompatibilityResolver(
    private val registry: MinecraftAdapterRegistry,
    val adapterSelector: MinecraftAdapterSelector = MinecraftAdapterSelector(registry),
) {
    fun resolve(plan: BuildPlan, runtime: MinecraftRuntimeDescriptor): MinecraftCompatibilityResult =
        resolve(runtime, BuildPlanRequirements.from(plan))

    fun resolveRuntime(runtime: MinecraftRuntimeDescriptor): MinecraftCompatibilityResult =
        resolve(runtime, BuildPlanRequirements.runtimeExecution)

    fun resolve(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult {
        val malformed = malformedRuntimeReasons(runtime)
        if (malformed.isNotEmpty()) {
            return unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = malformed.map { it.second },
                reasonCodes = malformed.map { it.first }.toSet(),
            )
        }

        val profiles = registry.allProfiles()
        if (runtime.edition != MinecraftEdition.UNKNOWN && runtime.edition !in registry.declaredEditions()) {
            return unresolved(
                status = MinecraftCompatibilityStatus.UNSUPPORTED,
                requirements = requirements,
                reasons = listOf("No ${runtime.edition.displayName} runtime adapter is registered. It is not routed through a Java/Fabric adapter."),
                reasonCodes = setOf(MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER),
            )
        }

        val incomplete = incompleteRuntimeReasons(runtime)
        if (incomplete.isNotEmpty()) {
            return unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = incomplete.map { it.second },
                reasonCodes = incomplete.map { it.first }.toSet(),
            )
        }

        // Deterministic adapter selection (Phase 13): exact matches only, ambiguity blocked, no nearest adapter.
        val selection = adapterSelector.select(runtime)
        return when (selection.status) {
            MinecraftAdapterSelectionStatus.AMBIGUOUS -> unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = listOf("More than one registered adapter claims this exact runtime; adapter selection is blocked."),
                reasonCodes = setOf(MinecraftCompatibilityReasonCode.AMBIGUOUS_ADAPTER_PROFILE),
            )

            MinecraftAdapterSelectionStatus.INVALID -> unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = selection.reasons,
                reasonCodes = selection.reasonCodes,
            )

            MinecraftAdapterSelectionStatus.NO_MATCH ->
                if (selection.reasonCodes.isNotEmpty()) {
                    unresolved(
                        status = MinecraftCompatibilityStatus.UNSUPPORTED,
                        requirements = requirements,
                        reasons = selection.reasons,
                        reasonCodes = selection.reasonCodes,
                    )
                } else {
                    unresolvedForUnsupportedIdentity(runtime, requirements, profiles)
                }

            MinecraftAdapterSelectionStatus.SELECTED ->
                // Selection is not an execution authorization: the selected adapter still reports status,
                // certification, capabilities, limits, and content compatibility for this exact runtime.
                selection.adapter?.compatibilityCheck(runtime, requirements) ?: unresolved(
                    status = MinecraftCompatibilityStatus.UNKNOWN,
                    requirements = requirements,
                    reasons = listOf("A registered runtime profile matched, but no adapter accepted it. Adapter selection is blocked."),
                    reasonCodes = setOf(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR),
                )
        }
    }

    /** Phase 13 automatic runtime detection over the same registry this resolver uses. */
    val runtimeDetector: MinecraftRuntimeDetector by lazy { MinecraftRuntimeDetector(registry) }

    /**
     * Phase 13 pipeline entry point built from this resolver's own detector, selector, and registry, so detection,
     * selection, and resolution can never disagree about which runtime is authoritative.
     */
    val runtimeGate: MinecraftRuntimeCompatibilityGate by lazy {
        MinecraftRuntimeCompatibilityGate(runtimeDetector, adapterSelector, this)
    }

    fun adapter(adapterId: MinecraftAdapterId?): MinecraftAdapter? = registry.adapter(adapterId)

    fun registeredAdapters(): List<MinecraftAdapter> = registry.allAdapters()

    fun registeredProfiles(): List<SupportedMinecraftRuntimeDescriptor> = registry.allProfiles()

    /** Bedrock contract identities registered by this build; empty certification lists mean no Bedrock target. */
    fun registeredBedrockProfiles(): List<BedrockRuntimeProfile> = registry.allBedrockProfiles()

    private fun unresolvedForUnsupportedIdentity(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
        profiles: List<SupportedMinecraftRuntimeDescriptor>,
    ): MinecraftCompatibilityResult {
        fun unsupported(
            code: MinecraftCompatibilityReasonCode,
            message: String,
        ) = unresolved(
            status = MinecraftCompatibilityStatus.UNSUPPORTED,
            requirements = requirements,
            reasons = listOf(message),
            reasonCodes = setOf(code),
        )

        if (runtime.edition == MinecraftEdition.BEDROCK) {
            return unresolvedBedrockIdentity(runtime, requirements)
        }
        if (runtime.edition == MinecraftEdition.LEGACY) {
            val edition = runtime.edition.displayName
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER,
                "No $edition runtime adapter is registered. It is not routed through a Java/Fabric adapter.",
            )
        }
        val sameEditionAndVersion = profiles.filter { it.edition == runtime.edition && it.version == runtime.version }
        if (sameEditionAndVersion.isEmpty()) {
            // The identifier's own channel decides which diagnosis is honest. A legacy identifier (classic/beta-era
            // token), a snapshot, or a beta/alpha/pre-release build is never mapped to a release version, and a
            // release token is never treated as legacy just because it is old: only a registered profile declares
            // legacy status.
            when (runtime.version.channel) {
                MinecraftVersionChannel.LEGACY -> return unsupported(
                    MinecraftCompatibilityReasonCode.UNSUPPORTED_LEGACY_VERSION,
                    "Minecraft ${runtime.version.displayIdentifier} is a legacy Minecraft identifier with no registered " +
                        "compatibility profile. Legacy identifiers are never mapped to a release build or to a nearest version.",
                )

                MinecraftVersionChannel.PRE_RELEASE,
                MinecraftVersionChannel.SNAPSHOT,
                MinecraftVersionChannel.BETA,
                MinecraftVersionChannel.ALPHA,
                -> return unsupported(
                    MinecraftCompatibilityReasonCode.UNSUPPORTED_RELEASE_CHANNEL,
                    "Minecraft ${runtime.version.displayIdentifier} is a ${runtime.version.channel.name} build and no " +
                        "registered compatibility profile covers that release channel. A snapshot, beta, alpha, or " +
                        "pre-release runtime is never matched to a release version and is not silently treated as supported.",
                )

                else -> return unsupported(
                    MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION,
                    "Minecraft ${runtime.version.identifier} has no registered production adapter. No nearest-version fallback is used.",
                )
            }
        }
        val sameLoader = sameEditionAndVersion.filter { it.loader == runtime.loader }
        if (sameLoader.isEmpty()) {
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER,
                "${runtime.loader.displayName} is not supported for Minecraft ${runtime.version.identifier}; no adapter is registered.",
            )
        }
        val sameLoaderVersion = sameLoader.filter { it.loaderVersion == runtime.loaderVersion }
        if (sameLoaderVersion.isEmpty()) {
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER_VERSION,
                "${runtime.loader.displayName} loader ${runtime.loaderVersion} does not match a registered profile for Minecraft ${runtime.version.identifier}.",
            )
        }
        val sameProtocol = sameLoaderVersion.filter { it.bridgeProtocolVersion == runtime.bridgeProtocolVersion }
        if (sameProtocol.isEmpty()) {
            val supported = sameLoaderVersion.map { it.bridgeProtocolVersion }.distinct().sorted().joinToString()
            return unsupported(
                MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH,
                "Bridge protocol ${runtime.bridgeProtocolVersion} is not supported by this app; registered protocol version(s): $supported. Update the bridge and app together.",
            )
        }
        val sameBridge = sameProtocol.filter { it.bridgeVersion == runtime.bridgeVersion }
        if (sameBridge.isEmpty()) {
            val supported = sameProtocol.map { it.bridgeVersion }.distinct().sorted().joinToString()
            return unsupported(
                MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH,
                "CraftMind Bridge ${runtime.bridgeVersion} is not registered for protocol ${runtime.bridgeProtocolVersion}; expected $supported.",
            )
        }
        return unsupported(
            MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            "The reported runtime did not match a complete registered compatibility profile.",
        )
    }

    /**
     * Explains why a reported Bedrock runtime did not match a registered Bedrock contract. Bedrock is never
     * matched by version proximity, never routed through the Java/Fabric adapter, and never inferred from the
     * app-side selection.
     */
    private fun unresolvedBedrockIdentity(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult {
        fun unsupported(
            code: MinecraftCompatibilityReasonCode,
            message: String,
        ) = unresolved(
            status = MinecraftCompatibilityStatus.UNSUPPORTED,
            requirements = requirements,
            reasons = listOf(message),
            reasonCodes = setOf(code),
        )

        val contracts = registry.allBedrockProfiles()
        if (contracts.isEmpty()) {
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER,
                "No ${MinecraftEdition.BEDROCK.displayName} runtime adapter is registered. It is not routed through a Java/Fabric adapter.",
            )
        }
        val sameProtocol = contracts.filter { it.bridgeProtocolVersion == runtime.bridgeProtocolVersion }
        if (sameProtocol.isEmpty()) {
            val supported = contracts.map { it.bridgeProtocolVersion }.distinct().sorted().joinToString()
            return unsupported(
                MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH,
                "Bridge protocol ${runtime.bridgeProtocolVersion} is not supported by this app for a Bedrock runtime; " +
                    "registered Bedrock contract protocol version(s): $supported. Update the bridge and app together.",
            )
        }
        val sameBridge = sameProtocol.filter { it.bridgeVersion == runtime.bridgeVersion }
        if (sameBridge.isEmpty()) {
            val supported = sameProtocol.map { it.bridgeVersion }.distinct().sorted().joinToString()
            return unsupported(
                MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH,
                "The reported Bedrock bridge version ${runtime.bridgeVersion} is not registered for protocol " +
                    "${runtime.bridgeProtocolVersion}; expected $supported.",
            )
        }
        if (sameBridge.none { runtime.platform in it.supportedPlatforms }) {
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_BEDROCK_PLATFORM,
                "The reported Bedrock runtime platform (${runtime.platform.displayName}) is not part of a registered " +
                    "CraftMind Bedrock contract. Platforms are never inferred or widened.",
            )
        }
        return unsupported(
            MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
            "The reported Bedrock runtime did not match a complete registered Bedrock contract.",
        )
    }

    /** Shared Phase 13 descriptor validation: one rule set for detection and resolution. */
    private fun malformedRuntimeReasons(runtime: MinecraftRuntimeDescriptor) =
        MinecraftRuntimeDescriptorValidation.invalidReasons(runtime, registry.allProfiles())

    private fun incompleteRuntimeReasons(runtime: MinecraftRuntimeDescriptor) =
        MinecraftRuntimeDescriptorValidation.missingReasons(runtime)

    private fun unresolved(
        status: MinecraftCompatibilityStatus,
        requirements: BuildPlanRequirements,
        reasons: List<String>,
        reasonCodes: Set<MinecraftCompatibilityReasonCode>,
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
            javaRuntimeRequirement = null,
            maximumOperationsPerTick = null,
            maximumExecutionSeconds = null,
        ),
        planWithinLimits = false,
        reasonCodes = reasonCodes,
    )

}

/** Stable UI/error mapping; typed resolver reasons remain available on the result. */
fun MinecraftCompatibilityResult.failureReasonCode(): String = when {
    MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH in reasonCodes -> "APP_VERSION_MISMATCH"
    MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED in reasonCodes -> "RUNTIME_DETECTION_UNAUTHORIZED"
    MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in reasonCodes -> "SESSION_IDENTITY_MISMATCH"
    MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED in reasonCodes -> "RUNTIME_IDENTITY_CHANGED"
    status == MinecraftCompatibilityStatus.UNKNOWN -> "BRIDGE_COMPATIBILITY_UNKNOWN"
    MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME in reasonCodes -> "BRIDGE_JAVA_RUNTIME_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH in reasonCodes -> "BRIDGE_FABRIC_API_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in reasonCodes -> "BRIDGE_PROTOCOL_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH in reasonCodes -> "BRIDGE_VERSION_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_BEDROCK_PLATFORM in reasonCodes -> "BEDROCK_PLATFORM_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_LEGACY_VERSION in reasonCodes -> "UNSUPPORTED_LEGACY_VERSION"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_RELEASE_CHANNEL in reasonCodes -> "UNSUPPORTED_RELEASE_CHANNEL"
    status == MinecraftCompatibilityStatus.UNSUPPORTED -> "BRIDGE_RUNTIME_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED in reasonCodes -> "BEDROCK_RUNTIME_NOT_CERTIFIED"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in reasonCodes -> "BUILD_PLAN_SCHEMA_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK_STATE in reasonCodes -> "UNSUPPORTED_BLOCK_STATE"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK in reasonCodes -> "UNSUPPORTED_BLOCK"
    !planContentSupported -> "UNSUPPORTED_BLOCK_STATE"
    MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in reasonCodes || !planWithinLimits -> "LIMIT_EXCEEDED"
    MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in reasonCodes || missingCapabilities.isNotEmpty() ->
        if (missingCapabilities.any { it in CONSTRUCTION_CAPABILITIES }) "CONSTRUCTION_DISABLED"
        else "BRIDGE_CAPABILITIES_UNSUPPORTED"
    // A recognized but uncertified runtime is reported only after any concrete plan-specific cause, so the plan
    // review still names an actionable reason; it is always more specific than the plain EXPERIMENTAL fallback.
    MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED in reasonCodes -> "RUNTIME_NOT_CERTIFIED"
    status == MinecraftCompatibilityStatus.EXPERIMENTAL -> "BRIDGE_RUNTIME_EXPERIMENTAL"
    else -> "BUILD_PLAN_NOT_EXECUTABLE"
}

private val CONSTRUCTION_CAPABILITIES = setOf(
    MinecraftCapability.WORLD_ACCESS,
    MinecraftCapability.BUILD_EXECUTION,
    MinecraftCapability.BLOCK_PLACEMENT,
    MinecraftCapability.CANCELLATION,
    MinecraftCapability.ORIGIN_RESOLUTION,
    MinecraftCapability.BUILD_PLAN_V2,
)
