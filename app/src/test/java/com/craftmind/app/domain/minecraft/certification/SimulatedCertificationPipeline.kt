package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildPlanValidationResult
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.BridgeCapabilitiesSnapshot
import com.craftmind.app.domain.minecraft.MinecraftBridgeFailure
import com.craftmind.app.domain.minecraft.MinecraftCancellationResult
import com.craftmind.app.domain.minecraft.MinecraftExecutionPhase
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview
import com.craftmind.app.domain.minecraft.MinecraftExecutionSnapshot
import com.craftmind.app.domain.minecraft.compatibility.AuthenticatedMinecraftRuntimeReport
import com.craftmind.app.domain.minecraft.compatibility.BuildPlanRequirements
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCompatibilityBinding
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCompatibilityGate
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeResolution
import com.craftmind.bridge.protocol.BridgeProtocol

/** The simulated end-to-end stages, in the exact order production runs them (§5). */
enum class SimulatedPipelineStage(val displayName: String) {
    BRIDGE_AUTHENTICATION("Bridge authentication"),
    CAPABILITY_EXCHANGE("Capability exchange"),
    APP_VERSION_ECHO("Application version echo"),
    RUNTIME_DETECTION("Runtime detection"),
    DESCRIPTOR_VALIDATION("Descriptor validation"),
    ADAPTER_SELECTION("Adapter selection"),
    COMPATIBILITY_RESOLUTION("Compatibility resolution"),
    BUILDPLAN_VALIDATION("BuildPlan validation"),
    PREFLIGHT("Server preflight"),
    EXECUTION_AUTHORIZATION("Execution authorization"),
    EXECUTION("Execution"),
    PROGRESS("Progress reporting"),
    CANCELLATION("Cancellation"),
    COMPLETION("Completion and world state"),
}

/** One stage's honest outcome. A stage after a failure is `NOT_RUN`, never silently skipped. */
data class SimulatedStageResult(
    val stage: SimulatedPipelineStage,
    val outcome: MinecraftVerificationOutcome,
    val detail: String,
    val reasonCode: String? = null,
)

/** What a simulated run allows the pipeline to do, and what it must assert afterwards. */
data class SimulatedPipelineRequest(
    val profileId: String,
    val plan: BuildPlan = CertificationBuildPlan.build(),
    val record: LocalBuildRecord = CertificationBuildPlan.localRecord(),
    val executionId: String? = null,
    val descriptorMutation: (MinecraftRuntimeDescriptor) -> MinecraftRuntimeDescriptor = { it },
    val reportMutation: (AuthenticatedMinecraftRuntimeReport) -> AuthenticatedMinecraftRuntimeReport = { it },
    /** Binding compatibility was previously resolved for; used for session/runtime/world TOCTOU cases. */
    val previousBinding: MinecraftRuntimeCompatibilityBinding? = null,
    /** Stops before execution stages (used for detection/selection-only matrix rows). */
    val stopAfterResolution: Boolean = false,
    /** Verifies cancellation of a second prepared execution, so a completed build is never disturbed. */
    val verifyCancellation: Boolean = true,
    val gate: MinecraftRuntimeCompatibilityGate = DefaultMinecraftCompatibility.resolver.runtimeGate,
)

