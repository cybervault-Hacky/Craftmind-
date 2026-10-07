package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.JavaFabric1201Adapter
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapter
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterId
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterRegistrationResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterRegistry
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCompatibilityGate
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.SupportedMinecraftRuntimeDescriptor
import com.craftmind.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 §4: the universal compatibility certification matrix, executed rather than merely written down.
 *
 * Every row is driven through the production pipeline (or, for rows the wire codec must reject first, through the
 * production detector on a descriptor-level report) and asserted field by field: detection status, selection status,
 * stable reason code, selected adapter, and executability. A row that stops being true is a failing test.
 */
class CertificationMatrixTest {
    private val resolver = DefaultMinecraftCompatibility.resolver
    private val detector = DefaultMinecraftCompatibility.detector

    @Test
    fun everyMatrixRowIsUniqueCoveredAndNeverClaimsRealRuntimeEvidence() {
        val rows = MinecraftCompatibilityCertificationMatrix.runtimeRows
        assertEquals(rows.size, rows.map { it.caseId }.distinct().size)
        assertTrue("the matrix must cover every runtime family", rows.size >= 30)
        rows.forEach { row ->
            assertTrue("${row.caseId} must declare applicable categories", row.applicableCategories.isNotEmpty())
            assertFalse(
                "${row.caseId} must not claim real-runtime evidence",
                MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION in row.applicableCategories,
            )
            assertFalse("${row.caseId} must not claim real-runtime coverage", row.isRealRuntimeCovered)
            assertTrue("${row.caseId} must state a summary", row.summary.isNotBlank())
        }
        assertTrue(MinecraftCompatibilityCertificationMatrix.rowsClaimingRealRuntimeEvidence().isEmpty())

        val executionCases = MinecraftCompatibilityCertificationMatrix.executionFailureCases
        assertEquals(executionCases.size, executionCases.map { it.caseId }.distinct().size)
        executionCases.forEach { case ->
            assertTrue("${case.caseId} must fail closed before any bridge write", case.expectedFailClosedBeforeBridgeWrite)
            assertTrue(case.expectedReasonCode.isNotBlank())
        }
        assertEquals(
            MinecraftCompatibilityCertificationMatrix.runtimeRows.size + executionCases.size,
            MinecraftCompatibilityCertificationMatrix.allCaseIds.distinct().size,
        )
    }

    @Test
    fun theMatrixCoversEveryRequiredUnsupportedAndSecurityFamily() {
        val required = listOf(
            "unknown version" to "unsupported-unknown-version",
            "unsupported java runtime" to "unsupported-java-runtime-21",
            "wrong loader" to "unsupported-wrong-loader-forge",
            "wrong fabric api" to "unsupported-wrong-fabric-api",
            "wrong protocol" to "invalid-protocol-downgrade",
            "wrong bridge version" to "unsupported-wrong-bridge-version",
            "wrong edition/loader combination" to "invalid-java-edition-with-bedrock-loader",
            "cross-edition mismatch" to "invalid-cross-edition-bedrock-with-fabric",
            "malformed runtime identity" to "invalid-malformed-runtime-identity",
            "forged capability" to "security-forged-capability",
            "missing limits" to "incomplete-missing-limits",
            "runtime identity changed" to "security-runtime-changed-after-prepare",
            "session changed" to "security-session-changed-after-prepare",
            "app version mismatch" to "security-app-version-mismatch",
            "ambiguous adapter" to "security-ambiguous-adapter",
            "unsupported release channel" to "unsupported-release-channel-snapshot",
            "snapshot" to "unsupported-release-channel-snapshot",
            "beta" to "unsupported-release-channel-beta",
            "alpha" to "unsupported-release-channel-alpha",
            "near version" to "unsupported-near-version-1.20.2",
        )
        val ids = MinecraftCompatibilityCertificationMatrix.runtimeRows.map { it.caseId }
        required.forEach { (family, caseId) ->
            assertTrue("matrix must cover $family ($caseId); have $ids", caseId in ids)
        }
        val executionIds = MinecraftCompatibilityCertificationMatrix.executionFailureCases.map { it.caseId }
        listOf(
            "execution-duplicate-execution-id", "execution-second-simultaneous-build", "execution-preflight-rejected",
            "execution-invalid-build-plan", "execution-unsupported-block", "execution-unsupported-block-state",
            "execution-cancellation", "execution-disconnect-during-build", "execution-stale-binding",
            "execution-schema-downgrade", "execution-limit-cannot-be-raised", "execution-authentication-failure",
            "execution-invalid-session", "execution-legacy-refusal", "execution-bedrock-refusal",
        ).forEach { caseId -> assertTrue("matrix must cover $caseId", caseId in executionIds) }
    }

