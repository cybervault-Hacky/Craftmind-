package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfile
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.SupportedMinecraftRuntimeDescriptor

/** Structured reasons a certification decision was reached. Stable, machine-readable, and never a guess. */
enum class MinecraftCertificationReasonCode(val displayName: String) {
    NO_EVIDENCE("No certification evidence was recorded"),
    RUNTIME_TEST_NOT_PERFORMED("No real Minecraft runtime test was performed"),
    SIMULATED_EVIDENCE_ONLY("Only simulated evidence exists; simulation never certifies a runtime"),
    ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME("The execution environment is not declared real-runtime capable"),
    MISSING_STATIC_VERIFICATION("Static verification is missing"),
    MISSING_UNIT_VERIFICATION("Unit verification is missing"),
    MISSING_PROTOCOL_VERIFICATION("Protocol verification is missing"),
    MISSING_SIMULATED_VERIFICATION("Simulated integration verification is missing"),
    MISSING_REAL_RUNTIME_VERIFICATION("Real runtime integration verification is missing"),
    MISSING_EXECUTION_VERIFICATION("End-to-end execution verification is missing"),
    WORLD_STATE_NOT_VERIFIED("Actual world state was not read back and verified"),
    EXECUTION_DID_NOT_WRITE_BLOCKS("Execution never reported blocks written"),
    EXECUTION_DID_NOT_COMPLETE("Execution never reported completion"),
    EXECUTION_FAILED("The verified execution failed"),
    CATEGORY_FAILED("A verification category failed"),
    BLOCKING_LIMITATION("A declared limitation states the runtime is not verified"),
    EVIDENCE_LEVEL_MISMATCH("The recorded evidence level does not match the recorded evidence"),
    EVIDENCE_NOT_SANITIZED("Certification evidence was rejected because it could contain a secret"),
    SHIPPED_RECORD_ACCEPTED("The existing shipped certification record was accepted as recorded"),
    DECLARED_STATUS_OVERCLAIM("The declared compatibility status exceeds the recorded certification"),
}

/** One certification decision with everything needed to explain and reproduce it. */
enum class MinecraftCertificationDecision(val displayName: String) {
    CERTIFIED("Certified"),
    NOT_CERTIFIED("Not certified"),
    INSUFFICIENT_EVIDENCE("Insufficient evidence"),
    BLOCKED_BY_LIMITATION("Blocked by a declared limitation"),
    FAILED("Failed verification"),
}

/**
 * The result of one deterministic certification evaluation.
 *
 * `maximumClaimableStatus` is what the UI and the registry may claim; it is derived from evidence, never from the
 * existence of an adapter, a resolver match, a passing unit test, or a syntactically valid capability report.
 */
data class MinecraftCertificationEvaluation(
    val decision: MinecraftCertificationDecision,
    val evidenceLevel: MinecraftRuntimeCertification,
    val evidenceMode: MinecraftEvidenceMode,
    val source: MinecraftCertificationSource,
    val maximumClaimableStatus: MinecraftCompatibilityStatus,
    val authorizesExecution: Boolean,
    val realRuntimeTested: Boolean,
    val reasonCodes: Set<MinecraftCertificationReasonCode>,
    val reasons: List<String>,
    val evidence: MinecraftCertificationEvidence?,
) {
    val isCertified: Boolean get() = decision == MinecraftCertificationDecision.CERTIFIED

    /** True when the declared status of a profile is stronger than the recorded evidence allows. */
    fun overClaims(declaredStatus: MinecraftCompatibilityStatus): Boolean =
        declaredStatus == MinecraftCompatibilityStatus.SUPPORTED &&
            maximumClaimableStatus != MinecraftCompatibilityStatus.SUPPORTED
}

/**
 * The project's recorded certification for a runtime that shipped before Phase 14.
 *
 * This is the only way an existing certification survives: as an explicit, documented record with its own policy
 * statement — not as an inference from an adapter existing or a test passing.
 */
data class MinecraftShippedCertificationRecord(
    val profileId: String,
    val certification: MinecraftRuntimeCertification,
    val declaredStatus: MinecraftCompatibilityStatus,
    val policyStatement: String,
)

