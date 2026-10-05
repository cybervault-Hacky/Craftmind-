package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Central list of CraftMind legacy/experimental Java integration contracts shipped by this build.
 *
 * These entries exist so the resolver, the UI, and the tests can represent *recognized* legacy runtimes
 * explicitly. They are **not** certification: every contract declares [MinecraftCompatibilityStatus.EXPERIMENTAL]
 * with [MinecraftRuntimeCertification.NOT_PERFORMED], lists the limitations CraftMind accepts for that runtime,
 * and declares an app-side block/state catalog that is empty because nothing has been verified. Consequently no
 * legacy runtime can authorize a build in this build, and CraftMind reports an honest UNSUPPORTED/EXPERIMENTAL
 * result instead of guessing.
 *
 * Declared bridge identities are *interface contract versions* a future CraftMind legacy bridge would have to
 * report; they are not assertions that such a bridge exists. Loader identities below are the published loader
 * generations of those Minecraft releases; they are recognition requirements, never compatibility evidence.
 *
 * Adding a legacy contract never weakens the production Java profile, never routes a legacy runtime through the
 * Fabric 1.20.1 adapter, and never bypasses the shared limits or security validation.
 */
object LegacyRuntimeProfileRegistry {
    /**
     * Adapter that owns every declared legacy contract. A registry profile must name its owning adapter, and one
     * adapter hosts all declared legacy contracts because they share the same fail-closed execution policy.
     */
    const val LEGACY_ADAPTER_ID = "java-legacy-experimental"

    /** Declared display name of the Minecraft-side legacy integration boundary. */
    const val LEGACY_BRIDGE_DISPLAY_NAME = "CraftMind Legacy Bridge"

    /**
     * Declared legacy bridge interface version. It is a contract version that a legacy bridge must report as its
     * `bridgeVersion`, never a claim that a deployed legacy bridge exists.
     */
    const val LEGACY_BRIDGE_CONTRACT_VERSION = "1.0.0-legacy"

    /**
     * Every declared legacy contract shares the same honest limitations: nothing about the runtime, the bridge
     * interface, or the block/state mapping has been verified here.
     */
    private val DECLARED_LIMITATIONS = setOf(
        MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED,
        MinecraftRuntimeLimitation.LEGACY_BRIDGE_INTERFACE_UNVERIFIED,
        MinecraftRuntimeLimitation.BLOCK_STATE_MAPPING_NOT_VERIFIED,
        MinecraftRuntimeLimitation.NO_ROLLBACK,
        MinecraftRuntimeLimitation.NO_AUTOMATIC_RESUME,
        MinecraftRuntimeLimitation.RECOVERY_REQUIRED_AFTER_INTERRUPTION,
        MinecraftRuntimeLimitation.PROGRESS_IS_BRIDGE_REPORTED,
        MinecraftRuntimeLimitation.SINGLE_ACTIVE_EXECUTION,
        MinecraftRuntimeLimitation.NO_BLOCK_ENTITY_DATA,
        MinecraftRuntimeLimitation.NO_TRANSACTIONAL_PLACEMENT,
        MinecraftRuntimeLimitation.ORIGIN_MUST_BE_OPERATOR_SELECTED,
        MinecraftRuntimeLimitation.CANCELLATION_AT_BATCH_BOUNDARY,
    )

    /**
     * Declared legacy contract for Minecraft 1.7.10 / Forge 10.13.4.1614 (the final published 1.7.10 build) on
     * Java 8. Reclassified as LEGACY only because this registry says so — the version token itself parses as a
     * normal release, which is exactly why legacy classification is never automatic.
     */
    val javaForge1710 = legacyJavaProfile(
        adapterId = MinecraftAdapterId(LEGACY_ADAPTER_ID),
        version = "1.7.10",
        loader = MinecraftLoader.FORGE,
        loaderVersion = "10.13.4.1614",
        javaRuntimeRequirement = JavaRuntimeRequirement(
            requiredMajor = 8,
            minimumSupportedMajor = 8,
            maximumSupportedMajor = 8,
        ),
    )

    /**
     * Declared legacy contract for Minecraft 1.12.2 / Forge 14.23.5.2859 (the recommended 1.12.2 build) on
     * Java 8. The declared Java requirement is per release: Java 17 is never assumed to fit a legacy release.
     */
    val javaForge1122 = legacyJavaProfile(
        adapterId = MinecraftAdapterId(LEGACY_ADAPTER_ID),
        version = "1.12.2",
        loader = MinecraftLoader.FORGE,
        loaderVersion = "14.23.5.2859",
        javaRuntimeRequirement = JavaRuntimeRequirement(
            requiredMajor = 8,
            minimumSupportedMajor = 8,
            maximumSupportedMajor = 8,
        ),
    )

