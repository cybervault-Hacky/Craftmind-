package com.craftmind.app.data.minecraft

import com.craftmind.app.BuildConfig
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.bridge.protocol.BridgeCrypto
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BridgeProtocolException
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Strict parser for authenticated protocol-v2 runtime/capability reports. */
internal object BridgeCapabilitiesWireCodec {
    private val bridgeIdPattern = Regex("bridge-[0-9a-f]{32}")
    private val editionNamePattern = Regex("[A-Za-z _-]{1,16}")
    private val minecraftVersionPattern = Regex("[A-Za-z0-9._+/-]{1,48}")
    private val loaderNamePattern = Regex("[A-Za-z0-9 ._+-]{1,32}")
    private val versionTokenPattern = Regex("[A-Za-z0-9._+-]{1,64}")
    private val dimensionPattern = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")
    private val worldSessionPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val knownCapabilityNames = MinecraftCapability.entries
        .filter { it != MinecraftCapability.UNKNOWN }
        .associateBy { it.wireValue }
    private val executionCapabilities = setOf(
        MinecraftCapability.BUILD_EXECUTION,
        MinecraftCapability.BLOCK_PLACEMENT,
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
            "protocolVersion", "bridgeId", "identityFingerprint", "clientAppVersion", "bridgeVersion", "edition", "minecraftVersion",
            "javaRuntimeMajor", "loaderName", "loaderVersion", "fabricApiVersion", "supportedCapabilities",
            "worldAccess", "constructionExecute", "cancellation", "maximumValidatedOperations", "maximumRequestBytes",
            "maximumOperationsPerTick", "maximumExecutionSeconds", "supportedBuildPlanSchemaVersions",
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
        val javaRuntimeMajor = BridgeProtocolCodec.requiredInt(payload, "javaRuntimeMajor")
        val loaderName = BridgeProtocolCodec.requiredString(payload, "loaderName", 32)
        val loaderVersion = BridgeProtocolCodec.requiredString(payload, "loaderVersion", 64)
        val fabricApiVersion = BridgeProtocolCodec.nullableString(payload, "fabricApiVersion", 64)
        val worldAccess = BridgeProtocolCodec.requiredBoolean(payload, "worldAccess")
        val constructionExecute = BridgeProtocolCodec.requiredBoolean(payload, "constructionExecute")
        val cancellation = BridgeProtocolCodec.requiredBoolean(payload, "cancellation")
        val maximumValidatedOperations = BridgeProtocolCodec.requiredInt(payload, "maximumValidatedOperations")
        val maximumRequestBytes = BridgeProtocolCodec.requiredInt(payload, "maximumRequestBytes")
        val maximumOperationsPerTick = BridgeProtocolCodec.requiredInt(payload, "maximumOperationsPerTick")
        val maximumExecutionSeconds = BridgeProtocolCodec.requiredInt(payload, "maximumExecutionSeconds")
        val dimensionId = BridgeProtocolCodec.nullableString(payload, "dimensionId", 130)
        val worldSessionId = BridgeProtocolCodec.nullableString(payload, "worldSessionId", 128)

        if (!bridgeIdPattern.matches(bridgeId) || fingerprint.isEmpty() ||
            (clientAppVersion != null && !versionTokenPattern.matches(clientAppVersion)) ||
            !editionNamePattern.matches(editionName) ||
            !minecraftVersionPattern.matches(minecraftVersion) || !loaderNamePattern.matches(loaderName) ||
            !versionTokenPattern.matches(loaderVersion) || !versionTokenPattern.matches(bridgeVersion) ||
            (fabricApiVersion != null && !versionTokenPattern.matches(fabricApiVersion)) ||
            javaRuntimeMajor !in 1..99 ||
            maximumValidatedOperations !in 1..BridgeProtocol.MAX_OPERATIONS ||
            maximumRequestBytes !in BridgeProtocol.MIN_EXECUTION_REQUEST_BYTES..BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES ||
            maximumOperationsPerTick !in 1..BridgeProtocol.MAX_OPERATIONS_PER_TICK ||
            maximumExecutionSeconds !in 1..BridgeProtocol.MAX_EXECUTION_SECONDS ||
            (dimensionId != null && !dimensionPattern.matches(dimensionId)) ||
            (worldSessionId != null && !worldSessionPattern.matches(worldSessionId)) ||
            ((dimensionId == null) != (worldSessionId == null))) {
            fail("BRIDGE_CAPABILITIES_INVALID")
        }

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
            javaRuntimeMajor = javaRuntimeMajor,
            loaderName = loaderName,
            loaderVersion = loaderVersion,
            fabricApiVersion = fabricApiVersion,
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
