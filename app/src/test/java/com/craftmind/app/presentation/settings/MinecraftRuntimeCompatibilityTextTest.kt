package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform
import com.craftmind.app.domain.minecraft.compatibility.MinecraftVersion
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
}
