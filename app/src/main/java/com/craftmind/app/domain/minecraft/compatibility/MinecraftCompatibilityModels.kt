package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanOperation
import com.craftmind.app.domain.buildplan.BuildPlanOperationKind
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Minecraft product family. Unknown wire values are deliberately retained as [UNKNOWN]. */
@Serializable(with = MinecraftEditionSerializer::class)
enum class MinecraftEdition(val wireValue: String, val displayName: String) {
    JAVA("java", "Java Edition"),
    BEDROCK("bedrock", "Bedrock Edition"),
    LEGACY("legacy", "Legacy Edition"),
    UNKNOWN("unknown", "Unknown edition");

    companion object {
        fun fromWire(value: String?): MinecraftEdition = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "java", "java edition" -> JAVA
            "bedrock", "bedrock edition" -> BEDROCK
            "legacy", "legacy edition" -> LEGACY
            else -> UNKNOWN
        }
    }
}

object MinecraftEditionSerializer : KSerializer<MinecraftEdition> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MinecraftEdition", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: MinecraftEdition) = encoder.encodeString(value.wireValue)
    override fun deserialize(decoder: Decoder): MinecraftEdition = MinecraftEdition.fromWire(decoder.decodeString())
}

/** Runtime implementation family, not an assertion that two loaders share compatibility. */
@Serializable(with = MinecraftLoaderSerializer::class)
enum class MinecraftLoader(val wireValue: String, val displayName: String) {
    FABRIC("Fabric", "Fabric"),
    FORGE("Forge", "Forge"),
    NEOFORGE("NeoForge", "NeoForge"),
    VANILLA("Vanilla", "Vanilla"),
    BEDROCK_NATIVE("Bedrock Native", "Bedrock Native"),
    UNKNOWN("unknown", "Unknown loader");

    val edition: MinecraftEdition
        get() = when (this) {
            FABRIC, FORGE, NEOFORGE, VANILLA -> MinecraftEdition.JAVA
            BEDROCK_NATIVE -> MinecraftEdition.BEDROCK
            UNKNOWN -> MinecraftEdition.UNKNOWN
        }

    companion object {
        fun fromWire(value: String?): MinecraftLoader = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "fabric" -> FABRIC
            "forge" -> FORGE
            "neoforge", "neo forge" -> NEOFORGE
            "vanilla" -> VANILLA
            "bedrock native", "bedrock_native", "bedrock-native" -> BEDROCK_NATIVE
            else -> UNKNOWN
        }
    }
}

object MinecraftLoaderSerializer : KSerializer<MinecraftLoader> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MinecraftLoader", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: MinecraftLoader) = encoder.encodeString(value.wireValue)
    override fun deserialize(decoder: Decoder): MinecraftLoader = MinecraftLoader.fromWire(decoder.decodeString())
}

/** Explicit ability vocabulary. Execution negotiation uses only values reported by the authenticated bridge. */
@Serializable(with = MinecraftCapabilitySerializer::class)
enum class MinecraftCapability(val wireValue: String, val displayName: String) {
    WORLD_ACCESS("WORLD_ACCESS", "World access"),
    BUILD_EXECUTION("BUILD_EXECUTION", "Build execution"),
    BLOCK_PLACEMENT("BLOCK_PLACEMENT", "Block placement"),
    BLOCK_STATE_SUPPORT("BLOCK_STATE_SUPPORT", "Validated block states"),
    WORLD_VALIDATION("WORLD_VALIDATION", "World preflight and validation"),
    ORIGIN_RESOLUTION("ORIGIN_RESOLUTION", "Operator-selected origin"),
    STRUCTURE_BATCHING("STRUCTURE_BATCHING", "Bounded construction batches"),
    PROGRESS_REPORTING("PROGRESS_REPORTING", "Progress reporting"),
    BUILD_STATUS("BUILD_STATUS", "Execution status"),
    CANCELLATION("CANCELLATION", "Cancellation"),
    BUILD_PLAN_V2("BUILD_PLAN_V2", "BuildPlan schema v2"),
    LARGE_BUILD_SUPPORT("LARGE_BUILD_SUPPORT", "Large builds"),
    MULTI_WORLD_SUPPORT("MULTI_WORLD_SUPPORT", "Multiple worlds"),
    UNKNOWN("UNKNOWN", "Unknown capability");

