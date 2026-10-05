package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinecraftCompatibilityTest {
    private val adapterRegistry = MinecraftAdapterRegistry().apply {
        assertEquals(MinecraftAdapterRegistrationResult.Registered, register(JavaFabric1201Adapter()))
    }
    private val resolver = MinecraftCompatibilityResolver(adapterRegistry)

    @Test
    fun minecraftVersionParsingKeepsReleasePrereleaseSnapshotBetaAlphaAndLegacyDistinct() {
        assertEquals(MinecraftVersionChannel.RELEASE, MinecraftVersion.parse("1.20.1").channel)
        assertEquals(MinecraftVersionChannel.PRE_RELEASE, MinecraftVersion.parse("1.21-pre1").channel)
        assertEquals(MinecraftVersionChannel.BETA, MinecraftVersion.parse("1.20.1-beta1").channel)
        assertEquals(MinecraftVersionChannel.SNAPSHOT, MinecraftVersion.parse("24w14a").channel)
        assertEquals(MinecraftVersionChannel.BETA, MinecraftVersion.parse("beta/1.7.3").channel)
        assertEquals(MinecraftVersionChannel.BETA, MinecraftVersion.parse("b1.7.3").channel)
        assertEquals(MinecraftVersionChannel.ALPHA, MinecraftVersion.parse("a1.2.6").channel)
        assertEquals(MinecraftVersionChannel.LEGACY, MinecraftVersion.parse("c0.30_01").channel)
        assertEquals(20, MinecraftVersion.parse("1.20.1").minor)
        assertEquals(1, MinecraftVersion.parse("1.20.1").patch)
        assertFalse(MinecraftVersion.parse("1.20.1").equals(MinecraftVersion.parse("1.20.2")))
        assertTrue(MinecraftVersion.parse("1.20.1").isKnown)
    }

    @Test
    fun malformedOrUnrecognizedVersionValuesNeverBecomeExecutableVersions() {
        assertFalse(MinecraftVersion.parse("1.20.1\n").isKnown)
        assertNull(MinecraftVersion.parse("1.20.1\n").identifier)
        assertFalse(MinecraftVersion.parse("999999999999999999999.1.1").isKnown)
        assertFalse(MinecraftVersion.parse("future-release").isKnown)
        assertFalse(MinecraftVersion.parse("x".repeat(49)).isKnown)
        assertEquals(
            MinecraftVersion.UNKNOWN,
            Json.decodeFromString<MinecraftVersion>(Json.encodeToString(MinecraftVersion.UNKNOWN)),
        )
    }

    @Test
    fun editionLoaderAndVersionSerializersMapUnknownInputToTypedUnknownValues() {
        val decoded = Json.decodeFromString<SerializedRuntimeValues>(
            """{"edition":"modded-edition","loader":"CustomLoader","version":"not a version","capabilities":["FUTURE_CAPABILITY"]}""",
        )

        assertEquals(MinecraftEdition.UNKNOWN, decoded.edition)
        assertEquals(MinecraftLoader.UNKNOWN, decoded.loader)
        assertFalse(decoded.version.isKnown)
        assertEquals(setOf(MinecraftCapability.UNKNOWN), decoded.capabilities)

        val restored = Json.decodeFromString<SerializedRuntimeValues>(Json.encodeToString(decoded))
        assertEquals(MinecraftEdition.UNKNOWN, restored.edition)
        assertEquals(MinecraftLoader.UNKNOWN, restored.loader)
        assertFalse(restored.version.isKnown)
    }

    @Test
    fun protocolV1DescriptorInfersOnlyKnownLoaderFamilyAndCarriesAuthenticatedCapabilities() {
        val snapshot = supportedCapabilities()
        val runtime = snapshot.runtimeDescriptor

        assertEquals(MinecraftEdition.JAVA, runtime.edition)
        assertEquals(MinecraftVersion.parse("1.20.1"), runtime.version)
        assertEquals(MinecraftLoader.FABRIC, runtime.loader)
        assertEquals(setOf(2), runtime.supportedBuildPlanSchemaVersions)
        assertTrue(MinecraftCapability.BUILD_EXECUTION in runtime.capabilities)
        assertTrue(MinecraftCapability.ORIGIN_RESOLUTION in runtime.capabilities)
        assertNull(runtime.javaRuntimeMajor)

        val unknownLoader = MinecraftRuntimeDescriptor.fromBridgeV1(
            bridgeProtocolVersion = 1,
            bridgeVersion = "1.1.0",
            minecraftVersion = "1.20.1",
            loaderName = "CustomLoader",
            loaderVersion = "1",
            worldAccess = true,
            constructionExecute = true,
            cancellation = true,
            maximumValidatedOperations = 4096,
            maximumRequestBytes = 1_048_576,
            supportedBuildPlanSchemaVersions = setOf(2),
            operatorOriginAvailable = true,
        )
        assertEquals(MinecraftEdition.UNKNOWN, unknownLoader.edition)
    }

    @Test
    fun buildPlanRequirementsRemainPlatformNeutralAndRequireBlockStateCapabilityOnlyWhenNeeded() {
        val plan = BuildPlanTestFixtures.semanticPlan()
        val ordinary = BuildPlanRequirements.from(plan)
        assertEquals(plan.operations.size, ordinary.operationCount)
        assertFalse(MinecraftCapability.BLOCK_STATE_SUPPORT in ordinary.requiredCapabilities)

        val withState = plan.copy(operations = listOf(plan.operations.first().copy(blockState = mapOf("axis" to "x"))))
        val stateRequirements = BuildPlanRequirements.from(withState)
        assertTrue(MinecraftCapability.BLOCK_STATE_SUPPORT in stateRequirements.requiredCapabilities)
        assertEquals(1, stateRequirements.operationCount)
        assertEquals(2, stateRequirements.schemaVersion)
    }

    @Test
    fun productionRegistryContainsOnlyTheRealJava1201FabricAdapter() {
        assertEquals(
            listOf(JavaFabric1201Adapter.ID),
            DefaultMinecraftCompatibility.resolver.registeredAdapters().map { it.adapterId },
        )
    }

    @Test
    fun registryRejectsDuplicateIdsAndOverlappingRuntimeProfiles() {
        val registry = MinecraftAdapterRegistry()
        val first = JavaFabric1201Adapter()
        assertEquals(MinecraftAdapterRegistrationResult.Registered, registry.register(first))
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateAdapterId(first.adapterId),
            registry.register(JavaFabric1201Adapter()),
        )

        val renamedDuplicate = object : MinecraftAdapter by first {
            override val adapterId = MinecraftAdapterId("test-java-fabric-profile")
        }
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(renamedDuplicate.adapterId, first.adapterId),
            registry.register(renamedDuplicate),
        )
        assertEquals(1, registry.allAdapters().size)
    }

    @Test
    fun exactJava1201FabricProfileResolvesSupportedWithAdapterAndCapabilities() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val result = resolver.resolve(BuildPlanTestFixtures.semanticPlan(), runtime)

        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, result.status)
        assertEquals(JavaFabric1201Adapter.ID, result.adapterId)
        assertTrue(result.canExecute)
        assertTrue(MinecraftCapability.BLOCK_STATE_SUPPORT in result.capabilities)
        assertTrue(result.missingCapabilities.isEmpty())
        assertEquals(4096, result.limits.maximumValidatedOperations)
        assertEquals(1_048_576, result.limits.maximumRequestBytes)
        assertEquals(MinecraftDimensionLimits(96, 64, 96), result.limits.maximumDimensions)
        assertTrue(result.warnings.any { it.contains("server JVM") })
    }

    @Test
    fun unsupportedKnownJavaRuntimeDoesNotDowngradeToTheClosestRegisteredProfile() {
        val runtime = supportedCapabilities().runtimeDescriptor.copy(version = MinecraftVersion.parse("1.21.1"))
        val result = resolver.resolve(BuildPlanTestFixtures.semanticPlan(), runtime)

        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
        assertNull(result.adapterId)
        assertFalse(result.canExecute)
        assertTrue(result.reasons.single().contains("exactly matches"))
    }

    @Test
    fun bedrockLegacyAndForgeAreNeverRoutedThroughTheFabricAdapter() {
        val bedrock = fullyDescribedRuntime(
            edition = MinecraftEdition.BEDROCK,
            version = MinecraftVersion.parse("1.21.0"),
            loader = MinecraftLoader.BEDROCK_NATIVE,
        )
        val forge = supportedCapabilities().runtimeDescriptor.copy(loader = MinecraftLoader.FORGE)
        val legacy = fullyDescribedRuntime(
            edition = MinecraftEdition.LEGACY,
            version = MinecraftVersion.parse("b1.7.3"),
            loader = MinecraftLoader.UNKNOWN,
        )

        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, resolver.resolveRuntime(bedrock).status)
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, resolver.resolveRuntime(forge).status)
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, resolver.resolveRuntime(legacy).status)
        assertNull(resolver.resolveRuntime(bedrock).adapterId)
    }

    @Test
    fun unknownRuntimeMetadataProducesUnknownAndNeverSelectsAnAdapter() {
        val unknown = MinecraftRuntimeDescriptor(
            edition = MinecraftEdition.UNKNOWN,
            version = MinecraftVersion.UNKNOWN,
            loader = MinecraftLoader.UNKNOWN,
        )
        val result = resolver.resolveRuntime(unknown)

        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, result.status)
        assertNull(result.adapterId)
        assertFalse(result.canExecute)
        assertTrue(result.reasons.isNotEmpty())
    }

    @Test
    fun supportedRuntimeWithServerCapabilityGapsIsNotExecutable() {
        val runtime = supportedCapabilities(constructionEnabled = false).runtimeDescriptor
        val result = resolver.resolve(BuildPlanTestFixtures.semanticPlan(), runtime)

        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, result.status)
        assertEquals("CONSTRUCTION_DISABLED", result.failureReasonCode())
        assertFalse(result.canExecute)
        assertTrue(MinecraftCapability.BUILD_EXECUTION in result.missingCapabilities)
    }

    @Test
    fun experimentalAdapterProfilesAreReportedButNeverAuthorizeConstruction() {
        val base = JavaFabric1201Adapter()
        val experimentalProfile = base.supportedRuntimeDescriptors.single().copy(
            loaderVersion = "0.16.11",
            supportStatus = MinecraftCompatibilityStatus.EXPERIMENTAL,
        )
        val experimentalRuntime = supportedCapabilities().runtimeDescriptor.copy(loaderVersion = "0.16.11")
        val experimentalAdapter = object : MinecraftAdapter by base {
            override val adapterId = MinecraftAdapterId("test-experimental-fabric")
            override val supportedRuntimeDescriptors = listOf(experimentalProfile)

            override fun compatibilityCheck(
                runtime: MinecraftRuntimeDescriptor,
                requirements: BuildPlanRequirements,
            ): MinecraftCompatibilityResult? {
                if (!experimentalProfile.matches(runtime)) return null
                val available = capabilities + runtime.capabilities
                val missing = requirements.requiredCapabilities - available
                return MinecraftCompatibilityResult(
                    status = MinecraftCompatibilityStatus.EXPERIMENTAL,
                    adapterId = adapterId,
                    capabilities = available,
                    missingCapabilities = missing,
                    reasons = listOf("Test-only experimental profile."),
                    warnings = emptyList(),
                    limits = MinecraftCompatibilityLimits(4096, 1_048_576, experimentalProfile.maximumDimensions, 17),
                )
            }
        }
        val testRegistry = MinecraftAdapterRegistry().apply {
            assertEquals(MinecraftAdapterRegistrationResult.Registered, register(experimentalAdapter))
        }
        val result = MinecraftCompatibilityResolver(testRegistry)
            .resolve(BuildPlanTestFixtures.semanticPlan(), experimentalRuntime)

        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result.status)
        assertEquals("BRIDGE_RUNTIME_EXPERIMENTAL", result.failureReasonCode())
        assertFalse(result.canExecute)
    }

    @Test
    fun legacyPlanSchemaIsNeverExecutedEvenIfTheBridgeAlsoAdvertisesIt() {
        val runtime = supportedCapabilities().runtimeDescriptor.copy(
            supportedBuildPlanSchemaVersions = setOf(1, 2),
        )
        val result = resolver.resolve(
            runtime,
            BuildPlanRequirements.runtimeExecution.copy(schemaVersion = 1, operationCount = 4, dimensions = BuildDimensions(8, 6, 8)),
        )

        assertFalse(result.canExecute)
        assertTrue(result.reasons.any { it.contains("schema 1") })
    }

    @Test
    fun resolverEnforcesOperationAndDimensionLimits() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val baseRequirements = BuildPlanRequirements.runtimeExecution
        val tooManyOperations = resolver.resolve(
            runtime,
            baseRequirements.copy(operationCount = 4097, schemaVersion = 2, dimensions = BuildDimensions(8, 6, 8)),
        )
        val tooWide = resolver.resolve(
            runtime,
            baseRequirements.copy(operationCount = 4, schemaVersion = 2, dimensions = BuildDimensions(97, 6, 8)),
        )
        val tooSmallRequestLimit = resolver.resolveRuntime(runtime.copy(maximumRequestBytes = 1_023))

        assertFalse(tooManyOperations.canExecute)
        assertTrue(tooManyOperations.reasons.any { it.contains("operation count") })
        assertFalse(tooWide.canExecute)
        assertTrue(tooWide.reasons.any { it.contains("dimensions") })
        assertFalse(tooSmallRequestLimit.canExecute)
        assertTrue(tooSmallRequestLimit.reasons.any { it.contains("request-byte") })
    }

    @Test
    fun supportedRuntimeDescriptorSerializationRoundTripsTypedValues() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val json = Json.encodeToString(runtime)
        val decoded = Json.decodeFromString<MinecraftRuntimeDescriptor>(json)

        assertEquals(runtime, decoded)
        assertNotNull(decoded.version.identifier)
        assertEquals(MinecraftLoader.FABRIC, decoded.loader)
    }

    private fun supportedCapabilities(constructionEnabled: Boolean = true) = BridgeCapabilitiesSnapshot(
        protocolVersion = 1,
        bridgeId = "bridge-0123456789abcdef0123456789abcdef",
        identityFingerprint = "00".repeat(32),
        bridgeVersion = "1.1.0",
        minecraftVersion = "1.20.1",
        loaderName = "Fabric",
        loaderVersion = "0.16.10",
        worldAccess = true,
        constructionExecute = constructionEnabled,
        cancellation = constructionEnabled,
        maximumValidatedOperations = 4096,
        maximumRequestBytes = 1_048_576,
        supportedBuildPlanSchemaVersions = listOf(2),
        dimensionId = "minecraft:overworld",
        worldSessionId = "world-session-1",
    )

    private fun fullyDescribedRuntime(
        edition: MinecraftEdition,
        version: MinecraftVersion,
        loader: MinecraftLoader,
    ) = MinecraftRuntimeDescriptor(
        edition = edition,
        version = version,
        loader = loader,
        loaderVersion = "1.0.0",
        bridgeProtocolVersion = 1,
        bridgeVersion = "1.0.0",
        maximumValidatedOperations = 4096,
        maximumRequestBytes = 1_048_576,
    )

    @Serializable
    private data class SerializedRuntimeValues(
        val edition: MinecraftEdition,
        val loader: MinecraftLoader,
        val version: MinecraftVersion,
        val capabilities: Set<MinecraftCapability>,
    )
}
