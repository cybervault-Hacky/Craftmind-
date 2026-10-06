package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * The single structural validation of a Minecraft runtime descriptor (Phase 13).
 *
 * Both consumers share it so runtime detection and compatibility resolution can never drift apart:
 * [MinecraftRuntimeDetector] turns these findings into a typed detection status, and
 * [MinecraftCompatibilityResolver] turns the same findings into an unresolved compatibility result. There is
 * deliberately no second rule set, no second descriptor model, and no auto-correction: contradictory facts are
 * reported as invalid, missing facts are reported as unknown/incomplete, and nothing is ever guessed, widened,
 * normalized to a nearest version, or silently substituted.
 */
internal object MinecraftRuntimeDescriptorValidation {
    private val SAFE_VERSION_TOKEN = Regex("[A-Za-z0-9._+-]{1,64}")

    fun isSafeVersionToken(value: String?): Boolean = value != null && SAFE_VERSION_TOKEN.matches(value)

    /**
     * Facts that contradict one another or violate the bridge protocol contract. A non-empty result makes the
     * descriptor invalid; the descriptor is never repaired.
     */
    fun invalidReasons(
        runtime: MinecraftRuntimeDescriptor,
        declaredProfiles: List<SupportedMinecraftRuntimeDescriptor>,
    ): List<Pair<MinecraftCompatibilityReasonCode, String>> = buildList {
        if (runtime.edition != MinecraftEdition.UNKNOWN && runtime.loader != MinecraftLoader.UNKNOWN &&
            runtime.loader.edition != runtime.edition
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported edition and loader are inconsistent.")
        }
        if (runtime.javaRuntimeMajor != null && runtime.javaRuntimeMajor !in 1..99) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported server Java runtime major is outside the valid range.")
        }
        if (runtime.bridgeProtocolVersion != null && runtime.bridgeProtocolVersion !in 1..64) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported bridge protocol version is outside the valid range.")
        }
        // Loader version is edition-dependent: a Java-style edition must report a bounded loader version exactly
        // as before, while Bedrock has no loader concept and must not report one at all (checked below). Only a
        // Bedrock runtime's *absent* loader version is therefore not malformed here.
        val loaderVersionIsMalformed = if (runtime.edition == MinecraftEdition.BEDROCK) {
            runtime.loaderVersion != null && !isSafeVersionToken(runtime.loaderVersion)
        } else {
            !isSafeVersionToken(runtime.loaderVersion)
        }
        if ((runtime.appVersion != null && !isSafeVersionToken(runtime.appVersion)) ||
            loaderVersionIsMalformed ||
            !isSafeVersionToken(runtime.bridgeVersion) ||
            (runtime.fabricApiVersion != null && !isSafeVersionToken(runtime.fabricApiVersion))
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "A runtime version field contains an invalid token.")
        }
        if (runtime.maximumValidatedOperations != null &&
            runtime.maximumValidatedOperations !in 1..BridgeProtocol.MAX_OPERATIONS
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported operation limit is outside the protocol bounds.")
        }
        if (runtime.maximumRequestBytes != null &&
            runtime.maximumRequestBytes !in BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported request-byte limit is outside the protocol bounds.")
        }
        if (runtime.maximumOperationsPerTick != null &&
            runtime.maximumOperationsPerTick !in 1..BridgeProtocol.MAX_OPERATIONS_PER_TICK
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported per-tick operation limit is outside the protocol bounds.")
        }
        if (runtime.maximumExecutionSeconds != null &&
            runtime.maximumExecutionSeconds !in 1..BridgeProtocol.MAX_EXECUTION_SECONDS
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported execution timeout is outside the protocol bounds.")
        }
        if (runtime.supportedBuildPlanSchemaVersions.size > 8 ||
            runtime.supportedBuildPlanSchemaVersions.any { it <= 0 } ||
            runtime.supportedBuildPlanSchemaVersions.toSet().size != runtime.supportedBuildPlanSchemaVersions.size
        ) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Reported BuildPlan schema versions are invalid.")
        }
        if (runtime.edition == MinecraftEdition.BEDROCK) {
            if (runtime.javaRuntimeMajor != null) {
                add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "A Bedrock runtime must not report a server Java runtime version.")
            }
            if (runtime.fabricApiVersion != null) {
                add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "A Bedrock runtime must not report a Fabric API version.")
            }
            if (runtime.loaderVersion != null) {
                add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "A Bedrock runtime must not report a loader version; Bedrock has no loader.")
            }
            if (runtime.platformVersion != null && !isSafeVersionToken(runtime.platformVersion)) {
                add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "The reported Bedrock platform version contains an invalid token.")
            }
        } else if (runtime.platform != MinecraftRuntimePlatform.UNKNOWN) {
            add(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to "Only a Bedrock runtime may report a Bedrock runtime platform.")
        }
        // A runtime may report integration limitations when it is genuinely outside the production release family: a
        // Bedrock/Legacy edition, a snapshot/beta/alpha/pre-release identifier, or an exact identity that this build
        // explicitly declares as a legacy/experimental contract. A production release runtime must not borrow them.
        val mayDeclareLimitations = runtime.edition == MinecraftEdition.BEDROCK ||
            runtime.edition == MinecraftEdition.LEGACY ||
            runtime.version.channel != MinecraftVersionChannel.RELEASE ||
            // Covering the reported edition + Minecraft version is enough: a runtime whose loader/bridge identity
            // does not match is then reported with its real reason (loader, protocol, or bridge mismatch) instead
            // of an unrelated descriptor error.
            declaredProfiles.any { profile ->
                profile.releaseChannel != MinecraftVersionChannel.RELEASE &&
                    profile.edition == runtime.edition && profile.version == runtime.version
            }
        if (!mayDeclareLimitations && runtime.limitations.isNotEmpty()) {
            add(
                MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to
                    "Only a Bedrock runtime may report Bedrock integration limitations.",
            )
        }
        // Phase 13 release-channel coherence: the channel is derived from the authoritative version identifier, so a
        // profile that declares a different channel family for that exact identity is a contradiction. The registry
        // already refuses to register such a profile; detection reports it instead of trusting either side.
        declaredProfiles
            .filter { it.matchesRuntimeIdentity(runtime) }
            .filter { !releaseChannelCoherent(it.releaseChannel, runtime.releaseChannel) }
            .forEach { profile ->
                add(
                    MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to
                        "The registered profile for ${profile.version.displayIdentifier} declares release channel " +
                        "${profile.releaseChannel.name}, which contradicts the reported identifier's channel " +
                        "${runtime.releaseChannel.name}. Contradictory runtime facts are never corrected silently.",
                )
            }
    }

    /** Authoritative facts the bridge must report before a runtime may be identified at all. */
    fun missingReasons(runtime: MinecraftRuntimeDescriptor): List<Pair<MinecraftCompatibilityReasonCode, String>> = buildList {
        if (runtime.edition == MinecraftEdition.UNKNOWN) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Edition could not be determined safely.")
        }
        if (!runtime.version.isKnown) {
            add(
                MinecraftCompatibilityReasonCode.UNKNOWN_MINECRAFT_VERSION to
                    "Minecraft version is missing or unrecognized; an unknown version is never assumed to be a nearby release.",
            )
            add(
                MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to
                    "The runtime descriptor stays incomplete while no trustworthy Minecraft version is reported.",
            )
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
        if (runtime.edition == MinecraftEdition.JAVA &&
            (!isSafeVersionToken(runtime.loaderVersion) || runtime.loaderVersion.equals("unknown", ignoreCase = true))
        ) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "Loader version is missing or unrecognized.")
        }
        if (runtime.edition == MinecraftEdition.JAVA && runtime.javaRuntimeMajor == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report the actual server Java runtime; compatibility cannot be inferred from build settings.")
        }
        if (runtime.loader == MinecraftLoader.FABRIC &&
            (runtime.fabricApiVersion == null || runtime.fabricApiVersion.equals("unknown", ignoreCase = true))
        ) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report the loaded Fabric API version; compatibility cannot be inferred from declared dependencies.")
        }
        if (runtime.edition == MinecraftEdition.BEDROCK) {
            if (runtime.platform == MinecraftRuntimePlatform.UNKNOWN) {
                add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The Bedrock bridge did not report its runtime platform; Bedrock compatibility cannot be inferred.")
            }
            if (runtime.loader != MinecraftLoader.BEDROCK_NATIVE) {
                add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The Bedrock runtime must report the Bedrock Native runtime; Java loader concepts do not apply to Bedrock.")
            }
        }
    }

    /**
     * Runtime-reported execution limits are mandatory: an absent limit is unknown, never defaulted, and never
     * loosened. Effective limits stay `min(CraftMind global limit, runtime-reported limit)` in the shared limit
     * evaluation, so a runtime that claims "unlimited" cannot bypass [com.craftmind.app.domain.buildplan.BuildPlanLimits].
     */
    fun missingLimitReasons(runtime: MinecraftRuntimeDescriptor): List<Pair<MinecraftCompatibilityReasonCode, String>> = buildList {
        if (runtime.maximumValidatedOperations == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report a validated-operation limit.")
        }
        if (runtime.maximumRequestBytes == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report a request-byte limit.")
        }
        if (runtime.maximumOperationsPerTick == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report a per-tick operation limit.")
        }
        if (runtime.maximumExecutionSeconds == null) {
            add(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR to "The bridge did not report an execution-time limit.")
        }
    }

    /**
     * Phase 13 capability coherence, used by runtime detection: the capability set and the runtime facts it
     * describes must agree. A report that claims world access or an operator-selected origin without the matching
     * authenticated capability (or the other way round) is forged rather than merely missing a capability, so
     * detection rejects it. A bridge that simply omits a capability stays a `MISSING_CAPABILITY` compatibility
     * result, exactly as in Phases 9-12.
     */
    fun forgedCapabilityReasons(runtime: MinecraftRuntimeDescriptor): List<Pair<MinecraftCompatibilityReasonCode, String>> = buildList {
        if (runtime.worldAvailable != (MinecraftCapability.WORLD_ACCESS in runtime.capabilities)) {
            add(
                MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to
                    "Reported world availability contradicts the authenticated capability set.",
            )
        }
        if (runtime.operatorOriginAvailable != (MinecraftCapability.ORIGIN_RESOLUTION in runtime.capabilities)) {
            add(
                MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to
                    "Reported operator origin availability contradicts the authenticated capability set.",
            )
        }
        if (MinecraftCapability.UNKNOWN in runtime.capabilities) {
            add(
                MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR to
                    "The bridge reported a capability this app build does not define; capabilities are never widened.",
            )
        }
    }

    /**
     * The registry's own rule, shared with detection: a profile may only claim a release channel that its exact
     * version identifier can actually carry. A LEGACY profile may describe an older *release* token (1.7.10) — that
     * explicit registry decision is the only reason such a runtime is treated as legacy — while a beta/alpha/
     * snapshot token keeps its channel.
     */
    fun releaseChannelCoherent(declared: MinecraftVersionChannel, reported: MinecraftVersionChannel): Boolean =
        declared != MinecraftVersionChannel.UNKNOWN &&
            (declared == reported || (declared == MinecraftVersionChannel.LEGACY && reported == MinecraftVersionChannel.RELEASE))
}
