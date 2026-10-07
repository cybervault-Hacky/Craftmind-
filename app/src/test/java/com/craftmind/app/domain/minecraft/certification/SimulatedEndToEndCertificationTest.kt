package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.LegacyRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 §5: the complete production pipeline exercised end-to-end against a controlled simulated bridge.
 *
 * Every stage runs through production code — protocol envelope and wire codecs, runtime detection, descriptor
 * validation, adapter selection, compatibility resolution, the production BuildPlan validator, the production Java
 * contract validator, the registered adapter, and the execution gate. Nothing is bypassed, and a simulated run never
 * produces runtime certification.
 */
class SimulatedEndToEndCertificationTest {
    private val resolver = DefaultMinecraftCompatibility.resolver

    private fun pipeline(
        bridge: SimulatedCertificationBridge,
        testRunId: String = "certification-run-java-production",
    ) = SimulatedCertificationPipeline(bridge = bridge, resolver = resolver, testRunId = testRunId)

    private fun javaBridge() = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)

    @Test
    fun theProductionJavaRuntimeCompletesEverySimulatedPipelineStageInOrder() = runBlocking {
        val bridge = javaBridge()
        val run = pipeline(bridge).run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))

        assertEquals(
            "every stage must pass: ${run.stages.filter { it.outcome != MinecraftVerificationOutcome.PASSED }}",
            SimulatedPipelineStage.entries.size,
            run.stages.count { it.outcome == MinecraftVerificationOutcome.PASSED },
        )
        assertTrue(run.completedEveryStage)
        assertEquals(SimulatedPipelineStage.entries.toList(), run.stages.map { it.stage })
        assertNull(run.failedStage)

        // Detection → selection → resolution used the authenticated report parsed by the production codec.
        assertNotNull(run.snapshot)
        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, run.resolution?.detection?.status)
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, run.resolution?.selection?.status)
        assertEquals("java-fabric-1.20.1", run.resolution?.selection?.adapterId?.value)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, run.resolution?.compatibility?.status)
        assertTrue(run.resolution?.canExecute == true)
        assertTrue(run.authorized)

        // Execution actually wrote the probe's placements, reported progress, and completed.
        assertEquals(CertificationBuildPlan.OPERATION_COUNT, run.worldBlocks.size)
        CertificationBuildPlan.EXPECTED_WORLD_STATE.forEach { placement ->
            assertEquals(placement.blockId, run.worldBlocks[placement.position])
        }
        assertEquals(MinecraftExecutionPhase.COMPLETED, run.executionSnapshot?.phase)
        assertTrue(bridge.progressReports >= CertificationBuildPlan.OPERATION_COUNT)
        assertTrue(bridge.events.any { it.startsWith("preflight-ready:") })
        assertTrue(bridge.events.any { it.startsWith("execution-started:") })
        assertTrue(bridge.events.any { it.startsWith("block-written:") })
        assertTrue(bridge.events.any { it.startsWith("progress-reported:") })

        // Cancellation was verified on a second prepared execution, without disturbing the completed build.
        assertEquals("CANCELLATION_ACCEPTED", run.cancellation?.outcome)
        assertEquals(CertificationBuildPlan.OPERATION_COUNT, run.worldBlocks.size)

        // Execution evidence is explicitly simulated: acceptance alone was not treated as placement.
        val execution = run.executionEvidence
        assertEquals(MinecraftEvidenceMode.SIMULATED, execution.mode)
        assertTrue(execution.wroteBlocks)
        assertTrue(execution.reportedProgress)
        assertTrue(execution.reachedCompletion)
        assertTrue(execution.cancellationVerified)
        assertFalse("a simulated run never verifies real world state", execution.worldStateVerified)
        MinecraftExecutionVerificationStage.progressOrder.forEach { stage ->
            assertTrue("expected stage $stage", stage in execution.observedStages)
        }
        assertTrue(MinecraftExecutionVerificationStage.EXECUTION_CANCELLED in execution.observedStages)
        assertFalse(MinecraftExecutionVerificationStage.EXECUTION_FAILED in execution.observedStages)
        assertEquals(MinecraftExecutionVerificationStage.EXECUTION_COMPLETED, execution.highestProgressStage)
    }

    @Test
    fun aSimulatedEndToEndRunNeverGrantsRuntimeCertification() = runBlocking {
        val run = pipeline(javaBridge()).run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))

        assertEquals(MinecraftEvidenceMode.SIMULATED, run.evidence.evidenceMode)
        assertFalse(run.evidence.realRuntimeTested)
        assertEquals(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED, run.evidence.evidenceLevel)
        assertEquals(MinecraftCertificationSource.SIMULATED_TEST_RUN, run.evidence.certificationSource)
        assertEquals(
            MinecraftVerificationOutcome.PASSED,
            run.evidence.categoryOutcomes[MinecraftVerificationCategory.PROTOCOL],
        )
        assertEquals(
            MinecraftVerificationOutcome.PASSED,
            run.evidence.categoryOutcomes[MinecraftVerificationCategory.SIMULATED_INTEGRATION],
        )
        assertEquals(
            MinecraftVerificationOutcome.PASSED,
            run.evidence.categoryOutcomes[MinecraftVerificationCategory.END_TO_END_EXECUTION],
        )
        assertEquals(
            MinecraftVerificationOutcome.NOT_PERFORMED,
            run.evidence.categoryOutcomes[MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION],
        )
        assertNull("a certification run in this repository has no trustworthy wall-clock record", run.evidence.recordedAtEpochMillis)

        val evaluation = run.evaluation
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertFalse(evaluation.isCertified)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.realRuntimeTested)
        assertEquals(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED, evaluation.evidenceLevel)
        assertTrue(MinecraftCertificationReasonCode.SIMULATED_EVIDENCE_ONLY in evaluation.reasonCodes)
        assertTrue(MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED in evaluation.reasonCodes)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, evaluation.maximumClaimableStatus)
    }

    @Test
    fun bedrockRunsTheWholePipelineAndIsRefusedBeforeAnyBridgeCall() = runBlocking {
        val bridge = SimulatedCertificationBridge(
            SimulatedCertificationBridge.TRUSTED_BRIDGE,
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                editionName = "bedrock",
                minecraftVersion = "1.21.60",
                platformName = "DEDICATED_SERVER",
                platformVersion = "1.21.60.3",
                bridgeVersion = BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION,
                limitations = BedrockRuntimeProfileRegistry.bedrockBridgeContract.limitations.map { it.name },
            ),
        )
        val run = pipeline(bridge, "certification-run-bedrock")
            .run(SimulatedPipelineRequest(profileId = "bedrock-bridge-contract"))

        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, run.resolution?.detection?.status)
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, run.resolution?.selection?.status)
        assertEquals("bedrock-bridge-contract", run.resolution?.selection?.adapterId?.value)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, run.resolution?.compatibility?.status)
        assertFalse(run.resolution?.canExecute == true)

        // The adapter refused before touching the bridge: no preflight, no blocks, no execution events.
        val preflight = run.stage(SimulatedPipelineStage.PREFLIGHT)
        assertEquals(MinecraftVerificationOutcome.FAILED, preflight?.outcome)
        assertEquals("BEDROCK_RUNTIME_NOT_CERTIFIED", preflight?.reasonCode)
        assertTrue(bridge.worldBlocks.isEmpty())
        assertFalse(bridge.events.any { it.startsWith("prepare:") })
        assertFalse(bridge.events.any { it.startsWith("execution-started:") })
        assertEquals(
            MinecraftVerificationOutcome.NOT_RUN,
            run.stage(SimulatedPipelineStage.EXECUTION)?.outcome,
        )

        assertFalse(run.evidence.realRuntimeTested)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, run.evaluation.decision)
        assertTrue(
            BedrockRuntimeProfileRegistry.bedrockBridgeContract.certifiedMinecraftVersions.isEmpty(),
        )
    }

    @Test
    fun legacyRuntimesRunTheWholePipelineAndAreRefusedBeforeAnyBridgeCall() = runBlocking {
        listOf("1.7.10" to "10.13.4.1614", "1.12.2" to "14.23.5.2859").forEach { (version, loaderVersion) ->
            val bridge = SimulatedCertificationBridge(
                SimulatedCertificationBridge.TRUSTED_BRIDGE,
                facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                    minecraftVersion = version,
                    loaderName = "Forge",
                    loaderVersion = loaderVersion,
                    fabricApiVersion = null,
                    javaRuntimeMajor = 8,
                    bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
                ),
            )
            val run = pipeline(bridge, "certification-run-legacy-$version")
                .run(SimulatedPipelineRequest(profileId = LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID))

            assertEquals("legacy $version detection", MinecraftRuntimeDetectionStatus.DETECTED, run.resolution?.detection?.status)
            assertEquals(MinecraftAdapterSelectionStatus.SELECTED, run.resolution?.selection?.status)
            assertEquals(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID, run.resolution?.selection?.adapterId?.value)
            assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, run.resolution?.compatibility?.status)
            assertFalse(run.resolution?.canExecute == true)

            val preflight = run.stage(SimulatedPipelineStage.PREFLIGHT)
            assertEquals(MinecraftVerificationOutcome.FAILED, preflight?.outcome)
            assertEquals("RUNTIME_NOT_CERTIFIED", preflight?.reasonCode)
            assertTrue(bridge.worldBlocks.isEmpty())
            assertFalse(bridge.events.any { it.startsWith("prepare:") })
            assertFalse(run.evidence.realRuntimeTested)
            assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, run.evaluation.decision)
        }
        Unit
    }

    @Test
    fun anUnsupportedRuntimeStopsBeforeExecutionAndWritesNothing() = runBlocking {
        val bridge = SimulatedCertificationBridge(
            SimulatedCertificationBridge.TRUSTED_BRIDGE,
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(minecraftVersion = "1.19.4"),
        )
        val run = pipeline(bridge, "certification-run-unregistered")
            .run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))

        assertEquals(MinecraftAdapterSelectionStatus.NO_MATCH, run.resolution?.selection?.status)
        assertEquals(MinecraftVerificationOutcome.FAILED, run.stage(SimulatedPipelineStage.ADAPTER_SELECTION)?.outcome)
        assertEquals(
            MinecraftVerificationOutcome.NOT_RUN,
            run.stage(SimulatedPipelineStage.BUILDPLAN_VALIDATION)?.outcome,
        )
        assertEquals(MinecraftVerificationOutcome.NOT_RUN, run.stage(SimulatedPipelineStage.EXECUTION)?.outcome)
        assertTrue(bridge.worldBlocks.isEmpty())
        assertFalse(run.authorized)
        assertFalse(run.evidence.realRuntimeTested)
    }

    @Test
    fun anInvalidRuntimeReportIsRejectedByTheProtocolCodecBeforeDetection() = runBlocking {
        val bridge = javaBridge()
        // A wire-level protocol downgrade is refused by the production codec, not by a later layer.
        bridge.facts = bridge.facts.copy(protocolVersion = 1)
        val rejection = runCatching { bridge.authenticatedSnapshot() }
        assertTrue(rejection.isFailure)
        assertEquals(
            "BRIDGE_PROTOCOL_UNSUPPORTED",
            (rejection.exceptionOrNull() as? MinecraftBridgeFailure)?.reasonCode,
        )

        bridge.facts = bridge.facts.copy(protocolVersion = 2, appVersionEcho = "0.9.0")
        val echoRejection = runCatching { bridge.authenticatedSnapshot() }
        assertTrue(echoRejection.isFailure)
        assertEquals(
            "BRIDGE_RESPONSE_MISMATCH",
            (echoRejection.exceptionOrNull() as? MinecraftBridgeFailure)?.reasonCode,
        )
    }

    @Test
    fun aCompletedRunProducesASanitizedMachineReadableReport() = runBlocking {
        val bridge = javaBridge()
        val run = pipeline(bridge).run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))
        val engine = MinecraftCertificationRuleEngine()
        val report = MinecraftCertificationReportBuilder(
            generatedByVersion = "1.0.0",
            generatedByEnvironment = SimulatedCertificationPipeline.JVM_UNIT_ENVIRONMENT,
            testSuiteId = SimulatedCertificationPipeline.TEST_SUITE_ID,
            testRunId = "certification-run-java-production",
            generatedAtEpochMillis = null,
            reproducibility = MinecraftCertificationReproducibility(
                harness = "SimulatedCertificationPipeline",
                toolchain = listOf("kotlin-2.1.10", "jdk-17-runtime", "jdk8-javac-tools"),
                commands = listOf(
                "./gradlew :app:testDebugUnitTest --tests '*certification*'",
                "./gradlew :bridge-protocol:test",
            ),
                sourceRevision = null,
                notes = listOf("Simulated evidence only; no Minecraft runtime was started."),
            ),
        )
            .addVersionKeyedProfile(
                resolver.registeredProfiles().single { it.adapterId.value == "java-fabric-1.20.1" },
                engine.evaluateShippedRecord(resolver.registeredProfiles().single { it.adapterId.value == "java-fabric-1.20.1" }),
            )
            .addEvidence(
                run.evidence,
                run.evaluation,
                declaredStatus = MinecraftCompatibilityStatus.SUPPORTED,
                declaredCertification = MinecraftRuntimeCertification.CERTIFIED,
            )
            .addBedrockContract(
                BedrockRuntimeProfileRegistry.bedrockBridgeContract,
                engine.evaluateShippedBedrockContract(BedrockRuntimeProfileRegistry.bedrockBridgeContract),
            )
            .build()

        assertEquals(MinecraftCertificationReport.REPORT_SCHEMA_VERSION, report.schemaVersion)
        assertEquals(3, report.entries.size)
        assertEquals(1, report.summary.certified)
        assertEquals(2, report.summary.notCertified)
        assertEquals(1, report.summary.simulatedRunsRecorded)
        assertFalse("no real runtime test happened", report.summary.realRuntimeTestsPerformed)
        assertEquals(
            MinecraftCompatibilityCertificationMatrix.runtimeRows.size,
            report.summary.matrixRuntimeCases,
        )

        val json = MinecraftCertificationReport.toJson(report)
        assertTrue(json.contains("SIMULATED_E2E_VERIFIED"))
        assertTrue(json.contains("SHIPPED_PRODUCTION_RECORD"))
        // The evidence *mode* is never REAL_RUNTIME, even though the category name appears in the report.
        assertFalse(json.contains("\"REAL_RUNTIME\""))
        assertFalse(json.contains("\"RUNTIME_TESTED\""))
        assertTrue(json.contains("\"realRuntimeTestsPerformed\": false"))
        // A committed certification report must be publishable: no credential-shaped content anywhere.
        SECRET_PROBES.forEach { probe -> assertFalse("report must not contain a secret", json.contains(probe)) }
        assertEquals(report, MinecraftCertificationReport.fromJson(json))
    }

    @Test
    fun reconnectingReRunsDetectionAndInvalidatesThePreviousBinding() = runBlocking {
        val bridge = javaBridge()
        val pipeline = pipeline(bridge)
        val before = pipeline.run(SimulatedPipelineRequest(profileId = "java-fabric-1.20.1"))
        assertTrue(before.authorized)

        val reconnectedSnapshot = bridge.reconnect()
        val afterReport = reconnectedSnapshot.runtimeReport(
            sessionId = bridge.sessionId,
            requestedAppVersion = bridge.appVersion,
            authenticated = true,
            authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
        )
        val after = resolver.runtimeGate.resolveRuntime(afterReport)
        assertTrue(after.detection.isDetected)
        assertFalse(before.resolution?.binding?.identity?.hasSameSessionIdentity(after.binding?.identity) ?: true)

        // A build authorized before the reconnect cannot be executed after it.
        val authorization = resolver.runtimeGate.authorizeExecution(
            report = afterReport,
            requirements = BuildPlanRequirements.runtimeExecution,
            previousBinding = before.resolution?.binding,
        )
        assertFalse(authorization.authorized)
        assertEquals("SESSION_IDENTITY_MISMATCH", authorization.reasonCode)
    }

    @Test
    fun aRuntimeChangeAfterPreparationAbortsExecutionAndLeavesTheWorldUntouched() = runBlocking {
        val bridge = javaBridge()
        val pipeline = pipeline(bridge)
        val prepared = pipeline.run(
            SimulatedPipelineRequest(profileId = "java-fabric-1.20.1", stopAfterResolution = false),
        )
        assertTrue(prepared.authorized)
        val blocksAfterFirstRun = bridge.worldBlocks.size

        bridge.changeRuntime(bridge.facts.copy(minecraftVersion = "1.19.4"))
        val changed = pipeline(bridge, "certification-run-runtime-changed").run(
            SimulatedPipelineRequest(
                profileId = "java-fabric-1.20.1",
                previousBinding = prepared.resolution?.binding,
            ),
        )
        assertFalse(changed.authorized)
        assertEquals("RUNTIME_IDENTITY_CHANGED", changed.authorization?.reasonCode)
        assertEquals(MinecraftVerificationOutcome.NOT_RUN, changed.stage(SimulatedPipelineStage.EXECUTION)?.outcome)
        assertEquals(blocksAfterFirstRun, bridge.worldBlocks.size)
    }

    private companion object {
        val SECRET_PROBES = listOf(
            "AIzaSy", "-----BEGIN", "Bearer ", "password=", "api_key", "client_secret", "keystore",
            SimulatedCertificationBridge.TRUSTED_BRIDGE.tlsFingerprint,
        )
    }
}