/**
 * The certification evidence policy (§1, §3, §15).
 *
 * Deliberately strict and explicit:
 * - [realRuntimeCapableEnvironments] is **empty**. No environment in this repository is declared able to produce
 *   real Minecraft runtime evidence, so no new evidence can reach `RUNTIME_TESTED` or `CERTIFIED` here. Certifying a
 *   runtime later requires a deliberate, reviewed change to this set plus a recorded real-runtime evidence artifact.
 * - [blockingLimitations] are the limitations whose own text says the runtime is unverified; they block certification
 *   (but not recognition) until a real test removes them.
 * - [shippedProductionRecords] keeps the pre-Phase-14 production record separate from new evidence.
 */
data class MinecraftCertificationPolicy(
    val requiredCategoriesForCertification: Set<MinecraftVerificationCategory> = setOf(
        MinecraftVerificationCategory.STATIC,
        MinecraftVerificationCategory.UNIT,
        MinecraftVerificationCategory.PROTOCOL,
        MinecraftVerificationCategory.SIMULATED_INTEGRATION,
        MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION,
        MinecraftVerificationCategory.END_TO_END_EXECUTION,
        MinecraftVerificationCategory.CERTIFICATION_DECISION,
    ),
    val requiredCategoriesForSimulatedEvidence: Set<MinecraftVerificationCategory> = setOf(
        MinecraftVerificationCategory.STATIC,
        MinecraftVerificationCategory.UNIT,
        MinecraftVerificationCategory.PROTOCOL,
        MinecraftVerificationCategory.SIMULATED_INTEGRATION,
        MinecraftVerificationCategory.END_TO_END_EXECUTION,
    ),
    val blockingLimitations: Set<MinecraftRuntimeLimitation> = setOf(
        MinecraftRuntimeLimitation.LEGACY_RUNTIME_NOT_VERIFIED,
        MinecraftRuntimeLimitation.LEGACY_BRIDGE_INTERFACE_UNVERIFIED,
        MinecraftRuntimeLimitation.BLOCK_STATE_MAPPING_NOT_VERIFIED,
    ),
    val realRuntimeCapableEnvironments: Set<String> = emptySet(),
    val shippedProductionRecords: Map<String, MinecraftShippedCertificationRecord> = mapOf(
        "java-fabric-1.20.1" to MinecraftShippedCertificationRecord(
            profileId = "java-fabric-1.20.1",
            certification = MinecraftRuntimeCertification.CERTIFIED,
            declaredStatus = MinecraftCompatibilityStatus.SUPPORTED,
            policyStatement = "Recorded as CraftMind's shipped production target before Phase 14. Phase 14 performed " +
                "no new real-Minecraft runtime test for it and adds no new runtime evidence; the record is kept " +
                "exactly as shipped and is reported separately from simulated evidence.",
        ),
    ),
) {
    companion object {
        val DEFAULT = MinecraftCertificationPolicy()
    }
}

/**
 * The single centralized certification decision engine (§3).
 *
 * Deterministic: the same evidence always produces the same decision, in the same order of checks, with the same
 * reason codes. It never promotes evidence, never treats a passing test as a runtime test, and never certifies a
 * runtime because an adapter exists, a resolver matched, a fake bridge responded, or a BuildPlan validated.
 */