    companion object {
        fun fromWire(value: String?): MinecraftCapability = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true)
        } ?: UNKNOWN
    }
}

object MinecraftCapabilitySerializer : KSerializer<MinecraftCapability> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MinecraftCapability", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: MinecraftCapability) = encoder.encodeString(value.wireValue)
    override fun deserialize(decoder: Decoder): MinecraftCapability = MinecraftCapability.fromWire(decoder.decodeString())
}

/** Exact identifier is preserved; this type intentionally provides no ordering or automatic version fallback. */
@Serializable
enum class MinecraftVersionChannel {
    RELEASE,
    PRE_RELEASE,
    SNAPSHOT,
    BETA,
    ALPHA,
    LEGACY,
    UNKNOWN,
}

@Serializable(with = MinecraftVersionSerializer::class)
class MinecraftVersion private constructor(
    /** Safe original token for display and exact matching; null means no trustworthy identifier was provided. */
    val identifier: String?,
    val major: Int?,
    val minor: Int?,
    val patch: Int?,
    val qualifier: String?,
    val channel: MinecraftVersionChannel,
) {
    val isKnown: Boolean get() = channel != MinecraftVersionChannel.UNKNOWN
    val displayIdentifier: String get() = identifier ?: "unknown"

    override fun equals(other: Any?): Boolean = other is MinecraftVersion &&
        identifier == other.identifier && major == other.major && minor == other.minor &&
        patch == other.patch && qualifier == other.qualifier && channel == other.channel

    override fun hashCode(): Int = arrayOf(identifier, major, minor, patch, qualifier, channel).contentHashCode()

    override fun toString(): String = displayIdentifier

    companion object {
        val UNKNOWN = MinecraftVersion(null, null, null, null, null, MinecraftVersionChannel.UNKNOWN)

        private val safeIdentifier = Regex("[A-Za-z0-9._+/-]{1,48}")
        private val releasePattern = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-((?:pre|rc|beta|alpha)\\d+))?", RegexOption.IGNORE_CASE)
        private val snapshotPattern = Regex("\\d{2}w\\d{2}[a-z]", RegexOption.IGNORE_CASE)
        private val betaPattern = Regex("(?:b|beta/)(\\d+)\\.(\\d+)(?:\\.(\\d+))?", RegexOption.IGNORE_CASE)
        private val alphaPattern = Regex("(?:a|alpha/)(\\d+)\\.(\\d+)(?:\\.(\\d+))?", RegexOption.IGNORE_CASE)
        private val legacyPattern = Regex("(?:c\\d+\\.\\d+(?:_\\d+)?|rd-\\d+|inf-\\d+|classic/[A-Za-z0-9._+-]{1,32})", RegexOption.IGNORE_CASE)

        /**
         * Parses only safe, bounded Minecraft identifiers. Unrecognized but safe values remain UNKNOWN and are never
         * compared by numeric proximity; malformed values lose their raw token.
         */
        fun parse(value: String?): MinecraftVersion {
            if (value?.equals("unknown", ignoreCase = true) == true) return UNKNOWN
            if (value.isNullOrEmpty() || value.length > 48 || value != value.trim() || !safeIdentifier.matches(value)) {
                return UNKNOWN
            }

            val release = releasePattern.matchEntire(value)
            if (release != null) {
                val parts = numericParts(release.groupValues[1], release.groupValues[2], release.groupValues[3])
                    ?: return unknown(value)
                val qualifier = release.groupValues[4].takeIf(String::isNotEmpty)
                val channel = when {
                    qualifier == null -> MinecraftVersionChannel.RELEASE
                    qualifier.startsWith("pre", ignoreCase = true) || qualifier.startsWith("rc", ignoreCase = true) ->
                        MinecraftVersionChannel.PRE_RELEASE
                    qualifier.startsWith("beta", ignoreCase = true) -> MinecraftVersionChannel.BETA
                    qualifier.startsWith("alpha", ignoreCase = true) -> MinecraftVersionChannel.ALPHA
                    else -> MinecraftVersionChannel.UNKNOWN
                }
                return if (channel == MinecraftVersionChannel.UNKNOWN) unknown(value)
                else MinecraftVersion(value, parts.first, parts.second, parts.third, qualifier, channel)
            }

            if (snapshotPattern.matches(value)) {
                return MinecraftVersion(value, null, null, null, null, MinecraftVersionChannel.SNAPSHOT)
            }

            val beta = betaPattern.matchEntire(value)
            if (beta != null) {
                val parts = numericParts(beta.groupValues[1], beta.groupValues[2], beta.groupValues[3]) ?: return unknown(value)
                return MinecraftVersion(value, parts.first, parts.second, parts.third, null, MinecraftVersionChannel.BETA)
            }

            val alpha = alphaPattern.matchEntire(value)
            if (alpha != null) {
                val parts = numericParts(alpha.groupValues[1], alpha.groupValues[2], alpha.groupValues[3]) ?: return unknown(value)
                return MinecraftVersion(value, parts.first, parts.second, parts.third, null, MinecraftVersionChannel.ALPHA)
            }

            if (legacyPattern.matches(value)) {
                return MinecraftVersion(value, null, null, null, null, MinecraftVersionChannel.LEGACY)
            }

            return unknown(value)
        }

        private fun unknown(safeValue: String): MinecraftVersion =
            MinecraftVersion(safeValue, null, null, null, null, MinecraftVersionChannel.UNKNOWN)

        private fun numericParts(major: String, minor: String, patch: String): Triple<Int, Int, Int?>? {
            val parsedMajor = major.toIntOrNull()?.takeIf { it in 0..99_999 } ?: return null
            val parsedMinor = minor.toIntOrNull()?.takeIf { it in 0..99_999 } ?: return null
            val parsedPatch = patch.takeIf(String::isNotEmpty)?.toIntOrNull()?.takeIf { it in 0..99_999 }
            if (patch.isNotEmpty() && parsedPatch == null) return null
            return Triple(parsedMajor, parsedMinor, parsedPatch)
        }
    }
}