    @Test
    fun everyMatrixRowProducesExactlyTheExpectedPipelineOutcome() = runBlocking {
        val failures = mutableListOf<String>()
        MinecraftCompatibilityCertificationMatrix.runtimeRows.forEach { row ->
            val outcome = runCatching { evaluate(row) }.getOrElse { error ->
                failures += "${row.caseId}: threw ${error.javaClass.simpleName}: ${error.message}"
                return@forEach
            }
            if (outcome.detectionStatus != row.expectedDetectionStatus) {
                failures += "${row.caseId}: detection ${outcome.detectionStatus} != ${row.expectedDetectionStatus}"
            }
            if (outcome.selectionStatus != row.expectedSelectionStatus) {
                failures += "${row.caseId}: selection ${outcome.selectionStatus} != ${row.expectedSelectionStatus}"
            }
            if (outcome.reasonCode != row.expectedReasonCode) {
                failures += "${row.caseId}: reason ${outcome.reasonCode} != ${row.expectedReasonCode}"
            }
            if (outcome.adapterId != row.expectedAdapterId) {
                failures += "${row.caseId}: adapter ${outcome.adapterId} != ${row.expectedAdapterId}"
            }
            // `expectedCanExecute` means "the pipeline authorizes execution end-to-end for this case". A fresh
            // resolution may still report canExecute=true for a TOCTOU row while authorization is denied, which is
            // exactly the protection being tested.
            if (outcome.authorized != row.expectedCanExecute) {
                failures += "${row.caseId}: authorized ${outcome.authorized} != expectedCanExecute ${row.expectedCanExecute}"
            }
            row.expectedCompatibilityReasonCode?.let { expected ->
                if (expected !in outcome.reasonCodes) {
                    failures += "${row.caseId}: expected structured reason $expected in ${outcome.reasonCodes}"
                }
            }
            if (row.mutation !in TOCTOU_MUTATIONS) {
                assertEquals("${row.caseId}: resolution executability", row.expectedCanExecute, outcome.canExecute)
            }
        }
        assertTrue("matrix failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun onlyTheShippedProductionRecordIsCertifiedAfterPhase14() {
        val engine = MinecraftCertificationRuleEngine()
        val production = resolver.registeredProfiles().single { it.adapterId.value == "java-fabric-1.20.1" }
        val productionEvaluation = engine.evaluateShippedRecord(production)
        assertEquals(MinecraftCertificationDecision.CERTIFIED, productionEvaluation.decision)
        assertEquals(MinecraftCertificationSource.SHIPPED_PRODUCTION_RECORD, productionEvaluation.source)
        assertTrue(productionEvaluation.authorizesExecution)
        assertNull(engine.auditDeclaredStatus(production, productionEvaluation))

        resolver.registeredProfiles()
            .filter { it.adapterId.value != "java-fabric-1.20.1" }
            .forEach { profile ->
                val evaluation = engine.evaluateShippedRecord(profile)
                assertEquals(
                    "profile ${profile.adapterId.value}",
                    MinecraftCertificationDecision.NOT_CERTIFIED,
                    evaluation.decision,
                )
                assertFalse(evaluation.authorizesExecution)
            }
        resolver.registeredBedrockProfiles().forEach { contract ->
            val evaluation = engine.evaluateShippedBedrockContract(contract)
            assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
            assertFalse(evaluation.authorizesExecution)
            assertFalse(evaluation.realRuntimeTested)
        }

        // Matrix expectations agree with the engine for the three real runtime families.
        MinecraftCompatibilityCertificationMatrix.runtimeRows.forEach { row ->
            val expected = when (row.group) {
                MinecraftCertificationMatrixGroup.JAVA_PRODUCTION -> MinecraftCertificationDecision.CERTIFIED
                else -> MinecraftCertificationDecision.NOT_CERTIFIED
            }
            assertEquals(row.caseId, expected, row.expectedProfileCertification)
        }
    }

    private companion object {
        val TOCTOU_MUTATIONS = setOf(
            MinecraftCertificationMatrixMutation.SESSION_CHANGED,
            MinecraftCertificationMatrixMutation.RUNTIME_CHANGED_AFTER_PREPARE,
            MinecraftCertificationMatrixMutation.WORLD_SESSION_CHANGED,
        )
    }

    // --------------------------------------------------------------------------------------------- driver

    private data class RowOutcome(
        val detectionStatus: com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionStatus?,
        val selectionStatus: com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelectionStatus?,
        val reasonCode: String?,
        val reasonCodes: Set<com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode>,
        val adapterId: String?,
        val canExecute: Boolean,
        val authorized: Boolean,
    )

    /** Descriptor/report-level rows are asserted through the production detector without the wire codec. */
    private fun descriptorLevelOutcome(row: MinecraftCertificationMatrixRow): RowOutcome {
        val base = MinecraftRuntimeProfileRegistry.javaFabric1201
        val descriptor = descriptorFor(row, base)
        val mutated = mutateDescriptor(row, descriptor)
        val report = mutateReport(
            row,
            com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport(
                descriptor = mutated,
                authenticated = row.mutation != MinecraftCertificationMatrixMutation.UNAUTHENTICATED,
                sessionId = if (row.mutation == MinecraftCertificationMatrixMutation.SESSION_IDENTITY_MISSING) {
                    null
                } else {
                    "matrix-session"
                },
                bridgeId = SimulatedCertificationBridge.TRUSTED_BRIDGE.bridgeId,
                identityFingerprint = SimulatedCertificationBridge.TRUSTED_BRIDGE.tlsFingerprint,
                authenticatedAtEpochMillis = SimulatedCertificationBridge.FIXED_AUTHENTICATED_AT,
                requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
                expectedBridgeId = SimulatedCertificationBridge.TRUSTED_BRIDGE.bridgeId,
                expectedIdentityFingerprint = SimulatedCertificationBridge.TRUSTED_BRIDGE.tlsFingerprint,
                dimensionId = "minecraft:overworld",
                worldSessionId = "world-session-certification",
            ),
        )
        val detection: MinecraftRuntimeDetectionResult = detector.detect(report)
        val selection = DefaultMinecraftCompatibility.selector.select(detection)
        val resolution = resolver.runtimeGate.resolve(report, com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements.runtimeExecution)
        return RowOutcome(
            detectionStatus = detection.status,
            selectionStatus = selection.status,
            reasonCode = resolution.failureReasonCode(),
            reasonCodes = resolution.reasonCodes,
            adapterId = selection.adapterId?.value,
            canExecute = resolution.canExecute,
            authorized = false,
        )
    }

    private suspend fun evaluate(row: MinecraftCertificationMatrixRow): RowOutcome =
        if (MinecraftVerificationCategory.SIMULATED_INTEGRATION !in row.applicableCategories) {
            descriptorLevelOutcome(row)
        } else {
            simulatedOutcome(row)
        }

    private suspend fun simulatedOutcome(row: MinecraftCertificationMatrixRow): RowOutcome {
        val bridge = SimulatedCertificationBridge(
            SimulatedCertificationBridge.TRUSTED_BRIDGE,
            facts = factsFor(row),
        )
        val gate = if (row.mutation == MinecraftCertificationMatrixMutation.AMBIGUOUS_ADAPTER_REGISTRY) {
            ambiguousGate()
        } else {
            resolver.runtimeGate
        }
        val previousBinding = when (row.mutation) {
            MinecraftCertificationMatrixMutation.SESSION_CHANGED,
            MinecraftCertificationMatrixMutation.RUNTIME_CHANGED_AFTER_PREPARE,
            MinecraftCertificationMatrixMutation.WORLD_SESSION_CHANGED,
            -> resolver.runtimeGate.resolveRuntime(baselineReport()).binding

            else -> null
        }
        if (row.mutation == MinecraftCertificationMatrixMutation.WORLD_SESSION_CHANGED) {
            bridge.changeWorldSession("world-session-after-move")
        }
        val pipeline = SimulatedCertificationPipeline(
            bridge = bridge,
            resolver = resolver,
            testRunId = "matrix-${row.caseId}",
        )
        val run = pipeline.run(
            SimulatedPipelineRequest(
                profileId = row.expectedAdapterId ?: "java-fabric-1.20.1",
                descriptorMutation = { mutateDescriptor(row, it) },
                reportMutation = { mutateReport(row, it) },
                previousBinding = previousBinding,
                stopAfterResolution = row.mutation == MinecraftCertificationMatrixMutation.NONE &&
                    !row.expectedCanExecute && row.expectedAdapterId == null,
                gate = gate,
            ),
        )
        return RowOutcome(
            detectionStatus = run.resolution?.detection?.status,
            selectionStatus = run.resolution?.selection?.status,
            reasonCode = when (row.mutation) {
                MinecraftCertificationMatrixMutation.SESSION_CHANGED,
                MinecraftCertificationMatrixMutation.RUNTIME_CHANGED_AFTER_PREPARE,
                MinecraftCertificationMatrixMutation.WORLD_SESSION_CHANGED,
                -> run.authorization?.reasonCode
                else -> run.effectiveReasonCode
            },
            reasonCodes = (run.resolution?.reasonCodes ?: emptySet()) +
                (run.authorization?.reasonCodes ?: emptySet()),
            adapterId = run.resolution?.selection?.adapterId?.value,
            canExecute = run.resolution?.canExecute == true,
            authorized = run.authorized,
        )
    }

    /**
     * A gate whose registry ends up with two adapters claiming the same exact runtime.
     *
     * The production registry refuses every overlap it can see at registration time, and that refusal is asserted
     * here too. The ambiguous situation is therefore introduced the only way it can arise in production: an adapter
     * that publishes a different profile set after it was registered. Selection must then block, never order.
     */
    private fun ambiguousGate(): MinecraftRuntimeCompatibilityGate {
        val production = JavaFabric1201Adapter()
        val secondId = MinecraftAdapterId("matrix-second-fabric-adapter")
        val second = MatrixMutableProfileAdapter(
            adapterId = secondId,
            delegate = production,
            profiles = listOf(
                MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = secondId, loaderVersion = "0.16.11"),
            ),
        )
        val registry = MinecraftAdapterRegistry().apply {
            assertEquals(MinecraftAdapterRegistrationResult.Registered, register(production))
            assertEquals(MinecraftAdapterRegistrationResult.Registered, register(second))

            // An overlapping registration is refused outright: ambiguity cannot be introduced honestly.
            val overlapId = MinecraftAdapterId("matrix-overlap-refused")
            val refused = register(
                MatrixMutableProfileAdapter(
                    adapterId = overlapId,
                    delegate = production,
                    profiles = listOf(MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = overlapId)),
                ),
            )
            assertTrue(
                "a second adapter claiming an already registered runtime must be refused, got $refused",
                refused is MinecraftAdapterRegistrationResult.DuplicateRuntimeProfile,
            )
        }