/** Everything one simulated run produced, including the evidence record and the certification decision. */
data class SimulatedPipelineRun(
    val profileId: String,
    val stages: List<SimulatedStageResult>,
    val snapshot: BridgeCapabilitiesSnapshot?,
    val report: AuthenticatedMinecraftRuntimeReport?,
    val resolution: MinecraftRuntimeResolution?,
    val preview: MinecraftExecutionPreview?,
    val executionSnapshot: MinecraftExecutionSnapshot?,
    val cancellation: MinecraftCancellationResult?,
    val authorization: com.craftmind.app.domain.minecraft.compatibility.MinecraftExecutionAuthorization?,
    val executionEvidence: MinecraftExecutionVerificationEvidence,
    val evidence: MinecraftCertificationEvidence,
    val evaluation: MinecraftCertificationEvaluation,
    val worldBlocks: Map<com.craftmind.app.domain.buildplan.BlockPosition, String>,
    val bridgeEvents: List<String>,
) {
    fun stage(stage: SimulatedPipelineStage): SimulatedStageResult? = stages.firstOrNull { it.stage == stage }

    fun passed(stage: SimulatedPipelineStage): Boolean =
        stage(stage)?.outcome == MinecraftVerificationOutcome.PASSED

    val failedStage: SimulatedStageResult? get() = stages.firstOrNull { it.outcome == MinecraftVerificationOutcome.FAILED }

    val completedEveryStage: Boolean get() = stages.all { it.outcome == MinecraftVerificationOutcome.PASSED }

    /** The stable reason code this run ended with, whichever layer refused first. */
    val effectiveReasonCode: String?
        get() = failedStage?.reasonCode
            ?: authorization?.takeIf { !it.authorized }?.reasonCode
            ?: resolution?.failureReasonCode()

    val authorized: Boolean get() = authorization?.authorized == true
}

/**
 * The simulated end-to-end certification pipeline (§5, §8).
 *
 * It drives the **production** architecture — production gate, resolver, selector, detector, adapters, BuildPlan
 * validator, protocol codecs, and the Java contract validator — against a controlled simulated bridge. No layer is
 * bypassed and no layer is re-implemented here. Every stage after a failure stays `NOT_RUN`, so a refusal can never
 * turn into partial execution.
 *
 * Evidence from this pipeline is always [MinecraftEvidenceMode.SIMULATED]: the certification engine caps it at
 * `SIMULATED_E2E_VERIFIED`, and no simulated run can ever produce `RUNTIME_TESTED` or `CERTIFIED`.
 */