object MinecraftVersionSerializer : KSerializer<MinecraftVersion> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MinecraftVersion", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: MinecraftVersion) = encoder.encodeString(value.identifier ?: "unknown")
    override fun deserialize(decoder: Decoder): MinecraftVersion = MinecraftVersion.parse(decoder.decodeString())
}

@Serializable
data class MinecraftRuntimeDescriptor(
    /** App version echoed by the authenticated capabilities request; it is not a server-reported runtime version. */
    val appVersion: String? = null,
    val edition: MinecraftEdition = MinecraftEdition.UNKNOWN,
    val version: MinecraftVersion = MinecraftVersion.UNKNOWN,
    /**
     * Bedrock runtime host reported by an authenticated Bedrock bridge. Java deployments leave this [UNKNOWN];
     * the Bedrock edition requires an explicit platform and never infers one.
     */
    val platform: MinecraftRuntimePlatform = MinecraftRuntimePlatform.UNKNOWN,
    /** Bedrock host/runtime version where the bridge can report it safely; never a Java loader version. */
    val platformVersion: String? = null,
    val javaRuntimeMajor: Int? = null,
    val loader: MinecraftLoader = MinecraftLoader.UNKNOWN,
    /** Runtime-reported loader version; null for Bedrock, which has no loader concept. */
    val loaderVersion: String? = null,
    /** Runtime-reported Fabric API version; null for loaders that do not use Fabric API. */
    val fabricApiVersion: String? = null,
    val bridgeProtocolVersion: Int? = null,
    val bridgeVersion: String? = null,
    /** Only capabilities reported by the authenticated Minecraft-side bridge. */
    val capabilities: Set<MinecraftCapability> = emptySet(),
    val supportedBuildPlanSchemaVersions: Set<Int> = emptySet(),
    val maximumValidatedOperations: Int? = null,
    val maximumRequestBytes: Int? = null,
    val maximumOperationsPerTick: Int? = null,
    val maximumExecutionSeconds: Int? = null,
    val worldAvailable: Boolean = false,
    val operatorOriginAvailable: Boolean = false,
    /**
     * Integration limitations declared by the reported runtime. Only a Bedrock runtime or a legacy/experimental
     * Java runtime may report them; the production release runtime must not claim limitations it does not have.
     */
    val limitations: Set<MinecraftRuntimeLimitation> = emptySet(),
) {
    /** True when this descriptor describes Bedrock rather than a Java/JVM runtime. */
    val isBedrock: Boolean get() = edition == MinecraftEdition.BEDROCK

    /**
     * Release channel securely derived from the authoritative Minecraft identifier the authenticated bridge
     * reported. It is never taken from a UI selection, a launcher name, a filename, or a profile's preference, and
     * a contradictory profile declaration makes the descriptor invalid instead of being corrected silently.
     */
    val releaseChannel: MinecraftVersionChannel get() = version.channel

    /** True when the bridge reported every execution limit CraftMind requires; missing limits fail closed. */
    val hasReportedLimits: Boolean
        get() = maximumValidatedOperations != null && maximumRequestBytes != null &&
            maximumOperationsPerTick != null && maximumExecutionSeconds != null

    companion object {
        /** Maps the authenticated protocol-v2 runtime report; no Java/API values are inferred by the app. */
        @Suppress("LongParameterList")
        fun fromBridgeV2(
            appVersion: String?,
            editionName: String,
            minecraftVersion: String,
            javaRuntimeMajor: Int?,
            loaderName: String,
            loaderVersion: String?,
            fabricApiVersion: String?,
            bridgeProtocolVersion: Int,
            bridgeVersion: String,
            reportedCapabilities: Set<MinecraftCapability>,
            supportedBuildPlanSchemaVersions: Set<Int>,
            maximumValidatedOperations: Int,
            maximumRequestBytes: Int,
            maximumOperationsPerTick: Int,
            maximumExecutionSeconds: Int,
            worldAvailable: Boolean,
            operatorOriginAvailable: Boolean,
            platform: MinecraftRuntimePlatform = MinecraftRuntimePlatform.UNKNOWN,
            platformVersion: String? = null,
            limitations: Set<MinecraftRuntimeLimitation> = emptySet(),
        ): MinecraftRuntimeDescriptor = MinecraftRuntimeDescriptor(
            appVersion = appVersion,
            edition = MinecraftEdition.fromWire(editionName),
            version = MinecraftVersion.parse(minecraftVersion),
            platform = platform,
            platformVersion = platformVersion,
            javaRuntimeMajor = javaRuntimeMajor,
            loader = MinecraftLoader.fromWire(loaderName),
            loaderVersion = loaderVersion,
            fabricApiVersion = fabricApiVersion,
            bridgeProtocolVersion = bridgeProtocolVersion,
            bridgeVersion = bridgeVersion,
            capabilities = reportedCapabilities,
            supportedBuildPlanSchemaVersions = supportedBuildPlanSchemaVersions,
            maximumValidatedOperations = maximumValidatedOperations,
            maximumRequestBytes = maximumRequestBytes,
            maximumOperationsPerTick = maximumOperationsPerTick,
            maximumExecutionSeconds = maximumExecutionSeconds,
            worldAvailable = worldAvailable,
            operatorOriginAvailable = operatorOriginAvailable,
            limitations = limitations,
        )
    }
}

