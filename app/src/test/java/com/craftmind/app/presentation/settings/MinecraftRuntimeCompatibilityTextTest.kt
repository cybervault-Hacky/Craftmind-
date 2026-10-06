package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
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
}
