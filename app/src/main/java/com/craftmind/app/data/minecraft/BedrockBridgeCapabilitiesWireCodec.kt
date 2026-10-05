package com.craftmind.app.data.minecraft

import com.craftmind.app.BuildConfig
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform
import com.craftmind.bridge.protocol.BridgeCrypto
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BridgeProtocolException
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Strict parser for the authenticated Bedrock runtime report of a CraftMind Bedrock bridge.
 *
 * The Bedrock report reuses the shared protocol-v2 envelope, authentication, replay protection, capability
 * negotiation, limits, and execution lifecycle. Only the runtime facts differ: Bedrock has no Java, loader, or
 * Fabric API, and reports a runtime platform, platform version, and declared integration limitations instead.
 * An unknown capability, platform, or limitation name is rejected instead of being widened or ignored.
 */
internal object BedrockBridgeCapabilitiesWireCodec {
    private val bridgeIdPattern = Regex("bridge-[0-9a-f]{32}")
    private val minecraftVersionPattern = Regex("[A-Za-z0-9._+/-]{1,48}")
    private val versionTokenPattern = Regex("[A-Za-z0-9._+-]{1,64}")
    private val editionNamePattern = Regex("[A-Za-z _-]{1,16}")
    private val dimensionPattern = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")
    private val worldSessionPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val knownCapabilityNames = MinecraftCapability.entries
        .filter { it != MinecraftCapability.UNKNOWN }
        .associateBy { it.wireValue }
    /**
     * Only the limitations a Bedrock bridge may declare are accepted from the wire. Java/legacy limitation names
     * stay unknown here so a Bedrock payload can never borrow another family's declaration.
     */
    /**
     * Integration limitations a Bedrock runtime may declare over the wire: a strict subset of the shared
     * limitation model, so legacy/experimental Java limitation names are rejected on a Bedrock payload.
     */
    private val BEDROCK_WIRE_LIMITATIONS = setOf(
        MinecraftRuntimeLimitation.CANCELLATION_AT_BATCH_BOUNDARY,
        MinecraftRuntimeLimitation.NO_ROLLBACK,
        MinecraftRuntimeLimitation.NO_AUTOMATIC_RESUME,
        MinecraftRuntimeLimitation.RECOVERY_REQUIRED_AFTER_INTERRUPTION,
        MinecraftRuntimeLimitation.PROGRESS_IS_BRIDGE_REPORTED,
        MinecraftRuntimeLimitation.SINGLE_ACTIVE_EXECUTION,
        MinecraftRuntimeLimitation.NO_BLOCK_ENTITY_DATA,
        MinecraftRuntimeLimitation.NO_TRANSACTIONAL_PLACEMENT,
        MinecraftRuntimeLimitation.ORIGIN_MUST_BE_OPERATOR_SELECTED,
    )

    private val knownLimitations = BEDROCK_WIRE_LIMITATIONS.associateBy { it.name }
    private val executionCapabilities = setOf(
        MinecraftCapability.BUILD_EXECUTION,
        MinecraftCapability.BLOCK_PLACEMENT,
        MinecraftCapability.BLOCK_STATE_SUPPORT,
        MinecraftCapability.STRUCTURE_BATCHING,
        MinecraftCapability.PROGRESS_REPORTING,
        MinecraftCapability.BUILD_STATUS,
        MinecraftCapability.CANCELLATION,
    )

    fun read(payload: JsonObject, expectedProfile: TrustedMinecraftBridge?): BridgeCapabilitiesSnapshot = try {
        parse(payload, expectedProfile)
    } catch (error: MinecraftBridgeFailure) {
        throw error
    } catch (_: Exception) {
        throw MinecraftBridgeFailure("BRIDGE_CAPABILITIES_INVALID")
    }

