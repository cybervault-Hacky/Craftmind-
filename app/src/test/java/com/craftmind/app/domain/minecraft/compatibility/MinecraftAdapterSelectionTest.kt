package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.bedrockSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.javaProductionSnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1122Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.legacyForge1710Snapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.preReleaseFamilySnapshot
import com.craftmind.app.domain.minecraft.compatibility.RuntimeDetectionTestFixtures.report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13 adapter selection: deterministic, exact, and fail-closed. Selection never picks a nearest adapter, never
 * crosses editions, and never resolves ambiguity by registration order.
 */
class MinecraftAdapterSelectionTest {
    private val selector = DefaultMinecraftCompatibility.selector
    private val resolver = DefaultMinecraftCompatibility.resolver

    @Test
    fun javaProductionRuntimeSelectsTheProductionFabricAdapterExactly() {
        val selection = selector.select(report(javaProductionSnapshot()).let { detected(it) })

        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, selection.status)
        assertEquals(JavaFabric1201Adapter.ID, selection.adapterId)
        assertEquals(MinecraftAdapterMatchKind.VERSION_KEYED_PROFILE, selection.matchKind)
        assertEquals(MinecraftRuntimeProfileRegistry.javaFabric1201, selection.matchedProfile)
        assertNull(selection.matchedBedrockProfile)
        assertTrue(selection.matchedProfileAuthorizesExecution)
        assertTrue(selection.isSelected)
        assertEquals(listOf(JavaFabric1201Adapter.ID), selection.candidateAdapterIds)
    }

    @Test
    fun declaredLegacyRuntimesSelectTheLegacyExperimentalAdapterWithoutBecomingExecutable() {
        val legacy1710 = selector.select(detected(report(legacyForge1710Snapshot())))
        val legacy1122 = selector.select(detected(report(legacyForge1122Snapshot())))

        listOf(legacy1710, legacy1122).forEach { selection ->
            assertEquals(MinecraftAdapterSelectionStatus.SELECTED, selection.status)
            assertEquals(MinecraftAdapterId(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID), selection.adapterId)
            assertEquals(MinecraftAdapterMatchKind.VERSION_KEYED_PROFILE, selection.matchKind)
            assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, selection.matchedProfile?.supportStatus)
            assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, selection.matchedProfile?.runtimeCertification)
            assertFalse(selection.matchedProfileAuthorizesExecution)
        }
        assertEquals("1.7.10", legacy1710.matchedProfile?.version?.displayIdentifier)
        assertEquals("1.12.2", legacy1122.matchedProfile?.version?.displayIdentifier)
        // Selection is not support: the resolver still refuses execution for a recognized legacy runtime.
        val resolved = resolver.resolveRuntime(detected(report(legacyForge1710Snapshot())).descriptor)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, resolved.status)
        assertTrue(MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED in resolved.reasonCodes)
        assertFalse(resolved.canExecute)
    }

    @Test
    fun bedrockRuntimesSelectTheBedrockContractAdapterAndNeverAJavaAdapter() {
        val selection = selector.select(detected(report(bedrockSnapshot())))

        assertEquals(MinecraftAdapterSelectionStatus.SELECTED, selection.status)
        assertEquals(BedrockRuntimeProfileRegistry.bedrockBridgeContract.adapterId, selection.adapterId)
        assertEquals(MinecraftAdapterMatchKind.BEDROCK_CONTRACT, selection.matchKind)
        assertNull(selection.matchedProfile)
        assertEquals(BedrockRuntimeProfileRegistry.bedrockBridgeContract, selection.matchedBedrockProfile)
        assertFalse(selection.matchedProfileAuthorizesExecution)

        val resolved = resolver.resolveRuntime(bedrockSnapshot().runtimeDescriptor)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, resolved.status)
        assertTrue(MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED in resolved.reasonCodes)
        assertFalse(resolved.canExecute)
    }

    @Test
    fun unregisteredRuntimesGetNoMatchInsteadOfTheClosestAdapter() {
        val unregistered = listOf(
            javaProductionSnapshot(minecraftVersion = "1.19.4"),
            javaProductionSnapshot(minecraftVersion = "1.20.2"),
            javaProductionSnapshot(loaderName = "Forge", loaderVersion = "47.2.20", fabricApiVersion = null),
            javaProductionSnapshot(loaderName = "NeoForge", loaderVersion = "20.4.1", fabricApiVersion = null),
            javaProductionSnapshot(loaderName = "Vanilla", loaderVersion = "1.20.1", fabricApiVersion = null),
            javaProductionSnapshot(loaderVersion = "0.16.11"),
            javaProductionSnapshot(javaRuntimeMajor = 21),
            javaProductionSnapshot(fabricApiVersion = "0.93.0+1.20.1"),
            javaProductionSnapshot(bridgeVersion = "1.3.0"),
            preReleaseFamilySnapshot("24w14a"),
        )
        unregistered.forEach { snapshot ->
            val detection = detected(report(snapshot))
            val selection = selector.select(detection)
            assertEquals(
                "runtime ${snapshot.minecraftVersion}/${snapshot.loaderName}",
                MinecraftAdapterSelectionStatus.NO_MATCH,
                selection.status,
            )
            assertNull(selection.adapter)
            assertNull(selection.adapterId)
            val resolved = resolver.resolveRuntime(detection.descriptor)
            assertNull(resolved.adapterId)
            assertFalse(resolved.canExecute)
        }
    }

    @Test
    fun exactIdentityMatchesWithTheWrongJavaOrFabricApiKeepTheirSpecificReasons() {
        val wrongJava = resolver.resolveRuntime(
            detected(report(javaProductionSnapshot(javaRuntimeMajor = 21))).descriptor,
        )
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, wrongJava.status)
        assertTrue(MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME in wrongJava.reasonCodes)
        assertEquals("BRIDGE_JAVA_RUNTIME_UNSUPPORTED", wrongJava.failureReasonCode())

        val wrongApi = resolver.resolveRuntime(
            detected(report(javaProductionSnapshot(fabricApiVersion = "0.93.0+1.20.1"))).descriptor,
        )
        assertTrue(MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH in wrongApi.reasonCodes)
        assertEquals("BRIDGE_FABRIC_API_UNSUPPORTED", wrongApi.failureReasonCode())

        val legacyOnJava17 = resolver.resolveRuntime(
            detected(report(legacyForge1710Snapshot(javaRuntimeMajor = 17))).descriptor,
        )
        assertTrue(MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME in legacyOnJava17.reasonCodes)
        assertFalse(legacyOnJava17.canExecute)
    }

    @Test
    fun selectionIsBlockedForUndetectedUnboundOrUnauthorizedRuntimes() {
        val incomplete = MinecraftRuntimeDetector(DefaultMinecraftCompatibility.registry)
            .detect(report(javaProductionSnapshot(javaRuntimeMajor = null)))
        assertEquals(MinecraftRuntimeDetectionStatus.INCOMPLETE, incomplete.status)
        val incompleteSelection = selector.select(incomplete)
        assertEquals(MinecraftAdapterSelectionStatus.INVALID, incompleteSelection.status)
        assertNull(incompleteSelection.adapter)

        val unauthenticated = MinecraftRuntimeDetector(DefaultMinecraftCompatibility.registry)
            .detect(report(javaProductionSnapshot(), authenticated = false))
        val unauthorizedSelection = selector.select(unauthenticated)
        assertEquals(MinecraftAdapterSelectionStatus.INVALID, unauthorizedSelection.status)
        assertTrue(
            MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED in unauthorizedSelection.reasonCodes,
        )

        val unbound = MinecraftRuntimeDetectionResult(
            status = MinecraftRuntimeDetectionStatus.DETECTED,
            descriptor = javaProductionSnapshot().runtimeDescriptor,
            runtimeIdentity = null,
        )
        val unboundSelection = selector.select(unbound)
        assertEquals(MinecraftAdapterSelectionStatus.INVALID, unboundSelection.status)
        assertTrue(MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH in unboundSelection.reasonCodes)
    }

    @Test
    fun twoAdaptersClaimingOneRuntimeProduceAnAmbiguousResultInsteadOfAnOrderedChoice() {
        val production = JavaFabric1201Adapter()
        val secondId = MinecraftAdapterId("test-second-fabric-adapter")
        val second = MutableProfileAdapter(
            adapterId = secondId,
            delegate = production,
            profiles = listOf(
                MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = secondId, loaderVersion = "0.16.11"),
            ),
        )
        val registry = MinecraftAdapterRegistry().apply {
            assertEquals(MinecraftAdapterRegistrationResult.Registered, register(production))
            assertEquals(MinecraftAdapterRegistrationResult.Registered, register(second))
        }
        val ambiguousSelector = MinecraftAdapterSelector(registry)
        val runtime = javaProductionSnapshot().runtimeDescriptor

        // Registration order must not decide the outcome: the same runtime is claimed by two adapters.
        second.profiles = listOf(
            MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = secondId),
            MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = secondId, loaderVersion = "0.16.11"),
        )
        val selection = ambiguousSelector.select(runtime)
        assertEquals(MinecraftAdapterSelectionStatus.AMBIGUOUS, selection.status)
        assertEquals(listOf(JavaFabric1201Adapter.ID, secondId), selection.candidateAdapterIds)
        assertTrue(MinecraftCompatibilityReasonCode.AMBIGUOUS_ADAPTER_PROFILE in selection.reasonCodes)
        assertNull(selection.adapter)

        val resolved = MinecraftCompatibilityResolver(registry, ambiguousSelector).resolveRuntime(runtime)
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, resolved.status)
        assertTrue(MinecraftCompatibilityReasonCode.AMBIGUOUS_ADAPTER_PROFILE in resolved.reasonCodes)
        assertNull(resolved.adapterId)
        assertFalse(resolved.canExecute)
    }

    @Test
    fun registrationOrderDoesNotChangeWhichAdapterIsSelected() {
        val runtime = report(javaProductionSnapshot())
        val legacyRuntime = report(legacyForge1710Snapshot())
        val bedrockRuntime = report(bedrockSnapshot())

        val forward = MinecraftAdapterRegistry().apply {
            register(JavaFabric1201Adapter())
            register(LegacyJavaRuntimeAdapter())
            register(BedrockBridgeAdapter())
        }
        val reversed = MinecraftAdapterRegistry().apply {
            register(BedrockBridgeAdapter())
            register(LegacyJavaRuntimeAdapter())
            register(JavaFabric1201Adapter())
        }
        val forwardSelector = MinecraftAdapterSelector(forward)
        val reversedSelector = MinecraftAdapterSelector(reversed)

        listOf(runtime, legacyRuntime, bedrockRuntime).forEach { report ->
            assertEquals(
                forwardSelector.select(detected(report)).adapterId,
                reversedSelector.select(detected(report)).adapterId,
            )
            assertEquals(
                forwardSelector.select(detected(report)).status,
                reversedSelector.select(detected(report)).status,
            )
        }
    }

    @Test
    fun theRegistryRefusesAVersionKeyedBedrockProfileThatCouldOverlapTheBedrockContract() {
        val bedrockProfile = MinecraftRuntimeProfileRegistry.javaFabric1201.copy(
            adapterId = MinecraftAdapterId("test-bedrock-version-keyed"),
            edition = MinecraftEdition.BEDROCK,
            loader = MinecraftLoader.BEDROCK_NATIVE,
            loaderVersion = "1.0.0",
            javaRuntimeRequirement = null,
            requiredFabricApiVersion = null,
        )
        val adapter = MutableProfileAdapter(
            adapterId = bedrockProfile.adapterId,
            delegate = JavaFabric1201Adapter(),
            profiles = listOf(bedrockProfile),
        )
        val registry = MinecraftAdapterRegistry()
        assertEquals(MinecraftAdapterRegistrationResult.Registered, registry.register(BedrockBridgeAdapter()))

        val result = registry.register(adapter)
        assertTrue(result is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        assertTrue((result as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains("Bedrock contract"))
        assertEquals(1, registry.allAdapters().size)
    }

    private fun detected(report: AuthenticatedMinecraftRuntimeReport): MinecraftRuntimeDetectionResult {
        val result = MinecraftRuntimeDetector(DefaultMinecraftCompatibility.registry).detect(report)
        assertEquals(
            "fixture report must be detected: ${result.diagnostics.map { it.detail }}",
            MinecraftRuntimeDetectionStatus.DETECTED,
            result.status,
        )
        return result
    }

    /** Test-only adapter whose registered profile list can change after registration. */
    private class MutableProfileAdapter(
        override val adapterId: MinecraftAdapterId,
        private val delegate: MinecraftAdapter,
        profiles: List<SupportedMinecraftRuntimeDescriptor>,
    ) : MinecraftAdapter by delegate {
        var profiles: List<SupportedMinecraftRuntimeDescriptor> = profiles

        override val supportedRuntimeDescriptors: List<SupportedMinecraftRuntimeDescriptor> get() = profiles

        override val bedrockRuntimeProfiles: List<BedrockRuntimeProfile> get() = emptyList()

        override fun compatibilityCheck(
            runtime: MinecraftRuntimeDescriptor,
            requirements: BuildPlanRequirements,
        ): MinecraftCompatibilityResult? = if (profiles.any { it.matches(runtime) }) {
            delegate.compatibilityCheck(runtime, requirements)?.copy(adapterId = adapterId)
        } else {
            null
        }
    }
}
