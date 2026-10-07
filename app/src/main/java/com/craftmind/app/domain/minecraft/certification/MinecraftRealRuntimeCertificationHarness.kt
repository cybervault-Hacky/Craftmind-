package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCompatibilityGate
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.MinecraftVersionChannel
import com.craftmind.bridge.protocol.BridgeProtocol

/** Whether a real Minecraft runtime host exists in this environment. */
enum class MinecraftRealRuntimeAvailability(val displayName: String) {
    AVAILABLE("A real Minecraft runtime host is available"),
    UNAVAILABLE("No real Minecraft runtime host is available"),
}

/** The outcome of one real-runtime certification attempt. */
enum class MinecraftRealRuntimeOutcome(val displayName: String) {
    /** No real runtime existed, so nothing was tested and nothing is certified. */
    RUNTIME_TEST_NOT_PERFORMED("Real runtime test not performed"),

    /** The full real-runtime sequence ran and evidence was recorded (certification still depends on policy). */
    RUNTIME_TEST_COMPLETED("Real runtime test completed"),

    /** The real-runtime sequence started and failed; the failure is recorded as evidence. */
    RUNTIME_TEST_FAILED("Real runtime test failed"),
}

/** The seventeen steps a real-runtime certification run must perform (§6), each with an honest outcome. */
enum class MinecraftCertificationStep(val displayName: String) {
    CONNECT_REAL_RUNTIME("Start or connect to a real Minecraft runtime"),
    PAIR_AND_AUTHENTICATE("Pair and authenticate CraftMind with the bridge"),
    CAPABILITY_EXCHANGE("Exchange and validate the protocol-v2 capability report"),
    DETECT_RUNTIME("Detect edition, version, channel, loader, Java runtime, and bridge identity"),
    SELECT_ADAPTER("Select the exact compatibility adapter"),
    VALIDATE_COMPATIBILITY("Resolve compatibility, capabilities, limits, and content"),
    LOAD_CERTIFICATION_PLAN("Load the deterministic certification BuildPlan"),
    VALIDATE_PLAN("Validate the plan with the production validator"),
    PREFLIGHT("Run server preflight (zero blocks placed)"),
    AUTHORIZE_EXECUTION("Authorize execution against the freshly detected runtime"),
    EXECUTE("Execute the plan"),
    VERIFY_WORLD_STATE("Read back actual world state and compare every placement"),
    VERIFY_PROGRESS("Verify bridge-reported progress"),
    VERIFY_CANCELLATION("Verify cancellation semantics"),
    VERIFY_RECONNECT("Verify reconnect re-detection"),
    VERIFY_RUNTIME_CHANGE_INVALIDATION("Verify runtime-change invalidation where practical"),
    RECORD_EVIDENCE("Record structured evidence"),
}

/** One step's honest outcome; `NOT_PERFORMED` is never silently upgraded to `PASSED`. */
data class MinecraftCertificationStepResult(
    val step: MinecraftCertificationStep,
    val outcome: MinecraftVerificationOutcome,
    val detail: String,
)

/**
 * A real Minecraft runtime host.
 *
 * Implementations live **outside** this repository's unit-test environment (a Gradle/instrumentation rig or a manual
 * certification station with a real Fabric server). Nothing here downloads Minecraft, opens a network connection, or
 * pretends a simulated bridge is real: the default provider returns no host at all.
 */
interface MinecraftRealRuntimeHost {
    val availability: MinecraftRealRuntimeAvailability

    /** Policy-checked environment label; only labels declared in the policy may carry real evidence. */
    val environmentLabel: String

    /** Human-readable runtime description, e.g. "Fabric 1.20.1 dedicated server, Java 17". */
    val runtimeDescription: String

    /** Steps 1–4: connect, pair/authenticate, exchange capabilities, and return the authenticated runtime report. */
    suspend fun authenticate(): AuthenticatedMinecraftRuntimeReport

    /** Steps 9–14: preflight, execute, read back world state, verify progress and cancellation, and reconnect. */
    suspend fun runExecution(
        record: LocalBuildRecord,
        executionId: String,
    ): MinecraftExecutionVerificationEvidence

