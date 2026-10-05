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
 * Runtime host reported by an authenticated Minecraft Bedrock bridge.
 *
 * This is a Bedrock runtime fact, not a Java loader concept: Bedrock has no Java, Fabric, Forge, NeoForge, or
 * Fabric API. A platform the app does not recognize is never inferred; it stays [UNKNOWN] and fails closed.
 */
enum class MinecraftRuntimePlatform(val wireValue: String, val displayName: String) {
    DEDICATED_SERVER("DEDICATED_SERVER", "Bedrock Dedicated Server"),
    CLIENT_HOSTED_WORLD("CLIENT_HOSTED_WORLD", "Client-hosted world"),
    REALMS("REALMS", "Realms"),
    UNKNOWN("UNKNOWN", "Unknown platform");

    companion object {
        fun fromWire(value: String?): MinecraftRuntimePlatform =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/** Integration limitations that a Bedrock contract declares up front instead of implying reliability. */
enum class BedrockRuntimeLimitation(val displayName: String) {
    CANCELLATION_AT_BATCH_BOUNDARY("Cancellation is applied at the next bounded batch boundary"),
    NO_ROLLBACK("Cancelled or failed work can leave partial world changes"),
    NO_AUTOMATIC_RESUME("An interrupted build is never resumed automatically"),
    RECOVERY_REQUIRED_AFTER_INTERRUPTION("Interrupted world state requires operator inspection"),
    PROGRESS_IS_BRIDGE_REPORTED("Progress is only bridge-reported; CraftMind never estimates it"),
    SINGLE_ACTIVE_EXECUTION("Only one prepared, queued, or running build is allowed"),
    NO_BLOCK_ENTITY_DATA("BuildPlan v2 cannot carry block-entity data"),
    NO_TRANSACTIONAL_PLACEMENT("Block placement is not transactional across the whole plan"),
    ORIGIN_MUST_BE_OPERATOR_SELECTED("The world origin is selected by an operator on the Minecraft side"),
}

/**
 * How far the Bedrock integration has actually been verified.
 *
 * This ladder exists so that a registry entry can never authorize construction without recorded runtime
 * verification. Only [RUNTIME_TESTED] may back a `SUPPORTED` Bedrock contract.
 */
enum class BedrockRuntimeCertification(val displayName: String) {
    NOT_PERFORMED("Not performed"),
    UNIT_TESTED("Source-level unit tests only"),
    BRIDGE_TESTED("Verified against a Bedrock bridge without a Minecraft runtime"),
    RUNTIME_TESTED("Verified against a real Minecraft Bedrock runtime");

    val authorizesSupport: Boolean get() = this == RUNTIME_TESTED
}

/**
 * One structured, bounded compatibility diagnostic suitable for the Android UI, logs, tests, and later AI
 * refinement. Diagnostics never contain credentials, tokens, or provider material.
 */
data class MinecraftCompatibilityDiagnostic(
    val reasonCode: MinecraftCompatibilityReasonCode,
    val componentId: String?,
    val blockId: String?,
    val stateProperties: List<String> = emptyList(),
    val detail: String,
)

/**
 * One declared CraftMind Bedrock integration contract.
 *
 * A Bedrock profile is deliberately *not* keyed by an exact Minecraft version: no Bedrock version has been
 * runtime-verified here, so [certifiedMinecraftVersions] is empty and the contract can only ever resolve to
 * `EXPERIMENTAL`/`UNSUPPORTED`. Versions are added to that set only with recorded certification.
 */
data class BedrockRuntimeProfile(
    val adapterId: MinecraftAdapterId,
    val edition: MinecraftEdition,
    val bridgeVersion: String,
    val bridgeProtocolVersion: Int,
    val supportedPlatforms: Set<MinecraftRuntimePlatform>,
    val certifiedMinecraftVersions: Set<MinecraftVersion>,
    /** Capabilities this contract defines. A bridge may never report a capability outside this set. */
    val contractCapabilities: Set<MinecraftCapability>,
    val requiredBuildPlanSchemaVersions: Set<Int>,
    val limitations: Set<BedrockRuntimeLimitation>,
    val maximumValidatedOperations: Int,
    val maximumRequestBytes: Int,
    val maximumOperationsPerTick: Int,
    val maximumExecutionSeconds: Int,
    val maximumDimensions: MinecraftDimensionLimits,
    /** Revision label of the block/state mapping set below; never a version guess. */
    val blockStateSupportRevision: String,
    val blockStateCatalog: BedrockBlockStateCatalog,
    val status: MinecraftCompatibilityStatus,
    val certification: BedrockRuntimeCertification,
) {
    init {
        require(edition == MinecraftEdition.BEDROCK) { "A Bedrock contract must declare the Bedrock edition" }
    }

    /** Bridge-identity match; the reported Minecraft Bedrock version is evaluated separately, never defaulted. */
    fun matchesBridgeIdentity(runtime: MinecraftRuntimeDescriptor): Boolean =
        runtime.edition == MinecraftEdition.BEDROCK &&
            runtime.loader == MinecraftLoader.BEDROCK_NATIVE &&
            runtime.platform in supportedPlatforms &&
            runtime.bridgeProtocolVersion == bridgeProtocolVersion &&
            runtime.bridgeVersion == bridgeVersion

    internal fun hasSameBridgeIdentity(other: BedrockRuntimeProfile): Boolean =
        bridgeProtocolVersion == other.bridgeProtocolVersion && bridgeVersion == other.bridgeVersion &&
            supportedPlatforms.intersect(other.supportedPlatforms).isNotEmpty()
}

/**
 * Central list of CraftMind Bedrock integration contracts shipped by this build.
 *
 * Exactly one contract is declared and it is **not certified**: [BedrockRuntimeProfile.certifiedMinecraftVersions]
 * is empty and [BedrockRuntimeCertification] is [BedrockRuntimeCertification.NOT_PERFORMED]. No Bedrock runtime
 * version is registered, and no Bedrock adapter can authorize construction in this build. Do not add a version
 * here unless a real Bedrock runtime test was performed and recorded.
 */
object BedrockRuntimeProfileRegistry {
    /** Display name of the Minecraft-side Bedrock integration boundary declared by this build. */
    const val BEDROCK_BRIDGE_DISPLAY_NAME = "CraftMind Bedrock Bridge"

    /**
     * Declared interface version a future CraftMind Bedrock bridge must report as its `bridgeVersion`. It is an
     * interface contract version, not an assertion that a deployed Bedrock bridge exists.
     */
    const val BEDROCK_BRIDGE_CONTRACT_VERSION = "1.0.0"

    /** Declared block/state mapping revision. `none-declared` means nothing has been verified yet. */
    const val BLOCK_STATE_SUPPORT_REVISION_NONE = "none-declared"

    val bedrockBridgeContract: BedrockRuntimeProfile = BedrockRuntimeProfile(
        adapterId = MinecraftAdapterId("bedrock-bridge-contract"),
        edition = MinecraftEdition.BEDROCK,
        bridgeVersion = BEDROCK_BRIDGE_CONTRACT_VERSION,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        supportedPlatforms = setOf(
            MinecraftRuntimePlatform.DEDICATED_SERVER,
            MinecraftRuntimePlatform.CLIENT_HOSTED_WORLD,
        ),
        certifiedMinecraftVersions = emptySet(),
        contractCapabilities = setOf(
            MinecraftCapability.WORLD_ACCESS,
            MinecraftCapability.BUILD_EXECUTION,
            MinecraftCapability.BLOCK_PLACEMENT,
            MinecraftCapability.BLOCK_STATE_SUPPORT,
            MinecraftCapability.WORLD_VALIDATION,
            MinecraftCapability.ORIGIN_RESOLUTION,
            MinecraftCapability.STRUCTURE_BATCHING,
            MinecraftCapability.PROGRESS_REPORTING,
            MinecraftCapability.BUILD_STATUS,
            MinecraftCapability.CANCELLATION,
            MinecraftCapability.BUILD_PLAN_V2,
        ),
        requiredBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        limitations = setOf(
            BedrockRuntimeLimitation.CANCELLATION_AT_BATCH_BOUNDARY,
            BedrockRuntimeLimitation.NO_ROLLBACK,
            BedrockRuntimeLimitation.NO_AUTOMATIC_RESUME,
            BedrockRuntimeLimitation.RECOVERY_REQUIRED_AFTER_INTERRUPTION,
            BedrockRuntimeLimitation.PROGRESS_IS_BRIDGE_REPORTED,
            BedrockRuntimeLimitation.SINGLE_ACTIVE_EXECUTION,
            BedrockRuntimeLimitation.NO_BLOCK_ENTITY_DATA,
            BedrockRuntimeLimitation.NO_TRANSACTIONAL_PLACEMENT,
            BedrockRuntimeLimitation.ORIGIN_MUST_BE_OPERATOR_SELECTED,
        ),
        maximumValidatedOperations = BuildPlanLimits.MAX_OPERATIONS,
        maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
        maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
        maximumDimensions = MinecraftDimensionLimits(
            width = BuildPlanLimits.MAX_BUILD_WIDTH,
            height = BuildPlanLimits.MAX_BUILD_HEIGHT,
            depth = BuildPlanLimits.MAX_BUILD_DEPTH,
        ),
        blockStateSupportRevision = BLOCK_STATE_SUPPORT_REVISION_NONE,
        blockStateCatalog = BedrockBlockStateCatalog.EMPTY,
        status = MinecraftCompatibilityStatus.EXPERIMENTAL,
        certification = BedrockRuntimeCertification.NOT_PERFORMED,
    )

    fun allProfiles(): List<BedrockRuntimeProfile> = listOf(bedrockBridgeContract)

    fun profile(adapterId: MinecraftAdapterId): BedrockRuntimeProfile? =
        allProfiles().firstOrNull { it.adapterId == adapterId }
}

/**
 * Bedrock adapter boundary.
 *
 * It recognizes a Bedrock runtime through the registered contract and always fails closed: while no Bedrock
 * version is runtime-certified, the result can be `EXPERIMENTAL` or `UNSUPPORTED`, never `SUPPORTED`, so
 * [MinecraftCompatibilityResult.canExecute] stays false and the shared authenticated bridge is never asked to
 * prepare a Bedrock build. The execution members throw a typed failure instead of forwarding a BuildPlan
 * because no genuine Bedrock execution path exists in this build.
 */
class BedrockBridgeAdapter(
    private val profiles: List<BedrockRuntimeProfile> = BedrockRuntimeProfileRegistry.allProfiles(),
) : MinecraftAdapter {
    override val adapterId: MinecraftAdapterId = BedrockRuntimeProfileRegistry.bedrockBridgeContract.adapterId

    /** Bedrock identity is contract-keyed; it intentionally declares no version-keyed Java-style profile. */
    override val supportedRuntimeDescriptors: List<SupportedMinecraftRuntimeDescriptor> = emptyList()

    override val bedrockRuntimeProfiles: List<BedrockRuntimeProfile> = profiles.toList()

    override fun compatibilityCheck(
        runtime: MinecraftRuntimeDescriptor,
        requirements: BuildPlanRequirements,
    ): MinecraftCompatibilityResult? {
        if (runtime.edition != MinecraftEdition.BEDROCK || runtime.loader != MinecraftLoader.BEDROCK_NATIVE) return null
        val profile = bedrockRuntimeProfiles.firstOrNull { it.matchesBridgeIdentity(runtime) } ?: return null

        val reported = runtime.capabilities.filterTo(linkedSetOf()) { it != MinecraftCapability.UNKNOWN }
        val outsideContract = reported - profile.contractCapabilities
        if (outsideContract.isNotEmpty()) {
            return MinecraftCompatibilityResult(
                status = MinecraftCompatibilityStatus.UNKNOWN,
                adapterId = adapterId,
                capabilities = reported,
                missingCapabilities = requirements.requiredCapabilities,
                reasons = listOf(
                    "The authenticated Bedrock bridge reported capabilities outside the declared Bedrock contract: " +
                        outsideContract.sortedBy(MinecraftCapability::name).joinToString { it.displayName } +
                        ". Capabilities are never inferred, widened, or invented.",
                ),
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
                planContentSupported = false,
                runtimeCertification = profile.certification,
                reasonCodes = setOf(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR),
            )
        }

        val missing = requirements.requiredCapabilities - reported
        val reasons = mutableListOf<String>()
        val reasonCodes = linkedSetOf<MinecraftCompatibilityReasonCode>()
        if (missing.isNotEmpty()) {
            reasonCodes += MinecraftCompatibilityReasonCode.MISSING_CAPABILITY
            reasons += "The authenticated Bedrock bridge did not report: ${missing.sortedBy(MinecraftCapability::name).joinToString { it.displayName }}."
        }

        val evaluation = MinecraftCompatibilityLimitsEvaluation.evaluate(
            runtime = runtime,
            requirements = requirements,
            maximumValidatedOperations = profile.maximumValidatedOperations,
            maximumRequestBytes = profile.maximumRequestBytes,
            maximumOperationsPerTick = profile.maximumOperationsPerTick,
            maximumExecutionSeconds = profile.maximumExecutionSeconds,
            maximumDimensions = profile.maximumDimensions,
        )
        reasons += evaluation.reasons
        reasonCodes += evaluation.reasonCodes
        var withinLimits = evaluation.withinLimits

        val missingSchemas = profile.requiredBuildPlanSchemaVersions - runtime.supportedBuildPlanSchemaVersions
        if (missingSchemas.isNotEmpty()) {
            withinLimits = false
            reasonCodes += MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA
            reasonCodes += MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED
            reasons += "The authenticated Bedrock bridge does not report BuildPlan schema(s) ${missingSchemas.sorted().joinToString()}, " +
                "which the registered Bedrock contract requires."
        }

        val certifiedVersion = runtime.version in profile.certifiedMinecraftVersions
        if (!certifiedVersion) {
            reasonCodes += MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED
            reasons += "Minecraft Bedrock ${runtime.version.displayIdentifier} is not a runtime-certified CraftMind Bedrock target: " +
                "the registered Bedrock contract records no verified Bedrock version yet. No BuildPlan is sent for execution."
        }

        val diagnostics = mutableListOf<MinecraftCompatibilityDiagnostic>()
        var contentSupported = true
        if (requirements.requestedContent.isNotEmpty()) {
            val catalog = profile.blockStateCatalog
            requirements.requestedContent.forEach { content ->
                when (val resolution = catalog.resolve(content.blockId, content.state)) {
                    is BedrockBlockStateResolution.Supported -> Unit
                    is BedrockBlockStateResolution.UnsupportedBlock -> {
                        contentSupported = false
                        if (diagnostics.size < MAXIMUM_DIAGNOSTICS) {
                            diagnostics += MinecraftCompatibilityDiagnostic(
                                reasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK,
                                componentId = content.componentId,
                                blockId = content.blockId,
                                stateProperties = content.state.keys.sorted(),
                                detail = if (catalog.isDeclared) {
                                    "The selected Bedrock runtime has no verified mapping for this block."
                                } else {
                                    "No verified Bedrock block/state mapping is declared for this runtime target."
                                },
                            )
                        }
                    }

                    is BedrockBlockStateResolution.UnsupportedState -> {
                        contentSupported = false
                        if (diagnostics.size < MAXIMUM_DIAGNOSTICS) {
                            diagnostics += MinecraftCompatibilityDiagnostic(
                                reasonCode = MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK_STATE,
                                componentId = content.componentId,
                                blockId = content.blockId,
                                stateProperties = resolution.unsupportedProperties.take(BuildPlanLimits.MAX_BLOCK_STATE_PROPERTIES),
                                detail = "The requested block exists in the mapping set, but the requested state can not be represented on this Bedrock runtime.",
                            )
                        }
                    }
                }
            }
            if (!contentSupported) {
                reasonCodes += diagnostics.map(MinecraftCompatibilityDiagnostic::reasonCode).toSet()
                reasons += "One or more requested blocks or block states cannot be represented on this Bedrock runtime. " +
                    "The plan is not executable and CraftMind does not substitute blocks or states."
            }
        }

        val warnings = mutableListOf(
            "CraftMind never substitutes a block or block state: every placement must be representable by a declared, verified Bedrock mapping.",
            "App-side resolution is not server-side validation. The Bedrock bridge must independently validate schema, size, dimensions, " +
                "operation count, block/state availability, coordinates, execution ID, protocol, capabilities, and session for every BuildPlan.",
        )
        requirements.requestedContent.takeIf { it.size > MAXIMUM_DIAGNOSTICS && !contentSupported }?.let {
            warnings += "Additional distinct block/state combinations were not listed; the plan is rejected as a whole."
        }
        if (withinLimits && contentSupported && missing.isEmpty() && certifiedVersion) {
            warnings += "This runtime matches a registered Bedrock contract and every check passed."
        }

        val status = if (profile.status == MinecraftCompatibilityStatus.SUPPORTED && !certifiedVersion) {
            MinecraftCompatibilityStatus.EXPERIMENTAL
        } else {
            profile.status
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
            runtimeCertification = profile.certification,
            diagnostics = diagnostics,
            reasonCodes = reasonCodes,
        )
    }

    override suspend fun preflight(
        bridge: MinecraftBridgePairingRepository,
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionPreview = throw noBedrockExecutionPath()

    override suspend fun execute(
        bridge: MinecraftBridgePairingRepository,
        preview: MinecraftExecutionPreview,
    ): MinecraftExecutionSnapshot = throw noBedrockExecutionPath()

    override suspend fun cancel(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftCancellationResult = throw noBedrockExecutionPath()

    override suspend fun status(
        bridge: MinecraftBridgePairingRepository,
        executionId: String,
    ): MinecraftExecutionQueryResult = throw noBedrockExecutionPath()

    private fun noBedrockExecutionPath() = MinecraftBridgeFailure(
        reasonCode = BEDROCK_RUNTIME_NOT_CERTIFIED,
        safeMessage = "No certified CraftMind Bedrock runtime exists in this build. No BuildPlan is sent to a Bedrock bridge.",
    )

    companion object {
        /** Stable reason code returned when a Bedrock execution is requested while no runtime is certified. */
        const val BEDROCK_RUNTIME_NOT_CERTIFIED = "BEDROCK_RUNTIME_NOT_CERTIFIED"

        private const val MAXIMUM_DIAGNOSTICS = 16
    }
}
