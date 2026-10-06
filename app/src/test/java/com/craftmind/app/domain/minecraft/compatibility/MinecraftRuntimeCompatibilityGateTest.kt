package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.ORIGIN_WITHOUT_CONSTRUCTION_CAPABILITIES
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.WORLD_SESSION_ID
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.bedrockSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.javaProductionSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1122Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1710Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.preReleaseFamilySnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 13 pipeline: session binding, runtime-change invalidation, reconnection behaviour, time-of-check/time-of-use
 * protection, and the final execution authorization. Nothing here authorizes a build from a cached or displayed
 * result; every authorization re-runs detection, validation, selection, and resolution.
 */
class MinecraftRuntimeCompatibilityGateTest {
    private companion object {
        const val EXECUTION_ID = "6f5d2f1e-8b3a-4c7d-9e10-1a2b3c4d5e6f"
    }

    private val registry = DefaultMinecraftCompatibility.registry
    private var now = 1_700_000_000_000L
    private val gate = MinecraftRuntimeCompatibilityGate(
        detector = MinecraftRuntimeDetector(registry),
        selector = MinecraftAdapterSelector(registry),
        resolver = MinecraftCompatibilityResolver(registry),
        clock = { now },
    )

    @Test
    fun theProductionJavaRuntimeResolvesReadyAndAuthorizesExecution() {
        val resolution = gate.resolveRuntime(report(javaProductionSnapshot()))

        assertEquals(MinecraftRuntimePipelinePhase.READY, resolution.phase)
        assertTrue(resolution.detection.isDetected)
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, resolution.selection.status)
        assertEquals(JavaFabric1201Adapter.ID, resolution.selection.adapterId)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, resolution.compatibility.status)
        assertTrue(resolution.canExecute)
        assertEquals("RUNTIME_READY", resolution.failureReasonCode())
        assertTrue(resolution.capabilityWarnings.isEmpty())

        val binding = resolution.binding
        assertNotNull(binding)
        assertTrue(binding?.canExecute == true)
        assertEquals(now, binding?.resolvedAtEpochMillis)
        assertEquals(MinecraftVersionChannel.RELEASE, binding?.identity?.releaseChannel)

        val authorization = gate.authorizeExecution(
            report = report(javaProductionSnapshot()),
            requirements = BuildPlanRequirements.from(BuildPlanTestFixtures.semanticPlan()),
            previousBinding = binding,
        )
        assertTrue(authorization.authorized)
        assertEquals("RUNTIME_EXECUTION_AUTHORIZED", authorization.reasonCode)
        assertNotNull(authorization.binding)
    }

    @Test
    fun aSelectedLegacyAdapterIsStillExperimentalAndNeverAuthorized() {
        val resolution = gate.resolveRuntime(report(legacyForge1710Snapshot()))

        assertEquals(MinecraftRuntimePipelinePhase.INCOMPATIBLE, resolution.phase)
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, resolution.selection.status)
        assertEquals(MinecraftAdapterId(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID), resolution.selection.adapterId)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, resolution.compatibility.status)
        assertTrue(MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED in resolution.compatibility.reasonCodes)
        assertFalse(resolution.canExecute)
        assertEquals(MinecraftVersionChannel.LEGACY, resolution.detection.detectedReleaseChannel)

        val authorization = gate.authorizeExecution(
            report = report(legacyForge1710Snapshot()),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = resolution.binding,
        )
        assertFalse(authorization.authorized)
        assertEquals("RUNTIME_NOT_CERTIFIED", authorization.reasonCode)
        assertNull(authorization.binding)

        val legacy1122 = gate.resolveRuntime(report(legacyForge1122Snapshot()))
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, legacy1122.selection.status)
        assertFalse(legacy1122.canExecute)
    }

    @Test
    fun bedrockIsDetectedAndSelectedButCertificationStaysUnchanged() {
        val resolution = gate.resolveRuntime(report(bedrockSnapshot()))

        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, resolution.detection.status)
        assertEquals(MinecraftEdition.BEDROCK, resolution.detection.detectedEdition)
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, resolution.selection.status)
        assertEquals(BedrockRuntimeProfileRegistry.bedrockBridgeContract.adapterId, resolution.selection.adapterId)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, resolution.compatibility.runtimeCertification)
        assertTrue(MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED in resolution.compatibility.reasonCodes)
        assertTrue(BedrockRuntimeProfileRegistry.bedrockBridgeContract.certifiedMinecraftVersions.isEmpty())
        assertFalse(resolution.canExecute)

        val authorization = gate.authorizeExecution(
            report = report(bedrockSnapshot()),
            requirements = BuildPlanRequirements.from(BuildPlanTestFixtures.semanticPlan()),
            previousBinding = resolution.binding,
        )
        assertFalse(authorization.authorized)
        assertEquals("BEDROCK_RUNTIME_NOT_CERTIFIED", authorization.reasonCode)
    }

    @Test
    fun anUnknownRuntimeNeverReachesAnAdapterAndNeverForwardsAPlan() {
        val unregistered = gate.resolveRuntime(report(javaProductionSnapshot(minecraftVersion = "1.19.4")))
        assertEquals(MinecraftRuntimePipelinePhase.SELECTING_ADAPTER, unregistered.phase)
        assertEquals(MinecraftAdapterSelectionStatus.NO_MATCH, unregistered.selection.status)
        assertNull(unregistered.binding)
        assertFalse(unregistered.canExecute)

        val unknownVersion = gate.resolveRuntime(report(javaProductionSnapshot(minecraftVersion = "unknown")))
        assertEquals(MinecraftRuntimePipelinePhase.UNKNOWN, unknownVersion.phase)
        assertEquals(MinecraftRuntimeDetectionStatus.UNKNOWN, unknownVersion.detection.status)
        assertNull(unknownVersion.binding)

        val snapshotBuild = gate.resolveRuntime(report(preReleaseFamilySnapshot("24w14a")))
        assertEquals(MinecraftAdapterSelectionStatus.NO_MATCH, snapshotBuild.selection.status)
        assertFalse(snapshotBuild.canExecute)

        listOf(unregistered, unknownVersion, snapshotBuild).forEach { resolution ->
            val authorization = gate.authorizeExecution(
                report = report(javaProductionSnapshot(minecraftVersion = "1.19.4")),
                requirements = BuildPlanRequirements.from(BuildPlanTestFixtures.semanticPlan()),
                previousBinding = resolution.binding,
            )
            assertFalse(authorization.authorized)
            assertNull(authorization.binding)
        }
    }

    @Test
    fun aChangedRuntimeBetweenResolutionAndExecutionAbortsInsteadOfReusingTheOldAdapter() {
        val displayed = gate.resolveRuntime(report(javaProductionSnapshot()))
        now += 60_000L
        val changedReport = report(javaProductionSnapshot(minecraftVersion = "1.19.4"))

        val authorization = gate.authorizeExecution(
            report = changedReport,
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )

        assertFalse(authorization.authorized)
        assertEquals("RUNTIME_IDENTITY_CHANGED", authorization.reasonCode)
        assertTrue(authorization.runtimeChanged)
        assertTrue(MinecraftCompatibilityReasonCode.RUNTIME_IDENTITY_CHANGED in authorization.reasonCodes)
        assertNull(authorization.binding)
        assertTrue(authorization.reasons.any { it.contains("1.20.1") && it.contains("1.19.4") })

        // The comparison uses the freshly detected identity, so it also works when the new runtime matches no
        // adapter and therefore has no binding of its own.
        val validity = displayed.binding?.validityFor(
            gate.resolveRuntime(changedReport).detection.runtimeIdentity,
            null,
        )
        assertTrue("expected a runtime change, got $validity", validity is MinecraftRuntimeBindingValidity.RuntimeChanged)
    }

    @Test
    fun aChangedSessionOrAdapterIdentityInvalidatesTheBinding() {
        val displayed = gate.resolveRuntime(report(javaProductionSnapshot()))
        now += 1_000L
        val reconnected = gate.resolveRuntime(report(javaProductionSnapshot(), sessionId = "session-02"))

        assertNotNull(reconnected.binding)
        val sessionValidity = displayed.binding?.validityFor(reconnected.binding?.identity, reconnected.selection.adapterId)
        assertTrue(sessionValidity is MinecraftRuntimeBindingValidity.SessionChanged)

        val sessionAuthorization = gate.authorizeExecution(
            report = report(javaProductionSnapshot(), sessionId = "session-02"),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )
        assertFalse(sessionAuthorization.authorized)
        assertEquals("SESSION_IDENTITY_MISMATCH", sessionAuthorization.reasonCode)
        assertTrue(sessionAuthorization.sessionChanged)

        // A different runtime in another session is reported as a session change first; the same runtime change
        // inside one session is reported as a runtime change.
        val legacyResolution = gate.resolveRuntime(report(legacyForge1710Snapshot(), sessionId = "session-03"))
        assertTrue(
            displayed.binding?.validityFor(legacyResolution.binding?.identity, legacyResolution.selection.adapterId) is
                MinecraftRuntimeBindingValidity.SessionChanged,
        )
        val sameSessionLegacy = gate.resolveRuntime(report(legacyForge1710Snapshot()))
        assertTrue(
            displayed.binding?.validityFor(sameSessionLegacy.binding?.identity, sameSessionLegacy.selection.adapterId) is
                MinecraftRuntimeBindingValidity.RuntimeChanged,
        )

        // A changed adapter for an unchanged runtime/session is also an execution-identity change.
        val reboundAdapter = displayed.binding?.copy(
            selection = displayed.binding!!.selection.copy(adapterId = MinecraftAdapterId("test-other-adapter")),
        )
        val adapterValidity = reboundAdapter?.validityFor(displayed.binding?.identity, displayed.selection.adapterId)
        assertTrue("adapter change must invalidate: $adapterValidity", adapterValidity is MinecraftRuntimeBindingValidity.AdapterChanged)
        assertEquals("RUNTIME_IDENTITY_CHANGED", adapterValidity?.reasonCode)
    }

    @Test
    fun aChangedWorldSessionInvalidatesPreparedWorkButANewlyReportedOriginDoesNot() {
        val prepared = gate.resolveRuntime(report(javaProductionSnapshot()))
        now += 1_000L
        val movedWorld = gate.resolveRuntime(report(javaProductionSnapshot().copy(worldSessionId = "world-session-2")))

        val validity = prepared.binding?.validityFor(movedWorld.binding?.identity, movedWorld.selection.adapterId)
        assertTrue(validity is MinecraftRuntimeBindingValidity.WorldSessionChanged)
        assertEquals("WORLD_SESSION_CHANGED", validity?.reasonCode)

        val authorization = gate.authorizeExecution(
            report = report(javaProductionSnapshot().copy(worldSessionId = "world-session-2")),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = prepared.binding,
        )
        assertFalse(authorization.authorized)
        assertEquals("WORLD_SESSION_CHANGED", authorization.reasonCode)

        // A binding that had no world session yet is not invalidated when the operator later selects an origin.
        val beforeOrigin = prepared.binding?.copy(identity = prepared.binding!!.identity.copy(worldSessionId = null))
        val afterOriginValidity = beforeOrigin?.validityFor(prepared.binding?.identity, prepared.selection.adapterId)
        assertEquals(MinecraftRuntimeBindingValidity.Valid, afterOriginValidity)
        assertEquals(WORLD_SESSION_ID, prepared.binding?.identity?.worldSessionId)
    }

    @Test
    fun executionIsRecheckedFreshSoCapabilityProtocolAndLimitChangesBlockTheBuild() {
        val displayed = gate.resolveRuntime(report(javaProductionSnapshot()))
        now += 1_000L

        val capabilityLoss = gate.authorizeExecution(
            report = report(javaProductionSnapshot(capabilities = ORIGIN_WITHOUT_CONSTRUCTION_CAPABILITIES)),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )
        assertFalse(capabilityLoss.authorized)
        assertEquals("CONSTRUCTION_DISABLED", capabilityLoss.reasonCode)
        assertTrue(MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in capabilityLoss.reasonCodes)

        val protocolChange = gate.authorizeExecution(
            report = report(javaProductionSnapshot(protocolVersion = 1)),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )
        assertFalse(protocolChange.authorized)
        assertEquals("BRIDGE_PROTOCOL_UNSUPPORTED", protocolChange.reasonCode)

        val appVersionChange = gate.authorizeExecution(
            report = report(javaProductionSnapshot(clientAppVersion = "0.9.0")),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )
        assertFalse(appVersionChange.authorized)
        assertEquals("APP_VERSION_MISMATCH", appVersionChange.reasonCode)

        val loaderChange = gate.authorizeExecution(
            report = report(javaProductionSnapshot(loaderVersion = "0.16.11")),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )
        assertFalse(loaderChange.authorized)
        assertTrue(loaderChange.runtimeChanged)

        val limitsLoss = gate.authorizeExecution(
            report = report(javaProductionSnapshot(maximumValidatedOperations = 1)),
            requirements = BuildPlanRequirements.from(BuildPlanTestFixtures.semanticPlan()),
            previousBinding = displayed.binding,
        )
        assertFalse(limitsLoss.authorized)
    }

    @Test
    fun anUnauthenticatedOrIncompleteRuntimeIsNeverAuthorized() {
        val unauthenticated = gate.authorizeExecution(
            report = report(javaProductionSnapshot(), authenticated = false),
            requirements = BuildPlanRequirements.runtimeExecution,
        )
        assertFalse(unauthenticated.authorized)
        assertEquals("RUNTIME_DETECTION_UNAUTHORIZED", unauthenticated.reasonCode)
        assertNull(unauthenticated.binding)
        assertEquals(MinecraftRuntimePipelinePhase.AUTHENTICATING, unauthenticated.resolution?.phase)

        val unbound = gate.authorizeExecution(
            report = report(javaProductionSnapshot(), sessionId = null),
            requirements = BuildPlanRequirements.runtimeExecution,
        )
        assertFalse(unbound.authorized)
        assertEquals("SESSION_IDENTITY_MISMATCH", unbound.reasonCode)
        assertEquals(MinecraftRuntimePipelinePhase.ERROR, unbound.resolution?.phase)

        val incomplete = gate.authorizeExecution(
            report = report(javaProductionSnapshot(javaRuntimeMajor = null)),
            requirements = BuildPlanRequirements.runtimeExecution,
        )
        assertFalse(incomplete.authorized)
        assertEquals("RUNTIME_INCOMPLETE", incomplete.reasonCode)
        assertEquals(MinecraftRuntimePipelinePhase.DETECTING_RUNTIME, incomplete.resolution?.phase)

        val invalidDescriptor = gate.authorizeExecution(
            report = report(javaProductionSnapshot(loaderName = "Bedrock Native")),
            requirements = BuildPlanRequirements.runtimeExecution,
        )
        assertFalse(invalidDescriptor.authorized)
        assertEquals("RUNTIME_DESCRIPTOR_INVALID", invalidDescriptor.reasonCode)
        assertEquals(MinecraftRuntimePipelinePhase.VALIDATING_RUNTIME, invalidDescriptor.resolution?.phase)
    }

    @Test
    fun planLevelAuthorizationKeepsSchemaLimitAndContentChecksFailClosed() {
        val plan = BuildPlanTestFixtures.semanticPlan()

        val production = gate.resolvePlan(plan, report(javaProductionSnapshot()))
        assertTrue(production.canExecute)
        assertTrue(production.compatibility.planWithinLimits)
        assertTrue(production.compatibility.planContentSupported)

        // The legacy adapter has no verified block/state mapping, so the same plan is not representable there.
        val legacy = gate.resolvePlan(plan, report(legacyForge1710Snapshot()))
        assertFalse(legacy.compatibility.planContentSupported)
        assertFalse(legacy.canExecute)
        val legacyAuthorization = gate.authorizeExecution(
            report = report(legacyForge1710Snapshot()),
            requirements = BuildPlanRequirements.from(plan),
            previousBinding = legacy.binding,
        )
        assertFalse(legacyAuthorization.authorized)

        val bedrock = gate.resolvePlan(plan, report(bedrockSnapshot()))
        assertFalse(bedrock.compatibility.planContentSupported)
        assertFalse(bedrock.canExecute)

        val oversized = gate.resolvePlan(
            plan,
            report(javaProductionSnapshot(maximumValidatedOperations = 1)),
        )
        assertFalse(oversized.compatibility.planWithinLimits)
        assertFalse(oversized.canExecute)

        val wrongSchema = gate.resolvePlan(
            plan,
            report(javaProductionSnapshot(schemaVersions = listOf(1))),
        )
        assertFalse(wrongSchema.canExecute)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in wrongSchema.compatibility.reasonCodes)
    }

    @Test
    fun reconnectingRunsDetectionAgainAndNeverReusesThePreviousCompatibilityResult() {
        val before = gate.resolveRuntime(report(javaProductionSnapshot()))
        assertTrue(before.canExecute)

        // Reconnect to the same runtime with a new session: detection, selection, and resolution all run again.
        now += 5_000L
        val sameRuntime = gate.resolveRuntime(report(javaProductionSnapshot(), sessionId = "session-02"))
        assertTrue(sameRuntime.canExecute)
        assertEquals(before.binding?.identity?.runtimeKey, sameRuntime.binding?.identity?.runtimeKey)
        assertFalse(before.binding?.identity?.hasSameSessionIdentity(sameRuntime.binding?.identity) ?: true)
        assertTrue((sameRuntime.binding?.resolvedAtEpochMillis ?: 0L) > (before.binding?.resolvedAtEpochMillis ?: 0L))
        assertTrue(
            before.binding?.validityFor(sameRuntime.binding?.identity, sameRuntime.selection.adapterId) is
                MinecraftRuntimeBindingValidity.SessionChanged,
        )

        // Reconnect to a different runtime: the previous eligibility is gone and the new runtime decides.
        now += 5_000L
        val afterSwitch = gate.resolveRuntime(report(legacyForge1710Snapshot(), sessionId = "session-03"))
        assertEquals(MinecraftAdapterId(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID), afterSwitch.selection.adapterId)
        assertFalse(afterSwitch.canExecute)
        assertTrue(
            before.binding?.validityFor(afterSwitch.binding?.identity, afterSwitch.selection.adapterId) is
                MinecraftRuntimeBindingValidity.SessionChanged,
        )
        val sameSessionSwitch = gate.resolveRuntime(report(legacyForge1710Snapshot()))
        assertTrue(
            before.binding?.validityFor(sameSessionSwitch.binding?.identity, sameSessionSwitch.selection.adapterId) is
                MinecraftRuntimeBindingValidity.RuntimeChanged,
        )

        // A cached descriptor may still be displayed, but it authorizes nothing.
        val cachedAuthorization = gate.authorizeExecution(
            report = report(legacyForge1710Snapshot()),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = before.binding,
        )
        assertFalse(cachedAuthorization.authorized)
        assertEquals("RUNTIME_IDENTITY_CHANGED", cachedAuthorization.reasonCode)
        assertTrue(cachedAuthorization.runtimeChanged)

        val reconnectAuthorization = gate.authorizeExecution(
            report = report(legacyForge1710Snapshot(), sessionId = "session-03"),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = before.binding,
        )
        assertFalse(reconnectAuthorization.authorized)
        assertEquals("SESSION_IDENTITY_MISMATCH", reconnectAuthorization.reasonCode)
    }

    @Test
    fun authorizationAlwaysUsesTheFreshlyResolvedBindingAndNeverTheDisplayedOne() {
        val displayed = gate.resolveRuntime(report(javaProductionSnapshot()))
        now += 30_000L
        val authorization = gate.authorizeExecution(
            report = report(javaProductionSnapshot()),
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = displayed.binding,
        )

        assertTrue(authorization.authorized)
        val fresh = authorization.binding
        assertNotNull(fresh)
        assertEquals(now, fresh?.resolvedAtEpochMillis)
        assertFalse(displayed.binding?.resolvedAtEpochMillis == fresh?.resolvedAtEpochMillis)
        assertEquals(displayed.binding?.identity?.runtimeKey, fresh?.identity?.runtimeKey)
        assertEquals(displayed.binding?.identity?.sessionKey, fresh?.identity?.sessionKey)
        // The authorization result carries the fresh resolution, so the caller cannot execute against stale data.
        assertEquals(fresh, authorization.resolution?.binding)
    }

    @Test
    fun deniedAuthorizationProvidesATypedFailureBeforeAnyBridgeCall() {
        val authorization = gate.authorizeExecution(
            report = report(bedrockSnapshot()),
            requirements = BuildPlanRequirements.runtimeExecution,
        )
        assertFalse(authorization.authorized)
        val failure = authorization.failure()
        assertTrue(failure is MinecraftBridgeFailure)
        assertEquals("BEDROCK_RUNTIME_NOT_CERTIFIED", (failure as MinecraftBridgeFailure).reasonCode)
    }

    @Test
    fun theLegacyAndBedrockAdaptersStillRefuseExecutionBeforeTouchingTheBridge() {
        val legacyAdapter = DefaultMinecraftCompatibility.resolver.adapter(
            MinecraftAdapterId(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID),
        )
        assertNotNull(legacyAdapter)
        assertTypedRefusal("RUNTIME_NOT_CERTIFIED") { legacyAdapter?.preflight(UnusedBridgeRepository(), failRecord, EXECUTION_ID) }

        val bedrockAdapter = DefaultMinecraftCompatibility.resolver.adapter(
            BedrockRuntimeProfileRegistry.bedrockBridgeContract.adapterId,
        )
        assertNotNull(bedrockAdapter)
        assertTypedRefusal("BEDROCK_RUNTIME_NOT_CERTIFIED") { bedrockAdapter?.preflight(UnusedBridgeRepository(), failRecord, EXECUTION_ID) }
    }

    private fun assertTypedRefusal(expectedCode: String, block: suspend () -> Unit) {
        val failure = try {
            runBlocking { block() }
            null
        } catch (error: MinecraftBridgeFailure) {
            error
        } catch (error: Exception) {
            fail("expected a typed bridge failure but got $error")
            null
        }
        assertNotNull("expected a typed refusal", failure)
        assertEquals(expectedCode, failure?.reasonCode)
    }

    /** A repository that fails the test if a certified-refusing adapter ever touches the shared bridge. */
    private class UnusedBridgeRepository : MinecraftBridgePairingRepository {
        override val connectionState: StateFlow<BridgeConnectionState> =
            MutableStateFlow(BridgeConnectionState.Disconnected)
        override val profile: Flow<TrustedMinecraftBridge?> = MutableStateFlow(null)

        override suspend fun pair(host: String, port: Int, tlsFingerprint: String, pairingCode: String): Unit = unused()
        override suspend fun connect(): Unit = unused()
        override suspend fun disconnect(): Unit = unused()
        override suspend fun refreshCapabilities(): Unit = unused()
        override suspend fun prepareExecution(record: LocalBuildRecord, executionId: String): MinecraftExecutionPreview = unused()
        override suspend fun startExecution(preview: MinecraftExecutionPreview): MinecraftExecutionSnapshot = unused()
        override suspend fun queryExecution(executionId: String): MinecraftExecutionQueryResult = unused()
        override suspend fun cancelExecution(executionId: String): MinecraftCancellationResult = unused()
        override suspend fun revoke(): Unit = unused()
        override suspend fun forgetLocally(): Unit = unused()

        private fun unused(): Nothing = throw AssertionError("The adapter must not touch the bridge")
    }

    private val failRecord = BuildPlanTestFixtures.record()}