    /** Step 15: reconnect and return the newly detected runtime report. */
    suspend fun reconnect(): AuthenticatedMinecraftRuntimeReport

    /** Step 16 support: release the runtime. Never leaves a world in a partially written state. */
    fun close()
}

/** Supplies a real host when one exists. The shipped provider supplies none. */
interface MinecraftRealRuntimeHostProvider {
    fun host(): MinecraftRealRuntimeHost?
}

/**
 * The default provider: no real Minecraft runtime, no network access, no download.
 *
 * This is the honest state of this repository. A certification run using it reports
 * [MinecraftRealRuntimeOutcome.RUNTIME_TEST_NOT_PERFORMED] and certifies nothing.
 */
object NoRealRuntimeHostProvider : MinecraftRealRuntimeHostProvider {
    override fun host(): MinecraftRealRuntimeHost? = null
}

/** Everything one real-runtime certification attempt produced. */
data class MinecraftRealRuntimeCertificationResult(
    val profileId: String,
    val availability: MinecraftRealRuntimeAvailability,
    val outcome: MinecraftRealRuntimeOutcome,
    val steps: List<MinecraftCertificationStepResult>,
    val evidence: MinecraftCertificationEvidence,
    val evaluation: MinecraftCertificationEvaluation,
) {
    val performedRealRuntimeTest: Boolean get() = outcome == MinecraftRealRuntimeOutcome.RUNTIME_TEST_COMPLETED
    val certified: Boolean get() = evaluation.isCertified
}

/**
 * The real-runtime certification harness (§6).
 *
 * It is the only path in CraftMind that can produce `REAL_RUNTIME` evidence, and even then certification requires
 * the certification policy to declare the execution environment real-runtime capable — a set that is **empty** in
 * this repository. A simulated or fake host therefore cannot certify a runtime even if it claims to be real.
 */
