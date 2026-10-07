package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.MinecraftVersionChannel
import com.craftmind.bridge.protocol.BridgeProtocol

/**
 * Deterministic certification evidence fixtures.
 *
 * These are *test inputs*, never claims. A fixture that says `REAL_RUNTIME` exists so the decision engine can be proved
 * to certify only when complete, genuine real-runtime evidence is presented — and to refuse everything else. No
 * production path in this repository can produce such a record: [MinecraftCertificationPolicy.DEFAULT] declares no
 * real-runtime capable environment, and the real-runtime harness ships without a host provider, so it always reports
 * `RUNTIME_TEST_NOT_PERFORMED`.
 *
 * Every fixture is fully deterministic (fixed timestamps, fixed identifiers) and carries no secret, host, session, or
 * network material, so it is safe to commit.
 */
internal object CertificationEvidenceFixtures {
    const val TEST_SUITE_ID = "craftmind-universal-certification"
    const val FIXED_RECORDED_AT_EPOCH_MILLIS = 1_700_000_000_000L
    const val PROFILE_ID = "java-fabric-1.20.1"
    const val MINECRAFT_VERSION = "1.20.1"
    const val LOADER_VERSION = "0.16.10"
    const val FABRIC_API_VERSION = "0.92.2+1.20.1"
    const val BRIDGE_VERSION = "1.2.0"
    const val BLOCK_STATE_CATALOG_REVISION = "java-1.20.1-v1"
    const val JAVA_RUNTIME_MAJOR = 17

    /** Every category passed — only meaningful together with real-runtime mode and verified world state. */
    val ALL_PASSED: Map<MinecraftVerificationCategory, MinecraftVerificationOutcome> =
        MinecraftVerificationCategory.entries.associateWith { MinecraftVerificationOutcome.PASSED }

    /** A complete execution that wrote every operation, reported progress, and was read back from the world. */
    fun completedExecution(
        mode: MinecraftEvidenceMode,
        operationCount: Int = CertificationBuildPlan.OPERATION_COUNT,
        worldStateVerified: Boolean = mode == MinecraftEvidenceMode.REAL_RUNTIME,
        cancellationVerified: Boolean = true,
        reconnectVerified: Boolean = true,
    ) = MinecraftExecutionVerificationEvidence(
        mode = mode,
        observedStages = setOf(
            MinecraftExecutionVerificationStage.REQUEST_ACCEPTED,
            MinecraftExecutionVerificationStage.PRECHECK_PASSED,
            MinecraftExecutionVerificationStage.EXECUTION_STARTED,
            MinecraftExecutionVerificationStage.BLOCKS_WRITTEN,
            MinecraftExecutionVerificationStage.PROGRESS_REPORTED,
            MinecraftExecutionVerificationStage.EXECUTION_COMPLETED,
        ),
        worldStateVerified = worldStateVerified,
        observedOperationCount = operationCount,
        expectedOperationCount = operationCount,
        cancellationVerified = cancellationVerified,
        reconnectVerified = reconnectVerified,
        notes = listOf("Fixture execution evidence; mode ${mode.name}."),
    )

    /** Honest empty record: nothing was performed, nothing is claimed, and no timestamp is invented. */
    fun notPerformed(
        profileId: String = PROFILE_ID,
        testRunId: String = "fixture-not-performed",
        executionEnvironment: String = MinecraftRealRuntimeCertificationHarness.UNKNOWN_ENVIRONMENT,
        knownLimitations: Set<MinecraftRuntimeLimitation> = emptySet(),
        reason: String = "RUNTIME_TEST_NOT_PERFORMED",
    ): MinecraftCertificationEvidence = MinecraftCertificationEvidence.notPerformed(
        profileId = profileId,
        edition = MinecraftEdition.JAVA,
        minecraftVersion = MINECRAFT_VERSION,
        releaseChannel = MinecraftVersionChannel.RELEASE,
        loader = MinecraftLoader.FABRIC,
        loaderVersion = LOADER_VERSION,
        javaRuntimeMajor = JAVA_RUNTIME_MAJOR,
        fabricApiVersion = FABRIC_API_VERSION,
        bridgeVersion = BRIDGE_VERSION,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        buildPlanSchemaVersion = BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION,
        blockStateCatalogRevision = BLOCK_STATE_CATALOG_REVISION,
        testSuiteId = TEST_SUITE_ID,
        testRunId = testRunId,
        executionEnvironment = executionEnvironment,
        knownLimitations = knownLimitations,
        reason = reason,
    )

