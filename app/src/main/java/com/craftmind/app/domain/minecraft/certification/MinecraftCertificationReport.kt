package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfile
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.SupportedMinecraftRuntimeDescriptor
import com.craftmind.bridge.protocol.BridgeProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The machine-readable certification report (§12).
 *
 * It records evidence and decisions, never credentials: every free-text field is passed through
 * [MinecraftCertificationSanitizer] before a report can be built, and building a report with a secret-looking field
 * fails instead of redacting silently. A report is a committed artifact, so it must be safe to publish.
 */
@Serializable
data class MinecraftCertificationReport(
    val schemaVersion: Int,
    /** CraftMind/app version that produced the report; never a secret, never a credential. */
    val generatedByVersion: String,
    val generatedByEnvironment: String,
    val testSuiteId: String,
    val testRunId: String,
    /** Null when the harness had no trustworthy clock. A timestamp is never invented. */
    val generatedAtEpochMillis: Long?,
    val entries: List<MinecraftCertificationReportEntry>,
    val summary: MinecraftCertificationSummary,
    val reproducibility: MinecraftCertificationReproducibility,
) {
    companion object {
        const val REPORT_SCHEMA_VERSION = 1

        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun toJson(report: MinecraftCertificationReport): String = JSON.encodeToString(serializer(), report)

        fun fromJson(json: String): MinecraftCertificationReport = JSON.decodeFromString(serializer(), json)
    }
}

/** One profile's certification evidence and decision, flattened to strings so the report stays schema-stable. */
@Serializable
data class MinecraftCertificationReportEntry(
    val profileId: String,
    val edition: String,
    val minecraftVersion: String,
    val releaseChannel: String,
    val loader: String,
    val loaderVersion: String?,
    val javaRuntimeMajor: Int?,
    val fabricApiVersion: String?,
    val bridgeVersion: String,
    val bridgeProtocolVersion: Int,
    val buildPlanSchemaVersion: Int,
    val blockStateCatalogRevision: String,
    val declaredStatus: String,
    val declaredCertification: String,
    val evidenceLevel: String,
    val evidenceMode: String,
    val certificationSource: String,
    val decision: String,
    val categoryOutcomes: Map<String, String>,
    val executionStagesObserved: List<String>,
    val worldStateVerified: Boolean,
    val realRuntimeTested: Boolean,
    val limitations: List<String>,
    val reasonCodes: List<String>,
    val reasons: List<String>,
)

@Serializable
data class MinecraftCertificationSummary(
    val profiles: Int,
    val certified: Int,
    val notCertified: Int,
    val insufficientEvidence: Int,
    val blockedByLimitation: Int,
    val failed: Int,
    /** True only when a real Minecraft runtime test actually completed and was recorded. */
    val realRuntimeTestsPerformed: Boolean,
    val simulatedRunsRecorded: Int,
    val matrixRuntimeCases: Int,
    val matrixExecutionCases: Int,
)

@Serializable
data class MinecraftCertificationReproducibility(
    val harness: String,
    val toolchain: List<String>,
    val commands: List<String>,
    /** Source revision the run was performed against, when the harness knows it. Never a secret. */
    val sourceRevision: String?,
    val notes: List<String>,
)

/**
 * Builds a report from evaluations, sanitizing everything on the way in.
 *
 * The builder is the only supported way to create a report, so a report cannot exist without passing the secret scan
 * and without stating honestly which categories were performed.
 */
