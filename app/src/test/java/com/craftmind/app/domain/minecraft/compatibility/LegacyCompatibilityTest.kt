package com.craftmind.app.domain.minecraft.compatibility

import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.BuildPlanTestFixtures
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.app.domain.minecraft.BridgeConnectionState
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.TrustedMinecraftBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 12: legacy, beta, and experimental compatibility.
 *
 * The suite proves the honest boundary described in the task: recognizing an old, beta, snapshot, or experimental
 * runtime is never the same as supporting it. Every case asserts fail-closed behavior — an exact identity match or
 * an explicit UNSUPPORTED/UNKNOWN result, never a nearest-version or cross-edition fallback, never fabricated
 * certification, and never an authorized execution for an unverified runtime.
 */
class LegacyCompatibilityTest {
    private val fixture = BuildPlanTestFixtures
    private val resolver = DefaultMinecraftCompatibility.resolver

    // ------------------------------------------------------------------ version model

    @Test
    fun versionModelCarriesStructuredPartsAndReleaseChannels() {
        val release = MinecraftVersion.parse("1.20.1")
        assertEquals(MinecraftVersionChannel.RELEASE, release.channel)
        assertEquals(1, release.major)
        assertEquals(20, release.minor)
        assertEquals(1, release.patch)
        assertNull(release.qualifier)

        val twoPart = MinecraftVersion.parse("1.19")
        assertEquals(MinecraftVersionChannel.RELEASE, twoPart.channel)
        assertNull(twoPart.patch)

        val preRelease = MinecraftVersion.parse("1.20.1-pre2")
        assertEquals(MinecraftVersionChannel.PRE_RELEASE, preRelease.channel)
        assertEquals("pre2", preRelease.qualifier)

        assertEquals(MinecraftVersionChannel.PRE_RELEASE, MinecraftVersion.parse("1.21.4-rc1").channel)
        assertEquals(MinecraftVersionChannel.SNAPSHOT, MinecraftVersion.parse("24w45a").channel)
        assertEquals(MinecraftVersionChannel.BETA, MinecraftVersion.parse("1.19.4-beta1").channel)
        assertEquals(MinecraftVersionChannel.BETA, MinecraftVersion.parse("b1.7.3").channel)
        assertEquals(MinecraftVersionChannel.ALPHA, MinecraftVersion.parse("a1.2.6").channel)

        // Historical identifiers are recognized as legacy tokens, but only because the identifier itself says so.
        listOf("rd-132211", "inf-20100618", "classic/0.0.14a").forEach { identifier ->
            assertEquals("$identifier must parse as a legacy identifier", MinecraftVersionChannel.LEGACY, MinecraftVersion.parse(identifier).channel)
        }

        // An old *release* keeps the release channel: nothing is auto-classified as legacy.
        assertEquals(MinecraftVersionChannel.RELEASE, MinecraftVersion.parse("1.7.10").channel)
        assertNotEquals(MinecraftVersion.parse("1.7.10"), MinecraftVersion.parse("1.8.9"))
        assertNotEquals(MinecraftVersion.parse("1.20.1"), MinecraftVersion.parse("1.20.2"))
        assertNotEquals(MinecraftVersion.parse("1.20.1"), MinecraftVersion.parse("1.21"))
    }

    @Test
    fun malformedAndUnknownVersionsNeverBecomeAKnownNeighbour() {
        listOf(null, "", "unknown", "  1.20.1", "1.20.1 ", "1.2.3.4", "1.20.1; rm -rf", "9".repeat(49)).forEach { raw ->
            val parsed = MinecraftVersion.parse(raw)
            assertEquals("$raw must stay unknown", MinecraftVersionChannel.UNKNOWN, parsed.channel)
            assertFalse(parsed.isKnown)
            assertFalse("UNKNOWN must never be matched to a release", parsed == MinecraftVersion.parse("1.20.1"))
        }
        // An unsafe token is not even retained for display; no untrusted text is echoed back.
        assertNull(MinecraftVersion.parse("1.20.1; rm -rf").identifier)
        assertEquals("unknown", MinecraftVersion.UNKNOWN.displayIdentifier)
    }

    // ------------------------------------------------------------------ release channels in the resolver