    /**
     * Complete real-runtime evidence. Only a real Minecraft host can honestly produce this; the engine still refuses to
     * certify it unless the execution environment is declared real-runtime capable by policy.
     */
    fun realRuntime(
        executionEnvironment: String,
        profileId: String = PROFILE_ID,
        testRunId: String = "fixture-real-runtime",
        categoryOutcomes: Map<MinecraftVerificationCategory, MinecraftVerificationOutcome> = ALL_PASSED,
        executionEvidence: MinecraftExecutionVerificationEvidence =
            completedExecution(MinecraftEvidenceMode.REAL_RUNTIME),
        knownLimitations: Set<MinecraftRuntimeLimitation> = emptySet(),
        evidenceLevel: MinecraftRuntimeCertification = MinecraftRuntimeCertification.CERTIFIED,
        recordedAtEpochMillis: Long? = FIXED_RECORDED_AT_EPOCH_MILLIS,
    ) = evidence(
        profileId = profileId,
        testRunId = testRunId,
        executionEnvironment = executionEnvironment,
        evidenceLevel = evidenceLevel,
        evidenceMode = MinecraftEvidenceMode.REAL_RUNTIME,
        categoryOutcomes = categoryOutcomes,
        executionEvidence = executionEvidence,
        knownLimitations = knownLimitations,
        realRuntimeTested = true,
        certificationSource = MinecraftCertificationSource.REAL_RUNTIME_TEST_RUN,
        recordedAtEpochMillis = recordedAtEpochMillis,
    )

    /**
     * Complete simulated evidence: the whole pipeline passed against a controlled fake bridge. It is the strongest
     * evidence a simulated run may record, and it certifies nothing.
     */
    fun simulated(
        executionEnvironment: String = SimulatedCertificationPipeline.JVM_UNIT_ENVIRONMENT,
        profileId: String = PROFILE_ID,
        testRunId: String = "fixture-simulated",
        categoryOutcomes: Map<MinecraftVerificationCategory, MinecraftVerificationOutcome> = simulatedOutcomes(),
        executionEvidence: MinecraftExecutionVerificationEvidence =
            completedExecution(MinecraftEvidenceMode.SIMULATED, worldStateVerified = false),
        knownLimitations: Set<MinecraftRuntimeLimitation> = emptySet(),
        evidenceLevel: MinecraftRuntimeCertification = MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL,
    ) = evidence(
        profileId = profileId,
        testRunId = testRunId,
        executionEnvironment = executionEnvironment,
        evidenceLevel = evidenceLevel,
        evidenceMode = MinecraftEvidenceMode.SIMULATED,
        categoryOutcomes = categoryOutcomes,
        executionEvidence = executionEvidence,
        knownLimitations = knownLimitations,
        realRuntimeTested = false,
        certificationSource = MinecraftCertificationSource.SIMULATED_TEST_RUN,
        recordedAtEpochMillis = FIXED_RECORDED_AT_EPOCH_MILLIS,
    )

    /** Simulated runs never perform real-runtime integration; everything else in the ladder can pass. */
    fun simulatedOutcomes(
        realRuntimeOutcome: MinecraftVerificationOutcome = MinecraftVerificationOutcome.NOT_PERFORMED,
    ): Map<MinecraftVerificationCategory, MinecraftVerificationOutcome> =
        MinecraftVerificationCategory.entries.associateWith { category ->
            if (category == MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION) {
                realRuntimeOutcome
            } else {
                MinecraftVerificationOutcome.PASSED
            }
        }

    @Suppress("LongParameterList")
    private fun evidence(
        profileId: String,
        testRunId: String,
        executionEnvironment: String,
        evidenceLevel: MinecraftRuntimeCertification,
        evidenceMode: MinecraftEvidenceMode,
        categoryOutcomes: Map<MinecraftVerificationCategory, MinecraftVerificationOutcome>,
        executionEvidence: MinecraftExecutionVerificationEvidence,
        knownLimitations: Set<MinecraftRuntimeLimitation>,
        realRuntimeTested: Boolean,
        certificationSource: MinecraftCertificationSource,
        recordedAtEpochMillis: Long?,
    ) = MinecraftCertificationEvidence(
        profileId = profileId,
        edition = MinecraftEdition.JAVA,
        minecraftVersion = MINECRAFT_VERSION,
        releaseChannel = MinecraftVersionChannel.RELEASE,
        loader = MinecraftLoader.FABRIC,
        loaderVersion = LOADER_VERSION,
        javaRuntimeMajor = JAVA_RUNTIME_MAJOR,
        fabricApiVersion = FABRIC_API_VERSION,
        bridgeVersion = BRIDGE_VERSION,
        bridgeProtocolVersion = BridgeProtocol.VERSION,
        buildPlanSchemaVersion = BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION,
        blockStateCatalogRevision = BLOCK_STATE_CATALOG_REVISION,
        testSuiteId = TEST_SUITE_ID,
        testRunId = testRunId,
        executionEnvironment = executionEnvironment,
        recordedAtEpochMillis = recordedAtEpochMillis,
        evidenceLevel = evidenceLevel,
        evidenceMode = evidenceMode,
        categoryOutcomes = categoryOutcomes,
        executionEvidence = executionEvidence,
        knownLimitations = knownLimitations,
        realRuntimeTested = realRuntimeTested,
        certificationSource = certificationSource,
        notes = listOf("Deterministic certification fixture; contains no secret, host, or session material."),
    )
}
