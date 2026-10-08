package com.craftmind.app.presentation.minecraft

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelector
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCompatibilityGate
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetector
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeResolution
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures
import com.craftmind.app.presentation.settings.BridgePairingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 §9: the Minecraft screen state is a pure derivation of the real bridge state.
 *
 * Every stage must come from something the app actually holds, a status must never be overstated, and a summary
 * must never offer a build that the resolved execution binding does not authorize.
 */
class MinecraftConnectionSummaryTest {
    private val registry = DefaultMinecraftCompatibility.registry
    private val gate = MinecraftRuntimeCompatibilityGate(
        detector = MinecraftRuntimeDetector(registry),
        selector = MinecraftAdapterSelector(registry),
        resolver = MinecraftCompatibilityResolver(registry),
        clock = { RuntimeDetectionTestFixtures.AUTHENTICATED_AT },
    )

    private val production: MinecraftRuntimeResolution = gate.resolveRuntime(
        RuntimeDetectionTestFixtures.report(RuntimeDetectionTestFixtures.javaProductionSnapshot()),
    )
    private val legacy: MinecraftRuntimeResolution = gate.resolveRuntime(
        RuntimeDetectionTestFixtures.report(RuntimeDetectionTestFixtures.legacyForge1710Snapshot()),
    )
    private val bedrock: MinecraftRuntimeResolution = gate.resolveRuntime(
        RuntimeDetectionTestFixtures.report(RuntimeDetectionTestFixtures.bedrockSnapshot()),
    )
    private val unregistered: MinecraftRuntimeResolution = gate.resolveRuntime(
        RuntimeDetectionTestFixtures.report(
            RuntimeDetectionTestFixtures.javaProductionSnapshot(minecraftVersion = "1.19.4"),
        ),
    )
    private val unidentified: MinecraftRuntimeResolution = gate.resolveRuntime(
        RuntimeDetectionTestFixtures.report(
            RuntimeDetectionTestFixtures.javaProductionSnapshot(minecraftVersion = "unknown"),
        ),
    )

    private fun connected(): BridgeConnectionState.Connected = BridgeConnectionState.Connected(
        bridge = PROFILE,
        capabilities = RuntimeDetectionTestFixtures.javaProductionSnapshot(),
        authenticatedAtEpochMillis = RuntimeDetectionTestFixtures.AUTHENTICATED_AT,
        sessionId = RuntimeDetectionTestFixtures.SESSION_ID,
    )

    private fun connectedWith(resolution: MinecraftRuntimeResolution, working: Boolean = false) = BridgePairingState(
        profile = PROFILE,
        isProfileLoaded = true,
        connection = connected(),
        runtimeResolution = resolution,
        isWorking = working,
    )

    private fun everyDesignState(): List<MinecraftConnectionSummary> = listOf(
        minecraftConnectionSummary(BridgePairingState(isProfileLoaded = false, profile = PROFILE)),
        minecraftConnectionSummary(BridgePairingState(isProfileLoaded = true)),
        minecraftConnectionSummary(
            BridgePairingState(
                profile = PROFILE,
                isProfileLoaded = true,
                connection = BridgeConnectionState.Connecting,
                isWorking = true,
            ),
        ),
        minecraftConnectionSummary(
            BridgePairingState(
                profile = PROFILE,
                isProfileLoaded = true,
                connection = BridgeConnectionState.Error("BRIDGE_AUTH_REJECTED"),
            ),
        ),
        minecraftConnectionSummary(
            BridgePairingState(
                profile = PROFILE,
                isProfileLoaded = true,
                connection = BridgeConnectionState.Error(AUTH_SESSION_EXPIRED_REASON_CODE),
            ),
        ),
        minecraftConnectionSummary(BridgePairingState(profile = PROFILE, isProfileLoaded = true)),
        minecraftConnectionSummary(
            BridgePairingState(profile = PROFILE, isProfileLoaded = true, connection = connected()),
        ),
        minecraftConnectionSummary(connectedWith(changedRuntime())),
        minecraftConnectionSummary(connectedWith(unidentified)),
        minecraftConnectionSummary(connectedWith(production)),
        minecraftConnectionSummary(connectedWith(production.copy(binding = null))),
        minecraftConnectionSummary(connectedWith(legacy)),
        minecraftConnectionSummary(connectedWith(bedrock)),
        minecraftConnectionSummary(connectedWith(unregistered)),
    )

