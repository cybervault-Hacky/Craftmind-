package com.craftmind.app.data.minecraft

import com.craftmind.app.BuildConfig
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform
import com.craftmind.bridge.protocol.BridgeCrypto
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockBridgeCapabilitiesWireCodecTest {
    @Test
    fun parsesBedrockRuntimeFactsWithoutInventingJvmLoaderOrFabricValues() {
        val snapshot = BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload(), trustedBridge())

        assertEquals(2, snapshot.protocolVersion)
        assertEquals(BuildConfig.VERSION_NAME, snapshot.clientAppVersion)
        assertEquals(BuildConfig.VERSION_NAME, snapshot.runtimeDescriptor.appVersion)
        assertEquals("bedrock", snapshot.editionName)
        assertEquals("1.21.60", snapshot.minecraftVersion)
        assertNull(snapshot.javaRuntimeMajor)
        assertNull(snapshot.loaderVersion)
        assertNull(snapshot.fabricApiVersion)
        assertEquals(MinecraftLoader.BEDROCK_NATIVE.wireValue, snapshot.loaderName)
        assertEquals(MinecraftRuntimePlatform.DEDICATED_SERVER, snapshot.platform)
        assertEquals("1.21.60.3", snapshot.platformVersion)
        assertEquals(setOf(BedrockRuntimeLimitation.NO_ROLLBACK, BedrockRuntimeLimitation.CANCELLATION_AT_BATCH_BOUNDARY), snapshot.limitations)
        assertEquals(REPORTED_CAPABILITIES, snapshot.supportedCapabilities)
        assertEquals(BridgeCrypto.formatFingerprint("00".repeat(32)), snapshot.identityFingerprint)

        val runtime = snapshot.runtimeDescriptor
        assertTrue(runtime.isBedrock)
        assertEquals(MinecraftLoader.BEDROCK_NATIVE, runtime.loader)
        assertNull(runtime.javaRuntimeMajor)
        assertNull(runtime.fabricApiVersion)
        assertEquals(BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION, runtime.bridgeVersion)

        // Recognized by the Bedrock contract boundary, but never executable while no runtime is certified.
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, snapshot.compatibilityResult.status)
        assertFalse(snapshot.executionCompatible)
    }

    @Test
    fun rejectsUnknownPlatformLimitationCapabilityAndEditionValues() {
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload().apply { addProperty("platform", "MOON_BASE") }, null)
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { add("limitations", JsonArray().apply { add("TELEPORTATION") }) },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { addProperty("edition", "java") },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { remove("platform") },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { addProperty("javaRuntimeMajor", 17) },
                null,
            )
        }
    }

    @Test
    fun boundsArraysAndEnforcesCrossFieldConsistency() {
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply {
                    add("supportedCapabilities", JsonArray().apply { repeat(33) { add("WORLD_ACCESS") } })
                },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { add("supportedBuildPlanSchemaVersions", JsonArray().apply { add(2); add(2) }) },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply {
                    add("limitations", JsonArray().apply {
                        BedrockRuntimeLimitation.entries.forEach { add(it.name) }
                        add(BedrockRuntimeLimitation.NO_ROLLBACK.name)
                    })
                },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload().apply { addProperty("constructionExecute", false) }, null)
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload().apply { addProperty("maximumOperationsPerTick", 65) }, null)
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { add("worldSessionId", JsonNull.INSTANCE) },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { addProperty("platformVersion", "1.21.60.3\n") },
                null,
            )
        }
    }

    @Test
    fun rejectsJavaShapedReportsAndMalformedScalars() {
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply {
                    remove("platform")
                    remove("platformVersion")
                    remove("limitations")
                    addProperty("javaRuntimeMajor", 17)
                    addProperty("loaderName", "Fabric")
                    addProperty("loaderVersion", "0.16.10")
                    addProperty("fabricApiVersion", "0.92.2+1.20.1")
                },
                null,
            )
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { addProperty("protocolVersion", "2") },
                null,
            )
        }
    }

    @Test
    fun protocolMismatchAndAppVersionEchoRemainFailClosed() {
        assertFailure("BRIDGE_PROTOCOL_UNSUPPORTED") {
            BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload().apply { addProperty("protocolVersion", 1) }, null)
        }
        assertFailure("BRIDGE_RESPONSE_MISMATCH") {
            BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload().apply { addProperty("clientAppVersion", "9.9.9") }, trustedBridge())
        }
        assertFailure("BRIDGE_IDENTITY_CHANGED") {
            BedrockBridgeCapabilitiesWireCodec.read(
                validBedrockPayload().apply { addProperty("bridgeId", "bridge-ffffffffffffffffffffffffffffffff") },
                trustedBridge(),
            )
        }
        assertFailure("BRIDGE_RESPONSE_MISMATCH") {
            BedrockBridgeCapabilitiesWireCodec.read(validBedrockPayload().apply { add("clientAppVersion", JsonNull.INSTANCE) }, trustedBridge())
        }
    }

    @Test
    fun unauthenticatedInfoReportsMayOmitTheAppEchoButStillReportNoRuntimeFactsAsDefaults() {
        val snapshot = BridgeRuntimeReportReader.read(
            validBedrockPayload().apply {
                add("clientAppVersion", JsonNull.INSTANCE)
                addProperty("constructionExecute", false)
                addProperty("cancellation", false)
                add("supportedCapabilities", JsonArray().apply {
                    listOf(
                        MinecraftCapability.WORLD_ACCESS,
                        MinecraftCapability.WORLD_VALIDATION,
                        MinecraftCapability.ORIGIN_RESOLUTION,
                        MinecraftCapability.BUILD_PLAN_V2,
                    ).sortedBy { it.name }.forEach { add(it.wireValue) }
                })
            },
            null,
        )

        assertNull(snapshot.clientAppVersion)
        assertNull(snapshot.runtimeDescriptor.javaRuntimeMajor)
        assertEquals(MinecraftRuntimePlatform.DEDICATED_SERVER, snapshot.runtimeDescriptor.platform)
        assertFalse(snapshot.executionCompatible)
    }

    @Test
    fun theReportReaderDispatchesBedrockAndUnknownEditionsWithoutGuessing() {
        val bedrock = BridgeRuntimeReportReader.read(validBedrockPayload(), trustedBridge())
        assertEquals(MinecraftRuntimePlatform.DEDICATED_SERVER, bedrock.platform)

        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BridgeRuntimeReportReader.read(validBedrockPayload().apply { remove("edition") }, trustedBridge())
        }
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BridgeRuntimeReportReader.read(validBedrockPayload().apply { addProperty("edition", "x".repeat(17)) }, trustedBridge())
        }
        // An unknown edition keeps the Java-shaped path, which fails closed instead of being inferred here.
        assertFailure("BRIDGE_CAPABILITIES_INVALID") {
            BridgeRuntimeReportReader.read(validBedrockPayload().apply { addProperty("edition", "mystery") }, trustedBridge())
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

    private fun trustedBridge() = TrustedMinecraftBridge(
        host = "192.168.1.20",
        port = 19872,
        tlsFingerprint = BridgeCrypto.formatFingerprint("00".repeat(32)),
        bridgeId = BRIDGE_ID,
        clientId = "client-test",
        displayName = "Bedrock test server",
        pairedAtEpochMillis = 1_700_000_000_000,
    )

    private fun validBedrockPayload() = JsonObject().apply {
        addProperty("protocolVersion", 2)
        addProperty("bridgeId", BRIDGE_ID)
        addProperty("identityFingerprint", "00".repeat(32))
        addProperty("clientAppVersion", BuildConfig.VERSION_NAME)
        addProperty("bridgeVersion", BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION)
        addProperty("edition", "bedrock")
        addProperty("minecraftVersion", "1.21.60")
        addProperty("platform", "DEDICATED_SERVER")
        addProperty("platformVersion", "1.21.60.3")
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
        add("limitations", JsonArray().apply {
            add(BedrockRuntimeLimitation.CANCELLATION_AT_BATCH_BOUNDARY.name)
            add(BedrockRuntimeLimitation.NO_ROLLBACK.name)
        })
        addProperty("dimensionId", "minecraft:overworld")
        addProperty("worldSessionId", "bedrock-world-session-1")
    }

    private companion object {
        const val BRIDGE_ID = "bridge-0123456789abcdef0123456789abcdef"

        val REPORTED_CAPABILITIES = setOf(
            MinecraftCapability.WORLD_ACCESS,
            MinecraftCapability.WORLD_VALIDATION,
            MinecraftCapability.BUILD_EXECUTION,
            MinecraftCapability.BLOCK_PLACEMENT,
            MinecraftCapability.BLOCK_STATE_SUPPORT,
            MinecraftCapability.ORIGIN_RESOLUTION,
            MinecraftCapability.STRUCTURE_BATCHING,
            MinecraftCapability.PROGRESS_REPORTING,
            MinecraftCapability.BUILD_STATUS,
            MinecraftCapability.CANCELLATION,
            MinecraftCapability.BUILD_PLAN_V2,
        )
    }
}