@Serializable
data class MinecraftDimensionLimits(
    val width: Int,
    val height: Int,
    val depth: Int,
)

@Serializable
data class JavaRuntimeRequirement(
    /** Preferred/certified runtime major recorded by this compatibility profile. */
    val requiredMajor: Int,
    val minimumSupportedMajor: Int,
    val maximumSupportedMajor: Int,
) {
    init {
        require(requiredMajor > 0 && minimumSupportedMajor > 0 && maximumSupportedMajor >= minimumSupportedMajor)
        require(requiredMajor in minimumSupportedMajor..maximumSupportedMajor)
    }

    fun supports(actualMajor: Int): Boolean = actualMajor in minimumSupportedMajor..maximumSupportedMajor

    internal fun overlaps(other: JavaRuntimeRequirement): Boolean =
        minimumSupportedMajor <= other.maximumSupportedMajor && other.minimumSupportedMajor <= maximumSupportedMajor
}

/**
 * How far a runtime integration has actually been verified.
 *
 * The ladder is ordered and must never be collapsed: a profile may only claim what was genuinely performed, and
 * [authorizesSupport] is true only for the two rungs that imply verification against a real runtime. Legacy,
 * pre-release, snapshot, beta, and experimental profiles stay below that line until a real test is recorded.
 */
