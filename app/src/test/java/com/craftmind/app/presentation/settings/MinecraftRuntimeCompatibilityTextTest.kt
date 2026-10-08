package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.certification.CertificationEvidenceFixtures
import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationDecision
import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationRuleEngine
import com.craftmind.app.domain.minecraft.certification.MinecraftVerificationCategory
import com.craftmind.app.domain.minecraft.certification.MinecraftVerificationOutcome
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.LegacyRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePipelinePhase
import com.craftmind.app.domain.minecraft.compatibility.MinecraftVersion
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.bedrockSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.javaProductionSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1710Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.report
import com.craftmind.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android UI text shown for a resolved runtime is edition-aware and honest: Bedrock is never described with
 * Java/loader/Fabric facts, and a Bedrock runtime that is not SUPPORTED always states that no BuildPlan is sent.
 */
class MinecraftRuntimeCompatibilityTextTest {
    private val resolver = DefaultMinecraftCompatibility.resolver

    private val bedrock = MinecraftRuntimeDescriptor(
        appVersion = "1.0.0",
        edition = MinecraftEdition.BEDROCK,
        version = MinecraftVersion.parse("1.21.60"),
        platform = MinecraftRuntimePlatform.DEDICATED_SERVER,
        platformVersion = "1.21.60.3",
        loader = MinecraftLoader.BEDROCK_NATIVE,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        bridgeVersion = BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION,
        capabilities = setOf(
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
        ),
        supportedBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS,
        maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
        maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
    )

    private val java = MinecraftRuntimeDescriptor(
        appVersion = "1.0.0",
        edition = MinecraftEdition.JAVA,
        version = MinecraftVersion.parse("1.20.1"),
        javaRuntimeMajor = 17,
        loader = MinecraftLoader.FABRIC,
        loaderVersion = "0.16.10",
        fabricApiVersion = "0.92.2+1.20.1",
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        bridgeVersion = "1.2.0",
        capabilities = setOf(MinecraftCapability.WORLD_ACCESS, MinecraftCapability.BUILD_PLAN_V2),
        supportedBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS,
        maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
        maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
    )

    @Test
    fun bedrockLabelsNeverBorrowJavaLoaderOrFabricFacts() {
        val facts = bedrock.runtimeFactsLabel()
        assertTrue(facts.contains("platform"))
        assertTrue(facts.contains("1.21.60.3"))
        assertFalse(facts.contains("Fabric API"))
        assertFalse(facts.contains("Java runtime reported by bridge"))
        assertFalse(facts.contains("loader"))

        assertEquals("CraftMind app 1.0.0 · Bedrock Edition · Minecraft 1.21.60", bedrock.runtimeIdentityLabel())
        assertTrue(bedrock.statusLabel(resolver.resolveRuntime(bedrock)).startsWith("Bedrock compatibility: "))
    }

    @Test
    fun javaLabelsKeepTheJavaRuntimeAndFabricFacts() {
        val facts = java.runtimeFactsLabel()
        assertTrue(facts.contains("Java runtime reported by bridge: Java 17"))
        assertTrue(facts.contains("Fabric API 0.92.2+1.20.1"))
        assertFalse(facts.contains("platform"))
        assertFalse(java.statusLabel(resolver.resolveRuntime(java)).startsWith("Bedrock"))
    }

    @Test
    fun uncertifiedBedrockStatesThatNoBuildPlanWillBeSent() {
        val result = resolver.resolveRuntime(bedrock)

        // The shipped Bedrock contract records no certified runtime, so nothing may execute.
        assertFalse(result.canExecute)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result.status)

