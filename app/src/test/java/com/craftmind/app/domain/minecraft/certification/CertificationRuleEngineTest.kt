package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeProfileRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 §1 and §3: the centralized certification ladder and the single decision engine.
 *
 * The ladder is ordered and deterministic, and the engine is the only place a decision is made. These tests pin the
 * behaviour that keeps certification honest: a passing adapter, resolver, unit test, simulated run, BuildPlan
 * validation, or syntactically valid capability report never certifies anything; only complete real-runtime evidence in
 * a policy-declared environment does; and the project's shipped Java 1.20.1 production record stays exactly as shipped,
 * reported separately from evidence this phase established.
 */
class CertificationRuleEngineTest {
    private val engine = MinecraftCertificationRuleEngine()
    private val declaredEnvironment = "declared-certification-lab"
    private val declaredPolicy = MinecraftCertificationPolicy.DEFAULT.copy(
        realRuntimeCapableEnvironments = setOf(declaredEnvironment),
    )
    private val declaredEngine = MinecraftCertificationRuleEngine(declaredPolicy)

    // --------------------------------------------------------------------------------------------- §1 the ladder

    @Test
    fun theCertificationLadderIsOrderedDeterministicAndNeverCollapses() {
        val ladder = MinecraftRuntimeCertification.entries.toList()
        assertEquals(
            listOf(
                "NOT_PERFORMED", "STATIC_ONLY", "UNIT_TESTED", "BRIDGE_TESTED",
                "SIMULATED_E2E_VERIFIED", "RUNTIME_TESTED", "CERTIFIED",
            ),
            ladder.map { it.name },
        )
        // Deterministic ordering by explicit rank, not by declaration accident.
        assertEquals((0 until ladder.size).toList(), ladder.map { it.evidenceRank })
        ladder.forEach { certification ->
            assertTrue(certification.atLeast(MinecraftRuntimeCertification.NOT_PERFORMED))
            assertTrue(certification.atMost(MinecraftRuntimeCertification.CERTIFIED))
            assertTrue(certification.atLeast(certification))
        }
        assertTrue(MinecraftRuntimeCertification.CERTIFIED.atLeast(MinecraftRuntimeCertification.RUNTIME_TESTED))
        assertFalse(MinecraftRuntimeCertification.RUNTIME_TESTED.atLeast(MinecraftRuntimeCertification.CERTIFIED))
        assertTrue(
            MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL),
        )
        assertFalse(
            MinecraftRuntimeCertification.RUNTIME_TESTED.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL),
        )

        // The simulated ceiling is real evidence about CraftMind, and still never authorizes support.
        assertTrue(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.isSimulatedEvidence)
        assertFalse(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.isRealRuntimeEvidence)
        assertFalse(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.authorizesSupport)
        assertFalse(MinecraftRuntimeCertification.SIMULATED_E2E_VERIFIED.authorizesBedrockSupport)
        assertTrue(MinecraftRuntimeCertification.RUNTIME_TESTED.isRealRuntimeEvidence)
        assertTrue(MinecraftRuntimeCertification.RUNTIME_TESTED.authorizesSupport)
        assertTrue(MinecraftRuntimeCertification.RUNTIME_TESTED.authorizesBedrockSupport)
        assertTrue(MinecraftRuntimeCertification.CERTIFIED.authorizesSupport)
        assertFalse("only a real Bedrock runtime test authorizes Bedrock", MinecraftRuntimeCertification.CERTIFIED.authorizesBedrockSupport)
        ladder.forEach { certification ->
            assertEquals(
                "only runtime-tested/certified rungs authorize SUPPORTED",
                certification == MinecraftRuntimeCertification.RUNTIME_TESTED ||
                    certification == MinecraftRuntimeCertification.CERTIFIED,
                certification.authorizesSupport,
            )
        }
    }

    @Test
    fun theShippedPolicyRequiresEveryCategoryAndDeclaresNoRealRuntimeEnvironment() {
        val policy = MinecraftCertificationPolicy.DEFAULT
        assertEquals(MinecraftVerificationCategory.entries.toSet(), policy.requiredCategoriesForCertification)
        assertEquals(
            MinecraftVerificationCategory.entries.toSet() -
                MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION -
                MinecraftVerificationCategory.CERTIFICATION_DECISION,
            policy.requiredCategoriesForSimulatedEvidence,
        )
        assertTrue(
            "this repository must not declare any real-runtime capable environment",
            policy.realRuntimeCapableEnvironments.isEmpty(),
        )
        assertEquals(
            setOf(
                MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED,
                MinecraftRuntimeLimitation.LEGACY_BRIDGE_INTERFACE_UNVERIFIED,
                MinecraftRuntimeLimitation.BLOCK_STATE_MAPPING_NOT_VERIFIED,
            ),
            policy.blockingLimitations,
        )
        assertEquals(setOf("java-fabric-1.20.1"), policy.shippedProductionRecords.keys)
        val record = policy.shippedProductionRecords.getValue("java-fabric-1.20.1")
        assertEquals(MinecraftRuntimeCertification.CERTIFIED, record.certification)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, record.declaredStatus)
        assertTrue(
            "the shipped record must state that Phase 14 added no new runtime evidence",
            record.policyStatement.contains("no new real-Minecraft runtime test"),
        )
    }

    // ---------------------------------------------------------------------------------------- §3 decision engine

    @Test
    fun noEvidenceIsNeverCertified() {
        val evaluation = engine.evaluate(null)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, evaluation.evidenceLevel)
        assertEquals(MinecraftEvidenceMode.NONE, evaluation.evidenceMode)
        assertEquals(MinecraftCertificationSource.NONE, evaluation.source)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.realRuntimeTested)
        assertFalse(evaluation.isCertified)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, evaluation.maximumClaimableStatus)
        assertTrue(MinecraftCertificationReasonCode.NO_EVIDENCE in evaluation.reasonCodes)
        assertTrue(MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED in evaluation.reasonCodes)
        assertNull(evaluation.evidence)
        assertTrue(evaluation.reasons.isNotEmpty())
    }

    @Test
    fun completeSimulatedEvidenceNeverCertifies() {
        val evidence = CertificationEvidenceFixtures.simulated()
        val evaluation = engine.evaluate(evidence)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertEquals(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL, evaluation.evidenceLevel)
        assertEquals(MinecraftEvidenceMode.SIMULATED, evaluation.evidenceMode)
        assertEquals(MinecraftCertificationSource.SIMULATED_TEST_RUN, evaluation.source)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.realRuntimeTested)
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, evaluation.maximumClaimableStatus)
        assertTrue(MinecraftCertificationReasonCode.SIMULATED_EVIDENCE_ONLY in evaluation.reasonCodes)
        assertTrue(MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED in evaluation.reasonCodes)
        assertEquals(evidence, evaluation.evidence)
    }

    @Test
    fun aSimulatedRunThatClaimsRealRuntimeIntegrationStillCannotCertify() {
        // A higher-level pass must never auto-certify: even a simulated record that claims every category passed is
        // capped, because the evidence mode and the real-runtime flag are what the engine trusts.
        val evidence = CertificationEvidenceFixtures.simulated(categoryOutcomes = CertificationEvidenceFixtures.ALL_PASSED)
        val evaluation = engine.evaluate(evidence)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertTrue(MinecraftCertificationReasonCode.SIMULATED_EVIDENCE_ONLY in evaluation.reasonCodes)
        assertFalse(evaluation.authorizesExecution)
        assertTrue(evaluation.evidenceLevel.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL))
    }

    @Test
    fun missingCategoriesProduceInsufficientEvidenceNamingExactlyWhatIsMissing() {
        val withoutProtocol = CertificationEvidenceFixtures.simulated(
            categoryOutcomes = CertificationEvidenceFixtures.simulatedOutcomes().toMutableMap().apply {
                put(MinecraftVerificationCategory.PROTOCOL, MinecraftVerificationOutcome.NOT_RUN)
            },
        )
        val protocolEvaluation = engine.evaluate(withoutProtocol)
        assertEquals(MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE, protocolEvaluation.decision)
        assertTrue(MinecraftCertificationReasonCode.MISSING_PROTOCOL_VERIFICATION in protocolEvaluation.reasonCodes)
        assertFalse(protocolEvaluation.authorizesExecution)

        val withoutExecution = CertificationEvidenceFixtures.simulated(
            categoryOutcomes = CertificationEvidenceFixtures.simulatedOutcomes().toMutableMap().apply {
                put(MinecraftVerificationCategory.END_TO_END_EXECUTION, MinecraftVerificationOutcome.NOT_PERFORMED)
            },
        )
        val executionEvaluation = engine.evaluate(withoutExecution)
        assertEquals(MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE, executionEvaluation.decision)
        assertTrue(MinecraftCertificationReasonCode.MISSING_EXECUTION_VERIFICATION in executionEvaluation.reasonCodes)

        val withoutStatic = CertificationEvidenceFixtures.realRuntime(
            executionEnvironment = declaredEnvironment,
            categoryOutcomes = CertificationEvidenceFixtures.ALL_PASSED.toMutableMap().apply {
                put(MinecraftVerificationCategory.STATIC, MinecraftVerificationOutcome.NOT_AVAILABLE)
            },
        )
        val staticEvaluation = declaredEngine.evaluate(withoutStatic)
        assertEquals(MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE, staticEvaluation.decision)
        assertTrue(MinecraftCertificationReasonCode.MISSING_STATIC_VERIFICATION in staticEvaluation.reasonCodes)
        assertFalse("an incomplete real run must not certify", staticEvaluation.isCertified)
    }

    @Test
    fun aFailedCategoryAlwaysFailsTheDecision() {
        val failedUnit = CertificationEvidenceFixtures.simulated(
            categoryOutcomes = CertificationEvidenceFixtures.simulatedOutcomes().toMutableMap().apply {
                put(MinecraftVerificationCategory.UNIT, MinecraftVerificationOutcome.FAILED)
            },
        )
        val evaluation = engine.evaluate(failedUnit)
        assertEquals(MinecraftCertificationDecision.FAILED, evaluation.decision)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, evaluation.evidenceLevel)
        assertTrue(MinecraftCertificationReasonCode.CATEGORY_FAILED in evaluation.reasonCodes)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.realRuntimeTested)
        assertEquals(setOf(MinecraftVerificationCategory.UNIT), failedUnit.failedCategories)
    }

    @Test
    fun aDeclaredLimitationThatSaysUnverifiedBlocksCertification() {
        val blocked = CertificationEvidenceFixtures.realRuntime(
            executionEnvironment = declaredEnvironment,
            knownLimitations = setOf(MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED),
        )
        val evaluation = declaredEngine.evaluate(blocked)
        assertEquals(MinecraftCertificationDecision.BLOCKED_BY_LIMITATION, evaluation.decision)
        assertTrue(MinecraftCertificationReasonCode.BLOCKING_LIMITATION in evaluation.reasonCodes)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.isCertified)
    }

    @Test
    fun executionDepthIsVerifiedRatherThanAssumed() {
        fun realWith(execution: MinecraftExecutionVerificationEvidence) = CertificationEvidenceFixtures.realRuntime(
            executionEnvironment = declaredEnvironment,
            executionEvidence = execution,
        )

        val neverWrote = declaredEngine.evaluate(
            realWith(
                execution(
                    setOf(
                        MinecraftExecutionVerificationStage.REQUEST_ACCEPTED,
                        MinecraftExecutionVerificationStage.PRECHECK_PASSED,
                        MinecraftExecutionVerificationStage.EXECUTION_STARTED,
                    ),
                ),
            ),
        )
        assertEquals(MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE, neverWrote.decision)
        assertTrue(MinecraftCertificationReasonCode.EXECUTION_DID_NOT_WRITE_BLOCKS in neverWrote.reasonCodes)
        assertTrue(MinecraftCertificationReasonCode.EXECUTION_DID_NOT_COMPLETE in neverWrote.reasonCodes)
        assertFalse("acceptance is not execution", neverWrote.authorizesExecution)

        val neverCompleted = declaredEngine.evaluate(
            realWith(
                execution(
                    setOf(
                        MinecraftExecutionVerificationStage.REQUEST_ACCEPTED,
                        MinecraftExecutionVerificationStage.PRECHECK_PASSED,
                        MinecraftExecutionVerificationStage.EXECUTION_STARTED,
                        MinecraftExecutionVerificationStage.BLOCKS_WRITTEN,
                        MinecraftExecutionVerificationStage.PROGRESS_REPORTED,
                    ),
                ),
            ),
        )
        assertEquals(MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE, neverCompleted.decision)
        assertTrue(MinecraftCertificationReasonCode.EXECUTION_DID_NOT_COMPLETE in neverCompleted.reasonCodes)

        val worldStateNeverReadBack = declaredEngine.evaluate(
            realWith(
                execution(
                    MinecraftExecutionVerificationStage.progressOrder.toSet(),
                    worldStateVerified = false,
                ),
            ),
        )
        assertEquals(MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE, worldStateNeverReadBack.decision)
        assertTrue(MinecraftCertificationReasonCode.WORLD_STATE_NOT_VERIFIED in worldStateNeverReadBack.reasonCodes)

        val failedExecution = declaredEngine.evaluate(
            realWith(
                execution(
                    MinecraftExecutionVerificationStage.progressOrder.toSet() +
                        MinecraftExecutionVerificationStage.EXECUTION_FAILED,
                ),
            ),
        )
        assertFalse(failedExecution.isCertified)
        assertTrue(MinecraftCertificationReasonCode.EXECUTION_FAILED in failedExecution.reasonCodes)
        assertFalse(failedExecution.authorizesExecution)
    }

    @Test
    fun onlyCompleteRealRuntimeEvidenceInADeclaredEnvironmentCertifies() {
        val evidence = CertificationEvidenceFixtures.realRuntime(executionEnvironment = declaredEnvironment)
        val evaluation = declaredEngine.evaluate(evidence)
        assertEquals(MinecraftCertificationDecision.CERTIFIED, evaluation.decision)
        assertEquals(MinecraftRuntimeCertification.CERTIFIED, evaluation.evidenceLevel)
        assertEquals(MinecraftEvidenceMode.REAL_RUNTIME, evaluation.evidenceMode)
        assertEquals(MinecraftCertificationSource.REAL_RUNTIME_TEST_RUN, evaluation.source)
        assertTrue(evaluation.authorizesExecution)
        assertTrue(evaluation.realRuntimeTested)
        assertTrue(evaluation.isCertified)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, evaluation.maximumClaimableStatus)
        assertTrue(evaluation.reasonCodes.isEmpty())

        // The same evidence under the shipped policy certifies nothing: the gate is the policy, not the evidence shape.
        val shipped = engine.evaluate(evidence)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, shipped.decision)
        assertTrue(MinecraftCertificationReasonCode.ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME in shipped.reasonCodes)
        assertFalse(shipped.authorizesExecution)
        assertEquals(MinecraftEvidenceMode.SIMULATED, shipped.evidenceMode)
    }

    @Test
    fun evaluationIsDeterministicForIdenticalEvidence() {
        val evidence = CertificationEvidenceFixtures.simulated()
        val first = engine.evaluate(evidence)
        val second = engine.evaluate(evidence)
        assertEquals(first, second)
        assertEquals(first.reasonCodes.toList(), second.reasonCodes.toList())
        assertEquals(first.reasons, second.reasons)
        assertEquals(engine.evaluate(null), engine.evaluate(null))
    }

    // ------------------------------------------------------- §3 shipped record versus newly established evidence

    @Test
    fun theShippedProductionRecordIsKeptSeparateFromNewEvidence() {
        val profile = MinecraftRuntimeProfileRegistry.javaFabric1201
        val evaluation = engine.evaluateShippedRecord(profile)
        assertEquals(MinecraftCertificationDecision.CERTIFIED, evaluation.decision)
        assertEquals(MinecraftRuntimeCertification.CERTIFIED, evaluation.evidenceLevel)
        assertEquals(MinecraftCertificationSource.SHIPPED_PRODUCTION_RECORD, evaluation.source)
        assertEquals(MinecraftEvidenceMode.NONE, evaluation.evidenceMode)
        assertNull("a shipped record must not be dressed up as a new verification run", evaluation.evidence)
        assertTrue(evaluation.authorizesExecution)
        assertTrue(MinecraftCertificationReasonCode.SHIPPED_RECORD_ACCEPTED in evaluation.reasonCodes)
        assertEquals(MinecraftCompatibilityStatus.SUPPORTED, evaluation.maximumClaimableStatus)
        assertFalse(evaluation.overClaims(profile.supportStatus))
        assertNull(engine.auditDeclaredStatus(profile, evaluation))
        assertTrue(
            "the record must say Phase 14 established no new runtime evidence",
            evaluation.reasons.any { it.contains("no new real-Minecraft runtime test") },
        )
    }

    @Test
    fun everyOtherRegisteredProfileStaysUncertifiedAndNonExecutable() {
        val profiles = DefaultMinecraftCompatibility.resolver.registeredProfiles()
            .filter { it.adapterId.value != "java-fabric-1.20.1" }
        assertTrue("the registry must still contain the legacy profiles", profiles.isNotEmpty())
        profiles.forEach { profile ->
            val evaluation = engine.evaluateShippedRecord(profile)
            assertEquals("${profile.adapterId.value} must stay uncertified", MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
            assertFalse(evaluation.authorizesExecution)
            assertFalse(evaluation.realRuntimeTested)
            assertEquals(MinecraftEvidenceMode.NONE, evaluation.evidenceMode)
            assertEquals(MinecraftCertificationSource.NONE, evaluation.source)
            assertTrue(MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED in evaluation.reasonCodes)
            assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, evaluation.maximumClaimableStatus)
            assertNull(engine.auditDeclaredStatus(profile, evaluation))
            assertTrue(
                "${profile.adapterId.value} must declare why it is not verified",
                profile.limitations.isNotEmpty(),
            )
        }

        val bedrock = DefaultMinecraftCompatibility.resolver.registeredBedrockProfiles()
        assertTrue(bedrock.isNotEmpty())
        bedrock.forEach { contract ->
            val evaluation = engine.evaluateShippedBedrockContract(contract)
            assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
            assertFalse(evaluation.authorizesExecution)
            assertFalse(evaluation.realRuntimeTested)
            assertTrue(contract.certifiedMinecraftVersions.isEmpty())
            assertTrue(contract.limitations.isNotEmpty())
        }
    }

    @Test
    fun aDeclaredStatusThatExceedsTheEvidenceIsFlaggedAsAnOverclaim() {
        val simulated = engine.evaluate(CertificationEvidenceFixtures.simulated())
        assertTrue(simulated.overClaims(MinecraftCompatibilityStatus.SUPPORTED))
        assertFalse(simulated.overClaims(MinecraftCompatibilityStatus.EXPERIMENTAL))
        assertEquals(
            MinecraftCertificationReasonCode.DECLARED_STATUS_OVERCLAIM,
            engine.auditDeclaredStatus(MinecraftRuntimeProfileRegistry.javaFabric1201, simulated),
        )

        val nothing = engine.evaluate(null)
        assertTrue(nothing.overClaims(MinecraftCompatibilityStatus.SUPPORTED))
        assertEquals(MinecraftCompatibilityStatus.EXPERIMENTAL, nothing.maximumClaimableStatus)
    }

    // --------------------------------------------------------------------------------------------- helpers

    private fun execution(
        stages: Set<MinecraftExecutionVerificationStage>,
        worldStateVerified: Boolean = true,
    ) = MinecraftExecutionVerificationEvidence(
        mode = MinecraftEvidenceMode.REAL_RUNTIME,
        observedStages = stages,
        worldStateVerified = worldStateVerified,
        observedOperationCount = CertificationBuildPlan.OPERATION_COUNT,
        expectedOperationCount = CertificationBuildPlan.OPERATION_COUNT,
        cancellationVerified = true,
        reconnectVerified = true,
        notes = listOf("Fixture execution evidence for decision-engine tests."),
    )
}