    @Test
    fun snapshotBetaAlphaAndPreReleaseRuntimesFailClosedAndAreNeverMatchedToReleaseBuilds() {
        val productionOnly = productionOnlyResolver()
        listOf("24w45a", "1.19.4-beta1", "a1.2.6", "1.20.1-rc2").forEach { identifier ->
            val runtime = javaRuntime(version = identifier)
            val result = productionOnly.resolveRuntime(runtime)

            assertEquals("$identifier must not be supported", MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
            assertFalse(result.canExecute)
            assertNull(result.adapterId)
            assertTrue(
                "$identifier must be diagnosed by release channel",
                MinecraftCompatibilityReasonCode.UNSUPPORTED_RELEASE_CHANNEL in result.reasonCodes,
            )
            assertTrue(result.reasons.any { it.contains("never matched to a release version") })
            assertEquals("UNSUPPORTED_RELEASE_CHANNEL", result.failureReasonCode())
        }

        // 1.20.1-rc2 is adjacent to the supported 1.20.1 profile and is still not treated as 1.20.1.
        val releaseCandidate = productionOnly.resolveRuntime(javaRuntime(version = "1.20.1-rc2"))
        assertNotEquals(MinecraftCompatibilityStatus.SUPPORTED, releaseCandidate.status)
        assertTrue(releaseCandidate.reasons.any { it.contains("nearest-version") || it.contains("never matched") })
    }

    @Test
    fun legacyIdentifiersFailClosedWithALegacyReasonAndNoAdapter() {
        val result = resolver.resolveRuntime(
            javaRuntime(version = "rd-132211", loader = MinecraftLoader.VANILLA, loaderVersion = "vanilla", fabricApi = null, javaMajor = 8),
        )

        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
        assertFalse(result.canExecute)
        assertNull(result.adapterId)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_LEGACY_VERSION in result.reasonCodes)
        assertTrue(result.reasons.any { it.contains("never mapped to a release build") })
        assertEquals("UNSUPPORTED_LEGACY_VERSION", result.failureReasonCode())
    }