    private fun changedRuntime(): MinecraftRuntimeResolution = production.copy(
        compatibility = production.compatibility.copy(
            reasonCodes = production.compatibility.reasonCodes +
                MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED,
        ),
    )

    private fun text(summary: MinecraftConnectionSummary): String = buildString {
        append(summary.headline).append('\n')
        append(summary.detail).append('\n')
        summary.runtimeLines.forEach { append(it).append('\n') }
        summary.certificationLines.forEach { append(it).append('\n') }
        summary.diagnosticLines.forEach { append(it).append('\n') }
    }

    @Test
    fun theSavedProfileIsReadBeforeAnythingIsClaimed() {
        val summary = minecraftConnectionSummary(
            BridgePairingState(isProfileLoaded = false, profile = PROFILE),
        )

        assertEquals(MinecraftConnectionStage.LOADING_PROFILE, summary.stage)
        assertNull(summary.badge)
        assertFalse(summary.canBuild)
        assertNotNull(summary.unavailableReason)
        assertTrue(summary.runtimeLines.isEmpty())
        assertTrue(summary.certificationLines.isEmpty())
    }

    @Test
    fun anUnpairedDeviceStillExplainsWhatWorksWithoutABridge() {
        val summary = minecraftConnectionSummary(BridgePairingState(isProfileLoaded = true))

        assertEquals(MinecraftConnectionStage.NOT_PAIRED, summary.stage)
        assertEquals("Not paired", summary.badge)
        assertEquals(CraftMindTone.NEUTRAL, summary.tone)
        assertTrue(summary.detail.contains("AI plan generation and local build history work without a bridge."))
        assertFalse(summary.canBuild)
    }

    @Test
    fun pairingInFlightIsBusyAndAssumesNothing() {
        val summary = minecraftConnectionSummary(
            BridgePairingState(
                profile = PROFILE,
                isProfileLoaded = true,
                connection = BridgeConnectionState.Connecting,
                isWorking = true,
            ),
        )

        assertEquals(MinecraftConnectionStage.CONNECTING, summary.stage)
        assertTrue(summary.isBusy)
        assertEquals("Authenticating with the paired bridge…", summary.busyLabel)
        assertTrue(summary.detail.contains("Nothing is assumed about the Minecraft runtime"))
        assertFalse(summary.canBuild)
    }

    @Test
    fun anAuthenticationFailureKeepsItsReasonCodeAndStaysClosed() {
        val summary = minecraftConnectionSummary(
            BridgePairingState(
                profile = PROFILE,
                isProfileLoaded = true,
                connection = BridgeConnectionState.Error("BRIDGE_AUTH_REJECTED"),
            ),
        )

        assertEquals(MinecraftConnectionStage.AUTHENTICATION_FAILED, summary.stage)
        assertEquals("Connection failed", summary.badge)
        assertEquals(CraftMindTone.NEGATIVE, summary.tone)
        assertTrue(summary.detail.contains("BRIDGE_AUTH_REJECTED"))
        assertTrue(summary.diagnosticLines.contains("Bridge reason code: BRIDGE_AUTH_REJECTED"))
        assertFalse(summary.canBuild)
    }

    @Test
    fun anExpiredSessionIsItsOwnStageAndAsksToReconnect() {
        val summary = minecraftConnectionSummary(
            BridgePairingState(
                profile = PROFILE,
                isProfileLoaded = true,
                connection = BridgeConnectionState.Error(AUTH_SESSION_EXPIRED_REASON_CODE),
            ),
        )

        assertEquals(MinecraftConnectionStage.SESSION_EXPIRED, summary.stage)
        assertEquals("Session expired", summary.badge)
        assertEquals(CraftMindTone.CAUTION, summary.tone)
        assertTrue(summary.detail.contains("Reconnect to re-run runtime detection"))
        assertFalse(summary.canBuild)
    }