    private fun parse(payload: JsonObject, expectedProfile: TrustedMinecraftBridge?): BridgeCapabilitiesSnapshot {
        val protocolVersion = BridgeProtocolCodec.requiredInt(payload, "protocolVersion")
        if (protocolVersion != BridgeProtocol.VERSION) fail("BRIDGE_PROTOCOL_UNSUPPORTED")
        BridgeProtocolCodec.requireExactKeys(
            payload,
            "protocolVersion", "bridgeId", "identityFingerprint", "clientAppVersion", "bridgeVersion", "edition",
            "minecraftVersion", "platform", "platformVersion", "supportedCapabilities", "worldAccess",
            "constructionExecute", "cancellation", "maximumValidatedOperations", "maximumRequestBytes",
            "maximumOperationsPerTick", "maximumExecutionSeconds", "supportedBuildPlanSchemaVersions", "limitations",
            "dimensionId", "worldSessionId",
        )
        val bridgeId = BridgeProtocolCodec.requiredString(payload, "bridgeId", 80)
        val fingerprint = BridgeCrypto.normalizeFingerprint(
            BridgeProtocolCodec.requiredString(payload, "identityFingerprint", 95),
        )
        val clientAppVersion = BridgeProtocolCodec.nullableString(payload, "clientAppVersion", 64)
        val bridgeVersion = BridgeProtocolCodec.requiredString(payload, "bridgeVersion", 64)
        val editionName = BridgeProtocolCodec.requiredString(payload, "edition", 16)
        val minecraftVersion = BridgeProtocolCodec.requiredString(payload, "minecraftVersion", 48)
        val platformName = BridgeProtocolCodec.requiredString(payload, "platform", 32)
        val platformVersion = BridgeProtocolCodec.nullableString(payload, "platformVersion", 64)
        val worldAccess = BridgeProtocolCodec.requiredBoolean(payload, "worldAccess")
        val constructionExecute = BridgeProtocolCodec.requiredBoolean(payload, "constructionExecute")
        val cancellation = BridgeProtocolCodec.requiredBoolean(payload, "cancellation")
        val maximumValidatedOperations = BridgeProtocolCodec.requiredInt(payload, "maximumValidatedOperations")
        val maximumRequestBytes = BridgeProtocolCodec.requiredInt(payload, "maximumRequestBytes")
        val maximumOperationsPerTick = BridgeProtocolCodec.requiredInt(payload, "maximumOperationsPerTick")
        val maximumExecutionSeconds = BridgeProtocolCodec.requiredInt(payload, "maximumExecutionSeconds")
        val dimensionId = BridgeProtocolCodec.nullableString(payload, "dimensionId", 130)
        val worldSessionId = BridgeProtocolCodec.nullableString(payload, "worldSessionId", 128)
        val platform = MinecraftRuntimePlatform.fromWire(platformName)

        if (!bridgeIdPattern.matches(bridgeId) || fingerprint.isEmpty() ||
            (clientAppVersion != null && !versionTokenPattern.matches(clientAppVersion)) ||
            MinecraftEdition.fromWire(editionName) != MinecraftEdition.BEDROCK ||
            !minecraftVersionPattern.matches(minecraftVersion) || !versionTokenPattern.matches(bridgeVersion) ||
            platform == MinecraftRuntimePlatform.UNKNOWN ||
            (platformVersion != null && !versionTokenPattern.matches(platformVersion)) ||
            maximumValidatedOperations !in 1..BridgeProtocol.MAX_OPERATIONS ||
            maximumRequestBytes !in BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES ||
            maximumOperationsPerTick !in 1..BridgeProtocol.MAX_OPERATIONS_PER_TICK ||
            maximumExecutionSeconds !in 1..BridgeProtocol.MAX_EXECUTION_SECONDS ||
            (dimensionId != null && !dimensionPattern.matches(dimensionId)) ||
            (worldSessionId != null && !worldSessionPattern.matches(worldSessionId)) ||
            ((dimensionId == null) != (worldSessionId == null))) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }
        if (!editionNamePattern.matches(editionName)) fail("BRIDGE_CAPABILITIES_INVALID")