    @Test
    fun anOldReleaseWithoutARegisteredLegacyProfileStaysAPlainUnsupportedVersion() {
        val productionOnly = productionOnlyResolver()
        val result = productionOnly.resolveRuntime(
            javaRuntime(version = "1.7.10", loader = MinecraftLoader.FORGE, loaderVersion = "10.13.4.1614", fabricApi = null, javaMajor = 8),
        )

        // No automatic "old version == legacy" classification: the resolver reports an unregistered version.
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_MINECRAFT_VERSION in result.reasonCodes)
        assertFalse(MinecraftCompatibilityReasonCode.UNSUPPORTED_LEGACY_VERSION in result.reasonCodes)
        assertTrue(result.reasons.any { it.contains("No nearest-version fallback") })
    }

    // ------------------------------------------------------------------ shipped legacy contracts

    @Test
    fun shippedLegacyContractsAreDeclaredHonestlyAndCannotAuthorizeExecution() {
        val profiles = LegacyRuntimeProfileRegistry.allProfiles()
        assertEquals(2, profiles.size)
        profiles.forEach { profile ->
            assertEquals(MinecraftEdition.JAVA, profile.edition)
            assertEquals(MinecraftVersionChannel.LEGACY, profile.releaseChannel)
            assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, profile.supportStatus)
            assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, profile.runtimeCertification)
            assertFalse(profile.authorizesExecution)
            assertTrue(profile.limitations.isNotEmpty())
            assertFalse(profile.blockStateCatalog.isDeclared)
            assertEquals(MinecraftContentValidationMode.APP_SIDE_MAPPING, profile.contentValidationMode)
            assertEquals(8, profile.javaRuntimeRequirement?.requiredMajor)
            assertEquals(MinecraftVersionChannel.RELEASE, profile.version.channel)
        }

        val adapterIds = resolver.registeredAdapters().map { it.adapterId.value }.toSet()
        assertEquals(
            setOf("java-fabric-1.20.1", "java-legacy-experimental", "bedrock-bridge-contract"),
            adapterIds,
        )
        assertEquals(3, resolver.registeredProfiles().size)
        assertEquals(1, resolver.registeredBedrockProfiles().size)
        // Every registered profile that claims SUPPORTED must be backed by a certification rung.
        resolver.registeredProfiles().filter { it.supportStatus == MinecraftCompatibilityStatus.SUPPORTED }
            .forEach { assertTrue(it.runtimeCertification.authorizesSupport) }
    }

    @Test
    fun recognizedLegacyRuntimeResolvesExperimentalWithTypedReasonsAndWarning() {
        val runtime = legacyForge1710Runtime()
        val result = resolver.resolveRuntime(runtime)

        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result.status)
        assertEquals("java-legacy-experimental", result.adapterId?.value)
        assertFalse(result.canExecute)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, result.runtimeCertification)
        assertTrue(MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED in result.reasonCodes)
        assertTrue(result.reasons.any { it.contains("no runtime verification has been recorded") })
        assertTrue(result.warnings.any { it.contains("Recognition is not support") })
        assertTrue(result.warnings.any { it.contains("Declared limitation") })
        assertEquals("RUNTIME_NOT_CERTIFIED", result.failureReasonCode())

        // The plan path fails closed on content as well: the shipped legacy catalog declares no verified mapping.
        val plan = fixture.semanticPlan()
        val planResult = resolver.resolve(plan, runtime)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, planResult.status)
        assertFalse(planResult.canExecute)
        assertFalse(planResult.planContentSupported)
        assertTrue(
            MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK in planResult.reasonCodes ||
                MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK_STATE in planResult.reasonCodes,
        )
        assertTrue(planResult.reasons.any { it.contains("does not substitute blocks or states") })
        assertTrue(planResult.diagnostics.size <= 16)
    }

    @Test
    fun legacyRuntimeOnJava17IsRejectedInsteadOfAssumingJava17Fits() {
        listOf(17, 21, 7).forEach { major ->
            val result = resolver.resolveRuntime(legacyForge1710Runtime(javaMajor = major))

            assertEquals("Java $major must not satisfy the Java 8 legacy contract", MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
            assertFalse(result.canExecute)
            assertNull(result.adapterId)
            assertTrue(MinecraftCompatibilityReasonCode.INCOMPATIBLE_JAVA_RUNTIME in result.reasonCodes)
            assertTrue(result.reasons.any { it.contains("requires Java 8") })
            assertEquals("BRIDGE_JAVA_RUNTIME_UNSUPPORTED", result.failureReasonCode())
        }

        // The production runtime keeps its exact Java 17 requirement and is unaffected by the legacy contracts.
        val production = resolver.resolveRuntime(javaRuntime(version = "1.20.1"))
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, production.status)
        assertTrue(production.canExecute)
        assertEquals("java-fabric-1.20.1", production.adapterId?.value)
    }

    @Test
    fun legacyRuntimeIsNeverReroutedThroughTheProductionBridgeIdentity() {
        val productionBridge = resolver.resolveRuntime(legacyForge1710Runtime(bridgeVersion = "1.2.0"))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, productionBridge.status)
        assertNull(productionBridge.adapterId)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_VERSION_MISMATCH in productionBridge.reasonCodes)
        assertTrue(productionBridge.reasons.any { it.contains("1.0.0-legacy") })
        assertEquals("BRIDGE_VERSION_UNSUPPORTED", productionBridge.failureReasonCode())

        // No protocol downgrade: a legacy runtime reporting an older protocol is rejected, not negotiated.
        val oldProtocol = resolver.resolveRuntime(legacyForge1710Runtime(protocol = 1))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, oldProtocol.status)
        assertTrue(MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH in oldProtocol.reasonCodes)
        assertEquals("BRIDGE_PROTOCOL_UNSUPPORTED", oldProtocol.failureReasonCode())
    }

    @Test
    fun everyLoaderFamilyStaysExplicitAndIsNeverRemapped() {
        val production = MinecraftLoader.FABRIC
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, resolver.resolveRuntime(javaRuntime(version = "1.20.1", loader = production)).status)

        // Every loader family is matched on its own exact identity: none of them borrows the Fabric adapter and
        // none of them is substituted for another. Unknown remains its own explicit value.
        listOf(
            MinecraftLoader.FORGE,
            MinecraftLoader.NEOFORGE,
            MinecraftLoader.VANILLA,
            MinecraftLoader.UNKNOWN,
        ).forEach { loader ->
            val result = resolver.resolveRuntime(
                javaRuntime(version = "1.20.1", loader = loader, loaderVersion = "1.0.0", fabricApi = null, javaMajor = 17),
            )
            assertNull(result.adapterId)
            if (loader == MinecraftLoader.UNKNOWN) {
                assertEquals(MinecraftCompatibilityStatus.UNKNOWN, result.status)
                assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in result.reasonCodes)
            } else {
                assertEquals("$loader must not borrow the Fabric 1.20.1 adapter", MinecraftCompatibilityStatus.UNSUPPORTED, result.status)
                assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER in result.reasonCodes)
            }
        }

        // Bedrock Native is a Bedrock loader; pairing it with the Java edition is an inconsistent descriptor.
        val inconsistent = resolver.resolveRuntime(
            javaRuntime(version = "1.20.1", loader = MinecraftLoader.BEDROCK_NATIVE, loaderVersion = "1.0.0", fabricApi = null),
        )
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, inconsistent.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in inconsistent.reasonCodes)

        // A legacy Forge runtime that reports Fabric is a loader mismatch, not a legacy Fabric profile.
        val fabricLegacy = resolver.resolveRuntime(
            javaRuntime(version = "1.7.10", loader = MinecraftLoader.FABRIC, loaderVersion = "0.16.10", fabricApi = "0.92.2+1.20.1", javaMajor = 8),
        )
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, fabricLegacy.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER in fabricLegacy.reasonCodes)

        // Old vanilla is not mapped to the legacy Forge contract either.
        val vanillaLegacy = resolver.resolveRuntime(
            javaRuntime(version = "1.7.10", loader = MinecraftLoader.VANILLA, loaderVersion = "vanilla", fabricApi = null, javaMajor = 8),
        )
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, vanillaLegacy.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_LOADER in vanillaLegacy.reasonCodes)

        // A missing loader version is incomplete too, and never defaulted to a nearby loader's version.
        val missingLoaderVersion = resolver.resolveRuntime(
            javaRuntime(version = "1.20.1", loader = MinecraftLoader.FORGE, loaderVersion = "unknown", fabricApi = null),
        )
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, missingLoaderVersion.status)
        assertTrue(MinecraftCompatibilityReasonCode.UNKNOWN_RUNTIME_DESCRIPTOR in missingLoaderVersion.reasonCodes)

        // A declared non-Fabric legacy loader resolves through the legacy adapter, never through Fabric.
        val vanillaLegacyContract = legacyProfile(
            version = "1.16.5",
            loader = MinecraftLoader.VANILLA,
            loaderVersion = "vanilla",
            javaMajor = 17,
            releaseChannel = MinecraftVersionChannel.LEGACY,
        )
        val vanillaRegistry = MinecraftAdapterRegistry()
        vanillaRegistry.register(LegacyJavaRuntimeAdapter(listOf(vanillaLegacyContract)))
        val vanillaLegacyResult = MinecraftCompatibilityResolver(vanillaRegistry).resolveRuntime(
            javaRuntime(
                version = "1.16.5",
                loader = MinecraftLoader.VANILLA,
                loaderVersion = "vanilla",
                fabricApi = null,
                javaMajor = 17,
                capabilities = emptySet(),
                bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
            ),
        )
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, vanillaLegacyResult.status)
        assertEquals("java-legacy-experimental", vanillaLegacyResult.adapterId?.value)
        assertFalse(vanillaLegacyResult.canExecute)
    }

    // ------------------------------------------------------------------ certification

    @Test
    fun certificationLadderIsExplicitAndNeverCollapsed() {
        // Phase 14 §1 added exactly one rung, SIMULATED_E2E_VERIFIED, between BRIDGE_TESTED and RUNTIME_TESTED:
        // a full pipeline run against a controlled simulated bridge is real evidence about CraftMind's wiring and
        // must be recorded as such, while still never authorizing support. The ladder is otherwise unchanged.
        val expected = listOf(
            MinecraftRuntimeCertification.NOT_PERFORMED,
            MinecraftRuntimeCertification.STATIC_ONLY,
            MinecraftRuntimeCertification.UNIT_TESTED,
            MinecraftRuntimeCertification.BRIDGE_TESTED,
            MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED,
            MinecraftRuntimeCertification.RUNTIME_TESTED,
            MinecraftRuntimeCertification.CERTIFIED,
        )
        assertEquals(expected, MinecraftRuntimeCertification.entries.toList())
        // Deterministic ordering: the ladder is ranked, and the simulated ceiling sits below any real runtime rung.
        assertEquals(
            expected.mapIndexed { index, _ -> index }.toList(),
            expected.map { it.evidenceRank }.toList(),
        )
        assertEquals(
            MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED,
            MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL,
        )
        assertTrue(
            MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL.evidenceRank <
                MinecraftRuntimeCertification.RUNTIME_TESTED.evidenceRank,
        )
        assertFalse(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.isRealRuntimeEvidence)
        assertTrue(MinecraftRuntimeCertification.RUNTIME_TESTED.isRealRuntimeEvidence)
        assertFalse("simulated evidence must never authorize support", MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.authorizesSupport)
        expected.forEach { certification ->
            assertEquals(
                "only runtime-tested/certified rungs authorize SUPPORTED",
                certification == MinecraftRuntimeCertification.RUNTIME_TESTED ||
                    certification == MinecraftRuntimeCertification.CERTIFIED,
                certification.authorizesSupport,
            )
            assertEquals(
                "Bedrock requires a real Bedrock runtime test",
                certification == MinecraftRuntimeCertification.RUNTIME_TESTED,
                certification.authorizesBedrockSupport,
            )
        }
        // Static analysis and unit tests are recorded, but they are not runtime certification.
        assertFalse(MinecraftRuntimeCertification.STATIC_ONLY.authorizesSupport)
        assertFalse(MinecraftRuntimeCertification.UNIT_TESTED.authorizesSupport)
        assertFalse(MinecraftRuntimeCertification.CERTIFIED.authorizesBedrockSupport)
    }

    @Test
    fun registryRejectsLegacyProfilesThatOverclaimOrOmitTheirLimitations() {
        // SUPPORTED without a certification rung is rejected.
        val overclaimed = legacyProfile(status = MinecraftCompatibilityStatus.SUPPORTED, certification = MinecraftRuntimeCertification.NOT_PERFORMED)
        assertInvalid(overclaimed, "runtime certification")

        // A legacy/experimental profile must declare its limitations.
        assertInvalid(legacyProfile(limitations = emptySet()), "must declare its limitations")

        // The declared release channel must be coherent with the parsed identifier.
        assertInvalid(legacyProfile(releaseChannel = MinecraftVersionChannel.SNAPSHOT), "release channel")
        assertInvalid(
            legacyProfile(version = "rd-132211", releaseChannel = MinecraftVersionChannel.RELEASE),
            "release channel",
        )

        // Statistics-only and unit-test-only evidence may be registered, but never as SUPPORTED.
        assertInvalid(
            legacyProfile(status = MinecraftCompatibilityStatus.SUPPORTED, certification = MinecraftRuntimeCertification.STATIC_ONLY),
            "runtime certification",
        )
        assertInvalid(
            legacyProfile(status = MinecraftCompatibilityStatus.SUPPORTED, certification = MinecraftRuntimeCertification.UNIT_TESTED),
            "runtime certification",
        )
        assertInvalid(
            legacyProfile(status = MinecraftCompatibilityStatus.SUPPORTED, certification = MinecraftRuntimeCertification.BRIDGE_TESTED),
            "runtime certification",
        )

        // A declared app-side mapping needs a real support revision.
        val declaredMapping = MinecraftTargetBlockStateCatalog(
            listOf(
                MinecraftTargetBlockStateMapping(
                    blockId = "minecraft:test_block",
                    verification = BlockStateMappingVerification.RUNTIME_VERIFIED,
                ),
            ),
        )
        assertInvalid(
            legacyProfile(
                contentValidationMode = MinecraftContentValidationMode.APP_SIDE_MAPPING,
                blockStateCatalog = declaredMapping,
                blockStateSupportRevision = MinecraftTargetBlockStateCatalog.NONE_DECLARED_REVISION,
            ),
            "support revision",
        )

        // An explicitly declared legacy contract is registrable, and so is one backed by real runtime testing.
        assertRegistered(legacyProfile())
        assertRegistered(
            legacyProfile(
                status = MinecraftCompatibilityStatus.SUPPORTED,
                certification = MinecraftRuntimeCertification.RUNTIME_TESTED,
            ),
        )
    }

    @Test
    fun anUncertifiedProfileCanNeverSurfaceAsSupportedEvenIfItDeclaresSo() {
        // Simulates a registry bypass: the adapter itself must still refuse to claim SUPPORTED.
        val misleading = legacyProfile(
            status = MinecraftCompatibilityStatus.SUPPORTED,
            certification = MinecraftRuntimeCertification.NOT_PERFORMED,
        )
        val adapter = LegacyJavaRuntimeAdapter(listOf(misleading))
        val result = adapter.compatibilityCheck(
            javaRuntime(
                version = "1.7.10",
                loader = MinecraftLoader.FORGE,
                loaderVersion = "10.13.4.1614",
                fabricApi = null,
                javaMajor = 8,
                bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
            ),
            BuildPlanRequirements.runtimeExecution,
        )

        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, result?.status)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, result?.runtimeCertification)
        assertFalse(result?.canExecute ?: true)
        assertTrue(MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED in (result?.reasonCodes ?: emptySet()))
    }

    @Test
    fun aCertificationBackedLegacyProfileCanAuthorizeExecutionOnlyWithAnExactMatch() {
        val certified = legacyProfile(
            status = MinecraftCompatibilityStatus.SUPPORTED,
            certification = MinecraftRuntimeCertification.RUNTIME_TESTED,
        )
        val registry = MinecraftAdapterRegistry()
        assertEquals(MinecraftAdapterRegistrationResult.Registered, registry.register(LegacyJavaRuntimeAdapter(listOf(certified))))
        val testResolver = MinecraftCompatibilityResolver(registry)

        val runtime = javaRuntime(
            version = "1.7.10",
            loader = MinecraftLoader.FORGE,
            loaderVersion = "10.13.4.1614",
            fabricApi = null,
            javaMajor = 8,
            bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
        )
        val result = testResolver.resolveRuntime(runtime)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, result.status)
        assertTrue(result.canExecute)
        assertEquals("java-legacy-experimental", result.adapterId?.value)

        // A runtime that is only *near* the certified identity is still rejected.
        val neighbour = testResolver.resolveRuntime(runtime.copy(version = MinecraftVersion.parse("1.7.2")))
        assertEquals(MinecraftCompatibilityStatus.UNSUPPORTED, neighbour.status)
        assertFalse(neighbour.canExecute)
    }

    // ------------------------------------------------------------------ block/state

    @Test
    fun blockStateMappingSupportsExactTranslationsAndNeverSubstitutes() {
        val catalog = MinecraftTargetBlockStateCatalog(
            listOf(
                MinecraftTargetBlockStateMapping(
                    blockId = "minecraft:test_axis_block",
                    stateTranslations = mapOf(
                        "axis" to BlockStateTranslation("axis", mapOf("x" to "x", "y" to "y", "z" to "z")),
                    ),
                    verification = BlockStateMappingVerification.RUNTIME_VERIFIED,
                ),
            ),
        )

        assertEquals(
            MinecraftBlockStateResolution.Supported("minecraft:test_axis_block", mapOf("axis" to "x")),
            catalog.resolve("minecraft:test_axis_block", mapOf("axis" to "x")),
        )
        // Unmapped block: no substitution with a mapped one.
        assertEquals(
            MinecraftBlockStateResolution.UnsupportedBlock("minecraft:oak_planks"),
            catalog.resolve("minecraft:oak_planks", emptyMap()),
        )
        // Mapped block, unmapped value: reported, never guessed.
        assertEquals(
            MinecraftBlockStateResolution.UnsupportedState("minecraft:test_axis_block", listOf("axis")),
            catalog.resolve("minecraft:test_axis_block", mapOf("axis" to "sideways")),
        )
        // The shipped legacy catalog declares nothing and fails closed for everything.
        assertFalse(MinecraftTargetBlockStateCatalog.EMPTY.isDeclared)
        assertEquals(
            MinecraftBlockStateResolution.UnsupportedBlock("minecraft:stone"),
            MinecraftTargetBlockStateCatalog.EMPTY.resolve("minecraft:stone", emptyMap()),
        )

        // Verification is mandatory and duplicates are rejected.
        assertFailsWithIllegalArgument {
            MinecraftTargetBlockStateCatalog(
                listOf(MinecraftTargetBlockStateMapping("minecraft:test", verification = BlockStateMappingVerification.NOT_VERIFIED)),
            )
        }
        assertFailsWithIllegalArgument {
            MinecraftTargetBlockStateCatalog(
                listOf(
                    MinecraftTargetBlockStateMapping("minecraft:test", verification = BlockStateMappingVerification.RUNTIME_VERIFIED),
                    MinecraftTargetBlockStateMapping("minecraft:test", verification = BlockStateMappingVerification.DOCUMENTED_ONLY),
                ),
            )
        }
    }

    @Test
    fun legacyContentIsCheckedAgainstTheDeclaredCatalogAndFailsClosedWhenUnmapped() {
        val mappedCatalog = MinecraftTargetBlockStateCatalog(
            listOf(
                MinecraftTargetBlockStateMapping(
                    blockId = "minecraft:oak_planks",
                    verification = BlockStateMappingVerification.RUNTIME_VERIFIED,
                ),
            ),
        )
        val profile = legacyProfile(blockStateCatalog = mappedCatalog, blockStateSupportRevision = "test-rev-1")
        val registry = MinecraftAdapterRegistry()
        registry.register(LegacyJavaRuntimeAdapter(listOf(profile)))
        val testResolver = MinecraftCompatibilityResolver(registry)
        val runtime = javaRuntime(
            version = "1.7.10",
            loader = MinecraftLoader.FORGE,
            loaderVersion = "10.13.4.1614",
            fabricApi = null,
            javaMajor = 8,
            bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
        )

        val supportedContentPlan = fixture.semanticPlan().copy(
            operations = listOf(fixture.operation(0, "minecraft:oak_planks", 0, 0, 0, "house")),
        )
        val supported = testResolver.resolve(supportedContentPlan, runtime)
        assertTrue(supported.planContentSupported)
        assertFalse(MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK in supported.reasonCodes)
        // Still not executable: mapped content does not create runtime certification.
        assertFalse(supported.canExecute)

        val unmappedContentPlan = fixture.semanticPlan().copy(
            operations = listOf(fixture.operation(0, "minecraft:oak_stairs", 0, 0, 0, "house")),
        )
        val unmapped = testResolver.resolve(unmappedContentPlan, runtime)
        assertFalse(unmapped.planContentSupported)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_BLOCK in unmapped.reasonCodes)
        assertTrue(unmapped.diagnostics.any { it.blockId == "minecraft:oak_stairs" })
        assertEquals("UNSUPPORTED_BLOCK", unmapped.failureReasonCode())
    }

    // ------------------------------------------------------------------ BuildPlan compatibility

    @Test
    fun legacyRuntimesRejectUntestedSchemasOversizedPlansAndInvalidContent() {
        val runtime = legacyForge1710Runtime()

        val legacySchema = resolver.resolve(fixture.legacyPlan(), runtime)
        assertFalse(legacySchema.planWithinLimits)
        assertTrue(MinecraftCompatibilityReasonCode.UNSUPPORTED_BUILDPLAN_SCHEMA in legacySchema.reasonCodes)
        assertEquals("BUILD_PLAN_SCHEMA_UNSUPPORTED", legacySchema.failureReasonCode())
        assertFalse(legacySchema.canExecute)

        val oversized = fixture.semanticPlan().copy(
            operations = List(BuildPlanLimits.MAX_OPERATIONS + 1) { index ->
                fixture.operation(index, "minecraft:stone", index % 64, 0, index / 64, "house")
            },
        )
        val oversizedResult = resolver.resolve(oversized, runtime)
        assertFalse(oversizedResult.planWithinLimits)
        assertTrue(MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in oversizedResult.reasonCodes)
        assertEquals("LIMIT_EXCEEDED", oversizedResult.failureReasonCode())

        // A runtime that reports a lower limit than the profile is honoured; limits are never loosened.
        val tighterRuntime = legacyForge1710Runtime(maxOps = 2)
        val tighter = resolver.resolve(fixture.semanticPlan(), tighterRuntime)
        assertFalse(tighter.planWithinLimits)
        assertEquals(2, tighter.limits.maximumValidatedOperations)
        assertTrue(MinecraftCompatibilityReasonCode.PLAN_LIMIT_EXCEEDED in tighter.reasonCodes)

        // A bridge that does not report a usable limit fails closed rather than defaulting.
        val unreportedLimits = resolver.resolve(fixture.semanticPlan(), legacyForge1710Runtime(maxOps = null))
        assertFalse(unreportedLimits.planWithinLimits)
        assertTrue(unreportedLimits.reasons.any { it.contains("usable operation limit") })
        assertFalse(unreportedLimits.canExecute)

        // Invalid placement content is never silently dropped or substituted.
        val invalidContent = fixture.semanticPlan().copy(
            operations = listOf(fixture.operation(0, "not a block id", 0, 0, 0, "house")),
        )
        val invalidContentResult = resolver.resolve(invalidContent, runtime)
        assertFalse(invalidContentResult.planContentSupported)
        assertFalse(invalidContentResult.canExecute)
    }

    // ------------------------------------------------------------------ security boundaries

    @Test
    fun legacyResolutionEnforcesTheSharedDescriptorAndCapabilityValidation() {
        // Malformed/oversized tokens fail closed as invalid descriptors.
        listOf(
            legacyForge1710Runtime(loaderVersion = "a".repeat(65)),
            legacyForge1710Runtime(bridgeVersion = "b".repeat(65)),
            legacyForge1710Runtime(loaderVersion = "a b c!*"),
        ).forEach { runtime ->
            val result = resolver.resolveRuntime(runtime)
            assertEquals(MinecraftCompatibilityStatus.UNKNOWN, result.status)
            assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in result.reasonCodes)
            assertFalse(result.canExecute)
        }

        // An oversized schema-version list is rejected.
        val oversizedSchema = resolver.resolveRuntime(
            legacyForge1710Runtime(schemaVersions = (1..9).toSet()),
        )
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, oversizedSchema.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in oversizedSchema.reasonCodes)

        // A production release runtime must not claim legacy/experimental limitations.
        val withLimitations = resolver.resolveRuntime(
            javaRuntime(version = "1.20.1", limitations = setOf(MinecraftRuntimeLimitation.NO_ROLLBACK)),
        )
        assertEquals(MinecraftCompatibilityStatus.UNKNOWN, withLimitations.status)
        assertTrue(MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR in withLimitations.reasonCodes)
        assertTrue(withLimitations.reasons.any { it.contains("may report Bedrock integration limitations") })

        // Capabilities are only what the authenticated bridge reported; UNKNOWN never counts.
        val unknownCapability = resolver.resolveRuntime(
            legacyForge1710Runtime(capabilities = setOf(MinecraftCapability.UNKNOWN)),
        )
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, unknownCapability.status)
        assertTrue(unknownCapability.capabilities.isEmpty())
        assertTrue(MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in unknownCapability.reasonCodes)
        assertFalse(unknownCapability.canExecute)

        // A missing required capability keeps the result non-executable even when the profile is certified.
        val certified = legacyProfile(
            status = MinecraftCompatibilityStatus.SUPPORTED,
            certification = MinecraftRuntimeCertification.RUNTIME_TESTED,
        )
        val registry = MinecraftAdapterRegistry()
        registry.register(LegacyJavaRuntimeAdapter(listOf(certified)))
        val missingCapability = MinecraftCompatibilityResolver(registry).resolve(
            javaRuntime(
                version = "1.7.10",
                loader = MinecraftLoader.FORGE,
                loaderVersion = "10.13.4.1614",
                fabricApi = null,
                javaMajor = 8,
                bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
                capabilities = fullCapabilities() - MinecraftCapability.CANCELLATION,
            ),
            BuildPlanRequirements.runtimeExecution,
        )
        assertFalse(missingCapability.canExecute)
        assertTrue(MinecraftCompatibilityReasonCode.MISSING_CAPABILITY in missingCapability.reasonCodes)
    }

    @Test
    fun legacyExecutionMembersRefuseBeforeAnyBridgeCall() {
        val adapter = LegacyJavaRuntimeAdapter()
        val runtime = legacyForge1710Runtime()
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, resolver.resolveRuntime(runtime).status)

        val bridge = RecordingBridge()
        kotlinx.coroutines.runBlocking {
            try {
                adapter.preflight(bridge, fixture.record(), "execution-1")
                fail("legacy preflight must not be available")
            } catch (failure: MinecraftBridgeFailure) {
                assertEquals(LegacyJavaRuntimeAdapter.RUNTIME_NOT_CERTIFIED, failure.reasonCode)
            }
        }
        assertEquals(0, bridge.prepareCalls)
    }

    // ------------------------------------------------------------------ helpers

    private class RecordingBridge : MinecraftBridgePairingRepository {
        var prepareCalls = 0

        override val connectionState = MutableStateFlow<BridgeConnectionState>(BridgeConnectionState.Disconnected)
        override val profile: Flow<TrustedMinecraftBridge?> = emptyFlow()
        override suspend fun pair(host: String, port: Int, tlsFingerprint: String, pairingCode: String) = error("not used")
        override suspend fun connect() = error("not used")
        override suspend fun disconnect() = error("not used")
        override suspend fun refreshCapabilities() = error("not used")
        override suspend fun prepareExecution(record: com.craftmind.app.domain.buildplan.LocalBuildRecord, executionId: String) =
            error("legacy execution must never reach the bridge")

        override suspend fun startExecution(preview: com.craftmind.app.domain.minecraft.MinecraftExecutionPreview) = error("not used")
        override suspend fun queryExecution(executionId: String) = error("not used")
        override suspend fun cancelExecution(executionId: String) = error("not used")
        override suspend fun revoke() = error("not used")
        override suspend fun forgetLocally() = error("not used")
    }

    private fun productionOnlyResolver(): MinecraftCompatibilityResolver {
        val registry = MinecraftAdapterRegistry()
        assertEquals(MinecraftAdapterRegistrationResult.Registered, registry.register(JavaFabric1201Adapter()))
        return MinecraftCompatibilityResolver(registry)
    }

    private fun assertInvalid(profile: SupportedMinecraftRuntimeDescriptor, expectedReasonFragment: String) {
        val result = MinecraftAdapterRegistry().register(LegacyJavaRuntimeAdapter(listOf(profile)))
        assertTrue("expected rejection, got $result", result is MinecraftAdapterRegistrationResult.InvalidRuntimeProfile)
        assertTrue(
            "expected reason about $expectedReasonFragment, got $result",
            (result as MinecraftAdapterRegistrationResult.InvalidRuntimeProfile).reason.contains(expectedReasonFragment),
        )
    }

    private fun assertRegistered(profile: SupportedMinecraftRuntimeDescriptor) {
        val result = MinecraftAdapterRegistry().register(LegacyJavaRuntimeAdapter(listOf(profile)))
        assertEquals(MinecraftAdapterRegistrationResult.Registered, result)
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected an IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected: an unverifiable mapping can never be constructed
        }
    }

    private fun fullCapabilities(): Set<MinecraftCapability> = setOf(
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

    private fun javaRuntime(
        version: String,
        loader: MinecraftLoader = MinecraftLoader.FABRIC,
        loaderVersion: String = "0.16.10",
        javaMajor: Int? = 17,
        fabricApi: String? = "0.92.2+1.20.1",
        bridgeVersion: String = "1.2.0",
        protocol: Int = BridgeProtocol.VERSION,
        capabilities: Set<MinecraftCapability> = fullCapabilities(),
        schemaVersions: Set<Int> = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maxOps: Int? = BridgeProtocol.MAX_OPERATIONS,
        limitations: Set<MinecraftRuntimeLimitation> = emptySet(),
    ) = MinecraftRuntimeDescriptor(
        appVersion = "1.0.0",
        edition = MinecraftEdition.JAVA,
        version = MinecraftVersion.parse(version),
        javaRuntimeMajor = javaMajor,
        loader = loader,
        loaderVersion = loaderVersion,
        fabricApiVersion = fabricApi,
        bridgeProtocolVersion = protocol,
        bridgeVersion = bridgeVersion,
        capabilities = capabilities,
        supportedBuildPlanSchemaVersions = schemaVersions,
        maximumValidatedOperations = maxOps,
        maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
        maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
        worldAvailable = true,
        operatorOriginAvailable = true,
        limitations = limitations,
    )

    private fun legacyForge1710Runtime(
        javaMajor: Int = 8,
        bridgeVersion: String = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
        protocol: Int = BridgeProtocol.VERSION,
        loaderVersion: String = "10.13.4.1614",
        capabilities: Set<MinecraftCapability> = fullCapabilities(),
        schemaVersions: Set<Int> = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
        maxOps: Int? = BridgeProtocol.MAX_OPERATIONS,
    ) = javaRuntime(
        version = "1.7.10",
        loader = MinecraftLoader.FORGE,
        loaderVersion = loaderVersion,
        fabricApi = null,
        javaMajor = javaMajor,
        bridgeVersion = bridgeVersion,
        protocol = protocol,
        capabilities = capabilities,
        schemaVersions = schemaVersions,
        maxOps = maxOps,
        limitations = LegacyRuntimeProfileRegistry.declaredLimitations(),
    )

    private fun legacyProfile(
        version: String = "1.7.10",
        loader: MinecraftLoader = MinecraftLoader.FORGE,
        loaderVersion: String = "10.13.4.1614",
        javaMajor: Int = 8,
        releaseChannel: MinecraftVersionChannel = MinecraftVersionChannel.LEGACY,
        status: MinecraftCompatibilityStatus = MinecraftCompatibilityStatus.EXPERIMENTAL,
        certification: MinecraftRuntimeCertification = MinecraftRuntimeCertification.NOT_PERFORMED,
        limitations: Set<MinecraftRuntimeLimitation> = setOf(MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED),
        contentValidationMode: MinecraftContentValidationMode = MinecraftContentValidationMode.APP_SIDE_MAPPING,
        blockStateCatalog: MinecraftTargetBlockStateCatalog = MinecraftTargetBlockStateCatalog.EMPTY,
        blockStateSupportRevision: String = MinecraftTargetBlockStateCatalog.NONE_DECLARED_REVISION,
    ) = SupportedMinecraftRuntimeDescriptor(
        adapterId = MinecraftAdapterId(LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID),
        edition = MinecraftEdition.JAVA,
        version = MinecraftVersion.parse(version),
        loader = loader,
        loaderVersion = loaderVersion,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
        javaRuntimeRequirement = JavaRuntimeRequirement(
            requiredMajor = javaMajor,
            minimumSupportedMajor = javaMajor,
            maximumSupportedMajor = javaMajor,
        ),
        requiredFabricApiVersion = if (loader == MinecraftLoader.FABRIC) "0.92.2+1.20.1" else null,
        supportStatus = status,
        releaseChannel = releaseChannel,
        runtimeCertification = certification,
        limitations = limitations,
        blockStateSupportRevision = blockStateSupportRevision,
        contentValidationMode = contentValidationMode,
        blockStateCatalog = blockStateCatalog,
        maximumValidatedOperations = BuildPlanLimits.MAX_OPERATIONS,
        maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
        maximumOperationsPerTick = BridgeProtocol.MAX_OPERATIONS_PER_TICK,
        maximumExecutionSeconds = BridgeProtocol.MAX_EXECUTION_SECONDS,
        maximumDimensions = MinecraftDimensionLimits(
            width = BuildPlanLimits.MAX_BUILD_WIDTH,
            height = BuildPlanLimits.MAX_BUILD_HEIGHT,
            depth = BuildPlanLimits.MAX_BUILD_DEPTH,
        ),
    )
}