    @Test
    fun aPairedButIdleBridgeOffersReconnectAndNothingElse() {
        val summary = minecraftConnectionSummary(
            BridgePairingState(profile = PROFILE, isProfileLoaded = true),
        )

        assertEquals(MinecraftConnectionStage.DISCONNECTED, summary.stage)
        assertEquals("Disconnected", summary.badge)
        assertTrue(summary.detail.contains(PROFILE.displayName))
        assertTrue(summary.actions.showSessionControls)
        assertTrue(summary.actions.showReconnect)
        assertFalse(summary.actions.showRefresh)
        assertFalse(summary.actions.showDisconnect)
        assertFalse(summary.actions.showPairingForm)
    }

    @Test
    fun anAuthenticatedSessionWithoutAReportAssumesNoRuntime() {
        val summary = minecraftConnectionSummary(
            BridgePairingState(profile = PROFILE, isProfileLoaded = true, connection = connected()),
        )

        assertEquals(MinecraftConnectionStage.DETECTING_RUNTIME, summary.stage)
        assertEquals("Authenticated", summary.badge)
        assertEquals(CraftMindTone.INFORMATIVE, summary.tone)
        assertTrue(summary.detail.contains("no edition, version, or loader is assumed"))
        assertTrue(summary.runtimeLines.isEmpty())
        assertTrue(summary.actions.showRefresh)
        assertTrue(summary.actions.showDisconnect)
        assertFalse(summary.actions.showReconnect)
        assertFalse(summary.canBuild)
    }

    @Test
    fun aChangedRuntimeInvalidatesThePreviousResolution() {
        val summary = minecraftConnectionSummary(connectedWith(changedRuntime()))

        assertEquals(MinecraftConnectionStage.RUNTIME_CHANGED, summary.stage)
        assertEquals("Runtime changed", summary.badge)
        assertEquals(CraftMindTone.CAUTION, summary.tone)
        assertTrue(summary.detail.contains("no longer applies"))
        assertFalse(summary.canBuild)
        assertNotNull(summary.unavailableReason)
    }

    @Test
    fun anUnidentifiedRuntimeIsReportedAsUnavailable() {
        val summary = minecraftConnectionSummary(connectedWith(unidentified))

        assertEquals(MinecraftConnectionStage.RUNTIME_UNAVAILABLE, summary.stage)
        assertEquals("Not detected", summary.badge)
        assertEquals(CraftMindTone.NEGATIVE, summary.tone)
        assertTrue(summary.runtimeLines.any { it.startsWith("Detection: ") })
        assertFalse(summary.canBuild)
    }