        // Now the misbehaving adapter republishes the production identity, creating the overlap post-registration.
        second.profiles = listOf(MinecraftRuntimeProfileRegistry.javaFabric1201.copy(adapterId = secondId))
        val ambiguousResolver = MinecraftCompatibilityResolver(registry)
        return MinecraftRuntimeCompatibilityGate(
            detector = com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetector(registry),
            selector = ambiguousResolver.adapterSelector,
            resolver = ambiguousResolver,
        )
    }

    /** Adapter whose published profiles can change after registration, mirroring a misbehaving adapter. */
    private class MatrixMutableProfileAdapter(
        override val adapterId: MinecraftAdapterId,
        private val delegate: MinecraftAdapter,
        profiles: List<SupportedMinecraftRuntimeDescriptor>,
    ) : MinecraftAdapter by delegate {
        var profiles: List<SupportedMinecraftRuntimeDescriptor> = profiles

        override val supportedRuntimeDescriptors: List<SupportedMinecraftRuntimeDescriptor> get() = profiles

        override val bedrockRuntimeProfiles: List<com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfile>
            get() = emptyList()

        override fun compatibilityCheck(
            runtime: com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor,
            requirements: com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements,
        ): com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult? =
            if (profiles.any { it.matches(runtime) }) {
                delegate.compatibilityCheck(runtime, requirements)?.copy(adapterId = adapterId)
            } else {
                null
            }
    }

    private fun baselineReport() = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        .authenticatedSnapshot()
        .runtimeReport(
            sessionId = SimulatedCertificationBridge.DEFAULT_SESSION_ID,
            requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
            authenticated = true,
            authenticatedAtEpochMillis = SimulatedCertificationBridge.FIXED_AUTHENTICATED_AT,
        )

    private fun factsFor(row: MinecraftCertificationMatrixRow) =
        SimulatedCertificationBridge.SimulatedRuntimeFacts(
            editionName = row.editionName,
            minecraftVersion = if (row.mutation == MinecraftCertificationMatrixMutation.RUNTIME_CHANGED_AFTER_PREPARE) {
                "1.19.4"
            } else {
                row.minecraftVersion
            },
            javaRuntimeMajor = row.javaRuntimeMajor ?: 17,
            loaderName = row.loaderName,
            loaderVersion = row.loaderVersion ?: "0.16.10",
            fabricApiVersion = row.fabricApiVersion,
            platformName = "DEDICATED_SERVER",
            platformVersion = if (row.editionName == "bedrock") "1.21.60.3" else null,
            limitations = if (row.editionName == "bedrock") {
                com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
                    .bedrockBridgeContract.limitations.map { it.name }
            } else {
                emptyList()
            },
            bridgeVersion = row.bridgeVersion,
            protocolVersion = BridgeProtocol.VERSION,
            capabilities = SimulatedCertificationBridge.FULL_CAPABILITIES,
        )

    private fun descriptorFor(
        row: MinecraftCertificationMatrixRow,
        base: SupportedMinecraftRuntimeDescriptor,
    ): MinecraftRuntimeDescriptor {
        val capabilities = SimulatedCertificationBridge.FULL_CAPABILITIES
        val originAvailable = MinecraftCapability.ORIGIN_RESOLUTION in capabilities
        return MinecraftRuntimeDescriptor(
            appVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
            edition = MinecraftCompatibilityCertificationMatrix.editionForRow(row),
            version = com.craftmind.app.domain.minecraft.compatibility.MinecraftVersion.parse(row.minecraftVersion),
            platform = if (row.editionName == "bedrock") {
                com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform.DEDICATED_SERVER
            } else {
                com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimePlatform.UNKNOWN
            },
            platformVersion = if (row.editionName == "bedrock") "1.21.60.3" else null,
            javaRuntimeMajor = if (row.editionName == "bedrock") null else row.javaRuntimeMajor,
            loader = MinecraftCompatibilityCertificationMatrix.loaderForRow(row),
            loaderVersion = if (row.editionName == "bedrock") null else row.loaderVersion,
            fabricApiVersion = row.fabricApiVersion,
            bridgeProtocolVersion = row.bridgeProtocolVersion,
            bridgeVersion = row.bridgeVersion,
            capabilities = capabilities,
            supportedBuildPlanSchemaVersions = setOf(BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION),
            maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS,
            maximumRequestBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
            maximumOperationsPerTick = 32,
            maximumExecutionSeconds = 300,
            worldAvailable = MinecraftCapability.WORLD_ACCESS in capabilities,
            operatorOriginAvailable = originAvailable,
            limitations = base.limitations,
        )
    }

    private fun mutateDescriptor(
        row: MinecraftCertificationMatrixRow,
        descriptor: MinecraftRuntimeDescriptor,
    ): MinecraftRuntimeDescriptor = when (row.mutation) {
        MinecraftCertificationMatrixMutation.APP_VERSION_MISMATCH -> descriptor.copy(appVersion = "0.9.0")
        MinecraftCertificationMatrixMutation.PROTOCOL_DOWNGRADE -> descriptor.copy(bridgeProtocolVersion = 1)
        MinecraftCertificationMatrixMutation.FORGED_CAPABILITY ->
            descriptor.copy(capabilities = descriptor.capabilities - MinecraftCapability.WORLD_ACCESS)

        MinecraftCertificationMatrixMutation.UNDECLARED_CAPABILITY ->
            descriptor.copy(capabilities = descriptor.capabilities + MinecraftCapability.UNKNOWN)

        MinecraftCertificationMatrixMutation.MISSING_LIMITS -> descriptor.copy(
            maximumValidatedOperations = null,
            maximumRequestBytes = null,
        )

        MinecraftCertificationMatrixMutation.MISSING_JAVA_RUNTIME -> descriptor.copy(javaRuntimeMajor = null)
        else -> descriptor
    }

    private fun mutateReport(
        row: MinecraftCertificationMatrixRow,
        report: com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport,
    ): com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport = when (row.mutation) {
        MinecraftCertificationMatrixMutation.UNAUTHENTICATED -> report.copy(authenticated = false)
        MinecraftCertificationMatrixMutation.SESSION_IDENTITY_MISSING -> report.copy(sessionId = null)
        MinecraftCertificationMatrixMutation.SESSION_CHANGED -> report.copy(sessionId = "matrix-session-after-reconnect")
        MinecraftCertificationMatrixMutation.BRIDGE_IDENTITY_CHANGED ->
            report.copy(expectedBridgeId = "bridge-ffffffffffffffffffffffffffffffff")

        MinecraftCertificationMatrixMutation.OVERSIZED_REPORT -> report.copy(
            reportedBytes = com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport.MAXIMUM_REPORT_BYTES + 1,
        )

        else -> report
    }
}
