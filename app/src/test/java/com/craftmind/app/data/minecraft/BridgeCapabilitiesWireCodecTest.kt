package com.craftmind.app.data.minecraft

import com.craftmind.app.BuildConfig
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.bridge.protocol.BridgeCrypto
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCapabilitiesWireCodecTest {
    @Test
    fun parsesExactAuthenticatedRuntimeAndServerReportedCapabilities() {
        val snapshot = BridgeCapabilitiesWireCodec.read(validPayload(), trustedBridge())

        assertEquals(2, snapshot.protocolVersion)
        assertEquals(BuildConfig.VERSION_NAME, snapshot.clientAppVersion)
        assertEquals(BuildConfig.VERSION_NAME, snapshot.runtimeDescriptor.appVersion)
        assertEquals("java", snapshot.editionName)
        assertEquals("1.20.1", snapshot.minecraftVersion)
        assertEquals(17, snapshot.javaRuntimeMajor)
        assertEquals("0.16.10", snapshot.loaderVersion)
        assertEquals("0.92.2+1.20.1", snapshot.fabricApiVersion)
        assertEquals("1.2.0", snapshot.bridgeVersion)
        assertEquals(REPORTED_CAPABILITIES, snapshot.supportedCapabilities)
        assertEquals(BridgeCrypto.formatFingerprint("00".repeat(32)), snapshot.identityFingerprint)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, snapshot.compatibilityResult.status)
        assertTrue(snapshot.executionCompatible)
    }

    @Test
    fun rejectsProtocolV1WithAnActionableUpgradeCodeWithoutInferringRuntimeValues() {
        val payload = validPayload().apply { addProperty("protocolVersion", 1) }

        assertFailure("BRIDGE_PROTOCOL_UNSUPPORTED") {
            BridgeCapabilitiesWireCodec.read(payload, null)
        }
    }

    @Test
    fun rejectsMissingOrUnknownRuntimeFieldsAndUnknownCapabilityNames() {
        val missingJava = validPayload().apply { remove("javaRuntimeMajor") }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(missingJava, null) }

        val unknownCapability = validPayload().apply {
            getAsJsonArray("supportedCapabilities").add("ARBITRARY_COMMANDS")
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(unknownCapability, null) }

        val extraField = validPayload().apply { addProperty("javaToolchainMajor", 17) }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(extraField, null) }
    }

    @Test
    fun boundsCapabilityAndSchemaArraysAndChecksCrossFieldConsistency() {
        val oversizedCapabilities = validPayload().apply {
            val values = JsonArray()
            repeat(33) { values.add("WORLD_ACCESS") }
            add("supportedCapabilities", values)
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(oversizedCapabilities, null) }

        val duplicateSchema = validPayload().apply {
            add("supportedBuildPlanSchemaVersions", JsonArray().apply { add(2); add(2) })
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(duplicateSchema, null) }

        val inconsistentExecution = validPayload().apply { addProperty("constructionExecute", false) }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(inconsistentExecution, null) }

        val outOfRangeLimits = validPayload().apply { addProperty("maximumOperationsPerTick", 65) }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") { BridgeCapabilitiesWireCodec.read(outOfRangeLimits, null) }
    }

    @Test
    fun missingBlockStateCapabilityIsNotInferredFromGenericConstructionSupport() {
        val payload = validPayload().apply {
            val capabilities = JsonArray()
            REPORTED_CAPABILITIES.filter { it != MinecraftCapability.BLOCK_STATE_SUPPORT }
                .sortedBy { it.name }
                .forEach { capabilities.add(it.wireValue) }
            add("supportedCapabilities", capabilities)
        }

        val snapshot = BridgeCapabilitiesWireCodec.read(payload, null)

        assertFalse(MinecraftCapability.BLOCK_STATE_SUPPORT in snapshot.supportedCapabilities)
        assertTrue(snapshot.executionCompatible)
    }

    @Test
    fun capabilityArrayMayBeEmptyButDoesNotCreateAdapterCapabilities() {
        val payload = validPayload().apply {
            add("supportedCapabilities", JsonArray())
            addProperty("worldAccess", false)
            addProperty("constructionExecute", false)
            addProperty("cancellation", false)
            add("dimensionId", com.google.gson.JsonNull.INSTANCE)
            add("worldSessionId", com.google.gson.JsonNull.INSTANCE)
            add("supportedBuildPlanSchemaVersions", JsonArray())
        }
        val snapshot = BridgeCapabilitiesWireCodec.read(payload, null)

        assertFalse(snapshot.executionCompatible)
        assertTrue(snapshot.runtimeDescriptor.capabilities.isEmpty())
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, snapshot.compatibilityResult.status)
    }

    @Test
    fun authenticatedCapabilitiesMustEchoTheExactLocalAppVersion() {
        val payload = validPayload().apply { addProperty("clientAppVersion", "9.9.9") }

        assertFailure("BRIDGE_RESPONSE_MISMATCH") {
            BridgeCapabilitiesWireCodec.read(payload, trustedBridge())
        }
    }

    @Test
    fun changedBridgeIdentityIsNotAcceptedAsTheSavedPairing() {
        val payload = validPayload().apply { addProperty("bridgeId", "bridge-ffffffffffffffffffffffffffffffff") }

        assertFailure("BRIDGE_IDENTITY_CHANGED") {
            BridgeCapabilitiesWireCodec.read(payload, trustedBridge())
        }
    }

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: MinecraftBridgeFailure) {
            assertEquals(expectedCode, failure.reasonCode)
            return
        }
        throw AssertionError("Expected $expectedCode")
    }

    private fun validPayload() = JsonObject().apply {
        addProperty("protocolVersion", 2)
        addProperty("bridgeId", BRIDGE_ID)
        addProperty("identityFingerprint", "00".repeat(32))
        addProperty("clientAppVersion", BuildConfig.VERSION_NAME)
        addProperty("bridgeVersion", "1.2.0")
        addProperty("edition", "java")
        addProperty("minecraftVersion", "1.20.1")
        addProperty("javaRuntimeMajor", 17)
        addProperty("loaderName", "Fabric")
        addProperty("loaderVersion", "0.16.10")
        addProperty("fabricApiVersion", "0.92.2+1.20.1")
        add("supportedCapabilities", JsonArray().apply {
            REPORTED_CAPABILITIES.sortedBy { it.name }.forEach { add(it.wireValue) }
        })
        addProperty("worldAccess", true)
        addProperty("constructionExecute", true)
        addProperty("cancellation", true)
        addProperty("maximumValidatedOperations", 4096)
        addProperty("maximumRequestBytes", 1_048_576)
        addProperty("maximumOperationsPerTick", 32)
        addProperty("maximumExecutionSeconds", 300)
        add("supportedBuildPlanSchemaVersions", JsonArray().apply { add(2) })
        addProperty("dimensionId", "minecraft:overworld")
        addProperty("worldSessionId", "world-session-test")
    }

    private fun trustedBridge() = TrustedMinecraftBridge(
        host = "192.168.1.20",
        port = 19872,
        tlsFingerprint = "00".repeat(32),
        bridgeId = BRIDGE_ID,
        clientId = "client-test",
        displayName = "Test server",
        pairedAtEpochMillis = 1_700_000_000_000,
    )

    private companion object {
        const val BRIDGE_ID = "bridge-0123456789abcdef0123456789abcdef"
        val REPORTED_CAPABILITIES = setOf(
            MinecraftCapability.WORLD_ACCESS,
            MinecraftCapability.WORLD_VALIDATION,
            MinecraftCapability.BUILD_EXECUTION,
            MinecraftCapability.BLOCK_PLACEMENT,
            MinecraftCapability.BLOCK_STATE_SUPPORT,
            MinecraftCapability.STRUCTURE_BATCHING,
            MinecraftCapability.PROGRESS_REPORTING,
            MinecraftCapability.BUILD_STATUS,
            MinecraftCapability.CANCELLATION,
            MinecraftCapability.BUILD_PLAN_V2,
            MinecraftCapability.ORIGIN_RESOLUTION,
        )
    }
}