    @Test
    fun theCertifiedProductionRuntimeCanBuildWithoutOverstating() {
        val summary = minecraftConnectionSummary(connectedWith(production))

        assertTrue(production.canExecute)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, production.compatibility.status)
        assertEquals(MinecraftConnectionStage.COMPATIBLE, summary.stage)
        assertEquals("Compatible · can build", summary.badge)
        assertEquals(CraftMindTone.POSITIVE, summary.tone)
        assertTrue(summary.canBuild)
        assertNull(summary.unavailableReason)
        assertTrue(summary.certificationLines.isNotEmpty())
        val text = text(summary)
        assertFalse(text.contains("fully compatible", ignoreCase = true))
        assertFalse(text.contains("perfect", ignoreCase = true))
        assertTrue(summary.detail.contains("A server preflight and your separate confirmation are still required"))
    }

    @Test
    fun aSupportedRuntimeWithoutAnExecutionBindingCannotBuild() {
        val summary = minecraftConnectionSummary(connectedWith(production.copy(binding = null)))

        assertEquals(MinecraftConnectionStage.COMPATIBLE, summary.stage)
        assertEquals("Supported · building blocked", summary.badge)
        assertEquals(CraftMindTone.CAUTION, summary.tone)
        assertFalse(summary.canBuild)
        assertNotNull(summary.unavailableReason)
        assertTrue(summary.detail.contains("never widens a limit or substitutes another runtime"))
    }

    @Test
    fun anExperimentalRuntimeNeverReadsAsBuildable() {
        val summary = minecraftConnectionSummary(connectedWith(legacy))

        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, legacy.compatibility.status)
        assertEquals(MinecraftConnectionStage.EXPERIMENTAL, summary.stage)
        assertEquals("Experimental", summary.badge)
        assertEquals(CraftMindTone.CAUTION, summary.tone)
        assertFalse(summary.canBuild)
        assertNotNull(summary.unavailableReason)
        val text = text(summary)
        assertTrue(text.contains("has not been performed"))
        assertFalse(text.contains("Certified production target"))
        assertFalse(text.contains("can build"))
    }

    @Test
    fun aBedrockRuntimeIsDescribedOnlyWithItsOwnFacts() {
        val summary = minecraftConnectionSummary(connectedWith(bedrock))

        assertEquals(MinecraftConnectionStage.EXPERIMENTAL, summary.stage)
        assertFalse(summary.canBuild)
        assertTrue(summary.runtimeLines.none { it.contains("Fabric") })
        assertTrue(summary.runtimeLines.none { it.contains("Java ") })
        assertTrue(text(summary).contains("not been performed") || text(summary).contains("not performed"))
    }

    @Test
    fun anIncompatibleRuntimeExplainsThatNothingIsApproximated() {
        val summary = minecraftConnectionSummary(connectedWith(unregistered))

        assertEquals(MinecraftConnectionStage.INCOMPATIBLE, summary.stage)
        assertEquals("Incompatible", summary.badge)
        assertEquals(CraftMindTone.NEGATIVE, summary.tone)
        assertTrue(summary.detail.contains("does not approximate a nearby version or cross editions"))
        assertFalse(summary.canBuild)
    }

    @Test
    fun everyDerivedStageKeepsTheSameInvariants() {
        val summaries = everyDesignState()
        assertEquals(MinecraftConnectionStage.entries.toSet(), summaries.map { it.stage }.toSet())
        for (summary in summaries) {
            if (summary.stage != MinecraftConnectionStage.LOADING_PROFILE) {
                assertNotNull("${summary.stage} must carry a badge", summary.badge)
            }
            assertEquals(
                "${summary.stage} must explain unavailability exactly when building is blocked",
                summary.canBuild,
                summary.unavailableReason == null,
            )
            if (summary.canBuild) {
                assertEquals(MinecraftConnectionStage.COMPATIBLE, summary.stage)
            }
            assertFalse(summary.isBusy && summary.busyLabel == null)
        }
    }

    @Test
    fun theBusyLabelExplainsWhatTheBridgeIsDoing() {
        assertEquals(
            "Refreshing authenticated capabilities…",
            minecraftConnectionSummary(connectedWith(production, working = true)).busyLabel,
        )
        assertEquals(
            "Working with the bridge…",
            minecraftConnectionSummary(
                BridgePairingState(profile = PROFILE, isProfileLoaded = true, isWorking = true),
            ).busyLabel,
        )
        assertNull(minecraftConnectionSummary(connectedWith(production)).busyLabel)
    }

    @Test
    fun theAuthorizationGatesNameTheRealPipelineInOrder() {
        assertEquals(4, minecraftAuthorizationGates.size)
        assertTrue(minecraftAuthorizationGates[0].startsWith("1."))
        assertTrue(minecraftAuthorizationGates[0].contains("Detection"))
        assertTrue(minecraftAuthorizationGates[1].contains("Adapter selection"))
        assertTrue(minecraftAuthorizationGates[1].contains("fails closed"))
        assertTrue(minecraftAuthorizationGates[2].contains("min(global, runtime)"))
        assertTrue(minecraftAuthorizationGates[3].contains("Authorization"))
    }

    private companion object {
        val PROFILE = TrustedMinecraftBridge(
            host = "192.168.1.20",
            port = 19872,
            tlsFingerprint = RuntimeDetectionTestFixtures.IDENTITY_FINGERPRINT,
            bridgeId = RuntimeDetectionTestFixtures.BRIDGE_ID,
            clientId = "client-test",
            displayName = "Study PC",
            pairedAtEpochMillis = RuntimeDetectionTestFixtures.AUTHENTICATED_AT,
        )
    }
}