        val capabilityArray = payload.get("supportedCapabilities")
        if (capabilityArray == null || !capabilityArray.isJsonArray ||
            capabilityArray.asJsonArray.size() > MAXIMUM_CAPABILITIES) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }
        val capabilityNames = capabilityArray.asJsonArray.map(::readArrayString)
        if (capabilityNames.toSet().size != capabilityNames.size) fail("BRIDGE_CAPABILITIES_INVALID")
        val capabilities = capabilityNames.map { name ->
            knownCapabilityNames[name] ?: fail("BRIDGE_CAPABILITIES_INVALID")
        }.toSet()

        val schemaElement = payload.get("supportedBuildPlanSchemaVersions")
        if (schemaElement == null || !schemaElement.isJsonArray ||
            schemaElement.asJsonArray.size() > MAXIMUM_SCHEMA_VERSIONS) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }
        val schemaVersions = schemaElement.asJsonArray.map(::readArrayInt)
        if (schemaVersions.any { it <= 0 } || schemaVersions.toSet().size != schemaVersions.size) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }

        val limitationElement = payload.get("limitations")
        if (limitationElement == null || !limitationElement.isJsonArray ||
            limitationElement.asJsonArray.size() > BEDROCK_WIRE_LIMITATIONS.size) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }
        val limitationNames = limitationElement.asJsonArray.map(::readArrayString)
        if (limitationNames.toSet().size != limitationNames.size) fail("BRIDGE_CAPABILITIES_INVALID")
        val limitations = limitationNames.map { name ->
            knownLimitations[name] ?: fail("BRIDGE_CAPABILITIES_INVALID")
        }.toSet()

        val originAvailable = dimensionId != null && worldSessionId != null
        if ((MinecraftCapability.WORLD_ACCESS in capabilities) != worldAccess ||
            (MinecraftCapability.WORLD_VALIDATION in capabilities) != worldAccess ||
            (MinecraftCapability.BUILD_EXECUTION in capabilities) != constructionExecute ||
            (MinecraftCapability.CANCELLATION in capabilities) != cancellation ||
            (MinecraftCapability.ORIGIN_RESOLUTION in capabilities) != originAvailable ||
            (MinecraftCapability.BUILD_PLAN_V2 in capabilities) !=
            (BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION in schemaVersions) ||
            (constructionExecute && !capabilities.containsAll(executionCapabilities)) ||
            (!constructionExecute && capabilities.any { it in executionCapabilities }) ||
            (constructionExecute && (!worldAccess || !originAvailable)) ||
            (cancellation && !constructionExecute)) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }

        if (expectedProfile != null && (bridgeId != expectedProfile.bridgeId ||
                fingerprint != BridgeCrypto.normalizeFingerprint(expectedProfile.tlsFingerprint))) {
            fail("BRIDGE_IDENTITY_CHANGED")
        }
        if (expectedProfile != null && clientAppVersion != BuildConfig.VERSION_NAME) {
            fail("BRIDGE_RESPONSE_MISMATCH")
        }

        return BridgeCapabilitiesSnapshot(
            protocolVersion = protocolVersion,
            bridgeId = bridgeId,
            identityFingerprint = BridgeCrypto.formatFingerprint(fingerprint),
            clientAppVersion = clientAppVersion,
            bridgeVersion = bridgeVersion,
            editionName = editionName,
            minecraftVersion = minecraftVersion,
            javaRuntimeMajor = null,
            loaderName = MinecraftLoader.BEDROCK_NATIVE.wireValue,
            loaderVersion = null,
            fabricApiVersion = null,
            platform = platform,
            platformVersion = platformVersion,
            limitations = limitations,
            supportedCapabilities = capabilities,
            worldAccess = worldAccess,
            constructionExecute = constructionExecute,
            cancellation = cancellation,
            maximumValidatedOperations = maximumValidatedOperations,
            maximumRequestBytes = maximumRequestBytes,
            maximumOperationsPerTick = maximumOperationsPerTick,
            maximumExecutionSeconds = maximumExecutionSeconds,
            supportedBuildPlanSchemaVersions = schemaVersions,
            dimensionId = dimensionId,
            worldSessionId = worldSessionId,
        )
    }

    private fun readArrayString(item: JsonElement): String {
        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) fail("BRIDGE_CAPABILITIES_INVALID")
        return try {
            BridgeProtocolCodec.requiredString(JsonObject().apply { add("value", item) }, "value", 64)
        } catch (_: BridgeProtocolException) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }
    }

    private fun readArrayInt(item: JsonElement): Int {
        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isNumber) fail("BRIDGE_CAPABILITIES_INVALID")
        return try {
            BridgeProtocolCodec.requiredInt(JsonObject().apply { add("value", item) }, "value")
        } catch (_: BridgeProtocolException) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }
    }

    private fun fail(reasonCode: String): Nothing = throw MinecraftBridgeFailure(reasonCode)

    private const val MAXIMUM_CAPABILITIES = 32
    private const val MAXIMUM_SCHEMA_VERSIONS = 8
}
