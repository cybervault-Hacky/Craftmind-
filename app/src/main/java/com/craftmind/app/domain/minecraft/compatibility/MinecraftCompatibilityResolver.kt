package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.bridge.protocol.BridgeProtocol

/** Resolves one exact registered profile; it never chooses a nearest version or falls through to another edition. */
class MinecraftCompatibilityResolver(
    private val registry: MinecraftAdapterRegistry,
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
        if (runtime.edition in setOf(MinecraftEdition.BEDROCK, MinecraftEdition.LEGACY) &&
            profiles.none { it.edition == runtime.edition }) {
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

        val exactIdentityProfiles = profiles.filter { it.matchesRuntimeIdentity(runtime) }
        if (exactIdentityProfiles.isEmpty()) {
            return unresolvedForUnsupportedIdentity(runtime, requirements, profiles)
        }

        val javaCompatible = exactIdentityProfiles.filter { profile ->
            profile.javaRuntimeRequirement?.supports(runtime.javaRuntimeMajor ?: return@filter false) ?: true
        }
        val apiCompatible = javaCompatible.filter { profile ->
            profile.requiredFabricApiVersion == null || profile.requiredFabricApiVersion == runtime.fabricApiVersion
        }
        if (apiCompatible.isEmpty()) {
            val reasons = mutableListOf<String>()
            val reasonCodes = linkedSetOf<MinecraftCompatibilityReasonCode>()
            if (javaCompatible.isEmpty() && exactIdentityProfiles.any { it.javaRuntimeRequirement != null }) {
                val requirement = exactIdentityProfiles.firstNotNullOf { it.javaRuntimeRequirement }
                reasonCodes += MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME
                reasons += "The bridge reports Java ${runtime.javaRuntimeMajor}; this profile requires Java ${requirement.requiredMajor} (supported ${requirement.minimumSupportedMajor}–${requirement.maximumSupportedMajor})."
            }
            val profilesWithFabricApiRequirement = exactIdentityProfiles.filter { it.requiredFabricApiVersion != null }
            if (profilesWithFabricApiRequirement.isNotEmpty() &&
                profilesWithFabricApiRequirement.none { it.requiredFabricApiVersion == runtime.fabricApiVersion }) {
                val expected = profilesWithFabricApiRequirement.firstNotNullOf { it.requiredFabricApiVersion }
                reasonCodes += MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH
                reasons += "The bridge reports Fabric API ${runtime.fabricApiVersion}; this profile requires exactly $expected."
            }
            if (reasons.isEmpty()) {
                reasonCodes += MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR
                reasons += "The runtime descriptor does not satisfy a registered profile."
            }
            return unresolved(
                status = MinecraftCompatibilityStatus.UNSUPPORTED,
                requirements = requirements,
                reasons = reasons,
                reasonCodes = reasonCodes,
            )
        }

        val matches = registry.compatibilityChecks(runtime, requirements)
        if (matches.size == 1) return matches.single()
        if (matches.size > 1) {
            return unresolved(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                requirements = requirements,
                reasons = listOf("More than one registered adapter claims this exact runtime; adapter selection is blocked."),
                reasonCodes = setOf(MinecraftCompatibilityReasonCode.AMBIGUOUS_ADAPTER_PROFILE),
            )
        }

        return unresolved(
            status = MinecraftCompatibilityStatus.UNKNOWN,
            requirements = requirements,
            reasons = listOf("A registered runtime profile matched, but no adapter accepted it. Adapter selection is blocked."),
            reasonCodes = setOf(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR),
        )
    }

    fun adapter(adapterId: MinecraftAdapterId?): MinecraftAdapter? = registry.adapter(adapterId)

    fun registeredAdapters(): List<MinecraftAdapter> = registry.allAdapters()

    fun registeredProfiles(): List<SupportedMinecraftRuntimeDescriptor> = registry.allProfiles()

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

        if (runtime.edition in setOf(MinecraftEdition.BEDROCK, MinecraftEdition.LEGACY)) {
            val edition = runtime.edition.displayName
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER,
                "No $edition runtime adapter is registered. It is not routed through a Java/Fabric adapter.",
            )
        }
        val sameEditionAndVersion = profiles.filter { it.edition == runtime.edition && it.version == runtime.version }
        if (sameEditionAndVersion.isEmpty()) {
            return unsupported(
                MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION,
                "Minecraft ${runtime.version.identifier} has no registered production adapter. No nearest-version fallback is used.",
            )
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

    private fun malformedRuntimeReasons(runtime: MinecraftRuntimeDescriptor): List<Pair<MinecraftCompatibilityReasonCode, String>> = buildList {
        if (runtime.edition != MinecraftEdition.UNKNOWN && runtime.loader != MinecraftLoader.UNKNOWN &&
            runtime.loader.edition != runtime.edition) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported edition and loader are inconsistent.")
        }
        if (runtime.javaRuntimeMajor != null && runtime.javaRuntimeMajor !in 1..99) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported server Java runtime major is outside the valid range.")
        }
        if (runtime.bridgeProtocolVersion != null && runtime.bridgeProtocolVersion !in 1..64) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported bridge protocol version is outside the valid range.")
        }
        if ((runtime.appVersion != null && !isSafeVersionToken(runtime.appVersion)) ||
            !isSafeVersionToken(runtime.loaderVersion) || !isSafeVersionToken(runtime.bridgeVersion) ||
            (runtime.fabricApiVersion != null && !isSafeVersionToken(runtime.fabricApiVersion))) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "A runtime version field contains an invalid token.")
        }
        if (runtime.maximumValidatedOperations != null &&
            runtime.maximumValidatedOperations !in 1..BridgeProtocol.MAX_OPERATIONS) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported operation limit is outside the protocol bounds.")
        }
        if (runtime.maximumRequestBytes != null &&
            runtime.maximumRequestBytes !in BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported request-byte limit is outside the protocol bounds.")
        }
        if (runtime.maximumOperationsPerTick != null &&
            runtime.maximumOperationsPerTick !in 1..BridgeProtocol.MAX_OPERATIONS_PER_TICK) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported per-tick operation limit is outside the protocol bounds.")
        }
        if (runtime.maximumExecutionSeconds != null &&
            runtime.maximumExecutionSeconds !in 1..BridgeProtocol.MAX_EXECUTION_SECONDS) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported execution timeout is outside the protocol bounds.")
        }
        if (runtime.supportedBuildPlanSchemaVersions.size > 8 ||
            runtime.supportedBuildPlanSchemaVersions.any { it <= 0 } ||
            runtime.supportedBuildPlanSchemaVersions.toSet().size != runtime.supportedBuildPlanSchemaVersions.size) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported BuildPlan schema versions are invalid.")
        }
    }

    private fun incompleteRuntimeReasons(runtime: MinecraftRuntimeDescriptor): List<Pair<MinecraftCompatibilityReasonCode, String>> = buildList {
        if (runtime.edition == MinecraftEdition.UNKNOWN) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Edition could not be determined safely.")
        }
        if (!runtime.version.isKnown) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Minecraft version is missing or unrecognized.")
        }
        if (runtime.loader == MinecraftLoader.UNKNOWN) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Loader is missing or unrecognized.")
        }
        if (runtime.bridgeProtocolVersion == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Bridge protocol version was not reported.")
        }
        if (!isSafeVersionToken(runtime.bridgeVersion) || runtime.bridgeVersion.equals("unknown", ignoreCase = true)) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Bridge version is missing or unrecognized.")
        }
        if (!isSafeVersionToken(runtime.loaderVersion) || runtime.loaderVersion.equals("unknown", ignoreCase = true)) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Loader version is missing or unrecognized.")
        }
        if (runtime.edition == MinecraftEdition.JAVA && runtime.javaRuntimeMajor == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report the actual server Java runtime; compatibility cannot be inferred from build settings.")
        }
        if (runtime.loader == MinecraftLoader.FABRIC &&
            (runtime.fabricApiVersion == null || runtime.fabricApiVersion.equals("unknown", ignoreCase = true))) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report the loaded Fabric API version; compatibility cannot be inferred from declared dependencies.")
        }
    }

    private fun isSafeVersionToken(value: String?): Boolean =
        value != null && SAFE_VERSION_TOKEN.matches(value)

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

    private companion object {
        val SAFE_VERSION_TOKEN = Regex("[A-Za-z0-9._+-]{1,64}")
    }
}