@Serializable
enum class MinecraftRuntimeCertification(val displayName: String) {
    NOT_PERFORMED("Not performed"),
    STATIC_ONLY("Static/source-level analysis only"),
    UNIT_TESTED("Source-level unit tests only"),
    BRIDGE_TESTED("Verified against a bridge without a Minecraft runtime"),
    RUNTIME_TESTED("Verified against a real Minecraft runtime"),
    CERTIFIED("Recorded as a certified production target"),

    ;

    /** True only when the recorded verification justifies a SUPPORTED claim. */
    val authorizesSupport: Boolean get() = this == RUNTIME_TESTED || this == CERTIFIED

    /** Bedrock additionally requires a real Bedrock runtime test; a release-process label is not enough. */
    val authorizesBedrockSupport: Boolean get() = this == RUNTIME_TESTED
}

/** Integration limitations a runtime declares up front instead of implying reliability. */
@Serializable
enum class MinecraftRuntimeLimitation(val displayName: String) {
    CANCELLATION_AT_BATCH_BOUNDARY("Cancellation is applied at the next bounded batch boundary"),
    NO_ROLLBACK("Cancelled or failed work can leave partial world changes"),
    NO_AUTOMATIC_RESUME("An interrupted build is never resumed automatically"),
    RECOVERY_REQUIRED_AFTER_INTERRUPTION("Interrupted world state requires operator inspection"),
    PROGRESS_IS_BRIDGE_REPORTED("Progress is only bridge-reported; CraftMind never estimates it"),
    SINGLE_ACTIVE_EXECUTION("Only one prepared, queued, or running build is allowed"),
    NO_BLOCK_ENTITY_DATA("BuildPlan v2 cannot carry block-entity data"),
    NO_TRANSACTIONAL_PLACEMENT("Block placement is not transactional across the whole plan"),
    ORIGIN_MUST_BE_OPERATOR_SELECTED("The world origin is selected by an operator on the Minecraft side"),
    LEGACY_RUNTIME_NOT_VERIFIED("This legacy or experimental runtime has no recorded runtime verification"),
    LEGACY_BRIDGE_INTERFACE_UNVERIFIED("The declared legacy bridge interface has never been exercised"),
    BLOCK_STATE_MAPPING_NOT_VERIFIED("No verified block/state mapping exists for this runtime target"),
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

/** One exact runtime identity; Java and API constraints are explicit rather than inferred from Minecraft version. */
@Serializable
data class SupportedMinecraftRuntimeDescriptor(
    val adapterId: MinecraftAdapterId,
    val edition: MinecraftEdition,
    val version: MinecraftVersion,
    val loader: MinecraftLoader,
    val loaderVersion: String,
    val bridgeProtocolVersion: Int,
    val bridgeVersion: String,
    val javaRuntimeRequirement: JavaRuntimeRequirement? = null,
    val requiredFabricApiVersion: String? = null,
    val supportStatus: MinecraftCompatibilityStatus = MinecraftCompatibilityStatus.SUPPORTED,
    /**
     * Release channel this profile claims. It is an explicit registry decision, never inferred from the version
     * number: `1.7.10` parses as a release token but is only treated as legacy because a profile declares it so.
     */
    val releaseChannel: MinecraftVersionChannel = MinecraftVersionChannel.RELEASE,
    /** Recorded verification level of this profile; a SUPPORTED claim requires a supporting rung. */
    val runtimeCertification: MinecraftRuntimeCertification = MinecraftRuntimeCertification.NOT_PERFORMED,
    /** Declared integration limitations. Required for legacy/pre-release/snapshot/beta/alpha profiles. */
    val limitations: Set<MinecraftRuntimeLimitation> = emptySet(),
    /** Revision label of the block/state support below; never a version guess. */
    val blockStateSupportRevision: String = MinecraftTargetBlockStateCatalog.NONE_DECLARED_REVISION,
    /** How platform-neutral BuildPlan content is checked for this profile. */
    val contentValidationMode: MinecraftContentValidationMode = MinecraftContentValidationMode.SERVER_SIDE_VALIDATION,
    /** Verified block/state mapping for [MinecraftContentValidationMode.APP_SIDE_MAPPING]; EMPTY fails closed. */
    @kotlinx.serialization.Transient
    val blockStateCatalog: MinecraftTargetBlockStateCatalog = MinecraftTargetBlockStateCatalog.EMPTY,
    val maximumValidatedOperations: Int,
    val maximumRequestBytes: Int,
    val maximumOperationsPerTick: Int,
    val maximumExecutionSeconds: Int,
    val maximumDimensions: MinecraftDimensionLimits,
) {
    /** True when this profile describes a runtime outside the current release channel family. */
    val isLegacyOrExperimental: Boolean get() = releaseChannel != MinecraftVersionChannel.RELEASE

    /** Certification summary used by the UI and by callers that only have a profile. */
    val certification: MinecraftRuntimeCertification get() = runtimeCertification

    /** Execution is authorized only for a certified, non-experimental profile. */
    val authorizesExecution: Boolean
        get() = supportStatus == MinecraftCompatibilityStatus.SUPPORTED && runtimeCertification.authorizesSupport

    fun matchesRuntimeIdentity(runtime: MinecraftRuntimeDescriptor): Boolean =
        runtime.edition == edition && runtime.version == version && runtime.loader == loader &&
            runtime.loaderVersion == loaderVersion && runtime.bridgeProtocolVersion == bridgeProtocolVersion &&
            runtime.bridgeVersion == bridgeVersion

    fun matches(runtime: MinecraftRuntimeDescriptor): Boolean =
        matchesRuntimeIdentity(runtime) &&
            (javaRuntimeRequirement == null || runtime.javaRuntimeMajor?.let(javaRuntimeRequirement::supports) == true) &&
            (requiredFabricApiVersion == null || runtime.fabricApiVersion == requiredFabricApiVersion)

    internal fun hasSameRuntimeIdentity(other: SupportedMinecraftRuntimeDescriptor): Boolean =
        edition == other.edition && version == other.version && loader == other.loader &&
            loaderVersion == other.loaderVersion && bridgeProtocolVersion == other.bridgeProtocolVersion &&
            bridgeVersion == other.bridgeVersion &&
            (javaRuntimeRequirement == null || other.javaRuntimeRequirement == null ||
                javaRuntimeRequirement.overlaps(other.javaRuntimeRequirement)) &&
            (requiredFabricApiVersion == null || other.requiredFabricApiVersion == null ||
                requiredFabricApiVersion == other.requiredFabricApiVersion)
}

@Serializable
enum class MinecraftCompatibilityStatus {
    SUPPORTED,
    EXPERIMENTAL,
    UNSUPPORTED,
    UNKNOWN,
}

/** Stable machine-readable diagnostics; localized/user-readable explanations stay alongside each result. */
@Serializable
enum class MinecraftCompatibilityReasonCode(val displayName: String) {
    UNKNOWN_RUNTIME_DESCRIPTOR("Runtime details are incomplete or unrecognized"),
    INVALID_RUNTIME_DESCRIPTOR("Runtime details are inconsistent or invalid"),
    UNSUPPORTED_MINECRAFT_VERSION("Minecraft version is not registered"),
    UNSUPPORTED_LOADER("Minecraft loader is not registered"),
    UNSUPPORTED_LOADER_VERSION("Loader version is not registered"),
    INCOMPATIBLE_JAVA_RUNTIME("Server Java runtime is outside the profile's supported range"),
    FABRIC_API_MISMATCH("Fabric API version does not match the registered profile"),
    BRIDGE_PROTOCOL_MISMATCH("CraftMind Bridge protocol version does not match"),
    BRIDGE_VERSION_MISMATCH("CraftMind Bridge version is not registered"),
    MISSING_CAPABILITY("The server did not report a required capability"),
    UNSUPPORTED_BUILDPLAN_SCHEMA("BuildPlan schema is not supported by this profile"),
    PLAN_LIMIT_EXCEEDED("BuildPlan exceeds a runtime or adapter limit"),
    AMBIGUOUS_ADAPTER_PROFILE("More than one adapter claims this runtime"),
    UNSUPPORTED_BLOCK("The Minecraft server does not support a requested block"),
    UNSUPPORTED_BLOCK_STATE("The Minecraft server does not support a requested block state"),
    BEDROCK_RUNTIME_NOT_CERTIFIED("No runtime-certified CraftMind Bedrock target exists for this Bedrock runtime"),
    UNSUPPORTED_BEDROCK_PLATFORM("The reported Bedrock runtime platform is not part of a registered CraftMind Bedrock contract"),
    UNKNOWN_MINECRAFT_VERSION("Minecraft version is missing or unrecognized"),
    UNSUPPORTED_LEGACY_VERSION("The reported legacy Minecraft identifier has no registered compatibility profile"),
    UNSUPPORTED_RELEASE_CHANNEL("No registered compatibility profile covers this release channel"),
    RUNTIME_NOT_CERTIFIED("This runtime is recognized but has no recorded runtime certification"),

