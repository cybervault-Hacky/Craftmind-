package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanLimits
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.MinecraftBlockCatalog
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.JavaFabric1201Adapter
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.compatibility.LegacyRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.bridge.protocol.BridgeProtocol
import com.craftmind.bridge.protocol.BridgeProtocolCodec
import com.craftmind.bridge.protocol.BuildPlanContractValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 §9: failure injection. Every documented failure must fail closed.
 *
 * Each case in [MinecraftCompatibilityCertificationMatrix.executionFailureCases] is driven through the production
 * pipeline (or, where a failure can only be produced by concurrent bridge state, through the production adapter and
 * the production contract validator) and asserted on three things:
 *
 * 1. the documented reason code is actually reported by the layer that refuses,
 * 2. the refusal happens at the documented stage, and
 * 3. nothing is written to the world before the validation that refuses it — a partial build is never the outcome
 *    of a failed authentication, a failed preflight, a stale binding, or an uncertified runtime.
 *
 * Cancellation is the only case that intentionally writes a bounded prefix: it is honoured at a batch boundary after
 * authorization and preflight succeeded, and it still never reports completion.
 */
class CertificationFailureInjectionTest {
    private val resolver = DefaultMinecraftCompatibility.resolver
    private val adapter = JavaFabric1201Adapter()

    /** Cases that can only be produced by concurrent bridge state; asserted by dedicated tests below. */
    private val bridgeLevelCases = setOf(
        "execution-duplicate-execution-id",
        "execution-second-simultaneous-build",
    )

    // --------------------------------------------------------------------------------------------- scenario table

    private class Scenario(
        val facts: SimulatedCertificationBridge.SimulatedRuntimeFacts =
            SimulatedCertificationBridge.SimulatedRuntimeFacts(),
        val bridgeConfig: SimulatedCertificationBridge.() -> Unit = {},
        val profileId: String = "java-fabric-1.20.1",
        val planTransform: (BuildPlan) -> BuildPlan = { it },
        val stalePreviousBinding: Boolean = false,
        /**
         * The code the production pipeline reports for this case, when it differs from the bridge-contract code the
         * matrix documents. Production validation refuses forged block content first (`INVALID_BLOCK_ID` /
         * `INVALID_BLOCK_STATE`); the documented `UNSUPPORTED_BLOCK` / `UNSUPPORTED_BLOCK_STATE` codes are what the
         * bridge contract validator reports for a payload that bypassed it, asserted separately below.
         */
        val pipelineReasonCode: String? = null,
        val expectedStage: SimulatedPipelineStage,
        /** True when authorization legitimately succeeds and a later stage refuses. */
        val authorizationSucceedsFirst: Boolean = false,
        /** True only for cancellation, which writes a bounded prefix before terminating. */
        val writesBoundedPrefix: Boolean = false,
    )

