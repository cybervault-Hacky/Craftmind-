package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.bridge.protocol.BridgeProtocol

/** Runtime-specific policy plus a thin mapping onto CraftMind's existing secure bridge contract. */
interface MinecraftAdapter {
    val adapterId: MinecraftAdapterId

    /** Version-keyed runtime identities (Java editions). Empty only for a contract-keyed Bedrock adapter. */
    val supportedRuntimeDescriptors: List<SupportedMinecraftRuntimeDescriptor>

    /**
     * Bedrock contract identities, keyed by bridge identity instead of an exact Minecraft version because no
     * Bedrock runtime version is certified. Empty for Java adapters; an adapter must never mix both families.
     */
    val bedrockRuntimeProfiles: List<BedrockRuntimeProfile> get() = emptyList()

    /** Returns null unless this adapter exactly recognizes the runtime profile. */
    fun compatibilityCheck(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult?

    suspend fun preflight(
        bridge: MinecraftBridgePairingRepository,
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionPreview

    suspend fun execute(
        bridge: MinecraftBridgePairingRepository,
        preview: MinecraftExecutionPreview,
    ): MinecraftExecutionSnapshot

    suspend fun cancel(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftCancellationResult

    suspend fun status(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftExecutionQueryResult
}

sealed interface MinecraftAdapterRegistrationResult {
    data object Registered : MinecraftAdapterRegistrationResult
    data class DuplicateAdapterId(val adapterId: MinecraftAdapterId) : MinecraftAdapterRegistrationResult
    data class AdapterIdMismatch(val adapterId: MinecraftAdapterId, val profileAdapterId: MinecraftAdapterId) :
        MinecraftAdapterRegistrationResult
    data class EmptyRuntimeProfiles(val adapterId: MinecraftAdapterId) : MinecraftAdapterRegistrationResult
    data class DuplicateRuntimeProfile(val adapterId: MinecraftAdapterId, val existingAdapterId: MinecraftAdapterId) :
        MinecraftAdapterRegistrationResult
    data class InvalidRuntimeProfile(val adapterId: MinecraftAdapterId, val reason: String) :
        MinecraftAdapterRegistrationResult
}

/** A small explicit registry; ambiguous and duplicate profiles are rejected rather than first-match selected. */
class MinecraftAdapterRegistry {
    private val adapters = linkedMapOf<MinecraftAdapterId, MinecraftAdapter>()

    @Synchronized
    fun register(adapter: MinecraftAdapter): MinecraftAdapterRegistrationResult {
        if (adapter.adapterId in adapters) {
            return MinecraftAdapterRegistrationResult.DuplicateAdapterId(adapter.adapterId)
        }
        val bedrockProfiles = adapter.bedrockRuntimeProfiles
        if (adapter.supportedRuntimeDescriptors.isEmpty() && bedrockProfiles.isEmpty()) {
            return MinecraftAdapterRegistrationResult.EmptyRuntimeProfiles(adapter.adapterId)
        }
        if (adapter.supportedRuntimeDescriptors.isNotEmpty() && bedrockProfiles.isNotEmpty()) {
            return MinecraftAdapterRegistrationResult.InvalidRuntimeProfile(
                adapter.adapterId,
                "an adapter must not mix version-keyed runtime profiles with Bedrock contract profiles",
            )
        }
        val invalidProfile = adapter.supportedRuntimeDescriptors.firstOrNull { it.adapterId != adapter.adapterId }
        if (invalidProfile != null) {
            return MinecraftAdapterRegistrationResult.AdapterIdMismatch(adapter.adapterId, invalidProfile.adapterId)
        }
        val invalidBedrockProfile = bedrockProfiles.firstOrNull { it.adapterId != adapter.adapterId }
        if (invalidBedrockProfile != null) {
            return MinecraftAdapterRegistrationResult.AdapterIdMismatch(adapter.adapterId, invalidBedrockProfile.adapterId)
        }
        adapter.supportedRuntimeDescriptors.forEachIndexed { index, profile ->
            invalidProfileReason(profile)?.let { reason ->
                return MinecraftAdapterRegistrationResult.InvalidRuntimeProfile(adapter.adapterId, reason)
            }
            if (adapter.supportedRuntimeDescriptors.drop(index + 1).any(profile::hasSameRuntimeIdentity)) {
                return MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(adapter.adapterId, adapter.adapterId)
            }
        }
        bedrockProfiles.forEachIndexed { index, profile ->
            invalidBedrockProfileReason(profile)?.let { reason ->
                return MinecraftAdapterRegistrationResult.InvalidRuntimeProfile(adapter.adapterId, reason)
            }
            if (bedrockProfiles.drop(index + 1).any(profile::hasSameBridgeIdentity)) {
                return MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(adapter.adapterId, adapter.adapterId)
            }
        }
        val overlap = adapters.values.firstOrNull { existing ->
            existing.supportedRuntimeDescriptors.any { oldProfile ->
                adapter.supportedRuntimeDescriptors.any(oldProfile::hasSameRuntimeIdentity)
            }
        }
        if (overlap != null) {
            return MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(adapter.adapterId, overlap.adapterId)
        }
        val bedrockOverlap = adapters.values.firstOrNull { existing ->
            existing.bedrockRuntimeProfiles.any { oldProfile ->
                bedrockProfiles.any(oldProfile::hasSameBridgeIdentity)
            }
        }
        if (bedrockOverlap != null) {
            return MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(adapter.adapterId, bedrockOverlap.adapterId)
        }
        adapters[adapter.adapterId] = adapter
        return MinecraftAdapterRegistrationResult.Registered
    }

    private fun invalidProfileReason(profile: SupportedMinecraftRuntimeDescriptor): String? = when {
        profile.edition == MinecraftEdition.UNKNOWN || !profile.version.isKnown || profile.loader == MinecraftLoader.UNKNOWN ->
            "edition, stable version, and loader must be explicit"
        // Phase 13: Bedrock identities are contract-keyed because no Bedrock Minecraft version is certified. A
        // version-keyed Bedrock profile could overlap a Bedrock contract and make adapter selection ambiguous, so
        // the registry refuses it instead of leaving the ambiguity to be discovered at selection time.
        profile.edition == MinecraftEdition.BEDROCK ->
            "a Bedrock runtime must be matched by a Bedrock contract profile, never by a version-keyed profile"
        profile.loader.edition != profile.edition -> "edition and loader family are inconsistent"
        profile.supportStatus !in setOf(MinecraftCompatibilityStatus.SUPPORTED, MinecraftCompatibilityStatus.EXPERIMENTAL) ->
            "only implemented supported or explicitly experimental profiles may be registered"
        !releaseChannelMatches(profile.releaseChannel, profile.version.channel) ->
            "the declared release channel must match the version identifier's channel (a legacy profile may declare a release token)"
        profile.supportStatus == MinecraftCompatibilityStatus.SUPPORTED && !profile.runtimeCertification.authorizesSupport ->
            "a SUPPORTED profile requires recorded runtime certification that authorizes support"
        profile.isLegacyOrExperimental && profile.limitations.isEmpty() ->
            "a legacy/pre-release/snapshot/beta/alpha profile must declare its limitations"
        !MinecraftAdapterRegistry.SAFE_VERSION.matches(profile.blockStateSupportRevision) ->
            "block/state support revision must be a bounded exact token"
        profile.contentValidationMode == MinecraftContentValidationMode.APP_SIDE_MAPPING &&
            profile.blockStateCatalog.isDeclared &&
            profile.blockStateSupportRevision == MinecraftTargetBlockStateCatalog.NONE_DECLARED_REVISION ->
            "a declared block/state mapping requires a real support revision"
        !SAFE_VERSION.matches(profile.loaderVersion) || !SAFE_VERSION.matches(profile.bridgeVersion) ->
            "loader and bridge versions must be bounded exact tokens"
        profile.bridgeProtocolVersion != BridgeProtocol.VERSION -> "bridge protocol version is not supported by this app build"
        profile.edition == MinecraftEdition.JAVA && profile.javaRuntimeRequirement == null ->
            "Java runtime compatibility must be explicit"
        profile.loader == MinecraftLoader.FABRIC && profile.requiredFabricApiVersion == null ->
            "Fabric API compatibility must be explicit"
        profile.requiredFabricApiVersion?.let { !SAFE_VERSION.matches(it) } == true ->
            "Fabric API version must be a bounded exact token"
        profile.maximumValidatedOperations !in 1..BridgeProtocol.MAX_OPERATIONS ->
            "operation limit is outside the shared protocol bounds"
        profile.maximumRequestBytes !in BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES ->
            "request limit is outside the shared protocol bounds"
        profile.maximumOperationsPerTick !in 1..BridgeProtocol.MAX_OPERATIONS_PER_TICK ->
            "per-tick limit is outside the shared protocol bounds"
        profile.maximumExecutionSeconds !in 1..BridgeProtocol.MAX_EXECUTION_SECONDS ->
            "execution timeout is outside the shared protocol bounds"
        profile.maximumDimensions.width !in 1..BuildPlanLimits.MAX_BUILD_WIDTH ||
            profile.maximumDimensions.height !in 1..BuildPlanLimits.MAX_BUILD_HEIGHT ||
            profile.maximumDimensions.depth !in 1..BuildPlanLimits.MAX_BUILD_DEPTH ->
            "dimension limits must remain within shared BuildPlan limits"
        else -> null
    }

    /**
     * A Bedrock contract is only registrable when it is honest about what has been verified: `SUPPORTED`
     * requires recorded runtime certification plus at least one exact certified Bedrock version.
     */
    private fun invalidBedrockProfileReason(profile: BedrockRuntimeProfile): String? = when {
        profile.edition != MinecraftEdition.BEDROCK -> "a Bedrock contract must declare the Bedrock edition"
        profile.supportedPlatforms.isEmpty() || MinecraftRuntimePlatform.UNKNOWN in profile.supportedPlatforms ->
            "Bedrock contract platforms must be explicit and must not include the unknown platform"
        profile.contractCapabilities.isEmpty() || MinecraftCapability.UNKNOWN in profile.contractCapabilities ->
            "declared Bedrock contract capabilities must be explicit and must not include the unknown capability"
        profile.requiredBuildPlanSchemaVersions.isEmpty() || profile.requiredBuildPlanSchemaVersions.any { it <= 0 } ->
            "required Bedrock BuildPlan schemas must be explicit"
        !SAFE_VERSION.matches(profile.bridgeVersion) -> "bridge version must be a bounded exact token"
        !SAFE_VERSION.matches(profile.blockStateSupportRevision) -> "block/state support revision must be a bounded token"
        profile.bridgeProtocolVersion != BridgeProtocol.VERSION -> "bridge protocol version is not supported by this app build"
        profile.status !in setOf(
            MinecraftCompatibilityStatus.SUPPORTED,
            MinecraftCompatibilityStatus.EXPERIMENTAL,
            MinecraftCompatibilityStatus.UNSUPPORTED,
        ) -> "a Bedrock contract status must be SUPPORTED, EXPERIMENTAL, or UNSUPPORTED"
        profile.status == MinecraftCompatibilityStatus.SUPPORTED &&
            (!profile.certification.authorizesBedrockSupport || profile.certifiedMinecraftVersions.isEmpty()) ->
            "a SUPPORTED Bedrock contract requires recorded runtime certification and a certified Minecraft version"
        profile.certifiedMinecraftVersions.any { !it.isKnown } -> "certified Bedrock versions must be exact known versions"
        profile.maximumValidatedOperations !in 1..BridgeProtocol.MAX_OPERATIONS ->
            "operation limit is outside the shared protocol bounds"
        profile.maximumRequestBytes !in BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES ->
            "request limit is outside the shared protocol bounds"
        profile.maximumOperationsPerTick !in 1..BridgeProtocol.MAX_OPERATIONS_PER_TICK ->
            "per-tick limit is outside the shared protocol bounds"
        profile.maximumExecutionSeconds !in 1..BridgeProtocol.MAX_EXECUTION_SECONDS ->
            "execution timeout is outside the shared protocol bounds"
        profile.maximumDimensions.width !in 1..BuildPlanLimits.MAX_BUILD_WIDTH ||
            profile.maximumDimensions.height !in 1..BuildPlanLimits.MAX_BUILD_HEIGHT ||
            profile.maximumDimensions.depth !in 1..BuildPlanLimits.MAX_BUILD_DEPTH ->
            "dimension limits must remain within shared BuildPlan limits"
        else -> null
    }

    @Synchronized
    fun adapter(adapterId: MinecraftAdapterId?): MinecraftAdapter? = adapterId?.let(adapters::get)

    @Synchronized
    fun allAdapters(): List<MinecraftAdapter> = adapters.values.toList()

    @Synchronized
    fun allProfiles(): List<SupportedMinecraftRuntimeDescriptor> =
        adapters.values.flatMap { it.supportedRuntimeDescriptors }

    @Synchronized
    fun allBedrockProfiles(): List<BedrockRuntimeProfile> =
        adapters.values.flatMap { it.bedrockRuntimeProfiles }

    /** Editions that at least one registered adapter claims; used only to explain missing adapters. */
    @Synchronized
    fun declaredEditions(): Set<MinecraftEdition> {
        val declared = linkedSetOf<MinecraftEdition>()
        adapters.values.forEach { adapter ->
            adapter.supportedRuntimeDescriptors.forEach { declared += it.edition }
            adapter.bedrockRuntimeProfiles.forEach { declared += it.edition }
        }
        return declared
    }

    @Synchronized
    internal fun compatibilityChecks(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): List<MinecraftCompatibilityResult> = adapters.values.mapNotNull { adapter ->
        adapter.compatibilityCheck(runtime, requirements)
    }

    private companion object {
        val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,64}")

        /**
         * Registration and Phase 13 detection share one release-channel rule: a profile may only claim a channel
         * that its exact version identifier can actually carry. A LEGACY profile may describe an older *release*
         * token (1.7.10) — that explicit registry decision is the only reason such a runtime is treated as legacy —
         * while a beta/alpha/snapshot token keeps its channel.
         */
        fun releaseChannelMatches(declared: MinecraftVersionChannel, parsed: MinecraftVersionChannel): Boolean =
            MinecraftRuntimeDescriptorValidation.releaseChannelCoherent(declared, parsed)
    }
}

/**
 * The production adapters registered by this build: the exact Java 1.20.1/Fabric production adapter, the
 * legacy/experimental Java contracts (recognized, never executable while uncertified), and the Bedrock contract
 * boundary, which is recognized but can never authorize construction while no Bedrock runtime is certified.
 * Other versions/loaders remain explicit, unsupported extension points.
 */
object DefaultMinecraftCompatibility {
    internal val registry: MinecraftAdapterRegistry by lazy {
        MinecraftAdapterRegistry().apply {
            when (val result = register(JavaFabric1201Adapter())) {
                MinecraftAdapterRegistrationResult.Registered -> Unit
                else -> error("Default Minecraft adapter registry is invalid: $result")
            }
            when (val result = register(LegacyJavaRuntimeAdapter())) {
                MinecraftAdapterRegistrationResult.Registered -> Unit
                else -> error("Default Minecraft adapter registry is invalid: $result")
            }
            when (val result = register(BedrockBridgeAdapter())) {
                MinecraftAdapterRegistrationResult.Registered -> Unit
                else -> error("Default Minecraft adapter registry is invalid: $result")
            }
        }
    }

    val resolver: MinecraftCompatibilityResolver by lazy { MinecraftCompatibilityResolver(registry, selector) }

    /** Phase 13 deterministic adapter selector over the same registry; there is no second adapter registry. */
    val selector: MinecraftAdapterSelector by lazy { MinecraftAdapterSelector(registry) }

    /** Phase 13 automatic runtime detection over authoritative, authenticated bridge facts. */
    val detector: MinecraftRuntimeDetector by lazy { MinecraftRuntimeDetector(registry) }

    /**
     * Phase 13 pipeline entry point: detection → validation → adapter selection → compatibility resolution →
     * session-bound execution authorization.
     */
    val gate: MinecraftRuntimeCompatibilityGate by lazy {
        MinecraftRuntimeCompatibilityGate(detector, selector, resolver)
    }
}
