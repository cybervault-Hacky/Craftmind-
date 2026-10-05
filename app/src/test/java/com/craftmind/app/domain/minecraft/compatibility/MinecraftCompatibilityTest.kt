package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.bridge.protocol.BridgeProtocol
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
    fun editionLoaderAndCapabilitySerializersMapUnknownInputToTypedUnknownValues() {
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
    fun protocolV2DescriptorUsesBridgeReportedRuntimeJavaApiAndCapabilities() {
        val snapshot = supportedCapabilities()
        val runtime = snapshot.runtimeDescriptor

        assertEquals("1.0.0-test", runtime.appVersion)
        assertEquals(MinecraftEdition.JAVA, runtime.edition)
        assertEquals(MinecraftVersion.parse("1.20.1"), runtime.version)
        assertEquals(17, runtime.javaRuntimeMajor)
        assertEquals(MinecraftLoader.FABRIC, runtime.loader)
        assertEquals("0.92.2+1.20.1", runtime.fabricApiVersion)
        assertEquals(2, runtime.bridgeProtocolVersion)
        assertEquals("1.2.0", runtime.bridgeVersion)
        assertEquals(REPORTED_CAPABILITIES, runtime.capabilities)
        assertEquals(setOf(2), runtime.supportedBuildPlanSchemaVersions)
        assertTrue(snapshot.executionCompatible)

        val unknownLoader = runtimeFromBridge(
            loaderName = "CustomLoader",
            loaderVersion = "1",
        )
        assertEquals(MinecraftEdition.JAVA, unknownLoader.edition)
        assertEquals(MinecraftLoader.UNKNOWN, unknownLoader.loader)
        assertEquals("1.20.1", unknownLoader.version.identifier)
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
    fun blockStateCapabilityIsRequiredOnlyByPlansThatRequestBlockStates() {
        val runtime = supportedCapabilities().runtimeDescriptor.copy(
            capabilities = REPORTED_CAPABILITIES - MinecraftCapability.BLOCK_STATE_SUPPORT,
        )
        val ordinaryPlan = BuildPlanTestFixtures.semanticPlan()
        val statePlan = ordinaryPlan.copy(
            operations = listOf(ordinaryPlan.operations.first().copy(blockState = mapOf("axis" to "x"))),
        )

        val ordinaryResult = resolver.resolve(ordinaryPlan, runtime)
        val stateResult = resolver.resolve(statePlan, runtime)

        assertTrue(ordinaryResult.canExecute)
        assertFalse(stateResult.canExecute)
        assertTrue(MinecraftCapability.BLOCK_STATE_SUPPORT in stateResult.missingCapabilities)
        assertTrue(MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in stateResult.reasonCodes)
    }

    @Test
    fun centralProfileRegistryContainsOnlyTheRealJava1201FabricProfile() {
        val profile = MinecraftRuntimeProfileRegistry.javaFabric1201
        assertEquals(listOf(profile), MinecraftRuntimeProfileRegistry.allProfiles())
        assertEquals(listOf(profile), DefaultMinecraftCompatibility.resolver.registeredProfiles())
        assertEquals(profile.adapterId, DefaultMinecraftCompatibility.resolver.registeredAdapters().first().adapterId)
        // Phase 11 registers exactly one Bedrock contract boundary in addition to the Java adapter; the contract
        // claims no certified Bedrock runtime and can never authorize construction.
        assertEquals(
            listOf(BedrockRuntimeProfileRegistry.bedrockBridgeContract),
            DefaultMinecraftCompatibility.resolver.registeredBedrockProfiles(),
        )
        assertTrue(BedrockRuntimeProfileRegistry.bedrockBridgeContract.certifiedMinecraftVersions.isEmpty())
        assertEquals(MinecraftEdition.JAVA, profile.edition)
        assertEquals(MinecraftVersion.parse("1.20.1"), profile.version)
        assertEquals(MinecraftLoader.FABRIC, profile.loader)
        assertEquals("0.16.10", profile.loaderVersion)
        assertEquals(17, profile.javaRuntimeRequirement?.requiredMajor)
        assertEquals(17, profile.javaRuntimeRequirement?.minimumSupportedMajor)
        assertEquals(17, profile.javaRuntimeRequirement?.maximumSupportedMajor)
        assertEquals("0.92.2+1.20.1", profile.requiredFabricApiVersion)
        assertEquals(2, profile.bridgeProtocolVersion)
        assertEquals("1.2.0", profile.bridgeVersion)
        assertEquals(BridgeProtocol.MAX_OPERATIONS, profile.maximumValidatedOperations)
        assertEquals(BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES, profile.maximumRequestBytes)
    }

    @Test
    fun registryRejectsDuplicateIdsMismatchedAdapterIdsAndOverlappingRuntimeProfiles() {
        val registry = MinecraftAdapterRegistry()
        val first = JavaFabric1201Adapter()
        assertEquals(MinecraftAdapterRegistrationResult.Registered, registry.register(first))
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateAdapterId(first.adapterId),
            registry.register(JavaFabric1201Adapter()),
        )

        val mismatched = object : MinecraftAdapter by first {
            override val adapterId = MinecraftAdapterId("test-java-fabric-profile")
        }
        assertEquals(
            MinecraftAdapterRegistrationResult.AdapterIdMismatch(mismatched.adapterId, first.adapterId),
            registry.register(mismatched),
        )

        val duplicateAdapterId = MinecraftAdapterId("test-overlapping-profile")
        val renamedDuplicate = object : MinecraftAdapter by first {
            override val adapterId = duplicateAdapterId
            override val supportedRuntimeDescriptors = listOf(
                first.supportedRuntimeDescriptors.single().copy(adapterId = duplicateAdapterId),
            )
        }
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(duplicateAdapterId, first.adapterId),
            registry.register(renamedDuplicate),
        )
        assertEquals(1, registry.allAdapters().size)
    }

    @Test
    fun adapterRegistrationKeepsIdMismatchSeparateFromInvalidProfileValidation() {
        val base = JavaFabric1201Adapter()
        val adapterId = base.adapterId
        val invalidProfile = base.supportedRuntimeDescriptors.single().copy(maximumValidatedOperations = 0)
        val invalidAdapter = object : MinecraftAdapter by base {
            override val supportedRuntimeDescriptors = listOf(invalidProfile)
        }
        val registry = MinecraftAdapterRegistry()

        val result = registry.register(invalidAdapter)

        assertTrue(result is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        val profileFailure = result as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile
        assertEquals(adapterId, profileFailure.adapterId)
        assertTrue(profileFailure.reason.contains("operation limit"))
        assertTrue(registry.allAdapters().isEmpty())
    }

    @Test
    fun adapterRegistrationCannotAdvertiseALegacyProtocolDowngrade() {
        val base = JavaFabric1201Adapter()
        val oldProtocolProfile = base.supportedRuntimeDescriptors.single().copy(
            bridgeProtocolVersion = 1,
            bridgeVersion = "1.1.0",
        )
        val oldProtocolAdapter = object : MinecraftAdapter by base {
            override val supportedRuntimeDescriptors = listOf(oldProtocolProfile)
        }
        val registry = MinecraftAdapterRegistry()

        val result = registry.register(oldProtocolAdapter)

        assertTrue(result is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        assertTrue((result as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains("protocol version"))
        assertTrue(registry.allAdapters().isEmpty())
    }

    @Test
    fun adapterRegistrationRejectsDuplicateProfilesInsideOneAdapter() {
        val base = JavaFabric1201Adapter()
        val profile = base.supportedRuntimeDescriptors.single()
        val adapterWithDuplicates = object : MinecraftAdapter by base {
            override val supportedRuntimeDescriptors = listOf(profile, profile.copy())
        }
        val registry = MinecraftAdapterRegistry()

        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(base.adapterId, base.adapterId),
            registry.register(adapterWithDuplicates),
        )
        assertTrue(registry.allAdapters().isEmpty())
    }

    @Test
    fun exactJava1201FabricProfileResolvesSupportedUsingOnlyBridgeReportedCapabilities() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val result = resolver.resolve(BuildPlanTestFixtures.semanticPlan(), runtime)

        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, result.status)
        assertEquals(JavaFabric1201Adapter.ID, result.adapterId)
        assertTrue(result.canExecute)
        assertEquals(REPORTED_CAPABILITIES, result.capabilities)
        assertTrue(result.missingCapabilities.isEmpty())
        assertEquals(4096, result.limits.maximumValidatedOperations)
        assertEquals(1_048_576, result.limits.maximumRequestBytes)
        assertEquals(MinecraftDimensionLimits(96, 64, 96), result.limits.maximumDimensions)
        assertEquals(32, result.limits.maximumOperationsPerTick)
        assertEquals(300, result.limits.maximumExecutionSeconds)
        assertEquals(JavaRuntimeRequirement(17, 17, 17), result.limits.javaRuntimeRequirement)
        assertTrue(result.warnings.any { it.contains("server validates") })
    }

    @Test
    fun unsupportedMinecraftVersionsLoadersAndBridgeIdentitiesNeverSelectANearestAdapter() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val unsupportedVersion = resolver.resolveRuntime(runtime.copy(version = MinecraftVersion.parse("1.21.1")))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, unsupportedVersion.status)
        assertNull(unsupportedVersion.adapterId)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION in unsupportedVersion.reasonCodes)
        assertTrue(unsupportedVersion.reasons.single().contains("No nearest-version fallback"))

        val forge = resolver.resolveRuntime(runtime.copy(loader = MinecraftLoader.FORGE))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, forge.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER in forge.reasonCodes)

        val oldProtocol = resolver.resolveRuntime(runtime.copy(bridgeProtocolVersion = 1, bridgeVersion = "1.1.0"))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, oldProtocol.status)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in oldProtocol.reasonCodes)
        assertEquals("BRIDGE_PROTOCOL_UNSUPPORTED", oldProtocol.failureReasonCode())

        val oldBridge = resolver.resolveRuntime(runtime.copy(bridgeVersion = "1.1.0"))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, oldBridge.status)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH in oldBridge.reasonCodes)
        assertEquals("BRIDGE_VERSION_UNSUPPORTED", oldBridge.failureReasonCode())
    }

    @Test
    fun bedrockLegacyForgeAndVanillaAreNeverRoutedThroughTheFabricAdapter() {
        // Bedrock is a first-class edition in Phase 11, but it is resolved by the Bedrock contract boundary with
        // Bedrock-owned runtime facts only. It is never matched by version proximity and never executable here.
        val bedrock = MinecraftRuntimeDescriptor(
            edition = MinecraftEdition.BEDROCK,
            version = MinecraftVersion.parse("1.21.0"),
            platform = MinecraftRuntimePlatform.DEDICATED_SERVER,
            platformVersion = "1.21.0.3",
            loader = MinecraftLoader.BEDROCK_NATIVE,
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            bridgeVersion = BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION,
            capabilities = REPORTED_CAPABILITIES,
            supportedBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
            maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS,
            maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
            maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
            maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
        )
        val legacy = fullyDescribedRuntime(
            edition = MinecraftEdition.LEGACY,
            version = MinecraftVersion.parse("b1.7.3"),
            loader = MinecraftLoader.UNKNOWN,
        )
        val vanilla = supportedCapabilities().runtimeDescriptor.copy(loader = MinecraftLoader.VANILLA)

        listOf(legacy, vanilla).forEach { runtime ->
            val result = resolver.resolveRuntime(runtime)
            assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
            assertNull(result.adapterId)
            assertFalse(result.canExecute)
        }
        assertTrue(resolver.resolveRuntime(legacy).reasons.single().contains("not routed through a Java/Fabric adapter"))

        // With only the Java adapter registered, a Bedrock runtime is unsupported and never routed through it.
        val bedrockWithoutBedrockAdapter = resolver.resolveRuntime(bedrock)
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, bedrockWithoutBedrockAdapter.status)
        assertNull(bedrockWithoutBedrockAdapter.adapterId)
        assertTrue(bedrockWithoutBedrockAdapter.reasons.single().contains("not routed through a Java/Fabric adapter"))

        // With the default registry, the Bedrock contract boundary recognizes the runtime but never authorizes it.
        val bedrockResult = DefaultMinecraftCompatibility.resolver.resolveRuntime(bedrock)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, bedrockResult.status)
        assertEquals(BedrockRuntimeProfileRegistry.bedrockBridgeContract.adapterId, bedrockResult.adapterId)
        assertEquals(JavaFabric1201Adapter.ID, MinecraftRuntimeProfileRegistry.javaFabric1201.adapterId)
        assertFalse(bedrockResult.canExecute)
        assertTrue(MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED in bedrockResult.reasonCodes)
        assertFalse(bedrockResult.reasons.any { it.contains("not routed through a Java/Fabric adapter") })
    }

    @Test
    fun unknownAndIncompleteRuntimeMetadataProducesUnknownAndNeverSelectsAnAdapter() {
        val unknown = resolver.resolveRuntime(MinecraftRuntimeDescriptor())
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, unknown.status)
        assertNull(unknown.adapterId)
        assertFalse(unknown.canExecute)
        // A descriptor whose required version tokens are absent is rejected fail-closed. The resolver classifies
        // a missing token as an invalid descriptor rather than an incomplete one, but the outcome is identical:
        // status UNKNOWN, no adapter claims the runtime, and no build can execute.
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in unknown.reasonCodes)
        assertTrue(unknown.reasons.any { it.contains("invalid token") })

        val actual = supportedCapabilities().runtimeDescriptor
        val javaMissing = resolver.resolveRuntime(actual.copy(javaRuntimeMajor = null))
        val apiMissing = resolver.resolveRuntime(actual.copy(fabricApiVersion = null))
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, javaMissing.status)
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, apiMissing.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in javaMissing.reasonCodes)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in apiMissing.reasonCodes)
        assertTrue(javaMissing.reasons.any { it.contains("actual server Java runtime") })
        assertTrue(apiMissing.reasons.any { it.contains("loaded Fabric API") })
    }

    @Test
    fun javaRuntimeAndFabricApiMismatchesHaveDistinctStructuredReasons() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val wrongJava = resolver.resolveRuntime(runtime.copy(javaRuntimeMajor = 21))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, wrongJava.status)
        assertTrue(MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME in wrongJava.reasonCodes)
        assertEquals("BRIDGE_JAVA_RUNTIME_UNSUPPORTED", wrongJava.failureReasonCode())

        val wrongApi = resolver.resolveRuntime(runtime.copy(fabricApiVersion = "0.92.3+1.20.1"))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, wrongApi.status)
        assertTrue(MinecraftCompatibilityReasonCode.FABRIC_API_MISMATCH in wrongApi.reasonCodes)
        assertEquals("BRIDGE_FABRIC_API_UNSUPPORTED", wrongApi.failureReasonCode())
    }

    @Test
    fun aSupportedVersionWithMissingBridgeReportedCapabilitiesCannotExecute() {
        val runtime = supportedCapabilities().runtimeDescriptor.copy(capabilities = emptySet())
        val result = resolver.resolve(BuildPlanTestFixtures.semanticPlan(), runtime)

        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, result.status)
        assertEquals("CONSTRUCTION_DISABLED", result.failureReasonCode())
        assertFalse(result.canExecute)
        assertTrue(result.capabilities.isEmpty())
        assertTrue(result.missingCapabilities.containsAll(BuildPlanRequirements.from(BuildPlanTestFixtures.semanticPlan()).requiredCapabilities))
        assertTrue(MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in result.reasonCodes)
    }

    @Test
    fun experimentalProfilesAreReportedButNeverAuthorizeConstruction() {
        val base = JavaFabric1201Adapter()
        val experimentalId = MinecraftAdapterId("test-experimental-fabric")
        val experimentalProfile = base.supportedRuntimeDescriptors.single().copy(
            adapterId = experimentalId,
            loaderVersion = "0.16.11",
            supportStatus = MinecraftCompatibilityStatus.EXPERIMENTAL,
        )
        val experimentalRuntime = supportedCapabilities().runtimeDescriptor.copy(loaderVersion = "0.16.11")
        val experimentalAdapter = object : MinecraftAdapter by base {
            override val adapterId = experimentalId
            override val supportedRuntimeDescriptors = listOf(experimentalProfile)

            override fun compatibilityCheck(
                runtime: MinecraftRuntimeDescriptor,
                requirements: BuildPlanRequirements,
            ): MinecraftCompatibilityResult? {
                if (!experimentalProfile.matches(runtime)) return null
                val available = runtime.capabilities.filterTo(linkedSetOf()) { it != MinecraftCapability.UNKNOWN }
                val missing = requirements.requiredCapabilities - available
                return MinecraftCompatibilityResult(
                    status = MinecraftCompatibilityStatus.EXPERIMENTAL,
                    adapterId = adapterId,
                    capabilities = available,
                    missingCapabilities = missing,
                    reasons = listOf("Test-only experimental profile."),
                    warnings = emptyList(),
                    limits = MinecraftCompatibilityLimits(
                        4096, 1_048_576, experimentalProfile.maximumDimensions,
                        experimentalProfile.javaRuntimeRequirement, 32, 300,
                    ),
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
    fun unsupportedBuildPlanSchemaAndRuntimeLimitsAreFailClosed() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val unsupportedSchema = resolver.resolve(
            runtime,
            BuildPlanRequirements.runtimeExecution.copy(schemaVersion = 1, operationCount = 4, dimensions = BuildDimensions(8, 6, 8)),
        )
        assertFalse(unsupportedSchema.canExecute)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in unsupportedSchema.reasonCodes)

        val tooManyOperations = resolver.resolve(
            runtime,
            BuildPlanRequirements.runtimeExecution.copy(operationCount = 4097, schemaVersion = 2, dimensions = BuildDimensions(8, 6, 8)),
        )
        val tooWide = resolver.resolve(
            runtime,
            BuildPlanRequirements.runtimeExecution.copy(operationCount = 4, schemaVersion = 2, dimensions = BuildDimensions(97, 6, 8)),
        )
        val tooFewOperationsConfigured = resolver.resolveRuntime(runtime.copy(maximumValidatedOperations = 1))
        val planAboveReportedLimit = resolver.resolve(
            runtime.copy(maximumValidatedOperations = 1),
            BuildPlanRequirements.runtimeExecution.copy(operationCount = 4, schemaVersion = 2, dimensions = BuildDimensions(8, 6, 8)),
        )

        assertFalse(tooManyOperations.canExecute)
        assertTrue(tooManyOperations.reasons.any { it.contains("operation count") })
        assertTrue(MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in tooManyOperations.reasonCodes)
        assertFalse(tooWide.canExecute)
        assertTrue(tooWide.reasons.any { it.contains("dimensions") })
        // A bridge that reports a smaller but usable operation limit is honoured, never replaced with a default:
        // the runtime-level gate stays open while every plan above the reported limit is refused.
        assertEquals(1, tooFewOperationsConfigured.limits.maximumValidatedOperations)
        assertFalse(planAboveReportedLimit.canExecute)
        assertEquals(1, planAboveReportedLimit.limits.maximumValidatedOperations)
        assertTrue(MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in planAboveReportedLimit.reasonCodes)
    }

    @Test
    fun malformedRuntimeLimitsAppVersionAndConflictingEditionAreRejectedAsUnknown() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val invalidLimit = resolver.resolveRuntime(runtime.copy(maximumRequestBytes = 0))
        val invalidAppVersion = resolver.resolveRuntime(runtime.copy(appVersion = "1.0.0\n"))
        val conflictingEdition = resolver.resolveRuntime(runtime.copy(edition = MinecraftEdition.BEDROCK))

        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, invalidLimit.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in invalidLimit.reasonCodes)
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, invalidAppVersion.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in invalidAppVersion.reasonCodes)
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, conflictingEdition.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in conflictingEdition.reasonCodes)
    }

    @Test
    fun runtimeDescriptorSerializationRoundTripsBridgeReportedValues() {
        val runtime = supportedCapabilities().runtimeDescriptor
        val json = Json.encodeToString(runtime)
        val decoded = Json.decodeFromString<MinecraftRuntimeDescriptor>(json)

        assertEquals(runtime, decoded)
        assertNotNull(decoded.version.identifier)
        assertEquals(MinecraftLoader.FABRIC, decoded.loader)
        assertEquals(17, decoded.javaRuntimeMajor)
        assertEquals("0.92.2+1.20.1", decoded.fabricApiVersion)
    }

    private fun supportedCapabilities(constructionEnabled: Boolean = true): BridgeCapabilitiesSnapshot {
        val capabilities = buildSet {
            add(MinecraftCapability.WORLD_ACCESS)
            add(MinecraftCapability.WORLD_VALIDATION)
            add(MinecraftCapability.ORIGIN_RESOLUTION)
            add(MinecraftCapability.BUILD_PLAN_V2)
            if (constructionEnabled) addAll(
                setOf(
                    MinecraftCapability.BUILD_EXECUTION,
                    MinecraftCapability.BLOCK_PLACEMENT,
                    MinecraftCapability.BLOCK_STATE_SUPPORT,
                    MinecraftCapability.STRUCTURE_BATCHING,
                    MinecraftCapability.PROGRESS_REPORTING,
                    MinecraftCapability.BUILD_STATUS,
                    MinecraftCapability.CANCELLATION,
                ),
            )
        }
        return BridgeCapabilitiesSnapshot(
            protocolVersion = 2,
            bridgeId = "bridge-0123456789abcdef0123456789abcdef",
            identityFingerprint = "00".repeat(32),
            bridgeVersion = "1.2.0",
            clientAppVersion = "1.0.0-test",
            editionName = "java",
            minecraftVersion = "1.20.1",
            javaRuntimeMajor = 17,
            loaderName = "Fabric",
            loaderVersion = "0.16.10",
            fabricApiVersion = "0.92.2+1.20.1",
            supportedCapabilities = capabilities,
            worldAccess = true,
            constructionExecute = constructionEnabled,
            cancellation = constructionEnabled,
            maximumValidatedOperations = 4096,
            maximumRequestBytes = 1_048_576,
            maximumOperationsPerTick = 32,
            maximumExecutionSeconds = 300,
            supportedBuildPlanSchemaVersions = listOf(2),
            dimensionId = "minecraft:overworld",
            worldSessionId = "world-session-1",
        )
    }

    private fun runtimeFromBridge(
        loaderName: String = "Fabric",
        loaderVersion: String = "0.16.10",
        fabricApiVersion: String? = "0.92.2+1.20.1",
    ) = MinecraftRuntimeDescriptor.fromBridgeV2(
        appVersion = "1.0.0-test",
        editionName = "java",
        minecraftVersion = "1.20.1",
        javaRuntimeMajor = 17,
        loaderName = loaderName,
        loaderVersion = loaderVersion,
        fabricApiVersion = fabricApiVersion,
        bridgeProtocolVersion = 2,
        bridgeVersion = "1.2.0",
        reportedCapabilities = REPORTED_CAPABILITIES,
        supportedBuildPlanSchemaVersions = setOf(2),
        maximumValidatedOperations = 4096,
        maximumRequestBytes = 1_048_576,
        maximumOperationsPerTick = 32,
        maximumExecutionSeconds = 300,
        worldAvailable = true,
        operatorOriginAvailable = true,
    )

    private fun fullyDescribedRuntime(
        edition: MinecraftEdition,
        version: MinecraftVersion,
        loader: MinecraftLoader,
    ) = MinecraftRuntimeDescriptor(
        edition = edition,
        version = version,
        javaRuntimeMajor = if (edition == MinecraftEdition.JAVA) 17 else null,
        loader = loader,
        loaderVersion = "1.0.0",
        fabricApiVersion = null,
        bridgeProtocolVersion = 2,
        bridgeVersion = "1.2.0",
        capabilities = REPORTED_CAPABILITIES,
        supportedBuildPlanSchemaVersions = setOf(2),
        maximumValidatedOperations = 4096,
        maximumRequestBytes = 1_048_576,
        maximumOperationsPerTick = 32,
        maximumExecutionSeconds = 300,
    )

    @Serializable
    private data class SerializedRuntimeValues(
        val edition: MinecraftEdition,
        val loader: MinecraftLoader,
        val version: MinecraftVersion,
        val capabilities: Set<MinecraftCapability>,
    )

    private companion object {
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