    private fun scenarioFor(case: MinecraftExecutionFailureCase): Scenario = when (case.caseId) {
        "execution-authentication-failure" -> Scenario(
            bridgeConfig = { authenticationFails = true },
            expectedStage = SimulatedPipelineStage.BRIDGE_AUTHENTICATION,
        )

        "execution-invalid-session" -> Scenario(
            bridgeConfig = { sessionExpiresImmediately = true },
            expectedStage = SimulatedPipelineStage.BRIDGE_AUTHENTICATION,
        )

        "execution-preflight-rejected" -> Scenario(
            bridgeConfig = { rejectPreflight = true },
            expectedStage = SimulatedPipelineStage.PREFLIGHT,
            authorizationSucceedsFirst = true,
        )

        "execution-invalid-build-plan" -> Scenario(
            planTransform = { it.copy(operations = emptyList()) },
            expectedStage = SimulatedPipelineStage.BUILDPLAN_VALIDATION,
        )

        "execution-unsupported-block" -> Scenario(
            planTransform = { plan ->
                plan.copy(
                    operations = plan.operations.mapIndexed { index, operation ->
                        if (index == 0) operation.copy(blockId = "minecraft:craftmind_probe_block") else operation
                    },
                )
            },
            pipelineReasonCode = "INVALID_BLOCK_ID",
            expectedStage = SimulatedPipelineStage.BUILDPLAN_VALIDATION,
            authorizationSucceedsFirst = true,
        )

        "execution-unsupported-block-state" -> Scenario(
            planTransform = { plan ->
                plan.copy(
                    operations = plan.operations.mapIndexed { index, operation ->
                        if (index == 2) operation.copy(blockState = mapOf("north" to "maybe")) else operation
                    },
                )
            },
            pipelineReasonCode = "INVALID_BLOCK_STATE",
            expectedStage = SimulatedPipelineStage.BUILDPLAN_VALIDATION,
            authorizationSucceedsFirst = true,
        )

        "execution-schema-downgrade" -> Scenario(
            planTransform = { plan ->
                plan.copy(metadata = plan.metadata.copy(schemaVersion = BuildPlanLimits.LEGACY_SCHEMA_VERSION))
            },
            expectedStage = SimulatedPipelineStage.PREFLIGHT,
        )

        "execution-limit-cannot-be-raised" -> Scenario(
            // The runtime reports a smaller ceiling than CraftMind's probe needs: the effective limit is the minimum,
            // so the probe is refused instead of being trimmed, split, or partially executed.
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(maximumValidatedOperations = 2),
            expectedStage = SimulatedPipelineStage.PREFLIGHT,
        )

        "execution-cancellation" -> Scenario(
            bridgeConfig = { cancelInsteadOfComplete = true },
            expectedStage = SimulatedPipelineStage.EXECUTION,
            authorizationSucceedsFirst = true,
            writesBoundedPrefix = true,
        )

        "execution-disconnect-during-build" -> Scenario(
            bridgeConfig = { disconnectDuringExecution = true },
            expectedStage = SimulatedPipelineStage.EXECUTION,
            authorizationSucceedsFirst = true,
        )

        "execution-stale-binding" -> Scenario(
            // A binding captured while the world session was different is presented for the same runtime: the binding
            // is stale and cannot authorize the build, even though the runtime facts still match exactly.
            stalePreviousBinding = true,
            expectedStage = SimulatedPipelineStage.EXECUTION_AUTHORIZATION,
        )

        "execution-legacy-refusal" -> Scenario(
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                minecraftVersion = "1.12.2",
                loaderName = "Forge",
                loaderVersion = "14.23.5.2859",
                fabricApiVersion = null,
                javaRuntimeMajor = 8,
                bridgeVersion = LegacyRuntimeProfileRegistry.LEGACY_BRIDGE_CONTRACT_VERSION,
            ),
            profileId = LegacyRuntimeProfileRegistry.LEGACY_ADAPTER_ID,
            expectedStage = SimulatedPipelineStage.PREFLIGHT,
        )