        val reason = result.unavailableReason(bedrock)
        assertTrue(reason.contains("not currently supported"))
        assertTrue(reason.contains("No BuildPlan will be sent for execution"))
        assertTrue(result.reasonCodes.any { it.name == "BEDROCK_RUNTIME_NOT_CERTIFIED" })
    }

    @Test
    fun recognizedLegacyRuntimeExplainsCertificationAndNoBuildPlanIsSent() {
        val legacy = MinecraftRuntimeDescriptor(
            appVersion = "1.0.0",
            edition = MinecraftEdition.JAVA,
            version = MinecraftVersion.parse("1.7.10"),
            javaRuntimeMajor = 8,
            loader = MinecraftLoader.FORGE,
            loaderVersion = "10.13.4.1614",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
            capabilities = setOf(MinecraftCapability.WORLD_ACCESS),
            supportedBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
            maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS,
            maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
            maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
            maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
            limitations = LegacyRuntimeProfileRegistry.declaredLimitations(),
        )

        val result = resolver.resolveRuntime(legacy)
        assertFalse(result.canExecute)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result.status)

        val reason = result.unavailableReason(legacy)
        assertTrue(reason.contains("legacy or experimental"))
        assertTrue(reason.contains("no recorded runtime certification"))
        assertTrue(reason.contains("No BuildPlan will be sent for execution"))

        val status = legacy.statusLabel(result)
        assertTrue(status.contains("Compatibility: EXPERIMENTAL"))
        assertTrue(status.contains("runtime certification: Not performed"))

        // The release channel is visible for every runtime, so a legacy/beta/snapshot target cannot be mistaken
        // for a plain release build.
        assertTrue(legacy.runtimeFactsLabel().contains("release channel RELEASE"))
        assertTrue(legacy.runtimeFactsLabel().contains("Java runtime reported by bridge: Java 8"))
    }

    @Test
    fun unavailableReasonNamesTheMissingCapabilityAndNeverSilentlyFallsBack() {
        val result = resolver.resolve(
            java,
            BuildPlanRequirements.runtimeExecution.copy(
                requiredCapabilities = setOf(MinecraftCapability.WORLD_ACCESS, MinecraftCapability.BUILD_STATUS),
            ),
        )

        assertFalse(result.canExecute)
        val reason = result.unavailableReason(java)
        assertTrue(reason.contains(MinecraftCapability.BUILD_STATUS.displayName))
        assertFalse(reason.contains("not currently supported"))

        // An unresolved runtime is reported as unmatched, never as a plan/limit problem, and the sentence always
        // states that no nearest-version or cross-edition fallback is used.
        val blank = MinecraftRuntimeDescriptor()
        val unknown = resolver.resolveRuntime(blank)
        val unknownReason = unknown.unavailableReason(blank)
        assertTrue(unknownReason.contains("no nearest-version or cross-edition fallback is used", ignoreCase = true))
        assertFalse(unknownReason.contains("exceeds the limits"))
    }
    // ---------------------------------------------------------------------------------------------
    // Phase 13: the Settings and Build Review wording for automatically detected runtimes. The user
    // is never asked to pick an edition, version, loader, or adapter, and an undetected runtime is
    // shown as unknown instead of being filled in.
    // ---------------------------------------------------------------------------------------------

    private val gate = DefaultMinecraftCompatibility.gate

    @Test
    fun buildReviewStatesThatTheTargetRuntimeWasDetectedAutomatically() {
        assertEquals("Target Runtime: Detected automatically", TARGET_RUNTIME_DETECTED_AUTOMATICALLY)

        val resolution = gate.resolveRuntime(report(javaProductionSnapshot()))
        assertEquals(MinecraftRuntimePipelinePhase.READY, resolution.phase)
        assertEquals("Ready", resolution.phase.displayName)
        assertEquals("Detected automatically from the authenticated bridge", resolution.detection.detectionStatusLabel())
        assertEquals("Build available for this authenticated runtime.", resolution.availabilityLabel())
    }

    @Test
    fun detectedJavaRuntimeShowsEditionVersionChannelLoaderBridgeAndAdapterFacts() {
        val resolution = gate.resolveRuntime(report(javaProductionSnapshot()))

        assertEquals(
            listOf(
                "Java Edition",
                "1.20.1",
                "Release",
                "Fabric 0.16.10 · Fabric API 0.92.2+1.20.1",
                "Java 17",
            ),
            resolution.runtimeSummaryLines(),
        )
        assertEquals(listOf("1.2.0", "Protocol 2"), resolution.bridgeSummaryLines())
        assertEquals(listOf("Java Edition / Fabric", "Selected"), resolution.adapterSummaryLines())
        assertEquals(
            listOf(
                "Supported",
                "Certification: Recorded as a certified production target",
                "Execution: authorized for this authenticated runtime",
            ),
            resolution.compatibilitySummaryLines(),
        )
        assertTrue(resolution.pipelineReasonLines().isEmpty())
    }

    @Test
    fun detectedLegacyRuntimeIsShownWithItsLegacyChannelAndAnUnavailableBuild() {
        val resolution = gate.resolveRuntime(report(legacyForge1710Snapshot()))

        assertEquals(MinecraftRuntimeDetectionStatus.DETECTED, resolution.detection.status)
        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, resolution.selection.status)
        assertFalse(resolution.canExecute)
        assertEquals(
            listOf("Java Edition", "1.7.10", "Legacy", "Forge 10.13.4.1614", "Java 8"),
            resolution.runtimeSummaryLines(),
        )
        assertEquals(listOf("Java Edition / Forge", "Selected"), resolution.adapterSummaryLines())
        assertTrue(resolution.compatibilitySummaryLines().contains("Experimental"))
        assertTrue(resolution.compatibilitySummaryLines().contains("Certification: Not performed"))
        assertTrue(resolution.compatibilitySummaryLines().contains("Execution: not authorized"))

        val availability = resolution.availabilityLabel()
        assertTrue(availability.startsWith("Build unavailable"))
        assertTrue(availability.contains("No BuildPlan will be sent for execution"))
        assertTrue(resolution.pipelineReasonLines().any { it.startsWith("RUNTIME_NOT_CERTIFIED:") })
        assertTrue(resolution.capabilityWarnings.any { it.contains("does not authorize execution") })
    }

    @Test
    fun detectedBedrockRuntimeShowsPlatformFactsAndNeverJavaOrFabricFacts() {
        val resolution = gate.resolveRuntime(report(bedrockSnapshot()))
        val lines = resolution.runtimeSummaryLines()

        assertEquals("Bedrock Edition", lines.first())
        assertTrue(lines.contains("Loader: Bedrock Native"))
        assertTrue(lines.any { it.startsWith("Platform: Bedrock Dedicated Server") })
        assertFalse(lines.any { it.startsWith("Java ") })
        assertFalse(lines.any { it.contains("Fabric API") })
        assertEquals(listOf("Bedrock Edition / Bedrock Native contract", "Selected"), resolution.adapterSummaryLines())
        assertTrue(resolution.availabilityLabel().contains("No BuildPlan will be sent for execution"))
    }

    @Test
    fun anUndetectedRuntimeIsShownAsUnableToVerifyInsteadOfSynthesizedFacts() {
        val unknownVersion = gate.resolveRuntime(report(javaProductionSnapshot(minecraftVersion = "unknown")))
        assertEquals(MinecraftRuntimePipelinePhase.UNKNOWN, unknownVersion.phase)
        assertEquals(MinecraftRuntimeDetectionStatus.UNKNOWN, unknownVersion.detection.status)

        val lines = unknownVersion.runtimeSummaryLines()
        assertEquals("Unable to verify runtime", lines.first())
        assertEquals("Execution unavailable", lines[1])
        assertTrue(lines.any { it.contains("Minecraft version") })
        assertEquals("Unable to verify runtime", unknownVersion.detection.detectionStatusLabel())
        assertTrue(unknownVersion.availabilityLabel().contains("No BuildPlan will be sent for execution"))
        assertEquals("none", unknownVersion.selection.adapterLabel())
        // Detection failed, so selection never ran: it is blocked rather than reported as a plain no-match.
        assertEquals("Adapter selection blocked", unknownVersion.selection.status.statusLabel())

        val incomplete = gate.resolveRuntime(report(javaProductionSnapshot(javaRuntimeMajor = null)))
        assertEquals(MinecraftRuntimePipelinePhase.DETECTING_RUNTIME, incomplete.phase)
        assertEquals("Runtime report incomplete", incomplete.detection.detectionStatusLabel())
        assertFalse(incomplete.canExecute)

        val rejected = gate.resolveRuntime(report(javaProductionSnapshot(protocolVersion = 1)))
        assertEquals(MinecraftRuntimePipelinePhase.VALIDATING_RUNTIME, rejected.phase)
        assertEquals("Runtime report rejected", rejected.detection.detectionStatusLabel())
        assertTrue(rejected.pipelineReasonLines().any { it.startsWith("BRIDGE_PROTOCOL_MISMATCH:") })

        val unauthorized = gate.resolveRuntime(report(javaProductionSnapshot(), authenticated = false))
        assertEquals(MinecraftRuntimePipelinePhase.AUTHENTICATING, unauthorized.phase)
        assertEquals("Adapter selection blocked", unauthorized.selection.status.statusLabel())
    }

    @Test
    fun anUnregisteredRuntimeIsReportedWithoutAnAdapterAndWithoutANearestVersion() {
        val resolution = gate.resolveRuntime(report(javaProductionSnapshot(minecraftVersion = "1.19.4")))

        assertEquals(MinecraftRuntimePipelinePhase.SELECTING_ADAPTER, resolution.phase)
        assertEquals(MinecraftAdapterSelectionStatus.NO_MATCH, resolution.selection.status)
        assertEquals("none", resolution.selection.adapterLabel())
        assertEquals("No matching adapter", resolution.selection.status.statusLabel())
        assertTrue(resolution.pipelineReasonLines().any { it.contains("no registered production adapter") })
        assertFalse(resolution.pipelineReasonLines().any { it.contains("1.20.1") })
        assertTrue(resolution.availabilityLabel().contains("No BuildPlan will be sent for execution"))
    }
    // -------------------------------------------------------------------------------- Phase 14 §11 wording

    @Test
    fun certificationWordingSeparatesSupportedFromCertifiedAndNeverOverclaims() {
        assertEquals(
            "Supported / Certified production target",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.CERTIFIED,
                MinecraftCertificationDecision.CERTIFIED,
            ),
        )
        // The shipped production target reads the same even when the decision came from the shipped record path.
        val shipped = MinecraftCertificationRuleEngine()
            .evaluateShippedRecord(MinecraftRuntimeProfileRegistry.javaFabric1201)
        assertEquals(
            "Supported / Certified production target",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.CERTIFIED,
                shipped.decision,
            ),
        )
        assertEquals(
            "Experimental / Runtime certification not performed",
            certificationStateLabel(
                MinecraftCompatibilityStatus.EXPERIMENTAL,
                MinecraftRuntimeCertification.NOT_PERFORMED,
                MinecraftCertificationDecision.NOT_CERTIFIED,
            ),
        )
        assertEquals(
            "Experimental / Runtime test required",
            certificationStateLabel(
                MinecraftCompatibilityStatus.EXPERIMENTAL,
                MinecraftRuntimeCertification.STATIC_ONLY,
                MinecraftCertificationDecision.NOT_CERTIFIED,
            ),
        )
        assertEquals(
            "Unsupported",
            certificationStateLabel(
                MinecraftCompatibilityStatus.UNSUPPORTED,
                MinecraftRuntimeCertification.NOT_PERFORMED,
                MinecraftCertificationDecision.NOT_CERTIFIED,
            ),
        )
        assertEquals(
            "Unavailable — runtime could not be verified",
            certificationStateLabel(
                MinecraftCompatibilityStatus.UNKNOWN,
                MinecraftRuntimeCertification.NOT_PERFORMED,
                MinecraftCertificationDecision.NOT_CERTIFIED,
            ),
        )
        assertEquals(
            "Unavailable — no authenticated runtime report",
            certificationStateLabel(null, null, null),
        )
        // Simulated evidence alone never reads as certified, even next to a SUPPORTED status.
        assertEquals(
            "Not certified",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED,
                MinecraftCertificationDecision.NOT_CERTIFIED,
            ),
        )
        assertEquals(
            "Not certified / Blocked by a declared limitation",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.BRIDGE_TESTED,
                MinecraftCertificationDecision.BLOCKED_BY_LIMITATION,
            ),
        )
        assertEquals(
            "Not certified / Insufficient evidence",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.BRIDGE_TESTED,
                MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE,
            ),
        )
        assertEquals(
            "Not certified / Verification failed",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.BRIDGE_TESTED,
                MinecraftCertificationDecision.FAILED,
            ),
        )
        assertEquals(
            "Not tested / Runtime test required",
            certificationStateLabel(
                MinecraftCompatibilityStatus.SUPPORTED,
                MinecraftRuntimeCertification.NOT_PERFORMED,
                MinecraftCertificationDecision.NOT_CERTIFIED,
            ),
        )
    }

    @Test
    fun noCertificationLabelEverClaimsFullCompatibility() {
        val labels = MinecraftCompatibilityStatus.entries.flatMap { status ->
            MinecraftRuntimeCertification.entries.flatMap { certification ->
                (MinecraftCertificationDecision.entries.map { it as MinecraftCertificationDecision? } + null)
                    .map { decision -> certificationStateLabel(status, certification, decision) }
            }
        }
        assertTrue(labels.size >= 100)
        labels.forEach { label ->
            assertFalse("ambiguous wording is not permitted: '$label'", label.contains("fully compatible", ignoreCase = true))
            assertFalse("ambiguous wording is not permitted: '$label'", label.contains("full compatibility", ignoreCase = true))
            assertFalse("ambiguous wording is not permitted: '$label'", label.contains("perfect", ignoreCase = true))
            // A label that denies certification must never also carry the certified production wording.
            if (label.startsWith("Not certified") || label.startsWith("Not tested") || label.startsWith("Experimental")) {
                assertFalse(
                    "a denying label must not claim the certified target: '$label'",
                    label.contains("Certified production target"),
                )
            }
            if (label.contains("Certified production target")) {
                assertTrue("only a supported label may claim the certified target: '$label'", label.startsWith("Supported"))
            }
            assertTrue("every label must be bounded, got ${label.length}", label.length <= 80)
        }
        // Only a certified, support-authorizing combination may use the word "Certified" as a claim.
        val certifiedClaims = MinecraftCompatibilityStatus.entries.flatMap { status ->
            MinecraftRuntimeCertification.entries.flatMap { certification ->
                MinecraftCertificationDecision.entries.map { decision ->
                    Triple(status, certification, decision) to certificationStateLabel(status, certification, decision)
                }
            }
        }.filter { (_, label) -> label == "Supported / Certified production target" }
        assertTrue(certifiedClaims.isNotEmpty())
        certifiedClaims.forEach { (combination, _) ->
            assertTrue(
                "only a support-authorizing certification may claim 'Certified', got $combination",
                combination.second.authorizesSupport,
            )
        }
    }

    @Test
    fun evidenceWordingNamesEveryCategoryAndWhetherARealRuntimeWasInvolved() {
        val simulated = CertificationEvidenceFixtures.simulated()
        val simulatedSummary = simulated.verificationSummaryLabel()
        MinecraftVerificationCategory.entries.forEach { category ->
            assertTrue("'$category' must appear in '$simulatedSummary'", simulatedSummary.contains(category.shortLabel))
        }
        assertTrue(simulatedSummary.contains("real runtime not performed"))
        assertTrue(simulatedSummary.contains("static verified"))
        assertTrue(simulatedSummary.contains("simulated e2e verified"))
        assertTrue(simulated.evidenceStateLabel().contains("simulated end-to-end"))
        assertTrue(
            "simulated wording must state that no runtime was touched",
            simulated.evidenceStateLabel().contains("no Minecraft runtime was started or modified"),
        )

        val real = CertificationEvidenceFixtures.realRuntime(executionEnvironment = "declared-certification-lab")
        assertTrue(real.verificationSummaryLabel().contains("real runtime verified"))
        assertTrue(real.evidenceStateLabel().contains("real Minecraft runtime"))
        assertFalse(real.evidenceStateLabel().contains("simulated"))

        val none = CertificationEvidenceFixtures.notPerformed()
        assertTrue(
            "an unperformed run must say so for every category",
            MinecraftVerificationCategory.entries.all { category ->
                none.categoryOutcomes[category] == MinecraftVerificationOutcome.NOT_PERFORMED
            },
        )
        assertTrue(none.verificationSummaryLabel().contains("execution not performed"))
        assertTrue(none.evidenceStateLabel().startsWith("Evidence: none recorded"))
        assertEquals(
            "not available here",
            MinecraftVerificationOutcome.NOT_AVAILABLE.evidenceMarker(),
        )
        assertEquals("blocked by policy", MinecraftVerificationOutcome.BLOCKED.evidenceMarker())
        assertEquals("FAILED", MinecraftVerificationOutcome.FAILED.evidenceMarker())
        assertEquals("not run", MinecraftVerificationOutcome.NOT_RUN.evidenceMarker())
    }

    @Test
    fun executionWordingExplainsWhyExecutionIsAllowedOrBlocked() {
        val engine = MinecraftCertificationRuleEngine()
        val shipped = engine.evaluateShippedRecord(MinecraftRuntimeProfileRegistry.javaFabric1201)
        assertEquals(
            "Execution allowed: the runtime is detected, exactly one adapter matched, and certification is recorded.",
            shipped.executionPermissionLabel(canExecute = true),
        )

        val simulated = engine.evaluate(CertificationEvidenceFixtures.simulated())
        assertEquals(
            "Execution blocked: no real Minecraft runtime test was performed, so this runtime is not certified.",
            simulated.executionPermissionLabel(canExecute = false),
        )
        assertEquals(
            "Execution allowed by the resolved compatibility result; certification evidence is reported separately.",
            simulated.executionPermissionLabel(canExecute = true),
        )

        val blocked = MinecraftCertificationRuleEngine(
            com.craftmind.app.domain.minecraft.certification.MinecraftCertificationPolicy.DEFAULT.copy(
                realRuntimeCapableEnvironments = setOf("declared-certification-lab"),
            ),
        ).evaluate(
            CertificationEvidenceFixtures.realRuntime(
                executionEnvironment = "declared-certification-lab",
                knownLimitations = setOf(
                    com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED,
                ),
            ),
        )
        assertEquals(MinecraftCertificationDecision.BLOCKED_BY_LIMITATION, blocked.decision)
        assertEquals(
            "Execution blocked: a declared limitation states this runtime is not verified.",
            blocked.executionPermissionLabel(canExecute = false),
        )

        val nothing = engine.evaluate(null)
        assertEquals(
            "Execution blocked: no real Minecraft runtime test was performed, so this runtime is not certified.",
            nothing.executionPermissionLabel(canExecute = false),
        )
    }
    @Test
    fun theCompatibilityBlockReportsCertificationStateEvidenceStateAndExecutionPermission() {
        val production = gate.resolveRuntime(report(javaProductionSnapshot()))
        val productionLines = production.certificationSummaryLines(production.compatibility)
        assertEquals(3, productionLines.size)
        assertEquals("Certification: Supported / Certified production target", productionLines[0])
        assertTrue(
            "the shipped record must say this build performed no new runtime test: ${productionLines[1]}",
            productionLines[1].startsWith("Evidence: recorded production certification") &&
                productionLines[1].contains("no new Minecraft runtime test"),
        )
        assertEquals(
            "Execution allowed: the runtime is detected, exactly one adapter matched, and certification is recorded.",
            productionLines[2],
        )

        val legacy = gate.resolveRuntime(report(legacyForge1710Snapshot()))
        val legacyLines = legacy.certificationSummaryLines(legacy.compatibility)
        assertEquals(3, legacyLines.size)
        assertEquals("Certification: Experimental / Runtime certification not performed", legacyLines[0])
        assertEquals("Evidence: none recorded (Not performed)", legacyLines[1])
        assertTrue(
            "legacy wording: ${legacyLines[2]}",
            legacyLines[2].startsWith("Execution blocked: no real Minecraft runtime test"),
        )

        val bedrock = gate.resolveRuntime(report(bedrockSnapshot()))
        val bedrockLines = bedrock.certificationSummaryLines(bedrock.compatibility)
        assertEquals(3, bedrockLines.size)
        assertEquals("Certification: Experimental / Runtime certification not performed", bedrockLines[0])
        assertEquals("Evidence: none recorded (Not performed)", bedrockLines[1])
        assertTrue(bedrockLines[2].startsWith("Execution blocked"))

        // Nothing was selected, so nothing is recorded and nothing is claimed.
        val unregistered = gate.resolveRuntime(report(javaProductionSnapshot(minecraftVersion = "1.19.4")))
        assertTrue(unregistered.certificationSummaryLines(unregistered.compatibility).isEmpty())

        // Evidence from a simulated certification run is labelled simulated and never as a real runtime.
        val simulated = CertificationEvidenceFixtures.simulated()
        val withEvidence = production.certificationSummaryLines(production.compatibility, simulated)
        assertTrue(withEvidence[1].contains("simulated end-to-end"))
        assertTrue(withEvidence[1].contains("no Minecraft runtime was started or modified"))
        assertFalse(withEvidence[1].contains("real Minecraft runtime"))
        withEvidence.forEach { line ->
            assertFalse("ambiguous wording is not permitted: '$line'", line.contains("fully compatible", ignoreCase = true))
        }

        // A real-runtime record, if one ever exists, is labelled as such and still names its level.
        val real = CertificationEvidenceFixtures.realRuntime(executionEnvironment = "declared-certification-lab")
        val withRealEvidence = production.certificationSummaryLines(production.compatibility, real)
        assertTrue(withRealEvidence[1].startsWith("Evidence: real Minecraft runtime"))
    }
}
