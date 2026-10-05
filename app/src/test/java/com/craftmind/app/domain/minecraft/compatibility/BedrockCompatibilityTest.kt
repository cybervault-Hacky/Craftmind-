package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildDimensions
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionQueryResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import com.craftmind.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockCompatibilityTest {
    private val registry = MinecraftAdapterRegistry().apply {
        assertEquals(MinecraftAdapterRegistrationResult.Registered, register(JavaFabric1201Adapter()))
        assertEquals(MinecraftAdapterRegistrationResult.Registered, register(BedrockBridgeAdapter()))
    }
    private val resolver = MinecraftCompatibilityResolver(registry)
    private val contract = BedrockRuntimeProfileRegistry.bedrockBridgeContract

    @Test
    fun bedrockRuntimeDescriptorsCarryBedrockFactsAndNeverJavaLoaderConcepts() {
        val runtime = bedrockRuntime()

        assertTrue(runtime.isBedrock)
        assertEquals(MinecraftEdition.BEDROCK, runtime.edition)
        assertEquals(MinecraftLoader.BEDROCK_NATIVE, runtime.loader)
        assertEquals(MinecraftRuntimePlatform.DEDICATED_SERVER, runtime.platform)
        assertEquals("1.21.60.3", runtime.platformVersion)
        assertNull(runtime.javaRuntimeMajor)
        assertNull(runtime.loaderVersion)
        assertNull(runtime.fabricApiVersion)

        val result = resolver.resolveRuntime(runtime)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result.status)
        assertEquals(contract.adapterId, result.adapterId)
        assertFalse(result.canExecute)
    }

    @Test
    fun bedrockRuntimesRejectJavaRuntimeLoaderAndFabricFacts() {
        val withJvm = resolver.resolveRuntime(bedrockRuntime().copy(javaRuntimeMajor = 17))
        val withLoaderVersion = resolver.resolveRuntime(bedrockRuntime().copy(loaderVersion = "0.16.10"))
        val withFabricApi = resolver.resolveRuntime(bedrockRuntime().copy(fabricApiVersion = "0.92.2+1.20.1"))

        listOf(withJvm, withLoaderVersion, withFabricApi).forEach { result ->
            assertEquals(MinecraftCompatibilityStatus.UNKNOWN, result.status)
            assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in result.reasonCodes)
            assertFalse(result.canExecute)
        }
        assertTrue(withJvm.reasons.any { it.contains("must not report a server Java runtime") })
        assertTrue(withLoaderVersion.reasons.any { it.contains("must not report a loader version") })
        assertTrue(withFabricApi.reasons.any { it.contains("must not report a Fabric API version") })
    }

    @Test
    fun bedrockRuntimesRequireAKnownPlatformAndBoundedPlatformVersion() {
        val unknownPlatform = resolver.resolveRuntime(bedrockRuntime().copy(platform = MinecraftRuntimePlatform.UNKNOWN))
        val invalidPlatformVersion = resolver.resolveRuntime(bedrockRuntime().copy(platformVersion = "1.21.60\n"))

        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, unknownPlatform.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in unknownPlatform.reasonCodes)
        assertTrue(unknownPlatform.reasons.any { it.contains("did not report its runtime platform") })
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, invalidPlatformVersion.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in invalidPlatformVersion.reasonCodes)
        assertFalse(unknownPlatform.canExecute)
        assertFalse(invalidPlatformVersion.canExecute)
    }

    @Test
    fun bedrockVersionsAreSeparateTargetsFromJavaVersions() {
        // The same numeric version is a different runtime: Bedrock is never matched through the Java profile.
        val bedrock1201 = resolver.resolveRuntime(bedrockRuntime(version = "1.20.1"))

        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, bedrock1201.status)
        assertEquals(contract.adapterId, bedrock1201.adapterId)
        assertFalse(bedrock1201.canExecute)

        val unknownFuture = resolver.resolveRuntime(bedrockRuntime(version = "1.99.0"))
        assertEquals(bedrock1201.status, unknownFuture.status)
        assertTrue(MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED in unknownFuture.reasonCodes)
        assertTrue(unknownFuture.reasons.any { it.contains("not a runtime-certified CraftMind Bedrock target") })
    }

    @Test
    fun unrecognizedBedrockVersionFormsFailClosedAsUnknown() {
        val fourPartPreview = resolver.resolveRuntime(bedrockRuntime(version = "1.21.60.10"))
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, fourPartPreview.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in fourPartPreview.reasonCodes)
        assertFalse(fourPartPreview.canExecute)

        assertFalse(MinecraftVersion.parse("not a version").isKnown)
        assertFalse(MinecraftVersion.parse("1.21.60.10").isKnown)
        assertTrue(MinecraftVersion.parse("1.21.60").isKnown)
        assertEquals(60, MinecraftVersion.parse("1.21.60").patch)
    }

    @Test
    fun bedrockContractRuntimeIsRecognizedAsExperimentalAndNeverExecutable() {
        val result = resolver.resolveRuntime(bedrockRuntime())

        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result.status)
        assertEquals(contract.adapterId, result.adapterId)
        assertEquals(BedrockRuntimeCertification.NOT_PERFORMED, result.runtimeCertification)
        assertEquals("BEDROCK_RUNTIME_NOT_CERTIFIED", result.failureReasonCode())
        assertTrue(result.reasonCodes.contains(MinecraftCompatibilityReasonCode.BEDROCK_RUNTIME_NOT_CERTIFIED))
        assertTrue(result.missingCapabilities.isEmpty())
        assertTrue(result.planWithinLimits)
        assertFalse(result.canExecute)
        assertEquals(4096, result.limits.maximumValidatedOperations)
        assertEquals(1_048_576, result.limits.maximumRequestBytes)
        assertEquals(32, result.limits.maximumOperationsPerTick)
        assertEquals(300, result.limits.maximumExecutionSeconds)
        assertEquals(MinecraftDimensionLimits(96, 64, 96), result.limits.maximumDimensions)
        assertNull(result.limits.javaRuntimeRequirement)
        assertTrue(result.warnings.any { it.contains("never substitutes a block") })
        assertTrue(result.warnings.any { it.contains("must independently validate") })
    }

    @Test
    fun bedrockPlanContentWithoutVerifiedMappingsFailsClosedWithoutSubstitution() {
        val result = resolver.resolve(BuildPlanTestFixtures.semanticPlan(), bedrockRuntime())

        assertFalse(result.canExecute)
        assertFalse(result.planContentSupported)
        assertTrue(result.reasonCodes.contains(MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK))
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.diagnostics.all { it.reasonCode == MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK })
        assertTrue(result.diagnostics.mapNotNull { it.blockId }.containsAll(listOf("minecraft:stone", "minecraft:oak_planks")))
        assertTrue(result.diagnostics.any { it.componentId == "house" })
        assertTrue(result.diagnostics.any { it.detail.contains("No verified Bedrock block/state mapping") })
        assertTrue(result.reasons.any { it.contains("does not substitute blocks or states") })
        assertEquals(contract.blockStateSupportRevision, BedrockRuntimeProfileRegistry.BLOCK_STATE_SUPPORT_REVISION_NONE)
    }

    @Test
    fun bedrockMissingCapabilitiesLimitsAndSchemaViolationsFailClosed() {
        val missingCapabilities = resolver.resolve(
            BuildPlanTestFixtures.semanticPlan(),
            bedrockRuntime(capabilities = emptySet()),
        )
        assertTrue(missingCapabilities.missingCapabilities.isNotEmpty())
        assertTrue(MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in missingCapabilities.reasonCodes)
        assertFalse(missingCapabilities.canExecute)

        val outsideContract = resolver.resolve(
            BuildPlanTestFixtures.semanticPlan(),
            bedrockRuntime(capabilities = REPORTED_CAPABILITIES + MinecraftCapability.MULTI_WORLD_SUPPORT),
        )
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, outsideContract.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in outsideContract.reasonCodes)
        assertTrue(outsideContract.reasons.any { it.contains("never inferred, widened, or invented") })

        val tooManyOperations = resolver.resolve(
            bedrockRuntime(),
            BuildPlanRequirements.runtimeExecution.copy(operationCount = 4097, schemaVersion = 2, dimensions = BuildDimensions(8, 6, 8)),
        )
        assertFalse(tooManyOperations.planWithinLimits)
        assertTrue(MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in tooManyOperations.reasonCodes)

        val unsupportedSchema = resolver.resolve(
            bedrockRuntime(),
            BuildPlanRequirements.runtimeExecution.copy(operationCount = 4, schemaVersion = 1, dimensions = BuildDimensions(8, 6, 8)),
        )
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in unsupportedSchema.reasonCodes)
        assertFalse(unsupportedSchema.canExecute)

        val tooWide = resolver.resolve(
            bedrockRuntime(),
            BuildPlanRequirements.runtimeExecution.copy(operationCount = 4, schemaVersion = 2, dimensions = BuildDimensions(200, 6, 8)),
        )
        assertFalse(tooWide.planWithinLimits)
        assertTrue(tooWide.reasons.any { it.contains("dimensions") })

        val noReportedLimits = resolver.resolveRuntime(bedrockRuntime(maximumValidatedOperations = null))
        assertFalse(noReportedLimits.planWithinLimits)
        assertTrue(noReportedLimits.reasons.any { it.contains("usable operation limit") })
        assertFalse(noReportedLimits.canExecute)
    }

    @Test
    fun bedrockProtocolBridgeAndPlatformMismatchesHaveDistinctStructuredReasons() {
        val wrongProtocol = resolver.resolveRuntime(bedrockRuntime(bridgeProtocolVersion = 1))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, wrongProtocol.status)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in wrongProtocol.reasonCodes)
        assertEquals("BRIDGE_PROTOCOL_UNSUPPORTED", wrongProtocol.failureReasonCode())
        assertTrue(wrongProtocol.reasons.single().contains("Update the bridge and app together"))

        val wrongBridge = resolver.resolveRuntime(bedrockRuntime(bridgeVersion = "9.9.9"))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, wrongBridge.status)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH in wrongBridge.reasonCodes)
        assertEquals("BRIDGE_VERSION_UNSUPPORTED", wrongBridge.failureReasonCode())

        val wrongPlatform = resolver.resolveRuntime(bedrockRuntime(platform = MinecraftRuntimePlatform.REALMS))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, wrongPlatform.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_BEDROCK_PLATFORM in wrongPlatform.reasonCodes)
        assertEquals("BEDROCK_PLATFORM_UNSUPPORTED", wrongPlatform.failureReasonCode())

        val javaLoaderOnBedrock = resolver.resolveRuntime(bedrockRuntime().copy(loader = MinecraftLoader.FABRIC))
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, javaLoaderOnBedrock.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in javaLoaderOnBedrock.reasonCodes)
        assertNull(javaLoaderOnBedrock.adapterId)
    }

    @Test
    fun bedrockAdapterRegistrationRejectsDuplicatesOverlapsMismatchesAndInvalidProfiles() {
        val bedrockRegistry = MinecraftAdapterRegistry()
        assertEquals(MinecraftAdapterRegistrationResult.Registered, bedrockRegistry.register(BedrockBridgeAdapter()))
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateAdapterId(contract.adapterId),
            bedrockRegistry.register(BedrockBridgeAdapter()),
        )

        // A second Bedrock adapter cannot reuse the contract adapter ID (duplicate adapter IDs are rejected
        // first), so the cross-adapter overlap is exercised with a distinct adapter ID claiming the same
        // bridge identity — the case the registry must reject to keep Bedrock resolution unambiguous.
        val overlappingId = MinecraftAdapterId("bedrock-contract-copy")
        val overlapping = object : MinecraftAdapter by BedrockBridgeAdapter() {
            override val adapterId = overlappingId
            override val bedrockRuntimeProfiles = listOf(contract.copy(adapterId = overlappingId))
        }
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(overlappingId, contract.adapterId),
            bedrockRegistry.register(overlapping),
        )

        // The adapter declares its own ID while the profile claims a different one: the registry rejects the
        // mismatch instead of resolving through an adapter whose profile identity is inconsistent.
        val mismatchedId = MinecraftAdapterId("bedrock-contract-mismatch")
        val mismatched = object : MinecraftAdapter by BedrockBridgeAdapter() {
            override val adapterId = mismatchedId
            override val bedrockRuntimeProfiles = listOf(contract)
        }
        assertEquals(
            MinecraftAdapterRegistrationResult.AdapterIdMismatch(mismatchedId, contract.adapterId),
            bedrockRegistry.register(mismatched),
        )

        val mixedId = MinecraftAdapterId("mixed-family-adapter")
        val mixed = object : MinecraftAdapter by BedrockBridgeAdapter() {
            override val adapterId = mixedId
            override val supportedRuntimeDescriptors =
                listOf(MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = mixedId))
            override val bedrockRuntimeProfiles = listOf(contract.copy(adapterId = mixedId))
        }
        val mixedResult = bedrockRegistry.register(mixed)
        assertTrue(mixedResult is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        assertTrue((mixedResult as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains("must not mix"))

        // Duplicate profiles inside one adapter are rejected before the adapter is ever registered, so this runs
        // against a fresh registry: the same adapter ID cannot be registered twice anyway.
        val duplicateInsideAdapter = BedrockBridgeAdapter(listOf(contract, contract))
        assertEquals(
            MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile(contract.adapterId, contract.adapterId),
            MinecraftAdapterRegistry().register(duplicateInsideAdapter),
        )

        listOf(
            contract.copy(bridgeProtocolVersion = 1) to "protocol version",
            contract.copy(supportedPlatforms = setOf(MinecraftRuntimePlatform.UNKNOWN)) to "platforms",
            contract.copy(supportedPlatforms = emptySet()) to "platforms",
            contract.copy(contractCapabilities = emptySet()) to "capabilities",
            contract.copy(maximumValidatedOperations = 0) to "operation limit",
            contract.copy(maximumOperationsPerTick = 65) to "per-tick limit",
            contract.copy(maximumExecutionSeconds = 901) to "execution timeout",
            contract.copy(maximumDimensions = MinecraftDimensionLimits(97, 64, 96)) to "dimension limits",
            contract.copy(bridgeVersion = "1.0.0\n") to "bridge version",
            contract.copy(blockStateSupportRevision = "bad revision") to "support revision",
            contract.copy(status = MinecraftCompatibilityStatus.UNKNOWN) to "contract status",
            contract.copy(certifiedMinecraftVersions = setOf(MinecraftVersion.UNKNOWN)) to "exact known versions",
        ).forEach { (invalidProfile, expectedReason) ->
            val invalidRegistry = MinecraftAdapterRegistry()
            val result = invalidRegistry.register(BedrockBridgeAdapter(listOf(invalidProfile)))
            assertTrue("expected rejection for $expectedReason", result is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
            assertTrue(
                (result as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains(expectedReason),
            )
            assertTrue(invalidRegistry.allAdapters().isEmpty())
        }
    }

    @Test
    fun bedrockRegistryCannotClaimSupportWithoutRecordedRuntimeCertification() {
        val registry = MinecraftAdapterRegistry()
        val claimedSupport = BedrockBridgeAdapter(
            listOf(
                certifiedTestProfile().copy(
                    status = MinecraftCompatibilityStatus.SUPPORTED,
                    certification = BedrockRuntimeCertification.NOT_PERFORMED,
                ),
            ),
        )
        val firstResult = registry.register(claimedSupport)
        assertTrue(firstResult is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        assertTrue((firstResult as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains("runtime certification"))

        val noCertifiedVersion = BedrockBridgeAdapter(
            listOf(certifiedTestProfile(certifiedVersions = emptySet())),
        )
        val secondResult = registry.register(noCertifiedVersion)
        assertTrue(secondResult is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        assertTrue((secondResult as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains("certified Minecraft version"))
        assertTrue(registry.allAdapters().isEmpty())

        assertFalse(BedrockRuntimeCertification.NOT_PERFORMED.authorizesSupport)
        assertFalse(BedrockRuntimeCertification.UNIT_TESTED.authorizesSupport)
        assertFalse(BedrockRuntimeCertification.BRIDGE_TESTED.authorizesSupport)
        assertTrue(BedrockRuntimeCertification.RUNTIME_TESTED.authorizesSupport)
    }

    @Test
    fun testOnlyCertifiedContractProvesTheExecutionGateIsEvidenceDriven() {
        // This contract exists only inside this test. It documents that authorization requires recorded runtime
        // certification and a certified version, and it is never registered in the shipped app.
        val certifiedProfile = certifiedTestProfile()
        val certifiedRegistry = MinecraftAdapterRegistry().apply {
            assertEquals(MinecraftAdapterRegistrationResult.Registered, register(BedrockBridgeAdapter(listOf(certifiedProfile))))
        }
        val certifiedResolver = MinecraftCompatibilityResolver(certifiedRegistry)
        val runtime = bedrockRuntime(bridgeVersion = certifiedProfile.bridgeVersion)

        val runtimeOnly = certifiedResolver.resolveRuntime(runtime)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, runtimeOnly.status)
        assertEquals(BedrockRuntimeCertification.RUNTIME_TESTED, runtimeOnly.runtimeCertification)
        assertTrue(runtimeOnly.canExecute)

        val withPlanContent = certifiedResolver.resolve(BuildPlanTestFixtures.semanticPlan(), runtime)
        assertFalse(withPlanContent.canExecute)
        assertFalse(withPlanContent.planContentSupported)

        val uncertifiedVersion = certifiedResolver.resolveRuntime(
            bedrockRuntime(bridgeVersion = certifiedProfile.bridgeVersion, version = "1.20.1"),
        )
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, uncertifiedVersion.status)
        assertFalse(uncertifiedVersion.canExecute)

        assertTrue(DefaultMinecraftCompatibility.resolver.registeredBedrockProfiles().none { it.certification.authorizesSupport })
    }

    @Test
    fun shippedBedrockRegistryClaimsNoCertifiedRuntime() {
        assertTrue(contract.certifiedMinecraftVersions.isEmpty())
        assertEquals(BedrockRuntimeCertification.NOT_PERFORMED, contract.certification)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, contract.status)
        assertEquals(MinecraftEdition.BEDROCK, contract.edition)
        assertEquals(BridgeProtocol.VERSION, contract.bridgeProtocolVersion)
        assertEquals(BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION, contract.bridgeVersion)
        assertEquals(0, contract.blockStateCatalog.mappingCount)
        assertFalse(contract.blockStateCatalog.isDeclared)
        assertEquals(BedrockRuntimeProfileRegistry.allProfiles(), DefaultMinecraftCompatibility.resolver.registeredBedrockProfiles())

        val shippedAdapter = DefaultMinecraftCompatibility.resolver.registeredAdapters()
            .filterIsInstance<BedrockBridgeAdapter>()
            .single()
        assertEquals(listOf(contract), shippedAdapter.bedrockRuntimeProfiles)
        assertTrue(shippedAdapter.supportedRuntimeDescriptors.isEmpty())
        assertFalse(DefaultMinecraftCompatibility.resolver.registeredBedrockProfiles().any { it.certifiedMinecraftVersions.isNotEmpty() })
    }

    @Test
    fun bedrockBlockStateCatalogRejectsUnverifiedDuplicateAndInvalidMappings() {
        assertThrows(IllegalArgumentException::class.java) {
            BedrockBlockStateCatalog(listOf(testAxisMapping().copy(verification = BedrockMappingVerification.NOT_VERIFIED)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BedrockBlockStateCatalog(listOf(testAxisMapping(), testAxisMapping()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BedrockStateTranslation("Axis", mapOf("x" to "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BedrockStateTranslation("axis", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BedrockBlockStateMapping("minecraft:test block", verification = BedrockMappingVerification.RUNTIME_VERIFIED)
        }
    }

    @Test
    fun bedrockBlockStateCatalogResolvesOnlyDeclaredMappingsAndNeverSubstitutes() {
        val catalog = BedrockBlockStateCatalog(
            listOf(
                testAxisMapping(),
                BedrockBlockStateMapping(
                    blockId = "minecraft:test_stairs",
                    stateTranslations = mapOf(
                        "facing" to BedrockStateTranslation(
                            bedrockProperty = "weirdo_direction",
                            valueTranslations = mapOf("east" to "0", "north" to "2"),
                        ),
                    ),
                    verification = BedrockMappingVerification.RUNTIME_VERIFIED,
                ),
                BedrockBlockStateMapping(
                    blockId = "minecraft:test_glass",
                    verification = BedrockMappingVerification.RUNTIME_VERIFIED,
                ),
            ),
        )

        assertEquals(
            BedrockBlockStateResolution.Supported("minecraft:test_axis_block", mapOf("axis" to "x")),
            catalog.resolve("minecraft:test_axis_block", mapOf("axis" to "x")),
        )
        assertEquals(
            BedrockBlockStateResolution.Supported("minecraft:test_stairs", mapOf("weirdo_direction" to "0")),
            catalog.resolve("minecraft:test_stairs", mapOf("facing" to "east")),
        )
        assertEquals(
            BedrockBlockStateResolution.Supported("minecraft:test_glass", emptyMap()),
            catalog.resolve("minecraft:test_glass", emptyMap()),
        )
        assertEquals(
            BedrockBlockStateResolution.UnsupportedState("minecraft:test_stairs", listOf("facing")),
            catalog.resolve("minecraft:test_stairs", mapOf("facing" to "south")),
        )
        assertEquals(
            BedrockBlockStateResolution.UnsupportedState(
                "minecraft:test_axis_block",
                listOf("weirdo_direction"),
            ),
            catalog.resolve("minecraft:test_axis_block", mapOf("axis" to "x", "weirdo_direction" to "1")),
        )
        assertEquals(
            BedrockBlockStateResolution.UnsupportedBlock("minecraft:oak_planks"),
            catalog.resolve("minecraft:oak_planks", emptyMap()),
        )
        assertEquals(
            BedrockBlockStateResolution.UnsupportedState(
                "minecraft:test_axis_block",
                (1..8).map { "property_$it" },
            ),
            catalog.resolve("minecraft:test_axis_block", (1..9).associate { "property_$it" to "value" }),
        )
        assertEquals(3, catalog.mappingCount)
        assertTrue(catalog.declaredBlockIds().contains("minecraft:test_axis_block"))
        assertFalse(BedrockBlockStateCatalog.EMPTY.isDeclared)
    }

    @Test
    fun bedrockAdapterNeverSendsAPlanToABridge() {
        val adapter = BedrockBridgeAdapter()
        val record = LocalBuildRecord(
            recordId = "record-1",
            plan = BuildPlanTestFixtures.semanticPlan(),
            request = BuildRequestSnapshot(prompt = "courtyard home"),
            savedAtEpochMillis = 1_700_000_000_000,
        )

        assertFailure(BedrockBridgeAdapter.BEDROCK_RUNTIME_NOT_CERTIFIED) {
            runBlocking { adapter.preflight(UnusedBridgeRepository(), record, EXECUTION_ID) }
        }
        assertFailure(BedrockBridgeAdapter.BEDROCK_RUNTIME_NOT_CERTIFIED) {
            runBlocking { adapter.cancel(UnusedBridgeRepository(), EXECUTION_ID) }
        }
        assertFailure(BedrockBridgeAdapter.BEDROCK_RUNTIME_NOT_CERTIFIED) {
            runBlocking { adapter.status(UnusedBridgeRepository(), EXECUTION_ID) }
        }
    }

    @Test
    fun oversizedMalformedAndCrossEditionBedrockDescriptorsFailClosed() {
        val tooManySchemas = resolver.resolveRuntime(bedrockRuntime(schemaVersions = (1..9).toSet()))
        val malformedAppVersion = resolver.resolveRuntime(bedrockRuntime().copy(appVersion = "1.0.0\n"))
        val limitsOutsideProtocol = resolver.resolveRuntime(bedrockRuntime(maximumOperationsPerTick = 65))

        listOf(tooManySchemas, malformedAppVersion, limitsOutsideProtocol).forEach { result ->
            assertEquals(MinecraftCompatibilityStatus.UNKNOWN, result.status)
            assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in result.reasonCodes)
            assertFalse(result.canExecute)
        }

        val javaWithBedrockFacts = javaRuntime().copy(
            platform = MinecraftRuntimePlatform.DEDICATED_SERVER,
            limitations = setOf(BedrockRuntimeLimitation.NO_ROLLBACK),
        )
        val crossEdition = resolver.resolveRuntime(javaWithBedrockFacts)
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, crossEdition.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in crossEdition.reasonCodes)
        assertTrue(crossEdition.reasons.any { it.contains("Only a Bedrock runtime may report a Bedrock runtime platform") })
        assertTrue(crossEdition.reasons.any { it.contains("Only a Bedrock runtime may report Bedrock integration limitations") })
    }

    @Test
    fun legacyAndOtherEditionsAreStillNeverRoutedThroughABedrockOrJavaAdapter() {
        // A syntactically complete Legacy descriptor (bounded version tokens, authenticated protocol fields) so
        // the resolver reaches edition routing instead of stopping at malformed-metadata classification.
        val legacy = MinecraftRuntimeDescriptor(
            edition = MinecraftEdition.LEGACY,
            version = MinecraftVersion.parse("b1.7.3"),
            loaderVersion = "1.0.0",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            bridgeVersion = "1.2.0",
            capabilities = REPORTED_CAPABILITIES,
            supportedBuildPlanSchemaVersions = setOf(2),
            maximumValidatedOperations = 4096,
            maximumRequestBytes = 1_048_576,
            maximumOperationsPerTick = 32,
            maximumExecutionSeconds = 300,
        )
        val legacyResult = resolver.resolveRuntime(legacy)
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, legacyResult.status)
        assertNull(legacyResult.adapterId)
        assertTrue(legacyResult.reasons.single().contains("not routed through a Java/Fabric adapter"))

        val unknownEdition = resolver.resolveRuntime(MinecraftRuntimeDescriptor())
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, unknownEdition.status)
        assertNull(unknownEdition.adapterId)
        assertFalse(unknownEdition.canExecute)
    }

    private fun assertFailure(expectedCode: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: MinecraftBridgeFailure) {
            assertEquals(expectedCode, failure.reasonCode)
            return
        }
        throw AssertionError("Expected $expectedCode")
    }

    private fun bedrockRuntime(
        version: String = "1.21.60",
        platform: MinecraftRuntimePlatform = MinecraftRuntimePlatform.DEDICATED_SERVER,
        platformVersion: String? = "1.21.60.3",
        bridgeVersion: String = BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION,
        bridgeProtocolVersion: Int = BridgeProtocol.VERSION,
        capabilities: Set<MinecraftCapability> = REPORTED_CAPABILITIES,
        schemaVersions: Set<Int> = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maximumValidatedOperations: Int? = 4096,
        maximumRequestBytes: Int? = 1_048_576,
        maximumOperationsPerTick: Int? = 32,
        maximumExecutionSeconds: Int? = 300,
    ) = MinecraftRuntimeDescriptor(
        edition = MinecraftEdition.BEDROCK,
        version = MinecraftVersion.parse(version),
        platform = platform,
        platformVersion = platformVersion,
        javaRuntimeMajor = null,
        loader = MinecraftLoader.BEDROCK_NATIVE,
        loaderVersion = null,
        fabricApiVersion = null,
        bridgeProtocolVersion = bridgeProtocolVersion,
        bridgeVersion = bridgeVersion,
        capabilities = capabilities,
        supportedBuildPlanSchemaVersions = schemaVersions,
        maximumValidatedOperations = maximumValidatedOperations,
        maximumRequestBytes = maximumRequestBytes,
        maximumOperationsPerTick = maximumOperationsPerTick,
        maximumExecutionSeconds = maximumExecutionSeconds,
        worldAvailable = true,
        operatorOriginAvailable = true,
        limitations = setOf(BedrockRuntimeLimitation.NO_ROLLBACK),
    )

    private fun javaRuntime() = MinecraftRuntimeDescriptor(
        edition = MinecraftEdition.JAVA,
        version = MinecraftVersion.parse("1.20.1"),
        javaRuntimeMajor = 17,
        loader = MinecraftLoader.FABRIC,
        loaderVersion = "0.16.10",
        fabricApiVersion = "0.92.2+1.20.1",
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        bridgeVersion = "1.2.0",
        capabilities = REPORTED_CAPABILITIES,
        supportedBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maximumValidatedOperations = 4096,
        maximumRequestBytes = 1_048_576,
        maximumOperationsPerTick = 32,
        maximumExecutionSeconds = 300,
        worldAvailable = true,
        operatorOriginAvailable = true,
    )

    /** Test-only contract that records real runtime certification; never registered in the shipped app. */
    private fun certifiedTestProfile(
        certifiedVersions: Set<MinecraftVersion> = setOf(MinecraftVersion.parse("1.21.60")),
        catalog: BedrockBlockStateCatalog = BedrockBlockStateCatalog.EMPTY,
    ) = BedrockRuntimeProfile(
        adapterId = contract.adapterId,
        edition = MinecraftEdition.BEDROCK,
        bridgeVersion = "9.9.9",
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        supportedPlatforms = setOf(MinecraftRuntimePlatform.DEDICATED_SERVER),
        certifiedMinecraftVersions = certifiedVersions,
        contractCapabilities = REPORTED_CAPABILITIES,
        requiredBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        limitations = setOf(BedrockRuntimeLimitation.NO_ROLLBACK),
        maximumValidatedOperations = 4096,
        maximumRequestBytes = 1_048_576,
        maximumOperationsPerTick = 32,
        maximumExecutionSeconds = 300,
        maximumDimensions = MinecraftDimensionLimits(96, 64, 96),
        blockStateSupportRevision = "test-only-revision",
        blockStateCatalog = catalog,
        status = MinecraftCompatibilityStatus.SUPPORTED,
        certification = BedrockRuntimeCertification.RUNTIME_TESTED,
    )

    private fun testAxisMapping() = BedrockBlockStateMapping(
        blockId = "minecraft:test_axis_block",
        stateTranslations = mapOf(
            "axis" to BedrockStateTranslation("axis", mapOf("x" to "x", "y" to "y", "z" to "z")),
        ),
        verification = BedrockMappingVerification.RUNTIME_VERIFIED,
    )

    /** A repository that fails the test if a Bedrock adapter ever touches the shared authenticated bridge. */
    private class UnusedBridgeRepository : MinecraftBridgePairingRepository {
        override val connectionState: StateFlow<BridgeConnectionState> = MutableStateFlow(BridgeConnectionState.Disconnected)
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

        private fun unused(): Nothing = throw AssertionError("The Bedrock adapter must not touch the bridge")
    }

    private companion object {
        const val EXECUTION_ID = "6f5d2f1e-8b3a-4c7d-9e10-1a2b3c4d5e6f"

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
