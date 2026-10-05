package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildPlan
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

/** Explicit abilities. Static adapter abilities and authenticated server-reported abilities are resolved separately. */
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
    val edition: MinecraftEdition = MinecraftEdition.UNKNOWN,
    val version: MinecraftVersion = MinecraftVersion.UNKNOWN,
    val loader: MinecraftLoader = MinecraftLoader.UNKNOWN,
    val loaderVersion: String? = null,
    val bridgeProtocolVersion: Int? = null,
    val bridgeVersion: String? = null,
    /** Null when the bridge protocol does not report the server JVM. */
    val javaRuntimeMajor: Int? = null,
    /** Runtime-reported capabilities only; adapter-provided capabilities are added during resolution. */
    val capabilities: Set<MinecraftCapability> = emptySet(),
    val supportedBuildPlanSchemaVersions: Set<Int> = emptySet(),
    val maximumValidatedOperations: Int? = null,
    val maximumRequestBytes: Int? = null,
    val worldAvailable: Boolean = false,
    val operatorOriginAvailable: Boolean = false,
) {
    companion object {
        /** Maps protocol-v1 fields without changing the existing bridge wire schema. */
        fun fromBridgeV1(
            bridgeProtocolVersion: Int,
            bridgeVersion: String,
            minecraftVersion: String,
            loaderName: String,
            loaderVersion: String,
            worldAccess: Boolean,
            constructionExecute: Boolean,
            cancellation: Boolean,
            maximumValidatedOperations: Int,
            maximumRequestBytes: Int,
            supportedBuildPlanSchemaVersions: Set<Int>,
            operatorOriginAvailable: Boolean,
        ): MinecraftRuntimeDescriptor {
            val loader = MinecraftLoader.fromWire(loaderName)
            val capabilities = buildSet {
                if (worldAccess) add(MinecraftCapability.WORLD_ACCESS)
                if (constructionExecute) {
                    add(MinecraftCapability.BUILD_EXECUTION)
                    add(MinecraftCapability.BLOCK_PLACEMENT)
                }
                if (cancellation) add(MinecraftCapability.CANCELLATION)
                if (operatorOriginAvailable) add(MinecraftCapability.ORIGIN_RESOLUTION)
                if (2 in supportedBuildPlanSchemaVersions) add(MinecraftCapability.BUILD_PLAN_V2)
            }
            return MinecraftRuntimeDescriptor(
                edition = loader.edition,
                version = MinecraftVersion.parse(minecraftVersion),
                loader = loader,
                loaderVersion = loaderVersion,
                bridgeProtocolVersion = bridgeProtocolVersion,
                bridgeVersion = bridgeVersion,
                capabilities = capabilities,
                supportedBuildPlanSchemaVersions = supportedBuildPlanSchemaVersions,
                maximumValidatedOperations = maximumValidatedOperations,
                maximumRequestBytes = maximumRequestBytes,
                worldAvailable = worldAccess,
                operatorOriginAvailable = operatorOriginAvailable,
            )
        }
    }
}

@Serializable
data class MinecraftDimensionLimits(
    val width: Int,
    val height: Int,
    val depth: Int,
)

/** One exact adapter-owned profile; version fields are matched literally, never by range or nearest version. */
@Serializable
data class SupportedMinecraftRuntimeDescriptor(
    val edition: MinecraftEdition,
    val version: MinecraftVersion,
    val loader: MinecraftLoader,
    val loaderVersion: String,
    val bridgeProtocolVersion: Int,
    val bridgeVersion: String,
    /** Build/mod dependency requirement only when protocol metadata does not report this runtime library. */
    val requiredPlatformApiVersion: String? = null,
    val supportStatus: MinecraftCompatibilityStatus = MinecraftCompatibilityStatus.SUPPORTED,
    val javaToolchainMajor: Int? = null,
    val maximumValidatedOperations: Int,
    val maximumRequestBytes: Int,
    val maximumDimensions: MinecraftDimensionLimits,
) {
    fun matches(runtime: MinecraftRuntimeDescriptor): Boolean =
        runtime.edition == edition &&
            runtime.version == version &&
            runtime.loader == loader &&
            runtime.loaderVersion == loaderVersion &&
            runtime.bridgeProtocolVersion == bridgeProtocolVersion &&
            runtime.bridgeVersion == bridgeVersion &&
            (runtime.javaRuntimeMajor == null || javaToolchainMajor == null || runtime.javaRuntimeMajor == javaToolchainMajor)

    internal fun hasSameRuntimeIdentity(other: SupportedMinecraftRuntimeDescriptor): Boolean =
        edition == other.edition && version == other.version && loader == other.loader &&
            loaderVersion == other.loaderVersion && bridgeProtocolVersion == other.bridgeProtocolVersion &&
            bridgeVersion == other.bridgeVersion &&
            (javaToolchainMajor == null || other.javaToolchainMajor == null || javaToolchainMajor == other.javaToolchainMajor)
}

@Serializable
enum class MinecraftCompatibilityStatus {
    SUPPORTED,
    EXPERIMENTAL,
    UNSUPPORTED,
    UNKNOWN,
}

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
) {
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
            )
        }
    }
}

data class MinecraftCompatibilityLimits(
    val maximumValidatedOperations: Int?,
    val maximumRequestBytes: Int?,
    val maximumDimensions: MinecraftDimensionLimits?,
    val javaToolchainMajor: Int?,
)

data class MinecraftCompatibilityResult(
    val status: MinecraftCompatibilityStatus,
    val adapterId: MinecraftAdapterId?,
    val capabilities: Set<MinecraftCapability>,
    val missingCapabilities: Set<MinecraftCapability>,
    val reasons: List<String>,
    val warnings: List<String>,
    val limits: MinecraftCompatibilityLimits,
    val planWithinLimits: Boolean = true,
) {
    /** EXPERIMENTAL, UNKNOWN, missing capabilities, or failed limits can never authorize construction. */
    val canExecute: Boolean
        get() = status == MinecraftCompatibilityStatus.SUPPORTED && adapterId != null &&
            missingCapabilities.isEmpty() && planWithinLimits
}
