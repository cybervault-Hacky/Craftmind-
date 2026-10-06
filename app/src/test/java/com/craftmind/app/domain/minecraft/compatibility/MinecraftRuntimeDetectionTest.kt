package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.APP_VERSION
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.AUTHENTICATED_AT
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.BRIDGE_ID
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.FULL_CAPABILITIES
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.IDENTITY_FINGERPRINT
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.SESSION_ID
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.bedrockSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.javaProductionSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1122Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1710Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.preReleaseFamilySnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.report
import com.craftmind.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13 runtime detection: edition, version, release channel, loader, Java runtime, bridge/protocol, app-version
 * echo, capabilities, limits, and session binding — always from authoritative bridge facts, never guessed.
 */
class MinecraftRuntimeDetectionTest {
    private val registry = DefaultMinecraftCompatibility.registry
    private val detector = MinecraftRuntimeDetector(registry)

    @Test
    fun javaProductionRuntimeIsDetectedFromAuthoritativeBridgeFactsOnly() {
        val result = detector.detect(report(javaProductionSnapshot()))

        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, result.status)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(MinecraftEdition.JAVA, result.detectedEdition)
        assertEquals(MinecraftVersion.parse("1.20.1"), result.detectedVersion)
        assertEquals(MinecraftVersionChannel.RELEASE, result.detectedReleaseChannel)
        assertEquals(MinecraftLoader.FABRIC, result.detectedLoader)
        assertEquals("0.16.10", result.detectedLoaderVersion)
        assertEquals(17, result.detectedJavaRuntimeMajor)
        assertTrue(result.canSelectAdapter)
        assertEquals("RUNTIME_DETECTED", result.failureReasonCode())