    // Phase 13: automatic runtime detection, session binding, and adapter-selection security.
    APP_VERSION_MISMATCH("The authenticated bridge echoed a different CraftMind app version"),
    RUNTIME_DETECTION_UNAUTHORIZED("Runtime detection requires an authenticated bridge session"),
    SESSION_IDENTITY_MISMATCH("This runtime is not bound to the current authenticated bridge session"),
    RUNTIME_IDENTITY_CHANGED("The authenticated Minecraft runtime changed after compatibility was resolved"),
}

@Serializable
@JvmInline
value class MinecraftAdapterId(val value: String) {
    init {
        require(Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(value)) { "Invalid Minecraft adapter ID" }
    }

    override fun toString(): String = value
}

data class BuildPlanRequirements(
    val requiredCapabilities: Set<MinecraftCapability>,
    /** Null for runtime-only resolution; otherwise the plan's requested operation count. */
    val operationCount: Int? = null,
    val schemaVersion: Int? = null,
    val dimensions: BuildDimensions? = null,
    /**
     * Distinct requested placement content, bounded by the shared operation limit. Empty for runtime-only
     * resolution. Adapters that must map platform-neutral content to a runtime representation use it; adapters
     * whose server validates live content (Java/Fabric) ignore it.
     */
    val requestedContent: List<RequestedBlockState> = emptyList(),
) {
    /** One distinct requested placement, independent of any edition-specific representation. */
    data class RequestedBlockState(
        val blockId: String,
        val state: Map<String, String>,
        /** First component that requested this content, when the plan carries semantic components. */
        val componentId: String?,
        val operationCount: Int,
    )

    companion object {
        val runtimeExecution = BuildPlanRequirements(
            requiredCapabilities = setOf(
                MinecraftCapability.WORLD_ACCESS,
                MinecraftCapability.BUILD_EXECUTION,
                MinecraftCapability.BLOCK_PLACEMENT,
                MinecraftCapability.WORLD_VALIDATION,
                MinecraftCapability.ORIGIN_RESOLUTION,
                MinecraftCapability.STRUCTURE_BATCHING,
                MinecraftCapability.PROGRESS_REPORTING,
                MinecraftCapability.BUILD_STATUS,
                MinecraftCapability.CANCELLATION,
                MinecraftCapability.BUILD_PLAN_V2,
            ),
        )

        fun from(plan: BuildPlan): BuildPlanRequirements {
            val required = runtimeExecution.requiredCapabilities.toMutableSet()
            if (plan.operations.any { it.blockState.isNotEmpty() }) {
                required += MinecraftCapability.BLOCK_STATE_SUPPORT
            }
            return BuildPlanRequirements(
                requiredCapabilities = required,
                operationCount = plan.operations.size,
                schemaVersion = plan.metadata.schemaVersion,
                dimensions = plan.metadata.dimensions,
                requestedContent = requestedContent(plan),
            )
        }

        /** Distinct placements in first-seen plan order; never widened, never re-ordered into a different plan. */
        private fun requestedContent(plan: BuildPlan): List<RequestedBlockState> {
            if (plan.operations.size > BuildPlanLimits.MAX_OPERATIONS) return emptyList()
            val grouped = LinkedHashMap<Pair<String, Map<String, String>>, MutableList<BuildPlanOperation>>()
            plan.operations.forEach { operation ->
                if (operation.kind != BuildPlanOperationKind.PLACE_BLOCK) return@forEach
                grouped.getOrPut(operation.blockId to operation.blockState) { mutableListOf() }.add(operation)
            }
            return grouped.entries.map { (key, operations) ->
                RequestedBlockState(
                    blockId = key.first,
                    state = key.second,
                    componentId = operations.first().componentId,
                    operationCount = operations.size,
                )
            }
        }
    }
}

data class MinecraftCompatibilityLimits(
    val maximumValidatedOperations: Int?,
    val maximumRequestBytes: Int?,
    val maximumDimensions: MinecraftDimensionLimits?,
    val javaRuntimeRequirement: JavaRuntimeRequirement?,
    val maximumOperationsPerTick: Int?,
    val maximumExecutionSeconds: Int?,
)

data class MinecraftCompatibilityResult(
    val status: MinecraftCompatibilityStatus,
    val adapterId: MinecraftAdapterId?,
    /** Capabilities reported by the authenticated bridge only. */
    val capabilities: Set<MinecraftCapability>,
    val missingCapabilities: Set<MinecraftCapability>,
    val reasons: List<String>,
    val warnings: List<String>,
    val limits: MinecraftCompatibilityLimits,
    val planWithinLimits: Boolean = true,
    /** False when the plan's requested block/state content cannot be represented by this runtime. */
    val planContentSupported: Boolean = true,
    /** Runtime-certification level of the matched Bedrock contract; null for Java runtimes. */
    val runtimeCertification: MinecraftRuntimeCertification? = null,
    /** Bounded structured diagnostics for the UI, logs, tests, and later AI refinement. */
    val diagnostics: List<MinecraftCompatibilityDiagnostic> = emptyList(),
    val reasonCodes: Set<MinecraftCompatibilityReasonCode> = emptySet(),
) {
    /**
     * EXPERIMENTAL, UNKNOWN, missing capabilities, unrepresentable plan content, or failed limits can never
     * authorize construction.
     */
    val canExecute: Boolean
        get() = status == MinecraftCompatibilityStatus.SUPPORTED && adapterId != null &&
            missingCapabilities.isEmpty() && planWithinLimits && planContentSupported
}