    private val profiles = listOf(javaForge1710, javaForge1122)

    fun allProfiles(): List<SupportedMinecraftRuntimeDescriptor> = profiles.toList()

    fun profile(adapterId: MinecraftAdapterId): SupportedMinecraftRuntimeDescriptor? =
        profiles.firstOrNull { it.adapterId == adapterId }

    private fun legacyJavaProfile(
        adapterId: MinecraftAdapterId,
        version: String,
        loader: MinecraftLoader,
        loaderVersion: String,
        javaRuntimeRequirement: JavaRuntimeRequirement,
    ): SupportedMinecraftRuntimeDescriptor = SupportedMinecraftRuntimeDescriptor(
        adapterId = adapterId,
        edition = MinecraftEdition.JAVA,
        version = MinecraftVersion.parse(version),
        loader = loader,
        loaderVersion = loaderVersion,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        bridgeVersion = LEGACY_BRIDGE_CONTRACT_VERSION,
        javaRuntimeRequirement = javaRuntimeRequirement,
        requiredFabricApiVersion = null,
        supportStatus = MinecraftCompatibilityStatus.EXPERIMENTAL,
        releaseChannel = MinecraftVersionChannel.LEGACY,
        runtimeCertification = MinecraftRuntimeCertification.NOT_PERFORMED,
        limitations = DECLARED_LIMITATIONS,
        // Nothing has been verified for these runtimes, so the catalog fails closed for every requested block.
        blockStateSupportRevision = MinecraftTargetBlockStateCatalog.NONE_DECLARED_REVISION,
        contentValidationMode = MinecraftContentValidationMode.APP_SIDE_MAPPING,
        blockStateCatalog = MinecraftTargetBlockStateCatalog.EMPTY,
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

    /** The declared limitations above are also exposed for documentation and UI purposes. */
    fun declaredLimitations(): Set<MinecraftRuntimeLimitation> = DECLARED_LIMITATIONS
}

/**
 * Legacy/experimental Java adapter boundary.
 *
 * It is a real adapter, not a fake one: it recognizes exactly the registered legacy contracts, and it *never*
 * authorizes construction. A recognized runtime resolves to `EXPERIMENTAL` (or `SUPPORTED` only when a legacy
 * profile records a real certification rung that authorizes support), and every execution member throws a typed
 * [MinecraftBridgeFailure] instead of forwarding a BuildPlan, because no legacy bridge interface has been
 * verified in this build.
 */
class LegacyJavaRuntimeAdapter(
    private val profiles: List<SupportedMinecraftRuntimeDescriptor> = LegacyRuntimeProfileRegistry.allProfiles(),
) : MinecraftAdapter {
    override val adapterId: MinecraftAdapterId = MinecraftAdapterId(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID)

    override val supportedRuntimeDescriptors: List<SupportedMinecraftRuntimeDescriptor> = profiles.toList()

    override fun compatibilityCheck(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult? {
        if (runtime.edition != MinecraftEdition.JAVA) return null
        val profile = supportedRuntimeDescriptors.firstOrNull { it.matches(runtime) } ?: return null

        val reported = runtime.capabilities.filterTo(linkedSetOf()) { it != MinecraftCapability.UNKNOWN }
        val missing = requirements.requiredCapabilities - reported

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
        var withinLimits = evaluation.withinLimits

        // The shared evaluation rejects any BuildPlan schema that this legacy runtime or its bridge does not
        // accept; the note below states the policy explicitly because legacy runtimes never receive a converted
        // plan. A schema migration would have to be implemented, tested, and registered before it could apply.
        if (MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in reasonCodes) {
            reasons += "This legacy runtime does not accept the plan's BuildPlan schema and no tested migration exists; " +
                "CraftMind never converts a plan schema silently."
        }

        if (!profile.runtimeCertification.authorizesSupport) {
            reasonCodes += MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED
            reasons += "Minecraft ${runtime.version.displayIdentifier} on ${runtime.loader.displayName} " +
                "${runtime.loaderVersion ?: "unknown"} is recognized as a declared legacy/experimental contract, " +
                "but no runtime verification has been recorded for it. No BuildPlan is sent for execution."
        }

        // App-side mapping: every requested block/state must exist in a verified target catalog. The shipped
        // legacy catalogs are empty, so unmapped content fails closed and is never substituted.
        val diagnostics = mutableListOf<MinecraftCompatibilityDiagnostic>()
        var contentSupported = true
        if (requirements.requestedContent.isNotEmpty() &&
            profile.contentValidationMode == MinecraftContentValidationMode.APP_SIDE_MAPPING
        ) {
            requirements.requestedContent.forEach { content ->
                when (val resolution = profile.blockStateCatalog.resolve(content.blockId, content.state)) {
                    is MinecraftBlockStateResolution.Supported -> Unit
                    is MinecraftBlockStateResolution.UnsupportedBlock -> {
                        contentSupported = false
                        if (diagnostics.size < MAXIMUM_DIAGNOSTICS) {
                            diagnostics += MinecraftCompatibilityDiagnostic(
                                reasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK,
                                componentId = content.componentId,
                                blockId = content.blockId,
                                stateProperties = content.state.keys.sorted(),
                                detail = if (profile.blockStateCatalog.isDeclared) {
                                    "The declared legacy target has no verified mapping for this block."
                                } else {
                                    "No verified block/state mapping is declared for this legacy runtime target."
                                },
                            )
                        }
                    }

                    is MinecraftBlockStateResolution.UnsupportedState -> {
                        contentSupported = false
                        if (diagnostics.size < MAXIMUM_DIAGNOSTICS) {
                            diagnostics += MinecraftCompatibilityDiagnostic(
                                reasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK_STATE,
                                componentId = content.componentId,
                                blockId = content.blockId,
                                stateProperties = resolution.unsupportedProperties.take(BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES),
                                detail = "The requested block exists in the mapping set, but the requested state can not be represented on this legacy runtime.",
                            )
                        }
                    }
                }
            }
            if (!contentSupported) {
                reasonCodes += diagnostics.map(MinecraftCompatibilityDiagnostic::reasonCode).toSet()
                reasons += "One or more requested blocks or block states have no verified representation on this " +
                    "legacy runtime. The plan is not executable and CraftMind does not substitute blocks or states."
            }
        }

        val warnings = mutableListOf(
            "This is a legacy/experimental runtime contract. Recognition is not support: CraftMind requires recorded runtime certification before any build.",
            "CraftMind never substitutes a block, a block state, a loader, or a Minecraft version for a legacy runtime.",
        )
        profile.limitations.sortedBy(MinecraftRuntimeLimitation::name).forEach { limitation ->
            warnings += "Declared limitation: ${limitation.displayName}."
        }

        // Belt-and-braces: an uncertified profile can never surface as SUPPORTED even if it declared so.
        val status = if (profile.supportStatus == MinecraftCompatibilityStatus.SUPPORTED &&
            profile.runtimeCertification.authorizesSupport
        ) {
            MinecraftCompatibilityStatus.SUPPORTED
        } else if (profile.supportStatus == MinecraftCompatibilityStatus.UNSUPPORTED) {
            MinecraftCompatibilityStatus.UNSUPPORTED
        } else {
            MinecraftCompatibilityStatus.EXPERIMENTAL
        }

        return MinecraftCompatibilityResult(
            status = status,
            adapterId = adapterId,
            capabilities = reported,
            missingCapabilities = missing,
            reasons = reasons.distinct(),
            warnings = warnings,
            limits = evaluation.limits,
            planWithinLimits = withinLimits,
            planContentSupported = contentSupported,
            runtimeCertification = profile.runtimeCertification,
            diagnostics = diagnostics,
            reasonCodes = reasonCodes,
        )
    }

    override suspend fun preflight(
        bridge: MinecraftBridgePairingRepository,
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionPreview = throw noLegacyExecutionPath()

    override suspend fun execute(
        bridge: MinecraftBridgePairingRepository,
        preview: MinecraftExecutionPreview,
    ): MinecraftExecutionSnapshot = throw noLegacyExecutionPath()

    override suspend fun cancel(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftCancellationResult = throw noLegacyExecutionPath()

    override suspend fun status(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftExecutionQueryResult = throw noLegacyExecutionPath()

    private fun noLegacyExecutionPath() = MinecraftBridgeFailure(
        reasonCode = RUNTIME_NOT_CERTIFIED,
        safeMessage = "No runtime-certified CraftMind legacy bridge exists in this build. No BuildPlan is sent to a legacy runtime.",
    )

    companion object {
        /** Stable machine-readable reason code returned when a legacy execution is requested without certification. */
        const val RUNTIME_NOT_CERTIFIED = "RUNTIME_NOT_CERTIFIED"

        private const val MAXIMUM_DIAGNOSTICS = 16
    }
}