/** Stable UI/error mapping; typed resolver reasons remain available on the result. */
fun MinecraftCompatibilityResult.failureReasonCode(): String = when {
    status == MinecraftCompatibilityStatus.UNKNOWN -> "BRIDGE_COMPATIBILITY_UNKNOWN"
    MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME in reasonCodes -> "BRIDGE_JAVA_RUNTIME_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH in reasonCodes -> "BRIDGE_FABRIC_API_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in reasonCodes -> "BRIDGE_PROTOCOL_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH in reasonCodes -> "BRIDGE_VERSION_UNSUPPORTED"
    status == MinecraftCompatibilityStatus.UNSUPPORTED -> "BRIDGE_RUNTIME_UNSUPPORTED"
    status == MinecraftCompatibilityStatus.EXPERIMENTAL -> "BRIDGE_RUNTIME_EXPERIMENTAL"
    MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in reasonCodes -> "BUILD_PLAN_SCHEMA_UNSUPPORTED"
    MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in reasonCodes || !planWithinLimits -> "LIMIT_EXCEEDED"
    MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in reasonCodes || missingCapabilities.isNotEmpty() ->
        if (missingCapabilities.any { it in CONSTRUCTION_CAPABILITIES }) "CONSTRUCTION_DISABLED"
        else "BRIDGE_CAPABILITIES_UNSUPPORTED"
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