        val identity = result.runtimeIdentity
        assertNotNull(identity)
        assertEquals(BRIDGE_ID, identity?.bridgeId)
        assertEquals(SESSION_ID, identity?.sessionId)
        assertEquals(AUTHENTICATED_AT, identity?.authenticatedAtEpochMillis)
        assertEquals(BridgeProtocol.VERSION, identity?.bridgeProtocolVersion)
        assertEquals("1.2.0", identity?.bridgeVersion)
    }

    @Test
    fun bedrockRuntimeIsDetectedAsBedrockNativeAndNeverAsJava() {
        val result = detector.detect(report(bedrockSnapshot()))

        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, result.status)
        assertEquals(MinecraftEdition.BEDROCK, result.detectedEdition)
        assertEquals(MinecraftLoader.BEDROCK_NATIVE, result.detectedLoader)
        assertNull(result.detectedJavaRuntimeMajor)
        assertEquals(MinecraftVersion.parse("1.21.60"), result.detectedVersion)
        assertFalse(result.descriptor.isBedrock.not())
        assertEquals(MinecraftRuntimePlatform.DEDICATED_SERVER, result.descriptor.platform)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun legacyJavaRuntimesAreDetectedAsJavaWithTheDeclaredLegacyChannelNotAsAThirdEdition() {
        val legacy1710 = detector.detect(report(legacyForge1710Snapshot()))
        val legacy1122 = detector.detect(report(legacyForge1122Snapshot()))

        listOf(legacy1710 to "1.7.10", legacy1122 to "1.12.2").forEach { (result, version) ->
            assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, result.status)
            assertEquals(MinecraftEdition.JAVA, result.detectedEdition)
            assertEquals(MinecraftVersion.parse(version), result.detectedVersion)
            assertEquals(MinecraftLoader.FORGE, result.detectedLoader)
            assertEquals(8, result.detectedJavaRuntimeMajor)
            // The identifier itself parses as a release token; only the explicit registry decision reports LEGACY.
            assertEquals(MinecraftVersionChannel.RELEASE, result.descriptor.releaseChannel)
            assertEquals(MinecraftVersionChannel.LEGACY, result.detectedReleaseChannel)
            assertEquals(MinecraftVersionChannel.LEGACY, result.runtimeIdentity?.releaseChannel)
        }
        assertEquals("10.13.4.1614", legacy1710.detectedLoaderVersion)
        assertEquals("14.23.5.2859", legacy1122.detectedLoaderVersion)
    }

    @Test
    fun snapshotBetaAndAlphaIdentifiersKeepTheirOwnChannelAndAreNeverMappedToARelease() {
        val snapshot = detector.detect(report(preReleaseFamilySnapshot("24w14a")))
        val beta = detector.detect(report(preReleaseFamilySnapshot("b1.7.3")))
        val alpha = detector.detect(report(preReleaseFamilySnapshot("a1.2.6")))
        val preRelease = detector.detect(report(preReleaseFamilySnapshot("1.21-pre1")))

        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, snapshot.status)
        assertEquals(MinecraftVersionChannel.SNAPSHOT, snapshot.detectedReleaseChannel)
        assertEquals(MinecraftVersionChannel.BETA, beta.detectedReleaseChannel)
        assertEquals(MinecraftVersionChannel.ALPHA, alpha.detectedReleaseChannel)
        assertEquals(MinecraftVersionChannel.PRE_RELEASE, preRelease.detectedReleaseChannel)
        // Detection is not support: none of these runtimes has a registered adapter.
        listOf(snapshot, beta, alpha, preRelease).forEach { result ->
            assertNull(result.declaredReleaseChannel)
            assertEquals(MinecraftAdapterSelectionStatus.NO_MATCH, DefaultMinecraftCompatibility.selector.select(result).status)
            assertFalse(DefaultMinecraftCompatibility.resolver.resolveRuntime(result.descriptor).canExecute)
        }
    }

    @Test
    fun unknownAndMalformedVersionsAreReportedAsUnknownWithoutANearestVersionFallback() {
        val unknown = detector.detect(report(javaProductionSnapshot(minecraftVersion = "unknown")))
        val malformed = detector.detect(report(javaProductionSnapshot(minecraftVersion = "not a version!")))
        val nearby = detector.detect(report(javaProductionSnapshot(minecraftVersion = "1.20.2")))

        assertEquals(MinecraftRuntimeDetectionStatus.UNKNOWN, unknown.status)
        assertEquals(MinecraftRuntimeDetectionStatus.UNKNOWN, malformed.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_MINECRAFT_VERSION in unknown.reasonCodes)
        assertEquals(MinecraftVersion.UNKNOWN, unknown.detectedVersion)
        assertEquals("RUNTIME_UNKNOWN", unknown.failureReasonCode())
        assertFalse(unknown.canSelectAdapter)

        // A recognized but unregistered release stays DETECTED and still resolves to no adapter.
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, nearby.status)
        assertEquals(MinecraftVersion.parse("1.20.2"), nearby.detectedVersion)
        val nearbyResolution = DefaultMinecraftCompatibility.resolver.resolveRuntime(nearby.descriptor)
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, nearbyResolution.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION in nearbyResolution.reasonCodes)
        assertNull(nearbyResolution.adapterId)
    }

    @Test
    fun editionAndLoaderCoherenceIsEnforcedAndNeverAutoCorrected() {
        val javaLoaders = listOf("Fabric" to "0.16.10", "Forge" to "0.16.10", "NeoForge" to "0.16.10", "Vanilla" to "1.0.0")
        javaLoaders.forEach { (loaderName, loaderVersion) ->
            val result = detector.detect(
                report(
                    javaProductionSnapshot(
                        loaderName = loaderName,
                        loaderVersion = loaderVersion,
                        fabricApiVersion = if (loaderName == "Fabric") "0.92.2+1.20.1" else null,
                    ),
                ),
            )
            assertEquals("loader $loaderName", MinecraftRuntimeDetectionStatus.DETECTED, result.status)
            assertEquals(MinecraftEdition.JAVA, result.detectedEdition)
        }

        val bedrockNative = detector.detect(report(bedrockSnapshot()))
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, bedrockNative.status)
        assertEquals(MinecraftEdition.BEDROCK, bedrockNative.detectedEdition)

        // Java + Bedrock Native and Bedrock + Fabric are contradictions, not something to repair.
        val javaWithBedrockLoader = detector.detect(report(javaProductionSnapshot(loaderName = "Bedrock Native")))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, javaWithBedrockLoader.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in javaWithBedrockLoader.reasonCodes)

        val bedrockWithFabric = detector.detect(
            report(
                bedrockSnapshot().let { snapshot ->
                    snapshot.copy(loaderName = "Fabric", loaderVersion = null, fabricApiVersion = null)
                },
            ),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, bedrockWithFabric.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in bedrockWithFabric.reasonCodes)
        assertEquals(MinecraftEdition.UNKNOWN, bedrockWithFabric.detectedEdition)
    }

    @Test
    fun javaRuntimeFactsAreValidatedPerReleaseInsteadOfAssumingJava17() {
        val production = detector.detect(report(javaProductionSnapshot(javaRuntimeMajor = 17)))
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, production.status)

        val legacyOnJava8 = detector.detect(report(legacyForge1710Snapshot(javaRuntimeMajor = 8)))
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, legacyOnJava8.status)

        // A missing or out-of-range Java runtime is never filled in from build settings.
        val missingJava = detector.detect(report(javaProductionSnapshot(javaRuntimeMajor = null)))
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, missingJava.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in missingJava.reasonCodes)
        assertEquals("RUNTIME_INCOMPLETE", missingJava.failureReasonCode())
        assertFalse(missingJava.canSelectAdapter)

        val impossibleJava = detector.detect(report(javaProductionSnapshot(javaRuntimeMajor = 400)))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, impossibleJava.status)
    }

    @Test
    fun missingLoaderFabricApiOrLimitsMakeTheRuntimeIncompleteInsteadOfGuessable() {
        val missingLoader = detector.detect(report(javaProductionSnapshot(loaderName = "CustomLoader")))
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, missingLoader.status)

        val missingFabricApi = detector.detect(report(javaProductionSnapshot(fabricApiVersion = null)))
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, missingFabricApi.status)

        val missingLoaderVersion = detector.detect(report(javaProductionSnapshot(loaderVersion = "unknown")))
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, missingLoaderVersion.status)

        val missingBridgeVersion = detector.detect(report(javaProductionSnapshot(bridgeVersion = "unknown")))
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, missingBridgeVersion.status)

        val descriptorWithoutLimits = javaProductionSnapshot().runtimeDescriptor.copy(
            maximumValidatedOperations = null,
            maximumRequestBytes = null,
        )
        val missingLimits = detector.detect(
            AuthenticatedMinecraftRuntimeReport(
                descriptor = descriptorWithoutLimits,
                authenticated = true,
                sessionId = SESSION_ID,
                bridgeId = BRIDGE_ID,
                identityFingerprint = IDENTITY_FINGERPRINT,
                authenticatedAtEpochMillis = AUTHENTICATED_AT,
                requestedAppVersion = APP_VERSION,
            ),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, missingLimits.status)
        assertTrue(missingLimits.diagnostics.any { it.field == MinecraftRuntimeField.LIMITS })
        assertFalse(missingLimits.canSelectAdapter)
    }

    @Test
    fun aCorrectMinecraftVersionWithAnIncompatibleBridgeStillFailsClosed() {
        val oldProtocol = detector.detect(report(javaProductionSnapshot(protocolVersion = 1)))
        val futureProtocol = detector.detect(report(javaProductionSnapshot(protocolVersion = 3)))
        val unknownBridge = detector.detect(report(javaProductionSnapshot(bridgeVersion = "9.9.9")))

        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, oldProtocol.status)
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, futureProtocol.status)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in oldProtocol.reasonCodes)
        assertEquals("BRIDGE_PROTOCOL_UNSUPPORTED", oldProtocol.failureReasonCode())
        assertFalse(oldProtocol.canSelectAdapter)

        // A registered profile requires bridge 1.2.0 exactly; a different bridge is not a protocol problem but is
        // still not executable, and never downgraded.
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, unknownBridge.status)
        val resolution = DefaultMinecraftCompatibility.resolver.resolveRuntime(unknownBridge.descriptor)
        assertFalse(resolution.canExecute)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH in resolution.reasonCodes)
    }

    @Test
    fun theApplicationVersionEchoMustMatchTheRequestedVersionExactly() {
        val mismatchedEcho = detector.detect(report(javaProductionSnapshot(clientAppVersion = "0.9.0")))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, mismatchedEcho.status)
        assertTrue(MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH in mismatchedEcho.reasonCodes)
        assertEquals("APP_VERSION_MISMATCH", mismatchedEcho.failureReasonCode())
        assertTrue(mismatchedEcho.diagnostics.any { it.field == MinecraftRuntimeField.APP_VERSION })
        assertFalse(mismatchedEcho.canSelectAdapter)

        val missingEcho = detector.detect(report(javaProductionSnapshot(clientAppVersion = null)))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, missingEcho.status)
        assertTrue(MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH in missingEcho.reasonCodes)

        val matchingEcho = detector.detect(report(javaProductionSnapshot(clientAppVersion = APP_VERSION)))
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, matchingEcho.status)
    }

    @Test
    fun anUnauthenticatedOrUnboundReportCanNeverReachAdapterSelection() {
        val unauthenticated = detector.detect(report(javaProductionSnapshot(), authenticated = false))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, unauthenticated.status)
        assertTrue(MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED in unauthenticated.reasonCodes)
        assertEquals("RUNTIME_DETECTION_UNAUTHORIZED", unauthenticated.failureReasonCode())
        assertNull(unauthenticated.runtimeIdentity)
        // The descriptor is never even interpreted for an unauthenticated caller.
        assertTrue(unauthenticated.diagnostics.all { it.field == MinecraftRuntimeField.SESSION })

        val selection = DefaultMinecraftCompatibility.selector.select(unauthenticated)
        assertEquals(MinecraftAdapterSelectionStatus.INVALID, selection.status)
        assertNull(selection.adapter)
        assertFalse(selection.isSelected)

        val noSession = detector.detect(report(javaProductionSnapshot(), sessionId = null))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, noSession.status)
        assertTrue(MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in noSession.reasonCodes)
        assertNull(noSession.runtimeIdentity)

        val staleSession = detector.detect(
            report(javaProductionSnapshot(), authenticatedAtEpochMillis = null),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, staleSession.status)
        assertTrue(MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in staleSession.reasonCodes)
    }

    @Test
    fun changedBridgeIdentityFingerprintOrOversizedReportsAreRejected() {
        val changedBridgeId = detector.detect(report(javaProductionSnapshot(), expectedBridgeId = "bridge-ffffffffffffffffffffffffffffffff"))
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, changedBridgeId.status)
        assertTrue(MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in changedBridgeId.reasonCodes)

        val changedFingerprint = detector.detect(
            report(javaProductionSnapshot(), expectedIdentityFingerprint = "11".repeat(32)),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, changedFingerprint.status)
        assertTrue(MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in changedFingerprint.reasonCodes)

        val oversized = detector.detect(
            report(javaProductionSnapshot(), reportedBytes = AuthenticatedMinecraftRuntimeReport.MAXIMUM_REPORT_BYTES + 1),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, oversized.status)
        assertTrue(oversized.diagnostics.any { it.field == MinecraftRuntimeField.DESCRIPTOR })
    }

    @Test
    fun forgedCapabilitiesAreRejectedInsteadOfTrusted() {
        val forgedWorldAccess = javaProductionSnapshot().runtimeDescriptor.copy(
            capabilities = FULL_CAPABILITIES - MinecraftCapability.WORLD_ACCESS,
            worldAvailable = true,
        )
        val forged = detector.detect(
            AuthenticatedMinecraftRuntimeReport(
                descriptor = forgedWorldAccess,
                authenticated = true,
                sessionId = SESSION_ID,
                bridgeId = BRIDGE_ID,
                identityFingerprint = IDENTITY_FINGERPRINT,
                authenticatedAtEpochMillis = AUTHENTICATED_AT,
                requestedAppVersion = APP_VERSION,
            ),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, forged.status)
        assertTrue(forged.diagnostics.any { it.field == MinecraftRuntimeField.CAPABILITIES })

        val undeclaredCapability = detector.detect(
            AuthenticatedMinecraftRuntimeReport(
                descriptor = javaProductionSnapshot().runtimeDescriptor.copy(
                    capabilities = FULL_CAPABILITIES + MinecraftCapability.UNKNOWN,
                ),
                authenticated = true,
                sessionId = SESSION_ID,
                bridgeId = BRIDGE_ID,
                identityFingerprint = IDENTITY_FINGERPRINT,
                authenticatedAtEpochMillis = AUTHENTICATED_AT,
                requestedAppVersion = APP_VERSION,
            ),
        )
        assertEquals(MinecraftRuntimeDetectionStatus.INVALID, undeclaredCapability.status)
    }

    @Test
    fun aBridgeClaimingBuildExecutionCannotMakeAnUncertifiedRuntimeExecutable() {
        val legacy = detector.detect(report(legacyForge1710Snapshot()))
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, legacy.status)
        assertTrue(MinecraftCapability.BUILD_EXECUTION in legacy.descriptor.capabilities)

        val resolution = DefaultMinecraftCompatibility.gate.resolveRuntime(report(legacyForge1710Snapshot()))
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, resolution.selection.status)
        assertFalse(resolution.canExecute)
        assertTrue(resolution.capabilityWarnings.any { it.contains("does not authorize execution") })

        val bedrockResolution = DefaultMinecraftCompatibility.gate.resolveRuntime(report(bedrockSnapshot()))
        assertFalse(bedrockResolution.canExecute)
        assertTrue(bedrockResolution.capabilityWarnings.any { it.contains("does not authorize execution") })
    }

    @Test
    fun detectionIsDeterministicForTheSameAuthoritativeReport() {
        val first = detector.detect(report(javaProductionSnapshot()), detectedAtEpochMillis = 1L)
        val second = detector.detect(report(javaProductionSnapshot()), detectedAtEpochMillis = 1L)
        val third = MinecraftRuntimeDetector(registry).detect(report(javaProductionSnapshot()), detectedAtEpochMillis = 1L)

        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(first.runtimeIdentity?.runtimeKey, second.runtimeIdentity?.runtimeKey)
        assertEquals(first.diagnostics, second.diagnostics)
    }

    @Test
    fun runtimeKeysDistinguishExactVersionsSoAChangedRuntimeIsDetectable() {
        val production = detector.detect(report(javaProductionSnapshot())).runtimeIdentity
        val older = detector.detect(report(javaProductionSnapshot(minecraftVersion = "1.19.4"))).runtimeIdentity
        val legacy = detector.detect(report(legacyForge1710Snapshot())).runtimeIdentity
        val bedrock = detector.detect(report(bedrockSnapshot())).runtimeIdentity

        assertNotNull(production)
        assertFalse(production?.hasSameRuntime(older) ?: true)
        assertFalse(production?.hasSameRuntime(legacy) ?: true)
        assertFalse(production?.hasSameRuntime(bedrock) ?: true)
        assertTrue(production?.hasSameRuntime(detector.detect(report(javaProductionSnapshot())).runtimeIdentity) ?: false)
        // Session identity is separate from runtime identity.
        assertTrue(production?.hasSameSessionIdentity(older) ?: false)
        assertFalse(
            production?.hasSameSessionIdentity(
                detector.detect(report(javaProductionSnapshot(), sessionId = "session-02")).runtimeIdentity,
            ) ?: true,
        )
    }
}