class MinecraftCertificationReportBuilder(
    private val schemaVersion: Int = MinecraftCertificationReport.REPORT_SCHEMA_VERSION,
    private val generatedByVersion: String,
    private val generatedByEnvironment: String,
    private val testSuiteId: String,
    private val testRunId: String,
    private val generatedAtEpochMillis: Long? = null,
    private val reproducibility: MinecraftCertificationReproducibility,
) {
    private val entries = mutableListOf<MinecraftCertificationReportEntry>()

    fun addVersionKeyedProfile(
        profile: SupportedMinecraftRuntimeDescriptor,
        evaluation: MinecraftCertificationEvaluation,
    ): MinecraftCertificationReportBuilder = apply {
        entries += entry(
            profileId = profile.adapterId.value,
            edition = profile.edition.displayName,
            minecraftVersion = profile.version.displayIdentifier,
            releaseChannel = profile.releaseChannel.name,
            loader = profile.loader.displayName,
            loaderVersion = profile.loaderVersion,
            javaRuntimeMajor = profile.javaRuntimeRequirement?.requiredMajor,
            fabricApiVersion = profile.requiredFabricApiVersion,
            bridgeVersion = profile.bridgeVersion,
            bridgeProtocolVersion = profile.bridgeProtocolVersion,
            declaredStatus = profile.supportStatus,
            declaredCertification = profile.runtimeCertification,
            blockStateCatalogRevision = profile.blockStateSupportRevision,
            limitations = profile.limitations.map { it.name },
            evaluation = evaluation,
        )
    }

    fun addBedrockContract(
        profile: BedrockRuntimeProfile,
        evaluation: MinecraftCertificationEvaluation,
    ): MinecraftCertificationReportBuilder = apply {
        entries += entry(
            profileId = profile.adapterId.value,
            edition = profile.edition.displayName,
            minecraftVersion = if (profile.certifiedMinecraftVersions.isEmpty()) {
                "no-certified-version"
            } else {
                profile.certifiedMinecraftVersions.joinToString { it.displayIdentifier }
            },
            releaseChannel = MinecraftVersionChannelLabel.UNKNOWN,
            loader = "Bedrock Native",
            loaderVersion = null,
            javaRuntimeMajor = null,
            fabricApiVersion = null,
            bridgeVersion = profile.bridgeVersion,
            bridgeProtocolVersion = profile.bridgeProtocolVersion,
            declaredStatus = profile.status,
            declaredCertification = profile.certification,
            blockStateCatalogRevision = profile.blockStateSupportRevision,
            limitations = profile.limitations.map { it.name },
            evaluation = evaluation,
        )
    }

    fun addEvidence(
        evidence: MinecraftCertificationEvidence,
        evaluation: MinecraftCertificationEvaluation,
        declaredStatus: MinecraftCompatibilityStatus,
        declaredCertification: MinecraftRuntimeCertification,
    ): MinecraftCertificationReportBuilder = apply {
        entries += entry(
            profileId = evidence.profileId,
            edition = evidence.edition.displayName,
            minecraftVersion = evidence.minecraftVersion,
            releaseChannel = evidence.releaseChannel.name,
            loader = evidence.loader.displayName,
            loaderVersion = evidence.loaderVersion,
            javaRuntimeMajor = evidence.javaRuntimeMajor,
            fabricApiVersion = evidence.fabricApiVersion,
            bridgeVersion = evidence.bridgeVersion,
            bridgeProtocolVersion = evidence.bridgeProtocolVersion,
            declaredStatus = declaredStatus,
            declaredCertification = declaredCertification,
            blockStateCatalogRevision = evidence.blockStateCatalogRevision,
            limitations = evidence.knownLimitations.map { it.name },
            evaluation = evaluation,
        )
    }

    fun build(
        matrixRuntimeCases: Int = MinecraftCompatibilityCertificationMatrix.runtimeRows.size,
        matrixExecutionCases: Int = MinecraftCompatibilityCertificationMatrix.executionFailureCases.size,
    ): MinecraftCertificationReport {
        val summary = MinecraftCertificationSummary(
            profiles = entries.size,
            certified = entries.count { it.decision == MinecraftCertificationDecision.CERTIFIED.name },
            notCertified = entries.count { it.decision == MinecraftCertificationDecision.NOT_CERTIFIED.name },
            insufficientEvidence = entries.count { it.decision == MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE.name },
            blockedByLimitation = entries.count { it.decision == MinecraftCertificationDecision.BLOCKED_BY_LIMITATION.name },
            failed = entries.count { it.decision == MinecraftCertificationDecision.FAILED.name },
            // Counts only evidence this run itself produced against a real Minecraft runtime. A shipped production
            // record (evidenceMode NONE) is reported separately and must never make this summary claim a new test.
            realRuntimeTestsPerformed = entries.any { it.evidenceMode == MinecraftEvidenceMode.REAL_RUNTIME.name },
            simulatedRunsRecorded = entries.count { it.evidenceMode == MinecraftEvidenceMode.SIMULATED.name },
            matrixRuntimeCases = matrixRuntimeCases,
            matrixExecutionCases = matrixExecutionCases,
        )
        val report = MinecraftCertificationReport(
            schemaVersion = schemaVersion,
            generatedByVersion = generatedByVersion,
            generatedByEnvironment = generatedByEnvironment,
            testSuiteId = testSuiteId,
            testRunId = testRunId,
            generatedAtEpochMillis = generatedAtEpochMillis,
            entries = entries.toList(),
            summary = summary,
            reproducibility = reproducibility,
        )
        requireSanitized(report)
        return report
    }

    @Suppress("LongParameterList")
    private fun entry(
        profileId: String,
        edition: String,
        minecraftVersion: String,
        releaseChannel: String,
        loader: String,
        loaderVersion: String?,
        javaRuntimeMajor: Int?,
        fabricApiVersion: String?,
        bridgeVersion: String,
        bridgeProtocolVersion: Int,
        declaredStatus: MinecraftCompatibilityStatus,
        declaredCertification: MinecraftRuntimeCertification,
        blockStateCatalogRevision: String,
        limitations: List<String>,
        evaluation: MinecraftCertificationEvaluation,
    ): MinecraftCertificationReportEntry {
        val evidence = evaluation.evidence
        val buildPlanSchemaVersion = evidence?.buildPlanSchemaVersion ?: BridgeProtocol.BUILD_PLAN_SCHEMA_VERSION
        return MinecraftCertificationReportEntry(
            profileId = profileId,
            edition = edition,
            minecraftVersion = minecraftVersion,
            releaseChannel = releaseChannel,
            loader = loader,
            loaderVersion = loaderVersion,
            javaRuntimeMajor = javaRuntimeMajor,
            fabricApiVersion = fabricApiVersion,
            bridgeVersion = bridgeVersion,
            bridgeProtocolVersion = bridgeProtocolVersion,
            buildPlanSchemaVersion = buildPlanSchemaVersion,
            blockStateCatalogRevision = blockStateCatalogRevision,
            declaredStatus = declaredStatus.name,
            declaredCertification = declaredCertification.name,
            evidenceLevel = evaluation.evidenceLevel.name,
            evidenceMode = evaluation.evidenceMode.name,
            certificationSource = evaluation.source.name,
            decision = evaluation.decision.name,
            categoryOutcomes = (evidence?.categoryOutcomes ?: emptyMap())
                .entries.sortedBy { it.key.rank }
                .associate { (category, outcome) -> category.name to outcome.name },
            executionStagesObserved = (evidence?.executionEvidence?.observedStages ?: emptySet())
                .sortedBy { it.name }.map { it.name },
            worldStateVerified = evidence?.executionEvidence?.worldStateVerified ?: false,
            realRuntimeTested = evaluation.realRuntimeTested,
            limitations = limitations.sorted(),
            reasonCodes = evaluation.reasonCodes.map { it.name }.sorted(),
            reasons = evaluation.reasons,
        )
    }

    private fun requireSanitized(report: MinecraftCertificationReport) {
        listOf(
            "generatedByVersion" to report.generatedByVersion,
            "generatedByEnvironment" to report.generatedByEnvironment,
            "testSuiteId" to report.testSuiteId,
            "testRunId" to report.testRunId,
            "reproducibility.harness" to report.reproducibility.harness,
            "reproducibility.sourceRevision" to report.reproducibility.sourceRevision,
        ).forEach { (field, value) -> MinecraftCertificationSanitizer.requireSanitized(field, value) }
        report.reproducibility.toolchain.forEachIndexed { index, value ->
            MinecraftCertificationSanitizer.requireSanitized("reproducibility.toolchain[$index]", value)
        }
        report.reproducibility.commands.forEachIndexed { index, value ->
            MinecraftCertificationSanitizer.requireSanitized("reproducibility.commands[$index]", value)
        }
        report.reproducibility.notes.forEachIndexed { index, value ->
            MinecraftCertificationSanitizer.requireSanitized(
                "reproducibility.notes[$index]", value, MinecraftCertificationSanitizer.MAXIMUM_TEXT_LENGTH,
            )
        }
        report.entries.forEach { entry ->
            listOf(
                "profileId" to entry.profileId,
                "minecraftVersion" to entry.minecraftVersion,
                "bridgeVersion" to entry.bridgeVersion,
                "loaderVersion" to entry.loaderVersion,
                "fabricApiVersion" to entry.fabricApiVersion,
                "blockStateCatalogRevision" to entry.blockStateCatalogRevision,
            ).forEach { (field, value) ->
                MinecraftCertificationSanitizer.requireSanitized("${entry.profileId}.$field", value)
            }
            entry.reasons.forEachIndexed { index, value ->
                MinecraftCertificationSanitizer.requireSanitized(
                    "${entry.profileId}.reasons[$index]",
                    value,
                    MinecraftCertificationSanitizer.MAXIMUM_TEXT_LENGTH,
                )
            }
        }
    }

    /** Release-channel labels are plain strings in the report so the schema never depends on enum ordinals. */
    private object MinecraftVersionChannelLabel {
        const val UNKNOWN = "UNKNOWN"
    }
}