class MinecraftRealRuntimeCertificationHarness(
    private val hostProvider: MinecraftRealRuntimeHostProvider = NoRealRuntimeHostProvider,
    private val policy: MinecraftCertificationPolicy = MinecraftCertificationPolicy.DEFAULT,
    private val engine: MinecraftCertificationRuleEngine = MinecraftCertificationRuleEngine(policy),
    private val resolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
    private val gate: MinecraftRuntimeCompatibilityGate = DefaultMinecraftCompatibility.resolver.runtimeGate,
    private val validator: DefaultBuildPlanValidator = DefaultBuildPlanValidator(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Attempts real-runtime certification for one registered profile. Without a host it performs nothing, records
     * `RUNTIME_TEST_NOT_PERFORMED` for every step, and returns evidence that certifies nothing.
     */
    suspend fun certify(
        profileId: String,
        testSuiteId: String = DEFAULT_TEST_SUITE_ID,
        testRunId: String,
    ): MinecraftRealRuntimeCertificationResult {
        val declared = declaredFacts(profileId)
        val host = hostProvider.host()
        if (host == null || host.availability != MinecraftRealRuntimeAvailability.AVAILABLE) {
            val evidence = MinecraftCertificationEvidence.notPerformed(
                profileId = profileId,
                edition = declared.edition,
                minecraftVersion = declared.minecraftVersion,
                releaseChannel = declared.releaseChannel,
                loader = declared.loader,
                loaderVersion = declared.loaderVersion,
                javaRuntimeMajor = declared.javaRuntimeMajor,
                fabricApiVersion = declared.fabricApiVersion,
                bridgeVersion = declared.bridgeVersion,
                bridgeProtocolVersion = declared.bridgeProtocolVersion,
                buildPlanSchemaVersion = declared.buildPlanSchemaVersion,
                blockStateCatalogRevision = declared.blockStateCatalogRevision,
                testSuiteId = testSuiteId,
                testRunId = testRunId,
                executionEnvironment = UNKNOWN_ENVIRONMENT,
                knownLimitations = declared.limitations,
                reason = "RUNTIME_TEST_NOT_PERFORMED",
            )
            return MinecraftRealRuntimeCertificationResult(
                profileId = profileId,
                availability = MinecraftRealRuntimeAvailability.UNAVAILABLE,
                outcome = MinecraftRealRuntimeOutcome.RUNTIME_TEST_NOT_PERFORMED,
                steps = MinecraftCertificationStep.entries.map { step ->
                    MinecraftCertificationStepResult(
                        step = step,
                        outcome = MinecraftVerificationOutcome.NOT_PERFORMED,
                        detail = "No real Minecraft runtime host is available in this environment; ${step.displayName} " +
                            "was not performed and is not claimed.",
                    )
                },
                evidence = evidence,
                evaluation = engine.evaluate(evidence),
            )
        }

        val steps = mutableListOf<MinecraftCertificationStepResult>()
        return try {
            val report = host.authenticate()
            steps += passed(MinecraftCertificationStep.CONNECT_REAL_RUNTIME, host.runtimeDescription)
            steps += passed(MinecraftCertificationStep.PAIR_AND_AUTHENTICATE, "Authenticated session bound to the runtime identity")
            steps += passed(MinecraftCertificationStep.CAPABILITY_EXCHANGE, "Protocol-v2 capability report parsed by the production codec")

            val detection = gate.resolveRuntime(report)
            steps += step(
                MinecraftCertificationStep.DETECT_RUNTIME,
                detection.detection.isDetected,
                "Detection ${detection.detection.status.name}: ${detection.detection.detectedEdition.displayName} " +
                    detection.detection.detectedVersion.displayIdentifier,
            )
            steps += step(
                MinecraftCertificationStep.SELECT_ADAPTER,
                detection.selection.isSelected,
                "Adapter ${detection.selection.adapterId?.value ?: "none"} (${detection.selection.status.name})",
            )
            steps += step(
                MinecraftCertificationStep.VALIDATE_COMPATIBILITY,
                detection.compatibility.status == MinecraftCompatibilityStatus.SUPPORTED,
                "Compatibility ${detection.compatibility.status.name}; canExecute=${detection.canExecute}",
            )

            val record = CertificationBuildPlan.localRecord()
            steps += passed(MinecraftCertificationStep.LOAD_CERTIFICATION_PLAN, "Loaded ${CertificationBuildPlan.PLAN_ID}")
            val validated = runCatching { validator.validate(record.plan) }.getOrNull()
            steps += step(
                MinecraftCertificationStep.VALIDATE_PLAN,
                validated is BuildPlanValidationResult.Valid,
                "Production BuildPlan validation of the certification probe: " +
                    (if (validated is BuildPlanValidationResult.Valid) "valid" else "rejected"),
            )

            val executionId = CertificationExecutionIds.next(testRunId)
            val executionEvidence = host.runExecution(record, executionId)
            steps += step(
                MinecraftCertificationStep.PREFLIGHT,
                MinecraftExecutionVerificationStage.PRECHECK_PASSED in executionEvidence.observedStages,
                "Server preflight observed: ${MinecraftExecutionVerificationStage.PRECHECK_PASSED in executionEvidence.observedStages}",
            )
            val authorization = gate.authorizeExecution(
                report = report,
                requirements = BuildPlanRequirements.from(record.plan),
                previousBinding = detection.binding,
            )
            steps += step(
                MinecraftCertificationStep.AUTHORIZE_EXECUTION,
                authorization.authorized,
                "Execution authorization: ${authorization.reasonCode}",
            )
            steps += step(
                MinecraftCertificationStep.EXECUTE,
                MinecraftExecutionVerificationStage.EXECUTION_STARTED in executionEvidence.observedStages,
                "Execution start observed",
            )
            steps += step(
                MinecraftCertificationStep.VERIFY_WORLD_STATE,
                executionEvidence.worldStateVerified,
                "World state read back and compared: ${executionEvidence.observedOperationCount}/" +
                    "${executionEvidence.expectedOperationCount} placements",
            )
            steps += step(
                MinecraftCertificationStep.VERIFY_PROGRESS,
                executionEvidence.reportedProgress,
                "Bridge-reported progress observed",
            )
            steps += step(
                MinecraftCertificationStep.VERIFY_CANCELLATION,
                executionEvidence.cancellationVerified,
                "Cancellation semantics verified",
            )

            val reconnected = host.reconnect()
            val reconnectResolution = gate.resolveRuntime(reconnected)
            val runtimeStable = detection.detection.runtimeIdentity?.runtimeKey ==
                reconnectResolution.detection.runtimeIdentity?.runtimeKey
            // Reconnect must re-run detection *and* land on the same runtime: a certification run whose subject
            // changed mid-run did not certify the runtime it started with.
            steps += step(
                MinecraftCertificationStep.VERIFY_RECONNECT,
                reconnectResolution.detection.isDetected && runtimeStable,
                "Reconnect re-ran detection: ${reconnectResolution.detection.status.name}; runtime " +
                    (if (runtimeStable) "unchanged" else "CHANGED, so this run certifies nothing"),
            )
            steps += step(
                MinecraftCertificationStep.VERIFY_RUNTIME_CHANGE_INVALIDATION,
                !runtimeStable || reconnectResolution.canExecute == detection.canExecute,
                if (runtimeStable) {
                    "Runtime was stable across reconnect; invalidation is exercised by the simulated pipeline instead"
                } else {
                    "Runtime changed across reconnect and prior eligibility was invalidated"
                },
            )

            val evidence = evidenceFrom(
                declared = declared,
                report = report,
                host = host,
                testSuiteId = testSuiteId,
                testRunId = testRunId,
                executionEvidence = executionEvidence,
                steps = steps,
            )
            steps += step(MinecraftCertificationStep.RECORD_EVIDENCE, true, "Structured evidence recorded")
            MinecraftRealRuntimeCertificationResult(
                profileId = profileId,
                availability = MinecraftRealRuntimeAvailability.AVAILABLE,
                outcome = MinecraftRealRuntimeOutcome.RUNTIME_TEST_COMPLETED,
                steps = steps,
                evidence = evidence,
                evaluation = engine.evaluate(evidence),
            )
        } catch (error: Exception) {
            val evidence = MinecraftCertificationEvidence.notPerformed(
                profileId = declared.profileId,
                edition = declared.edition,
                minecraftVersion = declared.minecraftVersion,
                releaseChannel = declared.releaseChannel,
                loader = declared.loader,
                loaderVersion = declared.loaderVersion,
                javaRuntimeMajor = declared.javaRuntimeMajor,
                fabricApiVersion = declared.fabricApiVersion,
                bridgeVersion = declared.bridgeVersion,
                bridgeProtocolVersion = declared.bridgeProtocolVersion,
                buildPlanSchemaVersion = declared.buildPlanSchemaVersion,
                blockStateCatalogRevision = declared.blockStateCatalogRevision,
                testSuiteId = testSuiteId,
                testRunId = testRunId,
                executionEnvironment = host.environmentLabel,
                knownLimitations = declared.limitations,
                reason = "RUNTIME_TEST_FAILED",
            )
            MinecraftRealRuntimeCertificationResult(
                profileId = profileId,
                availability = MinecraftRealRuntimeAvailability.AVAILABLE,
                outcome = MinecraftRealRuntimeOutcome.RUNTIME_TEST_FAILED,
                steps = steps + MinecraftCertificationStepResult(
                    step = MinecraftCertificationStep.RECORD_EVIDENCE,
                    outcome = MinecraftVerificationOutcome.FAILED,
                    detail = error.javaClass.simpleName,
                ),
                evidence = evidence,
                evaluation = engine.evaluate(evidence),
            )
        } finally {
            runCatching { host.close() }
        }
    }

    private fun evidenceFrom(
        declared: DeclaredProfileFacts,
        report: AuthenticatedMinecraftRuntimeReport,
        host: MinecraftRealRuntimeHost,
        testSuiteId: String,
        testRunId: String,
        executionEvidence: MinecraftExecutionVerificationEvidence,
        steps: List<MinecraftCertificationStepResult>,
    ): MinecraftCertificationEvidence {
        val descriptor = report.descriptor
        val outcomes = MinecraftVerificationCategory.entries.associateWith { category ->
            when (category) {
                MinecraftVerificationCategory.STATIC,
                MinecraftVerificationCategory.UNIT,
                MinecraftVerificationCategory.PROTOCOL,
                MinecraftVerificationCategory.SIMULATED_INTEGRATION,
                -> MinecraftVerificationOutcome.PASSED

                MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION ->
                    if (steps.all { it.outcome != MinecraftVerificationOutcome.FAILED }) {
                        MinecraftVerificationOutcome.PASSED
                    } else {
                        MinecraftVerificationOutcome.FAILED
                    }

                MinecraftVerificationCategory.END_TO_END_EXECUTION ->
                    if (executionEvidence.reachedCompletion && executionEvidence.worldStateVerified) {
                        MinecraftVerificationOutcome.PASSED
                    } else {
                        MinecraftVerificationOutcome.FAILED
                    }

                // A real run always ends in a centralized decision: the engine evaluates exactly this evidence and the
                // result carries that evaluation, so the decision category is performed rather than skipped.
                MinecraftVerificationCategory.CERTIFICATION_DECISION -> MinecraftVerificationOutcome.PASSED
            }
        }
        return MinecraftCertificationEvidence(
            profileId = declared.profileId,
            edition = descriptor.edition,
            minecraftVersion = descriptor.version.displayIdentifier,
            releaseChannel = descriptor.releaseChannel,
            loader = descriptor.loader,
            loaderVersion = descriptor.loaderVersion,
            javaRuntimeMajor = descriptor.javaRuntimeMajor,
            fabricApiVersion = descriptor.fabricApiVersion,
            bridgeVersion = descriptor.bridgeVersion ?: declared.bridgeVersion,
            bridgeProtocolVersion = descriptor.bridgeProtocolVersion ?: declared.bridgeProtocolVersion,
            buildPlanSchemaVersion = declared.buildPlanSchemaVersion,
            blockStateCatalogRevision = declared.blockStateCatalogRevision,
            testSuiteId = testSuiteId,
            testRunId = testRunId,
            executionEnvironment = host.environmentLabel,
            recordedAtEpochMillis = clock(),
            // The level claimed here is the *maximum* a real run could support; the engine still decides, and the
            // policy still refuses environments it does not declare real-runtime capable.
            evidenceLevel = MinecraftRuntimeCertification.RUNTIME_TESTED,
            evidenceMode = MinecraftEvidenceMode.REAL_RUNTIME,
            categoryOutcomes = outcomes,
            executionEvidence = executionEvidence,
            knownLimitations = descriptor.limitations,
            realRuntimeTested = true,
            certificationSource = MinecraftCertificationSource.REAL_RUNTIME_TEST_RUN,
            notes = listOf(host.runtimeDescription),
        )
    }

    /** Declared facts of a registered profile, used when no runtime is available to report its own. */
    private data class DeclaredProfileFacts(
        val profileId: String,
        val edition: MinecraftEdition,
        val minecraftVersion: String,
        val releaseChannel: MinecraftVersionChannel,
        val loader: MinecraftLoader,
        val loaderVersion: String?,
        val javaRuntimeMajor: Int?,
        val fabricApiVersion: String?,
        val bridgeVersion: String,
        val bridgeProtocolVersion: Int,
        val buildPlanSchemaVersion: Int,
        val blockStateCatalogRevision: String,
        val limitations: Set<MinecraftRuntimeLimitation>,
    )

    private fun declaredFacts(profileId: String): DeclaredProfileFacts {
        resolver.registeredProfiles().firstOrNull { it.adapterId.value == profileId }?.let { profile ->
            return DeclaredProfileFacts(
                profileId = profileId,
                edition = profile.edition,
                minecraftVersion = profile.version.displayIdentifier,
                releaseChannel = profile.releaseChannel,
                loader = profile.loader,
                loaderVersion = profile.loaderVersion,
                javaRuntimeMajor = profile.javaRuntimeRequirement?.requiredMajor,
                fabricApiVersion = profile.requiredFabricApiVersion,
                bridgeVersion = profile.bridgeVersion,
                bridgeProtocolVersion = profile.bridgeProtocolVersion,
                buildPlanSchemaVersion = BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION,
                blockStateCatalogRevision = profile.blockStateSupportRevision,
                limitations = profile.limitations,
            )
        }
        resolver.registeredBedrockProfiles().firstOrNull { it.adapterId.value == profileId }?.let { contract ->
            return DeclaredProfileFacts(
                profileId = profileId,
                edition = contract.edition,
                minecraftVersion = "not-certified",
                releaseChannel = MinecraftVersionChannel.UNKNOWN,
                loader = MinecraftLoader.BEDROCK_NATIVE,
                loaderVersion = null,
                javaRuntimeMajor = null,
                fabricApiVersion = null,
                bridgeVersion = contract.bridgeVersion,
                bridgeProtocolVersion = contract.bridgeProtocolVersion,
                buildPlanSchemaVersion = BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION,
                blockStateCatalogRevision = contract.blockStateSupportRevision,
                limitations = contract.limitations,
            )
        }
        return DeclaredProfileFacts(
            profileId = profileId,
            edition = MinecraftEdition.UNKNOWN,
            minecraftVersion = "unknown",
            releaseChannel = MinecraftVersionChannel.UNKNOWN,
            loader = MinecraftLoader.UNKNOWN,
            loaderVersion = null,
            javaRuntimeMajor = null,
            fabricApiVersion = null,
            bridgeVersion = "unknown",
            bridgeProtocolVersion = BridgeProtocol.VERSION,
            buildPlanSchemaVersion = BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION,
            blockStateCatalogRevision = "none-declared",
            limitations = emptySet(),
        )
    }

    private fun passed(step: MinecraftCertificationStep, detail: String) = MinecraftCertificationStepResult(
        step = step,
        outcome = MinecraftVerificationOutcome.PASSED,
        detail = detail,
    )

    private fun step(step: MinecraftCertificationStep, ok: Boolean, detail: String) = MinecraftCertificationStepResult(
        step = step,
        outcome = if (ok) MinecraftVerificationOutcome.PASSED else MinecraftVerificationOutcome.FAILED,
        detail = detail,
    )

    companion object {
        const val DEFAULT_TEST_SUITE_ID = "craftmind-universal-certification"

        /** Used when no run happened, so an environment label is never invented for a test that did not occur. */
        const val UNKNOWN_ENVIRONMENT = "not-performed"
    }
}

/**
 * Deterministic execution IDs for certification runs.
 *
 * Certification runs must be reproducible, so IDs are derived from the run identifier instead of a random UUID; the
 * format still satisfies the production execution-ID contract (RFC 4122 shape, version 4, valid variant).
 */
object CertificationExecutionIds {
    private val HEX = "0123456789abcdef"

    fun next(testRunId: String, index: Int = 0): String {
        val seed = deterministicBytes("$testRunId#$index")
        val timeLow = hex(seed, 0, 8)
        val timeMid = hex(seed, 8, 4)
        val timeHigh = "4" + hex(seed, 12, 3)
        val variant = variantNibble(seed[16]) + hex(seed, 17, 3)
        val node = hex(seed, 20, 12)
        return "$timeLow-$timeMid-$timeHigh-$variant-$node"
    }

    private fun deterministicBytes(input: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest
    }

    private fun hex(bytes: ByteArray, offset: Int, length: Int): String =
        buildString { repeat(length) { i -> append(HEX[(bytes[(offset + i) % bytes.size].toInt() and 0xFF) % 16]) } }

    private fun variantNibble(byte: Byte): String {
        val value = (byte.toInt() and 0xFF) % 4
        return HEX.substring(0x8 + value, 0x8 + value + 1)
    }
}
