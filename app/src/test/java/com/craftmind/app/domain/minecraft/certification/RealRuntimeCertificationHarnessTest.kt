package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 §6 and §15: the real-runtime certification hook, and the boundary around it.
 *
 * This repository has no Minecraft runtime, no network access, and no download path, so the shipped provider supplies
 * no host and every real-runtime step is reported `NOT_PERFORMED`. The tests here prove three things:
 *
 * 1. without a host, the harness performs nothing, claims nothing, and certifies nothing;
 * 2. a host that *claims* to be real still cannot mint certification unless the certification policy declares its
 *    environment real-runtime capable — and the shipped policy declares none, so no fake or simulated host can
 *    upgrade itself;
 * 3. when genuine, complete real-runtime evidence is presented in a policy-declared environment, the same harness and
 *    the same engine do certify. The refusal in (2) is a policy gate, not a hard-coded impossibility.
 *
 * The stub host below is a *test double for the hook*, never a fake Minecraft server: it is only used to prove the
 * harness's own logic, and every assertion states that no real runtime was involved.
 */
class RealRuntimeCertificationHarnessTest {
    private val profileId = "java-fabric-1.20.1"

    @Test
    fun withoutARealRuntimeHostNothingIsPerformedAndNothingIsCertified() = runBlocking {
        val harness = MinecraftRealRuntimeCertificationHarness()
        val result = harness.certify(profileId = profileId, testRunId = "real-runtime-not-performed")

        assertNull("the shipped provider must not supply a host", NoRealRuntimeHostProvider.host())
        assertEquals(MinecraftRealRuntimeAvailability.UNAVAILABLE, result.availability)
        assertEquals(MinecraftRealRuntimeOutcome.RUNTIME_TEST_NOT_PERFORMED, result.outcome)
        assertFalse(result.performedRealRuntimeTest)
        assertFalse(result.certified)

        // Every step is reported, in order, and none of them is upgraded to PASSED.
        assertEquals(MinecraftCertificationStep.entries.toList(), result.steps.map { it.step })
        assertTrue(
            "no step may be claimed without a real runtime",
            result.steps.all { it.outcome == MinecraftVerificationOutcome.NOT_PERFORMED },
        )
        assertTrue(result.steps.all { it.detail.contains("not performed") })

        val evidence = result.evidence
        assertEquals(MinecraftEvidenceMode.NONE, evidence.evidenceMode)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, evidence.evidenceLevel)
        assertFalse(evidence.realRuntimeTested)
        assertEquals(MinecraftCertificationSource.NONE, evidence.certificationSource)
        assertNull("no timestamp may be invented for a test that never ran", evidence.recordedAtEpochMillis)
        assertEquals(MinecraftRealRuntimeCertificationHarness.UNKNOWN_ENVIRONMENT, evidence.executionEnvironment)
        assertEquals(
            MinecraftVerificationCategory.entries.toSet(),
            evidence.notPerformedCategories,
        )
        assertTrue(evidence.passedCategories.isEmpty())
        assertTrue(evidence.failedCategories.isEmpty())
        assertEquals(listOf("RUNTIME_TEST_NOT_PERFORMED"), evidence.notes)