class MinecraftCertificationRuleEngine(
    private val policy: MinecraftCertificationPolicy = MinecraftCertificationPolicy.DEFAULT,
) {
    /** Evaluates newly recorded evidence from a Phase 14 (or later) verification run. */
    fun evaluate(evidence: MinecraftCertificationEvidence?): MinecraftCertificationEvaluation {
        if (evidence == null) {
            return evaluation(
                decision = MinecraftCertificationDecision.NOT_CERTIFIED,
                level = MinecraftRuntimeCertification.NOT_PERFORMED,
                mode = MinecraftEvidenceMode.NONE,
                source = MinecraftCertificationSource.NONE,
                realRuntimeTested = false,
                reasonCodes = linkedSetOf(
                    MinecraftCertificationReasonCode.NO_EVIDENCE,
                    MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED,
                ),
                reasons = listOf(
                    "No certification evidence was recorded for this runtime, so nothing is certified. " +
                        "An adapter, a resolver match, or a passing unit test is not certification evidence.",
                ),
                evidence = null,
            )
        }

        val reasonCodes = linkedSetOf<MinecraftCertificationReasonCode>()
        val reasons = mutableListOf<String>()

        // 1. Secrets are never accepted in certification artifacts.
        if (!isSanitized(evidence)) {
            reasonCodes += MinecraftCertificationReasonCode.EVIDENCE_NOT_SANITIZED
            reasons += "The evidence record was rejected because a field could contain a secret."
            return evaluation(
                MinecraftCertificationDecision.FAILED, MinecraftRuntimeCertification.NOT_PERFORMED,
                evidence.evidenceMode, MinecraftCertificationSource.NONE, false, reasonCodes, reasons, null,
            )
        }

        // 2. Any failed category fails the decision; a failure is never averaged away.
        if (evidence.failedCategories.isNotEmpty()) {
            evidence.failedCategories.sortedBy { it.rank }.forEach { category ->
                reasonCodes += MinecraftCertificationReasonCode.CATEGORY_FAILED
                reasons += "${category.displayName} failed."
            }
            return evaluation(
                MinecraftCertificationDecision.FAILED,
                MinecraftRuntimeCertification.NOT_PERFORMED,
                evidence.evidenceMode,
                evidence.certificationSource,
                evidence.realRuntimeTested,
                reasonCodes, reasons, evidence,
            )
        }

        // 3. A failed or incomplete execution never certifies.
        val execution = evidence.executionEvidence
        if (execution?.failed == true) {
            reasonCodes += MinecraftCertificationReasonCode.EXECUTION_FAILED
            reasons += "The verified execution failed, so no certification is granted."
        }

        // 3b. Nothing at all was performed: there is no evidence to weigh, so the decision is NOT_CERTIFIED rather
        //     than INSUFFICIENT_EVIDENCE. Keeping the two apart is what lets the UI say "not tested" honestly.
        if (evidence.passedCategories.isEmpty() && evidence.failedCategories.isEmpty()) {
            reasonCodes += MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED
            reasons += "No verification category was performed for this runtime, so nothing is certified."
            return evaluation(
                MinecraftCertificationDecision.NOT_CERTIFIED,
                MinecraftRuntimeCertification.NOT_PERFORMED,
                evidence.evidenceMode,
                evidence.certificationSource,
                false,
                reasonCodes, reasons, evidence,
            )
        }

        // 4. Limitations that state the runtime is unverified block certification.
        val blocking = evidence.knownLimitations.intersect(policy.blockingLimitations)
        if (blocking.isNotEmpty()) {
            blocking.sortedBy { it.name }.forEach { limitation ->
                reasonCodes += MinecraftCertificationReasonCode.BLOCKING_LIMITATION
                reasons += "Declared limitation ${limitation.displayName} blocks certification until a real runtime test removes it."
            }
            return evaluation(
                MinecraftCertificationDecision.BLOCKED_BY_LIMITATION,
                capLevel(evidence),
                evidence.evidenceMode,
                evidence.certificationSource,
                evidence.realRuntimeTested,
                reasonCodes, reasons, evidence,
            )
        }

        // 5. Real-runtime claims require a declared real-runtime environment.
        val environmentAllowsRealEvidence = evidence.executionEnvironment in policy.realRuntimeCapableEnvironments
        if (evidence.evidenceMode == MinecraftEvidenceMode.REAL_RUNTIME && !environmentAllowsRealEvidence) {
            reasonCodes += MinecraftCertificationReasonCode.ENVIRONMENT_NOT_DECLARED_FOR_REAL_RUNTIME
            reasons += "Execution environment '${evidence.executionEnvironment}' is not declared real-runtime capable " +
                "by the certification policy, so this evidence is treated as simulated and certifies nothing."
            return evaluation(
                MinecraftCertificationDecision.NOT_CERTIFIED,
                MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL,
                MinecraftEvidenceMode.SIMULATED,
                MinecraftCertificationSource.SIMULATED_TEST_RUN,
                false,
                reasonCodes, reasons, evidence,
            )
        }

        // 6. Missing categories: insufficient evidence, listed explicitly.
        val required = if (environmentAllowsRealEvidence && evidence.realRuntimeTested) {
            policy.requiredCategoriesForCertification
        } else {
            policy.requiredCategoriesForSimulatedEvidence
        }
        val missing = required.filter { evidence.categoryOutcomes[it] != MinecraftVerificationOutcome.PASSED }
        if (missing.isNotEmpty()) {
            missing.sortedBy { it.rank }.forEach { category ->
                reasonCodes += missingReasonCode(category)
                reasons += "${category.displayName} is ${evidence.categoryOutcomes[category]?.displayName ?: "missing"}."
            }
            return evaluation(
                MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE,
                capLevel(evidence),
                evidence.evidenceMode,
                evidence.certificationSource,
                evidence.realRuntimeTested,
                reasonCodes, reasons, evidence,
            )
        }

        // 7. Execution depth: acceptance alone proves nothing.
        if (execution == null || !execution.wroteBlocks) {
            reasonCodes += MinecraftCertificationReasonCode.EXECUTION_DID_NOT_WRITE_BLOCKS
            reasons += "Execution verification never observed blocks written to a world."
        }
        if (execution != null && !execution.reachedCompletion) {
            reasonCodes += MinecraftCertificationReasonCode.EXECUTION_DID_NOT_COMPLETE
            reasons += "Execution verification never observed completion."
        }

        // 8. Only a real runtime with verified world state can certify.
        if (!evidence.realRuntimeTested) {
            reasonCodes += MinecraftCertificationReasonCode.SIMULATED_EVIDENCE_ONLY
            reasonCodes += MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED
            reasons += "The pipeline was verified end-to-end against a simulated bridge. Simulation proves CraftMind's " +
                "wiring, never a Minecraft runtime, so the runtime stays uncertified and execution stays disabled."
            return evaluation(
                MinecraftCertificationDecision.NOT_CERTIFIED,
                MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL,
                MinecraftEvidenceMode.SIMULATED,
                MinecraftCertificationSource.SIMULATED_TEST_RUN,
                false,
                reasonCodes, reasons, evidence,
            )
        }
        if (execution == null || !execution.worldStateVerified) {
            reasonCodes += MinecraftCertificationReasonCode.WORLD_STATE_NOT_VERIFIED
            reasons += "Actual world state was not read back and verified after execution."
        }
        if (reasonCodes.isNotEmpty()) {
            return evaluation(
                MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE,
                capLevel(evidence),
                evidence.evidenceMode,
                evidence.certificationSource,
                evidence.realRuntimeTested,
                reasonCodes, reasons, evidence,
            )
        }

        return evaluation(
            MinecraftCertificationDecision.CERTIFIED,
            MinecraftRuntimeCertification.CERTIFIED,
            MinecraftEvidenceMode.REAL_RUNTIME,
            MinecraftCertificationSource.REAL_RUNTIME_TEST_RUN,
            true,
            linkedSetOf(),
            listOf("Every required category passed against a real Minecraft runtime with verified world state."),
            evidence,
        )
    }

    /**
     * Reports the project's existing shipped certification record for a registered profile, kept strictly separate
     * from evidence newly established by Phase 14. It never invents runtime evidence and never downgrades the
     * shipped production record merely because this sandbox cannot run Minecraft.
     */
    fun evaluateShippedRecord(profile: SupportedMinecraftRuntimeDescriptor): MinecraftCertificationEvaluation {
        val record = policy.shippedProductionRecords[profile.adapterId.value]
        return if (record != null && record.certification == profile.runtimeCertification &&
            record.declaredStatus == profile.supportStatus
        ) {
            evaluation(
                decision = MinecraftCertificationDecision.CERTIFIED,
                level = record.certification,
                mode = MinecraftEvidenceMode.NONE,
                source = MinecraftCertificationSource.SHIPPED_PRODUCTION_RECORD,
                realRuntimeTested = record.certification.isRealRuntimeEvidence,
                reasonCodes = linkedSetOf(MinecraftCertificationReasonCode.SHIPPED_RECORD_ACCEPTED),
                reasons = listOf(record.policyStatement),
                evidence = null,
            )
        } else {
            evaluation(
                decision = if (profile.runtimeCertification == MinecraftRuntimeCertification.NOT_PERFORMED) {
                    MinecraftCertificationDecision.NOT_CERTIFIED
                } else {
                    MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE
                },
                level = profile.runtimeCertification,
                mode = MinecraftEvidenceMode.NONE,
                source = MinecraftCertificationSource.NONE,
                realRuntimeTested = false,
                reasonCodes = linkedSetOf(MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED),
                reasons = listOf(
                    "Profile ${profile.adapterId.value} declares ${profile.runtimeCertification.displayName} and " +
                        "${profile.supportStatus.name}. No shipped certification record and no new runtime evidence " +
                        "exist for it, so it stays uncertified and non-executable.",
                ),
                evidence = null,
            )
        }
    }

    /** Reports the shipped Bedrock contract, which is uncertified by design and must stay that way. */
    fun evaluateShippedBedrockContract(profile: BedrockRuntimeProfile): MinecraftCertificationEvaluation = evaluation(
        decision = MinecraftCertificationDecision.NOT_CERTIFIED,
        level = profile.certification,
        mode = MinecraftEvidenceMode.NONE,
        source = MinecraftCertificationSource.NONE,
        realRuntimeTested = false,
        reasonCodes = linkedSetOf(
            MinecraftCertificationReasonCode.RUNTIME_TEST_NOT_PERFORMED,
            MinecraftCertificationReasonCode.NO_EVIDENCE,
        ),
        reasons = listOf(
            "The Bedrock contract ${profile.adapterId.value} records ${profile.certification.displayName} with " +
                "${profile.certifiedMinecraftVersions.size} certified Minecraft version(s). Detection recognizes a " +
                "Bedrock runtime; it never certifies one, and execution stays disabled.",
        ),
        evidence = null,
    )

    /**
     * Guards the registry-side claim: a profile may only declare `SUPPORTED` when its recorded certification
     * authorizes support. Phase 14 re-checks this instead of trusting the declaration.
     */
    fun auditDeclaredStatus(
        profile: SupportedMinecraftRuntimeDescriptor,
        evaluation: MinecraftCertificationEvaluation,
    ): MinecraftCertificationReasonCode? =
        if (evaluation.overClaims(profile.supportStatus)) {
            MinecraftCertificationReasonCode.DECLARED_STATUS_OVERCLAIM
        } else {
            null
        }

    private fun capLevel(evidence: MinecraftCertificationEvidence): MinecraftRuntimeCertification =
        if (evidence.evidenceMode == MinecraftEvidenceMode.REAL_RUNTIME) {
            evidence.evidenceLevel
        } else {
            if (evidence.evidenceLevel.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL)) {
                evidence.evidenceLevel
            } else {
                MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL
            }
        }

    private fun missingReasonCode(category: MinecraftVerificationCategory) = when (category) {
        MinecraftVerificationCategory.STATIC -> MinecraftCertificationReasonCode.MISSING_STATIC_VERIFICATION
        MinecraftVerificationCategory.UNIT -> MinecraftCertificationReasonCode.MISSING_UNIT_VERIFICATION
        MinecraftVerificationCategory.PROTOCOL -> MinecraftCertificationReasonCode.MISSING_PROTOCOL_VERIFICATION
        MinecraftVerificationCategory.SIMULATED_INTEGRATION -> MinecraftCertificationReasonCode.MISSING_SIMULATED_VERIFICATION
        MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION -> MinecraftCertificationReasonCode.MISSING_REAL_RUNTIME_VERIFICATION
        MinecraftVerificationCategory.END_TO_END_EXECUTION -> MinecraftCertificationReasonCode.MISSING_EXECUTION_VERIFICATION
        MinecraftVerificationCategory.CERTIFICATION_DECISION -> MinecraftCertificationReasonCode.NO_EVIDENCE
    }

    private fun isSanitized(evidence: MinecraftCertificationEvidence): Boolean = listOf(
        evidence.profileId, evidence.testSuiteId, evidence.testRunId, evidence.executionEnvironment,
        evidence.minecraftVersion, evidence.bridgeVersion, evidence.loaderVersion, evidence.fabricApiVersion,
        evidence.blockStateCatalogRevision,
    ).all { MinecraftCertificationSanitizer.isSanitized(it) } &&
        evidence.notes.all { MinecraftCertificationSanitizer.isSanitized(it) }

    private fun evaluation(
        decision: MinecraftCertificationDecision,
        level: MinecraftRuntimeCertification,
        mode: MinecraftEvidenceMode,
        source: MinecraftCertificationSource,
        realRuntimeTested: Boolean,
        reasonCodes: Set<MinecraftCertificationReasonCode>,
        reasons: List<String>,
        evidence: MinecraftCertificationEvidence?,
    ) = MinecraftCertificationEvaluation(
        decision = decision,
        evidenceLevel = level,
        evidenceMode = mode,
        source = source,
        // Certification is the only path to a SUPPORTED claim; everything else stays experimental or unsupported.
        maximumClaimableStatus = if (decision == MinecraftCertificationDecision.CERTIFIED && level.authorizesSupport) {
            MinecraftCompatibilityStatus.SUPPORTED
        } else {
            MinecraftCompatibilityStatus.EXPERIMENTAL
        },
        authorizesExecution = decision == MinecraftCertificationDecision.CERTIFIED && level.authorizesSupport,
        realRuntimeTested = realRuntimeTested,
        reasonCodes = reasonCodes,
        reasons = reasons.distinct().map { reason ->
            reason.take(MinecraftCertificationSanitizer.MAXIMUM_TEXT_LENGTH)
        },
        evidence = evidence,
    )
}