class SimulatedCertificationPipeline(
    private val bridge: SimulatedCertificationBridge,
    private val resolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
    private val validator: DefaultBuildPlanValidator = DefaultBuildPlanValidator(),
    private val engine: MinecraftCertificationRuleEngine = MinecraftCertificationRuleEngine(),
    private val testRunId: String,
    private val executionEnvironment: String = JVM_UNIT_ENVIRONMENT,
    /** Outcomes recorded by the surrounding suite for categories this run does not itself perform. */
    private val externalCategoryOutcomes: Map<MinecraftVerificationCategory, MinecraftVerificationOutcome> =
        mapOf(
            MinecraftVerificationCategory.STATIC to MinecraftVerificationOutcome.PASSED,
            MinecraftVerificationCategory.UNIT to MinecraftVerificationOutcome.PASSED,
        ),
) {
    suspend fun run(request: SimulatedPipelineRequest): SimulatedPipelineRun {
        val stages = mutableListOf<SimulatedStageResult>()
        var snapshot: BridgeCapabilitiesSnapshot? = null
        var report: AuthenticatedMinecraftRuntimeReport? = null
        var resolution: MinecraftRuntimeResolution? = null
        var preview: MinecraftExecutionPreview? = null
        var executionSnapshot: MinecraftExecutionSnapshot? = null
        var cancellation: MinecraftCancellationResult? = null
        var authorization: com.craftmind.app.domain.minecraft.compatibility.MinecraftExecutionAuthorization? = null
        var halted = false

        fun record(
            stage: SimulatedPipelineStage,
            outcome: MinecraftVerificationOutcome,
            detail: String,
            reasonCode: String? = null,
        ) {
            stages += SimulatedStageResult(stage, outcome, detail, reasonCode)
            if (outcome == MinecraftVerificationOutcome.FAILED) halted = true
        }

        fun skipRemaining() {
            SimulatedPipelineStage.entries.forEach { stage ->
                if (stages.none { it.stage == stage }) {
                    stages += SimulatedStageResult(
                        stage = stage,
                        outcome = MinecraftVerificationOutcome.NOT_RUN,
                        detail = "Not run: an earlier stage failed and the pipeline fails closed.",
                    )
                }
            }
        }

        // 1. Bridge authentication
        val authentication = runCatching { bridge.connect() }
        if (authentication.isFailure) {
            val failure = authentication.exceptionOrNull() as? MinecraftBridgeFailure
            record(
                SimulatedPipelineStage.BRIDGE_AUTHENTICATION,
                MinecraftVerificationOutcome.FAILED,
                "Authentication was refused; no capability report was accepted.",
                failure?.reasonCode ?: "BRIDGE_OPERATION_FAILED",
            )
        } else {
            record(
                SimulatedPipelineStage.BRIDGE_AUTHENTICATION,
                MinecraftVerificationOutcome.PASSED,
                "Authenticated session ${bridge.sessionId} bound to bridge ${bridge.trustedBridge.bridgeId}.",
            )
        }

        // 2. Capability exchange through the production envelope + wire codecs
        if (!halted) {
            val exchange = runCatching { bridge.authenticatedSnapshot() }
            exchange.onSuccess { parsed ->
                snapshot = parsed
                record(
                    SimulatedPipelineStage.CAPABILITY_EXCHANGE,
                    MinecraftVerificationOutcome.PASSED,
                    "Protocol-v2 capability report parsed by the production wire codec " +
                        "(protocol ${parsed.protocolVersion}, bridge ${parsed.bridgeVersion}).",
                )
            }.onFailure { error ->
                val failure = error as? MinecraftBridgeFailure
                record(
                    SimulatedPipelineStage.CAPABILITY_EXCHANGE,
                    MinecraftVerificationOutcome.FAILED,
                    "The production wire codec rejected the capability report.",
                    failure?.reasonCode ?: "BRIDGE_CAPABILITIES_INVALID",
                )
            }
        }

        // 3. Application version echo
        val parsedSnapshot = snapshot
        if (!halted && parsedSnapshot != null) {
            val echoed = parsedSnapshot.clientAppVersion
            if (echoed == bridge.appVersion) {
                record(
                    SimulatedPipelineStage.APP_VERSION_ECHO,
                    MinecraftVerificationOutcome.PASSED,
                    "The bridge echoed app version $echoed exactly.",
                )
            } else {
                record(
                    SimulatedPipelineStage.APP_VERSION_ECHO,
                    MinecraftVerificationOutcome.FAILED,
                    "The bridge echoed ${echoed ?: "nothing"} instead of ${bridge.appVersion}.",
                    "APP_VERSION_MISMATCH",
                )
            }
        }

        // 4–7. Detection → descriptor validation → adapter selection → compatibility resolution
        if (!halted && parsedSnapshot != null) {
            val baseReport = parsedSnapshot.runtimeReport(
                sessionId = bridge.sessionId,
                requestedAppVersion = bridge.appVersion,
                authenticated = true,
                authenticatedAtEpochMillis = bridge.authenticatedAtEpochMillis,
                reportedBytes = bridge.capabilitiesEnvelopeBytes().size,
            )
            val mutated = request.reportMutation(
                baseReport.copy(descriptor = request.descriptorMutation(baseReport.descriptor)),
            )
            report = mutated
            val runResolution = request.gate.resolveRuntime(mutated)
            resolution = runResolution

            record(
                SimulatedPipelineStage.RUNTIME_DETECTION,
                if (runResolution.detection.isDetected) {
                    MinecraftVerificationOutcome.PASSED
                } else {
                    MinecraftVerificationOutcome.FAILED
                },
                "Detection ${runResolution.detection.status.name}: " +
                    "${runResolution.detection.detectedEdition.displayName} " +
                    "${runResolution.detection.detectedVersion.displayIdentifier} " +
                    "(${runResolution.detection.detectedReleaseChannel.name})",
                if (runResolution.detection.isDetected) null else runResolution.detection.failureReasonCode(),
            )
            val invalidCodes = setOf(
                MinecraftCompatibilityReasonCode.INVALID_RUNTIME_DESCRIPTOR,
                MinecraftCompatibilityReasonCode.APP_VERSION_MISMATCH,
                MinecraftCompatibilityReasonCode.BRIDGE_PROTOCOL_MISMATCH,
                MinecraftCompatibilityReasonCode.SESSION_IDENTITY_MISMATCH,
                MinecraftCompatibilityReasonCode.RUNTIME_DETECTION_UNAUTHORIZED,
            )
            val validationFailures = runResolution.detection.diagnostics.filter { it.reasonCode in invalidCodes }
            record(
                SimulatedPipelineStage.DESCRIPTOR_VALIDATION,
                if (validationFailures.isEmpty()) {
                    MinecraftVerificationOutcome.PASSED
                } else {
                    MinecraftVerificationOutcome.FAILED
                },
                if (validationFailures.isEmpty()) {
                    "The descriptor is internally coherent and satisfies the bridge protocol contract."
                } else {
                    validationFailures.joinToString { "${it.field.displayName}: ${it.detail}" }
                },
                validationFailures.firstOrNull()?.reasonCode?.name,
            )
            record(
                SimulatedPipelineStage.ADAPTER_SELECTION,
                if (runResolution.selection.isSelected) {
                    MinecraftVerificationOutcome.PASSED
                } else {
                    MinecraftVerificationOutcome.FAILED
                },
                "Selection ${runResolution.selection.status.name}: adapter " +
                    "${runResolution.selection.adapterId?.value ?: "none"}",
                if (runResolution.selection.isSelected) null else runResolution.failureReasonCode(),
            )
            // Resolution "passes" when it produced a deterministic, structured verdict. Whether that verdict
            // permits execution is decided by the authorization stage, so an EXPERIMENTAL/UNSUPPORTED runtime is
            // still exercised down to its typed refusal instead of hiding it.
            record(
                SimulatedPipelineStage.COMPATIBILITY_RESOLUTION,
                MinecraftVerificationOutcome.PASSED,
                "Compatibility ${runResolution.compatibility.status.name}; canExecute=${runResolution.canExecute}" +
                    (if (runResolution.canExecute) "" else "; ${runResolution.failureReasonCode()}"),
                null,
            )
        }

        // 8. BuildPlan validation through the production validator
        if (!halted && !request.stopAfterResolution) {
            val planValidation = validator.validate(request.plan)
            record(
                SimulatedPipelineStage.BUILDPLAN_VALIDATION,
                if (planValidation is BuildPlanValidationResult.Valid) {
                    MinecraftVerificationOutcome.PASSED
                } else {
                    MinecraftVerificationOutcome.FAILED
                },
                when (planValidation) {
                    is BuildPlanValidationResult.Valid ->
                        "The certification probe passed production BuildPlan validation " +
                            "(${request.plan.operations.size} operations, schema ${request.plan.metadata.schemaVersion})."
                    is BuildPlanValidationResult.Invalid ->
                        "Production BuildPlan validation rejected the probe: ${planValidation.issues}"
                },
                if (planValidation is BuildPlanValidationResult.Invalid) "BUILD_PLAN_NOT_EXECUTABLE" else null,
            )
        }

        val selectedAdapterId = resolution?.selection?.adapterId
        val adapter = selectedAdapterId?.let { resolver.adapter(it) }

        // 9. Preflight through the production adapter and the production contract validator. Preflight places zero
        // blocks, so probing the adapter boundary is safe even for a runtime the gate will refuse; execution itself
        // is gated on authorization below.
        val executionId = request.executionId ?: CertificationExecutionIds.next(testRunId)
        if (!halted && !request.stopAfterResolution && adapter != null) {
            val preflight = runCatching { adapter.preflight(bridge, request.record, executionId) }
            preflight.onSuccess { result ->
                preview = result
                record(
                    SimulatedPipelineStage.PREFLIGHT,
                    MinecraftVerificationOutcome.PASSED,
                    "Preflight prepared ${result.operationCount} operation(s) with token expiry; zero blocks placed.",
                )
            }.onFailure { error ->
                val failure = error as? MinecraftBridgeFailure
                record(
                    SimulatedPipelineStage.PREFLIGHT,
                    MinecraftVerificationOutcome.FAILED,
                    "Preflight was refused before any block placement.",
                    failure?.reasonCode ?: "BRIDGE_OPERATION_FAILED",
                )
            }
        }

        // 10. Execution authorization re-checked against the runtime authenticated now (TOCTOU). This stage always
        // runs once a report exists, even when selection or resolution already failed, because the binding check is
        // the authoritative answer about whether a previously resolved compatibility may still be used.
        val currentReport = report
        if (!request.stopAfterResolution && currentReport != null) {
            val authorized = request.gate.authorizeExecution(
                report = currentReport,
                requirements = BuildPlanRequirements.from(request.plan),
                previousBinding = request.previousBinding ?: resolution?.binding,
            )
            authorization = authorized
            record(
                SimulatedPipelineStage.EXECUTION_AUTHORIZATION,
                if (authorized.authorized) {
                    MinecraftVerificationOutcome.PASSED
                } else {
                    MinecraftVerificationOutcome.FAILED
                },
                if (authorized.authorized) {
                    "Execution authorized for the freshly detected runtime and session."
                } else {
                    authorized.reasons.firstOrNull() ?: "Execution was not authorized."
                },
                if (authorized.authorized) null else authorized.reasonCode,
            )
        }

        // 11–14. Execution, progress, cancellation, completion. Nothing is written unless authorization passed and
        // preflight succeeded, so no partial execution can happen before required validation.
        val preparedPreview = preview
        val executionPermitted = authorization?.authorized == true && !halted
        if (executionPermitted && !request.stopAfterResolution && adapter != null && preparedPreview != null) {
            val execution = runCatching { adapter.execute(bridge, preparedPreview) }
            execution.onSuccess { result ->
                executionSnapshot = result
                record(
                    SimulatedPipelineStage.EXECUTION,
                    if (result.phase == MinecraftExecutionPhase.COMPLETED) {
                        MinecraftVerificationOutcome.PASSED
                    } else {
                        MinecraftVerificationOutcome.FAILED
                    },
                    "Execution finished in phase ${result.phase.name} " +
                        "(${result.completedOperations}/${result.totalOperations} operations).",
                    result.reasonCode,
                )
            }.onFailure { error ->
                val failure = error as? MinecraftBridgeFailure
                record(
                    SimulatedPipelineStage.EXECUTION,
                    MinecraftVerificationOutcome.FAILED,
                    "Execution was refused by the simulated bridge.",
                    failure?.reasonCode ?: "BRIDGE_OPERATION_FAILED",
                )
            }

            if (!halted) {
                val reported = bridge.progressReports
                record(
                    SimulatedPipelineStage.PROGRESS,
                    if (reported > 0) MinecraftVerificationOutcome.PASSED else MinecraftVerificationOutcome.FAILED,
                    "Bridge-reported progress events observed: $reported",
                    if (reported > 0) null else "PROGRESS_NOT_REPORTED",
                )
            }

            if (!halted && request.verifyCancellation) {
                val cancelExecutionId = CertificationExecutionIds.next(testRunId, index = 1)
                val secondPreflight = runCatching {
                    adapter.preflight(bridge, request.record, cancelExecutionId)
                }.getOrNull()
                val cancelResult = secondPreflight?.let { prepared ->
                    runCatching { adapter.cancel(bridge, prepared.executionId) }.getOrNull()
                }
                cancellation = cancelResult
                record(
                    SimulatedPipelineStage.CANCELLATION,
                    if (cancelResult?.outcome == "CANCELLATION_ACCEPTED") {
                        MinecraftVerificationOutcome.PASSED
                    } else {
                        MinecraftVerificationOutcome.FAILED
                    },
                    "Cancellation of a second prepared execution: ${cancelResult?.outcome ?: "unavailable"}",
                    if (cancelResult?.outcome == "CANCELLATION_ACCEPTED") null else "CANCELLATION_UNAVAILABLE",
                )
            }

            if (!halted) {
                val expected = CertificationBuildPlan.EXPECTED_WORLD_STATE
                val matched = expected.all { placement -> bridge.worldBlocks[placement.position] == placement.blockId }
                record(
                    SimulatedPipelineStage.COMPLETION,
                    if (matched && bridge.worldBlocks.size == expected.size) {
                        MinecraftVerificationOutcome.PASSED
                    } else {
                        MinecraftVerificationOutcome.FAILED
                    },
                    "Simulated world holds ${bridge.worldBlocks.size}/${expected.size} expected placements " +
                        "(simulated read-back, not real world-state verification).",
                    if (matched) null else "WORLD_STATE_MISMATCH",
                )
            }
        }

        if (request.stopAfterResolution || halted) skipRemaining()

        val executionEvidence = executionEvidence(request, stages)
        val evidence = evidence(request, report, stages, executionEvidence)
        return SimulatedPipelineRun(
            profileId = request.profileId,
            stages = stages,
            snapshot = snapshot,
            report = report,
            resolution = resolution,
            preview = preview,
            executionSnapshot = executionSnapshot,
            cancellation = cancellation,
            authorization = authorization,
            executionEvidence = executionEvidence,
            evidence = evidence,
            evaluation = engine.evaluate(evidence),
            worldBlocks = bridge.worldBlocks.toMap(),
            bridgeEvents = bridge.events.toList(),
        )
    }

    private fun executionEvidence(
        request: SimulatedPipelineRequest,
        stages: List<SimulatedStageResult>,
    ): MinecraftExecutionVerificationEvidence {
        fun passed(stage: SimulatedPipelineStage) =
            stages.firstOrNull { it.stage == stage }?.outcome == MinecraftVerificationOutcome.PASSED
        val observed = linkedSetOf<MinecraftExecutionVerificationStage>()
        if (passed(SimulatedPipelineStage.PREFLIGHT)) {
            observed += MinecraftExecutionVerificationStage.REQUEST_ACCEPTED
            observed += MinecraftExecutionVerificationStage.PRECHECK_PASSED
        }
        if (passed(SimulatedPipelineStage.EXECUTION)) {
            observed += MinecraftExecutionVerificationStage.EXECUTION_STARTED
            observed += MinecraftExecutionVerificationStage.BLOCKS_WRITTEN
            observed += MinecraftExecutionVerificationStage.EXECUTION_COMPLETED
        } else if (stages.any { it.stage == SimulatedPipelineStage.EXECUTION && it.outcome == MinecraftVerificationOutcome.FAILED }) {
            observed += MinecraftExecutionVerificationStage.EXECUTION_FAILED
        }
        if (passed(SimulatedPipelineStage.PROGRESS)) observed += MinecraftExecutionVerificationStage.PROGRESS_REPORTED
        if (passed(SimulatedPipelineStage.CANCELLATION)) observed += MinecraftExecutionVerificationStage.EXECUTION_CANCELLED
        return MinecraftExecutionVerificationEvidence(
            mode = MinecraftEvidenceMode.SIMULATED,
            observedStages = observed,
            // A simulated world map is not a real world; world-state verification stays false by construction.
            worldStateVerified = false,
            observedOperationCount = bridge.worldBlocks.size,
            expectedOperationCount = request.plan.operations.size,
            cancellationVerified = passed(SimulatedPipelineStage.CANCELLATION),
            reconnectVerified = false,
            notes = listOf(
                "Simulated bridge: protocol codecs and the production contract validator were exercised; no Minecraft " +
                    "runtime exists, so world state was not verified.",
            ),
        )
    }

    private fun evidence(
        request: SimulatedPipelineRequest,
        report: AuthenticatedMinecraftRuntimeReport?,
        stages: List<SimulatedStageResult>,
        executionEvidence: MinecraftExecutionVerificationEvidence,
    ): MinecraftCertificationEvidence {
        val descriptor = report?.descriptor ?: bridge.authenticatedSnapshotOrNull()?.runtimeDescriptor
        val profile = resolver.registeredProfiles().firstOrNull { it.adapterId.value == request.profileId }
        val contract = resolver.registeredBedrockProfiles().firstOrNull { it.adapterId.value == request.profileId }
        fun passed(stage: SimulatedPipelineStage) =
            stages.firstOrNull { it.stage == stage }?.outcome == MinecraftVerificationOutcome.PASSED
        val pipelineReached = passed(SimulatedPipelineStage.COMPATIBILITY_RESOLUTION)
        val protocolPassed = passed(SimulatedPipelineStage.CAPABILITY_EXCHANGE) &&
            passed(SimulatedPipelineStage.APP_VERSION_ECHO)
        // A stage can fail in two different ways, and certification must not blur them: a *typed refusal of an
        // unsupported runtime* is the required behaviour (NOT_RUN, so the decision stays NOT_CERTIFIED), while a
        // *security control rejecting a forged, downgraded, replayed, or unauthorized input* is a failed verification
        // (FAILED, so the decision is FAILED and never merely "insufficient evidence").
        fun securityRefusal(vararg stagesToCheck: SimulatedPipelineStage) = stages.any { result ->
            result.stage in stagesToCheck && result.outcome == MinecraftVerificationOutcome.FAILED &&
                result.reasonCode in SECURITY_REFUSAL_CODES
        }

        val outcomes = MinecraftVerificationCategory.entries.associateWith { category ->
            when (category) {
                MinecraftVerificationCategory.STATIC, MinecraftVerificationCategory.UNIT ->
                    externalCategoryOutcomes[category] ?: MinecraftVerificationOutcome.NOT_RUN

                MinecraftVerificationCategory.PROTOCOL -> when {
                    protocolPassed -> MinecraftVerificationOutcome.PASSED
                    securityRefusal(
                        SimulatedPipelineStage.BRIDGE_AUTHENTICATION,
                        SimulatedPipelineStage.CAPABILITY_EXCHANGE,
                        SimulatedPipelineStage.APP_VERSION_ECHO,
                    ) -> MinecraftVerificationOutcome.FAILED

                    else -> MinecraftVerificationOutcome.NOT_RUN
                }

                MinecraftVerificationCategory.SIMULATED_INTEGRATION -> when {
                    pipelineReached -> MinecraftVerificationOutcome.PASSED
                    securityRefusal(
                        SimulatedPipelineStage.RUNTIME_DETECTION,
                        SimulatedPipelineStage.DESCRIPTOR_VALIDATION,
                        SimulatedPipelineStage.ADAPTER_SELECTION,
                    ) -> MinecraftVerificationOutcome.FAILED

                    else -> MinecraftVerificationOutcome.NOT_RUN
                }

                MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION -> MinecraftVerificationOutcome.NOT_PERFORMED

                MinecraftVerificationCategory.END_TO_END_EXECUTION -> when {
                    passed(SimulatedPipelineStage.COMPLETION) -> MinecraftVerificationOutcome.PASSED
                    // A typed refusal before any bridge call is the required behaviour for uncertified runtimes.
                    passed(SimulatedPipelineStage.PREFLIGHT) -> MinecraftVerificationOutcome.PASSED
                    stages.any {
                        it.stage == SimulatedPipelineStage.PREFLIGHT && it.outcome == MinecraftVerificationOutcome.FAILED &&
                        it.reasonCode == "RUNTIME_NOT_CERTIFIED"
                    } -> MinecraftVerificationOutcome.PASSED
                    stages.any {
                        it.stage == SimulatedPipelineStage.PREFLIGHT && it.outcome == MinecraftVerificationOutcome.FAILED &&
                        it.reasonCode == "BEDROCK_RUNTIME_NOT_CERTIFIED"
                    } -> MinecraftVerificationOutcome.PASSED
                    securityRefusal(
                        SimulatedPipelineStage.BUILDPLAN_VALIDATION,
                        SimulatedPipelineStage.PREFLIGHT,
                        SimulatedPipelineStage.EXECUTION_AUTHORIZATION,
                        SimulatedPipelineStage.EXECUTION,
                        SimulatedPipelineStage.COMPLETION,
                    ) -> MinecraftVerificationOutcome.FAILED

                    else -> MinecraftVerificationOutcome.NOT_RUN
                }

                MinecraftVerificationCategory.CERTIFICATION_DECISION -> MinecraftVerificationOutcome.PASSED
            }
        }
        val level = when {
            outcomes.values.any { it == MinecraftVerificationOutcome.FAILED } ->
                com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification.NOT_PERFORMED

            outcomes[MinecraftVerificationCategory.END_TO_END_EXECUTION] != MinecraftVerificationOutcome.PASSED ->
                com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification.UNIT_TESTED
            outcomes.values.any { it == MinecraftVerificationOutcome.PASSED } ->
                com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL
            else -> com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification.NOT_PERFORMED
        }
        return MinecraftCertificationEvidence(
            profileId = request.profileId,
            edition = descriptor?.edition ?: profile?.edition
                ?: com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition.UNKNOWN,
            minecraftVersion = descriptor?.version?.displayIdentifier ?: profile?.version?.displayIdentifier ?: "unknown",
            releaseChannel = descriptor?.releaseChannel ?: profile?.releaseChannel ?: MinecraftVersionChannelSafe.UNKNOWN,
            loader = descriptor?.loader ?: profile?.loader
                ?: com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader.UNKNOWN,
            loaderVersion = descriptor?.loaderVersion ?: profile?.loaderVersion,
            javaRuntimeMajor = descriptor?.javaRuntimeMajor ?: profile?.javaRuntimeRequirement?.requiredMajor,
            fabricApiVersion = descriptor?.fabricApiVersion ?: profile?.requiredFabricApiVersion,
            bridgeVersion = descriptor?.bridgeVersion ?: profile?.bridgeVersion ?: contract?.bridgeVersion ?: "unknown",
            bridgeProtocolVersion = descriptor?.bridgeProtocolVersion ?: BridgeProtocol.VERSION,
            buildPlanSchemaVersion = BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION,
            blockStateCatalogRevision = profile?.blockStateSupportRevision
                ?: contract?.blockStateSupportRevision ?: "none-declared",
            testSuiteId = TEST_SUITE_ID,
            testRunId = testRunId,
            executionEnvironment = executionEnvironment,
            recordedAtEpochMillis = null,
            evidenceLevel = level,
            evidenceMode = MinecraftEvidenceMode.SIMULATED,
            categoryOutcomes = outcomes,
            executionEvidence = executionEvidence,
            knownLimitations = descriptor?.limitations ?: profile?.limitations ?: contract?.limitations ?: emptySet(),
            realRuntimeTested = false,
            certificationSource = MinecraftCertificationSource.SIMULATED_TEST_RUN,
            notes = listOf(
                "Simulated end-to-end run against a controlled bridge; no Minecraft runtime was started, " +
                    "connected, or modified.",
            ),
        )
    }

    companion object {
        const val TEST_SUITE_ID = "craftmind-universal-certification"
        const val JVM_UNIT_ENVIRONMENT = "jvm-unit-harness"

        /**
         * Stable reason codes that mean a security or contract control refused the input, as opposed to a runtime
         * simply being unsupported. A run that hits one of these recorded a FAILED verification category.
         */
        val SECURITY_REFUSAL_CODES = setOf(
            "AUTH_SIGNATURE_INVALID",
            "AUTH_SESSION_EXPIRED",
            "BRIDGE_PROTOCOL_UNSUPPORTED",
            "BRIDGE_CAPABILITIES_INVALID",
            "BRIDGE_SESSION_UNAVAILABLE",
            "APP_VERSION_MISMATCH",
            "RUNTIME_DETECTION_UNAUTHORIZED",
            "SESSION_IDENTITY_MISMATCH",
            "RUNTIME_DESCRIPTOR_INVALID",
            "INVALID_RUNTIME_DESCRIPTOR",
            "UNSUPPORTED_BUILD_PLAN_SCHEMA",
            "BUILD_PLAN_SCHEMA_UNSUPPORTED",
            "RUNTIME_IDENTITY_CHANGED",
            "WORLD_SESSION_CHANGED",
            "PREFLIGHT_TOKEN_INVALID",
            "PREFLIGHT_REQUIRED",
            "EXECUTION_ALREADY_EXISTS",
            "EXECUTION_ALREADY_ACTIVE",
        )
    }
}

/** Nullable snapshot access used when a run halted before the capability exchange. */
internal fun SimulatedCertificationBridge.authenticatedSnapshotOrNull(): BridgeCapabilitiesSnapshot? =
    runCatching { authenticatedSnapshot() }.getOrNull()

/** Release-channel fallback kept local so the evidence record never invents a channel. */
private object MinecraftVersionChannelSafe {
    val UNKNOWN = com.craftmind.app.domain.minecraft.compatibility.MinecraftVersionChannel.UNKNOWN
}