        "execution-bedrock-refusal" -> Scenario(
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                editionName = "bedrock",
                minecraftVersion = "1.21.60",
                platformName = "DEDICATED_SERVER",
                platformVersion = "1.21.60.3",
                bridgeVersion = BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_CONTRACT_VERSION,
                limitations = BedrockRuntimeProfileRegistry.bedrockBridgeContract.limitations.map { it.name },
            ),
            profileId = "bedrock-bridge-contract",
            expectedStage = SimulatedPipelineStage.PREFLIGHT,
        )

        else -> error("Failure-injection case ${case.caseId} is not covered by this suite")
    }

    // --------------------------------------------------------------------------------------------- §9 injection

    @Test
    fun everyPipelineExpressibleFailureCaseFailsClosedAtItsDocumentedLayer() = runBlocking {
        val failures = mutableListOf<String>()
        MinecraftCompatibilityCertificationMatrix.executionFailureCases
            .filterNot { it.caseId in bridgeLevelCases }
            .forEach { case ->
                val scenario = scenarioFor(case)
                val bridge = SimulatedCertificationBridge(
                    SimulatedCertificationBridge.TRUSTED_BRIDGE,
                    facts = scenario.facts,
                ).apply(scenario.bridgeConfig)
                val plan = scenario.planTransform(CertificationBuildPlan.build())
                val record = CertificationBuildPlan.localRecord().copy(plan = plan)
                val previousBinding = if (scenario.stalePreviousBinding) {
                    resolver.runtimeGate.resolveRuntime(staleWorldSessionReport()).binding
                } else {
                    null
                }
                val run = SimulatedCertificationPipeline(
                    bridge = bridge,
                    resolver = resolver,
                    testRunId = "failure-injection-${case.caseId}",
                ).run(
                    SimulatedPipelineRequest(
                        profileId = scenario.profileId,
                        plan = plan,
                        record = record,
                        previousBinding = previousBinding,
                    ),
                )
                val observed = observedReasonText(run, bridge)

                fun fail(message: String) {
                    failures += "${case.caseId}: $message"
                }

                val expectedCode = scenario.pipelineReasonCode ?: case.expectedReasonCode
                if (observed.none { it.contains(expectedCode) }) {
                    fail("expected reason code $expectedCode was never reported; observed $observed")
                }
                if (run.failedStage?.stage != scenario.expectedStage) {
                    fail("refused at ${run.failedStage?.stage?.name ?: "nothing"} instead of ${scenario.expectedStage}")
                }
                if (run.failedStage == null) {
                    fail("no stage failed; the case did not fail closed")
                }
                if (run.authorized != scenario.authorizationSucceedsFirst) {
                    fail("authorized=${run.authorized}, expected ${scenario.authorizationSucceedsFirst}")
                }
                if (scenario.writesBoundedPrefix) {
                    if (bridge.worldBlocks.size >= plan.operations.size) {
                        fail("a cancelled execution wrote every operation instead of a bounded prefix")
                    }
                    if (bridge.events.none { it.startsWith("execution-cancelled-at-batch-boundary") }) {
                        fail("cancellation was not reported at a batch boundary")
                    }
                    if (MinecraftExecutionVerificationStage.EXECUTION_COMPLETED in
                        run.executionEvidence.observedStages
                    ) {
                        fail("a cancelled execution was recorded as completed")
                    }
                } else if (bridge.worldBlocks.isNotEmpty()) {
                    fail("wrote ${bridge.worldBlocks.size} block(s) before failing closed")
                }
                // Authorization is never a write permission on its own.
                if (!run.authorized && bridge.events.any { it.startsWith("block-written") }) {
                    fail("blocks were written without authorization")
                }
                if (run.evaluation.decision == MinecraftCertificationDecision.CERTIFIED) {
                    fail("a failed run was certified")
                }
                if (run.evaluation.authorizesExecution) {
                    fail("a failed run authorizes execution")
                }
                if (run.evidence.realRuntimeTested) {
                    fail("a simulated failure injection claimed a real runtime test")
                }
                if (!run.evidence.evidenceLevel.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL)) {
                    fail("evidence level ${run.evidence.evidenceLevel} exceeds the simulated ceiling")
                }
            }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        Unit
    }

    @Test
    fun aReusedExecutionIdIsRefusedBeforeAnyWrite() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val record = CertificationBuildPlan.localRecord()
        val executionId = CertificationExecutionIds.next("failure-injection-duplicate")

        val first = adapter.preflight(bridge, record, executionId)
        assertNotNull(first)
        assertTrue(bridge.worldBlocks.isEmpty())

        val reuse = runCatching { adapter.preflight(bridge, record, executionId) }
        assertTrue("a reused execution ID must be refused", reuse.isFailure)
        assertEquals("EXECUTION_ALREADY_EXISTS", (reuse.exceptionOrNull() as MinecraftBridgeFailure).reasonCode)
        assertTrue("a refused duplicate must not write anything", bridge.worldBlocks.isEmpty())
        assertFalse("a refused duplicate must not start an execution", bridge.events.any { it.startsWith("execution-started:") })
        assertEquals(1, bridge.preparedExecutions.size)
    }

    @Test
    fun aSecondSimultaneousBuildIsRefusedWhileOneExecutionIsActive() = runBlocking {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        bridge.connect()
        val record = CertificationBuildPlan.localRecord()

        // A build is already running server-side; CraftMind never opens a second concurrent execution.
        bridge.markExecutionActive(CertificationExecutionIds.next("failure-injection-active"))
        val second = runCatching {
            adapter.preflight(bridge, record, CertificationExecutionIds.next("failure-injection-second"))
        }
        assertTrue("a second simultaneous build must be refused", second.isFailure)
        assertEquals("EXECUTION_ALREADY_ACTIVE", (second.exceptionOrNull() as MinecraftBridgeFailure).reasonCode)
        assertTrue(bridge.worldBlocks.isEmpty())
        assertFalse(
            "CraftMind must never opt into concurrent executions",
            bridge.allowSecondActiveExecution,
        )
    }

    @Test
    fun theProductionContractValidatorRefusesForgedPlanContent() {
        val bridge = SimulatedCertificationBridge(SimulatedCertificationBridge.TRUSTED_BRIDGE)
        runBlocking { bridge.connect() }
        val executionId = CertificationExecutionIds.next("failure-injection-contract")

        fun errorCode(
            record: LocalBuildRecord,
            maxOperations: Int = BridgeProtocol.MAX_OPERATIONS,
            maxRequestBytes: Int = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES,
            reportedBytes: Int? = null,
        ) = bridge.executionRequestPayload(record, executionId).let { payload ->
            val bytes = BridgeProtocolCodec.writeEnvelope(
                BridgeProtocolCodec.newEnvelope("execution.prepare.request", payload, executionId),
            )
            BuildPlanContractValidator.validateExecutionPayload(
                payload,
                reportedBytes ?: bytes.size,
                maxOperations,
                maxRequestBytes,
                CatalogBlockSupport,
            )
        }

        val baseline = CertificationBuildPlan.localRecord()
        assertNull("the certification probe must satisfy the production contract", errorCode(baseline))

        assertEquals(
            BridgeProtocol.ErrorCode.UNSUPPORTED_BLOCK,
            errorCode(recordWithBlock("minecraft:craftmind_probe_block")),
        )
        assertEquals(
            BridgeProtocol.ErrorCode.UNSUPPORTED_BLOCK_STATE,
            errorCode(recordWithState(mapOf("north" to "maybe"))),
        )
        assertEquals(
            BridgeProtocol.ErrorCode.UNSUPPORTED_BUILD_PLAN_SCHEMA,
            errorCode(recordWithSchema(BuildPlanLimits.LEGACY_SCHEMA_VERSION)),
        )
        assertEquals(
            BridgeProtocol.ErrorCode.BUILD_TOO_LARGE,
            errorCode(baseline, maxOperations = CertificationBuildPlan.OPERATION_COUNT - 1),
        )
        // A payload larger than the declared request-byte ceiling is refused as a limit violation, never truncated.
        assertEquals(
            BridgeProtocol.ErrorCode.LIMIT_EXCEEDED,
            errorCode(baseline, reportedBytes = BridgeProtocol.MAX_EXECUTION_REQUEST_BYTES + 1),
        )
        assertTrue("nothing may be written by validation alone", bridge.worldBlocks.isEmpty())
    }

    @Test
    fun effectiveLimitsAreTheMinimumOfGlobalAndRuntimeAndAreNeverRaised() = runBlocking {
        val small = runtimeLimits(maximumValidatedOperations = 2)
        assertEquals(2, small)

        val atCeiling = runtimeLimits(maximumValidatedOperations = BridgeProtocol.MAX_OPERATIONS)
        assertEquals(BridgeProtocol.MAX_OPERATIONS, atCeiling)

        // A runtime cannot raise CraftMind's global ceiling: the effective limit is capped, never expanded.
        assertTrue(atCeiling <= BridgeProtocol.MAX_OPERATIONS)
        assertTrue(small <= atCeiling)
    }

    @Test
    fun everyDocumentedExecutionFailureCaseIsAssertedByThisSuite() {
        val documented = MinecraftCompatibilityCertificationMatrix.executionFailureCases.map { it.caseId }.toSet()
        val pipelineCovered = documented.filterNot { it in bridgeLevelCases }
        pipelineCovered.forEach { caseId ->
            val case = MinecraftCompatibilityCertificationMatrix.executionFailureCases.single { it.caseId == caseId }
            assertNotNull("scenario for $caseId", scenarioFor(case))
            assertTrue(
                "$caseId must be asserted at a concrete stage",
                scenarioFor(case).expectedStage in SimulatedPipelineStage.entries,
            )
        }
        assertEquals(
            "the suite must cover every documented execution failure case",
            documented,
            pipelineCovered.toSet() + bridgeLevelCases,
        )
        assertTrue("the matrix must keep at least the 15 documented failure cases", documented.size >= 15)
        MinecraftCompatibilityCertificationMatrix.executionFailureCases.forEach { case ->
            assertTrue("${case.caseId} must fail closed before any bridge write", case.expectedFailClosedBeforeBridgeWrite)
            assertTrue(
                "${case.caseId} must be an execution-level case",
                MinecraftVerificationCategory.END_TO_END_EXECUTION in case.applicableCategories,
            )
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private fun case(
        cases: List<MinecraftExecutionFailureCase>,
        caseId: String,
    ): MinecraftExecutionFailureCase = cases.single { it.caseId == caseId }

    private suspend fun runtimeLimits(maximumValidatedOperations: Int): Int {
        val bridge = SimulatedCertificationBridge(
            SimulatedCertificationBridge.TRUSTED_BRIDGE,
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                maximumValidatedOperations = maximumValidatedOperations,
            ),
        )
        bridge.connect()
        val report = bridge.authenticatedSnapshot().runtimeReport(
            sessionId = bridge.sessionId,
            requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
            authenticated = true,
            authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
        )
        return resolver.runtimeGate.resolveRuntime(report).compatibility.limits.maximumValidatedOperations
            ?: error("the authenticated runtime must report a usable operation limit")
    }

    /** The same runtime, captured while the operator was in a different world session: a stale binding. */
    private fun staleWorldSessionReport(): com.craftmind.app.domain.minecraft.compatibility
        .AuthenticatedMinecraftRuntimeReport {
        val bridge = SimulatedCertificationBridge(
            SimulatedCertificationBridge.TRUSTED_BRIDGE,
            facts = SimulatedCertificationBridge.SimulatedRuntimeFacts(
                worldSessionId = "world-session-before-move",
            ),
        )
        runBlocking { bridge.connect() }
        return bridge.authenticatedSnapshot().runtimeReport(
            sessionId = bridge.sessionId,
            requestedAppVersion = SimulatedCertificationBridge.CERTIFICATION_APP_VERSION,
            authenticated = true,
            authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
        )
    }

    private fun recordWithBlock(blockId: String): LocalBuildRecord {
        val plan = CertificationBuildPlan.build()
        return CertificationBuildPlan.localRecord().copy(
            plan = plan.copy(
                operations = plan.operations.mapIndexed { index, operation ->
                    if (index == 0) operation.copy(blockId = blockId) else operation
                },
            ),
        )
    }

    private fun recordWithState(blockState: Map<String, String>): LocalBuildRecord {
        val plan = CertificationBuildPlan.build()
        return CertificationBuildPlan.localRecord().copy(
            plan = plan.copy(
                operations = plan.operations.mapIndexed { index, operation ->
                    if (index == 2) operation.copy(blockState = blockState) else operation
                },
            ),
        )
    }

    private fun recordWithSchema(schemaVersion: Int): LocalBuildRecord {
        val plan = CertificationBuildPlan.build()
        return CertificationBuildPlan.localRecord().copy(
            plan = plan.copy(metadata = plan.metadata.copy(schemaVersion = schemaVersion)),
        )
    }

    /** Every place a refusal can be reported, so a documented code cannot hide behind a generic message. */
    private fun observedReasonText(
        run: SimulatedPipelineRun,
        bridge: SimulatedCertificationBridge,
    ): List<String> = buildList {
        run.stages.forEach { stage ->
            stage.reasonCode?.let { add(it) }
            add(stage.detail)
        }
        run.authorization?.reasonCode?.let { add(it) }
        run.authorization?.reasons?.forEach { add(it) }
        run.resolution?.failureReasonCode()?.let { add(it) }
        run.resolution?.compatibility?.reasonCodes?.forEach { add(it.name) }
        run.executionSnapshot?.reasonCode?.let { add(it) }
        run.cancellation?.outcome?.let { add(it) }
        addAll(bridge.events)
    }

    /** The production block/state catalog, unchanged: certification never widens what a block may be. */
    private object CatalogBlockSupport : BuildPlanContractValidator.BlockSupport {
        override fun isSupportedBlock(blockId: String): Boolean = MinecraftBlockCatalog.supports(blockId)

        override fun hasValidState(blockId: String, state: MutableMap<String, String>): Boolean =
            MinecraftBlockCatalog.validState(blockId, state)
    }
}