        val evaluation = result.evaluation
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, evaluation.decision)
        assertFalse(evaluation.authorizesExecution)
        assertFalse(evaluation.realRuntimeTested)
        assertTrue(MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED in evaluation.reasonCodes)

        // The declared profile facts are still reported honestly, so the UI can say why nothing is certified.
        assertEquals(profileId, evidence.profileId)
        assertEquals("1.20.1", evidence.minecraftVersion)
        assertEquals("Fabric", evidence.loader.displayName)
    }

    @Test
    fun anUnknownProfileIsReportedAsNotPerformedRatherThanGuessed() = runBlocking {
        val result = MinecraftRealRuntimeCertificationHarness()
            .certify(profileId = "java-fabric-1.21.99", testRunId = "real-runtime-unknown-profile")
        assertEquals(MinecraftRealRuntimeOutcome.RUNTIME_TEST_NOT_PERFORMED, result.outcome)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, result.evaluation.decision)
        assertFalse(result.evidence.realRuntimeTested)
    }

    @Test
    fun aHostThatClaimsToBeRealStillCannotCertifyUnderTheShippedPolicy() = runBlocking {
        val host = StubRealRuntimeHost(environmentLabel = "stub-certification-station")
        val result = MinecraftRealRuntimeCertificationHarness(hostProvider = providerOf(host))
            .certify(profileId = profileId, testRunId = "real-runtime-undeclared-environment")

        assertEquals(MinecraftRealRuntimeAvailability.AVAILABLE, result.availability)
        assertEquals(MinecraftRealRuntimeOutcome.RUNTIME_TEST_COMPLETED, result.outcome)
        assertTrue(result.performedRealRuntimeTest)
        assertTrue(host.closed)

        // The evidence says a real runtime was exercised, but the *decision* refuses it: the environment is not
        // declared real-runtime capable by policy, so the evidence is treated as simulated and certifies nothing.
        assertEquals(MinecraftEvidenceMode.REAL_RUNTIME, result.evidence.evidenceMode)
        assertTrue(result.evidence.realRuntimeTested)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, result.evaluation.decision)
        assertFalse("a stub host must never certify", result.certified)
        assertFalse(result.evaluation.authorizesExecution)
        assertTrue(
            MinecraftCertificationReasonCode.ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME in
                result.evaluation.reasonCodes,
        )
        assertEquals(MinecraftEvidenceMode.SIMULATED, result.evaluation.evidenceMode)
        assertTrue(
            result.evaluation.evidenceLevel.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL),
        )
        assertTrue(MinecraftCertificationPolicy.DEFAULT.realRuntimeCapableEnvironments.isEmpty())
    }

    @Test
    fun genuineRealRuntimeEvidenceInADeclaredEnvironmentDoesCertify() = runBlocking {
        val host = StubRealRuntimeHost(environmentLabel = "declared-certification-lab")
        val policy = MinecraftCertificationPolicy.DEFAULT.copy(
            realRuntimeCapableEnvironments = setOf("declared-certification-lab"),
        )
        val result = MinecraftRealRuntimeCertificationHarness(hostProvider = providerOf(host), policy = policy)
            .certify(profileId = profileId, testRunId = "real-runtime-declared-environment")

        assertEquals(MinecraftRealRuntimeOutcome.RUNTIME_TEST_COMPLETED, result.outcome)
        assertEquals(MinecraftCertificationDecision.CERTIFIED, result.evaluation.decision)
        assertTrue(result.certified)
        assertTrue(result.evaluation.authorizesExecution)
        assertTrue(result.evaluation.realRuntimeTested)
        assertEquals(MinecraftRuntimeCertification.CERTIFIED, result.evaluation.evidenceLevel)
        assertEquals(MinecraftCertificationSource.REAL_RUNTIME_TEST_RUN, result.evaluation.source)
        assertEquals(
            "the harness must derive execution IDs deterministically from the test run",
            CertificationExecutionIds.next("real-runtime-declared-environment"),
            host.observedExecutionId,
        )
        assertEquals(MinecraftCertificationStep.entries.size, result.steps.size)
        assertTrue(result.steps.all { it.outcome == MinecraftVerificationOutcome.PASSED })
        assertEquals(
            MinecraftVerificationOutcome.PASSED,
            result.evidence.categoryOutcomes[MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION],
        )
        assertTrue(result.evidence.recordedAtEpochMillis != null)
    }

    @Test
    fun aRealRunWithoutVerifiedWorldStateFailsClosedInsteadOfCertifying() = runBlocking {
        val host = StubRealRuntimeHost(
            environmentLabel = "declared-certification-lab",
            executionEvidence = CertificationEvidenceFixtures.completedExecution(
                MinecraftEvidenceMode.REAL_RUNTIME,
                worldStateVerified = false,
            ),
        )
        val policy = MinecraftCertificationPolicy.DEFAULT.copy(
            realRuntimeCapableEnvironments = setOf("declared-certification-lab"),
        )
        val result = MinecraftRealRuntimeCertificationHarness(hostProvider = providerOf(host), policy = policy)
            .certify(profileId = profileId, testRunId = "real-runtime-unverified-world")

        assertEquals(
            MinecraftVerificationOutcome.FAILED,
            result.steps.single { it.step == MinecraftCertificationStep.VERIFY_WORLD_STATE }.outcome,
        )
        assertEquals(MinecraftCertificationDecision.FAILED, result.evaluation.decision)
        assertFalse(result.certified)
        assertFalse(result.evaluation.authorizesExecution)
        assertTrue(MinecraftCertificationReasonCode.CATEGORY_FAILED in result.evaluation.reasonCodes)
    }

    @Test
    fun aRuntimeThatChangesAcrossReconnectIsRecordedAsAFailedStep() = runBlocking {
        val host = StubRealRuntimeHost(
            environmentLabel = "declared-certification-lab",
            reconnectVersion = "1.19.4",
        )
        val policy = MinecraftCertificationPolicy.DEFAULT.copy(
            realRuntimeCapableEnvironments = setOf("declared-certification-lab"),
        )
        val result = MinecraftRealRuntimeCertificationHarness(hostProvider = providerOf(host), policy = policy)
            .certify(profileId = profileId, testRunId = "real-runtime-reconnect-change")

        assertEquals(
            MinecraftVerificationOutcome.FAILED,
            result.steps.single { it.step == MinecraftCertificationStep.VERIFY_RECONNECT }.outcome,
        )
        assertEquals(MinecraftCertificationDecision.FAILED, result.evaluation.decision)
        assertFalse("a changed runtime must never certify", result.certified)
        assertTrue(host.closed)
    }

    @Test
    fun aHostThatFailsToAuthenticateRecordsAFailedRunAndClosesTheRuntime() = runBlocking {
        val host = StubRealRuntimeHost(environmentLabel = "declared-certification-lab", failAuthentication = true)
        val policy = MinecraftCertificationPolicy.DEFAULT.copy(
            realRuntimeCapableEnvironments = setOf("declared-certification-lab"),
        )
        val result = MinecraftRealRuntimeCertificationHarness(hostProvider = providerOf(host), policy = policy)
            .certify(profileId = profileId, testRunId = "real-runtime-authentication-failure")

        assertEquals(MinecraftRealRuntimeOutcome.RUNTIME_TEST_FAILED, result.outcome)
        assertFalse(result.performedRealRuntimeTest)
        assertFalse(result.certified)
        assertEquals(MinecraftCertificationDecision.NOT_CERTIFIED, result.evaluation.decision)
        assertEquals(MinecraftEvidenceMode.NONE, result.evidence.evidenceMode)
        assertEquals(MinecraftRuntimeCertification.NOT_PERFORMED, result.evidence.evidenceLevel)
        assertFalse(result.evidence.realRuntimeTested)
        assertEquals(listOf("RUNTIME_TEST_FAILED"), result.evidence.notes)
        assertEquals(
            MinecraftVerificationOutcome.FAILED,
            result.steps.last().outcome,
        )
        assertEquals(MinecraftCertificationStep.RECORD_EVIDENCE, result.steps.last().step)
        assertTrue("the runtime must always be released", host.closed)
    }

    @Test
    fun certificationExecutionIdsAreDeterministicAndShapedLikeRequestIds() {
        val first = CertificationExecutionIds.next("real-runtime-run", index = 0)
        val second = CertificationExecutionIds.next("real-runtime-run", index = 0)
        val third = CertificationExecutionIds.next("real-runtime-run", index = 1)
        assertEquals("the same run and index must produce the same execution ID", first, second)
        assertTrue("different indexes must produce different execution IDs", first != third)
        assertTrue(
            "execution IDs must be protocol request IDs, got $first",
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(first),
        )
        // A different test run must not collide with this one.
        assertTrue(first != CertificationExecutionIds.next("another-run", index = 0))
    }

    /**
     * A test double for the real-runtime *hook*. It is not a Minecraft server and is never presented as one: it exists
     * so the harness's own sequencing, evidence recording, and policy gating can be verified in a JVM unit test.
     */
    private class StubRealRuntimeHost(
        override val environmentLabel: String,
        private val executionEvidence: MinecraftExecutionVerificationEvidence =
            CertificationEvidenceFixtures.completedExecution(MinecraftEvidenceMode.REAL_RUNTIME),
        private val reconnectVersion: String? = null,
        private val failAuthentication: Boolean = false,
    ) : MinecraftRealRuntimeHost {
        var closed = false
            private set

        /** The execution ID the harness derived from the test run, so determinism can be asserted from outside. */
        var observedExecutionId: String? = null
            private set

        override val availability = MinecraftRealRuntimeAvailability.AVAILABLE

        override val runtimeDescription = "Stub real-runtime host for harness tests (no Minecraft was started)"

        override suspend fun authenticate(): AuthenticatedMinecraftRuntimeReport {
            if (failAuthentication) throw IllegalStateException("stub authentication failure")
            return report("1.20.1")
        }

        override suspend fun runExecution(
            record: LocalBuildRecord,
            executionId: String,
        ): MinecraftExecutionVerificationEvidence {
            assertEquals(
                "the harness must execute the deterministic certification probe",
                CertificationBuildPlan.RECORD_ID,
                record.recordId,
            )
            observedExecutionId = executionId
            return executionEvidence
        }

        override suspend fun reconnect(): AuthenticatedMinecraftRuntimeReport = report(reconnectVersion ?: "1.20.1")

        override fun close() {
            closed = true
        }

        /** A production-valid authenticated report, produced through the real protocol codec. */
        private fun report(version: String): AuthenticatedMinecraftRuntimeReport {
            val bridge = SimulatedCertificationBridge(
                SimulatedCertificationBridge.TRUSTED_BRIDGE,
                facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(minecraftVersion = version),
            )
            runBlocking { bridge.connect() }
            return bridge.authenticatedSnapshot().runtimeReport(
                sessionId = bridge.sessionId,
                requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
                authenticated = true,
                authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
            )
        }
    }

    /** A provider is a one-method interface; this keeps the tests readable without a fake server. */
    private fun providerOf(host: MinecraftRealRuntimeHost?) = object : MinecraftRealRuntimeHostProvider {
        override fun host(): MinecraftRealRuntimeHost? = host
    }
}
