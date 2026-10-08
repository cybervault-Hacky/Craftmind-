package com.craftmind.app.domain.minecraft.certification

import com.craftmind.app.domain.minecraft.compatibility.MinecraftEdition
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeLimitation
import com.craftmind.app.domain.minecraft.compatibility.MinecraftVersionChannel

/**
 * Phase 14 verification vocabulary.
 *
 * CraftMind separates *what kind* of check ran from *what it proved*. These seven categories are the only ones the
 * certification engine understands, and every test, harness, and report entry names the category it belongs to, so
 * a simulated pipeline can never be mistaken for a real Minecraft runtime test.
 */
enum class MinecraftVerificationCategory(val displayName: String, val rank: Int) {
    /** Source-level review, structural rules, registry validation, and static scans. */
    STATIC("Static verification", 1),

    /** JVM unit tests of domain logic (detection, selection, resolution, limits, block/state rules). */
    UNIT("Unit verification", 2),

    /** Bridge protocol verification: envelopes, bounded parsing, exact keys, capability codec, contract validator. */
    PROTOCOL("Protocol verification", 3),

    /** Full pipeline against a controlled simulated bridge. Proves wiring, never a Minecraft runtime. */
    SIMULATED_INTEGRATION("Simulated integration verification", 4),

    /** Full pipeline against a real Minecraft runtime. Requires a real host; never simulated. */
    REAL_RUNTIME_INTEGRATION("Real runtime integration verification", 5),

    /** Actual execution verification: preflight, placement, progress, cancellation, completion, world state. */
    END_TO_END_EXECUTION("End-to-end execution verification", 6),

    /** The certification decision itself, derived from the recorded evidence above. */
    CERTIFICATION_DECISION("Certification decision", 7),
}

/**
 * Honest outcome vocabulary. "Not run", "not available", and "not performed" are distinct from "passed": absence of
 * a failure is never treated as evidence of success.
 */
enum class MinecraftVerificationOutcome(val displayName: String) {
    PASSED("Passed"),
    FAILED("Failed"),
    NOT_RUN("Not run"),
    NOT_AVAILABLE("Not available in this environment"),
    NOT_PERFORMED("Not performed"),
    BLOCKED("Blocked by policy"),

    ;

    val isEvidence: Boolean get() = this == PASSED
    val isFailure: Boolean get() = this == FAILED
}

/** Where evidence came from. A simulated run can never be relabeled as a real runtime run. */
enum class MinecraftEvidenceMode(val displayName: String) {
    SIMULATED("Simulated bridge · no Minecraft runtime"),
    REAL_RUNTIME("Real Minecraft runtime"),
    NONE("No verification run"),
}

/**
 * Execution verification stages (§8). "The bridge accepted the request" is *not* proof of placement: certification
 * distinguishes acceptance, precheck, start, written blocks, reported progress, and terminal outcome.
 */
enum class MinecraftExecutionVerificationStage(val isTerminal: Boolean, val displayName: String) {
    REQUEST_ACCEPTED(false, "Request accepted"),
    PRECHECK_PASSED(false, "Server precheck passed"),
    EXECUTION_STARTED(false, "Execution started"),
    BLOCKS_WRITTEN(false, "Blocks written to the world"),
    PROGRESS_REPORTED(false, "Progress reported by the bridge"),
    EXECUTION_COMPLETED(true, "Execution completed"),
    EXECUTION_CANCELLED(true, "Execution cancelled"),
    EXECUTION_FAILED(true, "Execution failed"),

    ;

    companion object {
        /** Ordered progress a successful build must reach; terminal failure/cancellation are recorded separately. */
        val progressOrder: List<MinecraftExecutionVerificationStage> = listOf(
            REQUEST_ACCEPTED, PRECHECK_PASSED, EXECUTION_STARTED, BLOCKS_WRITTEN, PROGRESS_REPORTED, EXECUTION_COMPLETED,
        )

        /** The stages that only a real runtime can prove, because they require reading actual world state. */
        val requiresRealWorld: Set<MinecraftExecutionVerificationStage> =
            setOf(BLOCKS_WRITTEN, PROGRESS_REPORTED, EXECUTION_COMPLETED)
    }
}

/** What an execution run actually proved, and whether it was simulated or real. */
data class MinecraftExecutionVerificationEvidence(
    val mode: MinecraftEvidenceMode,
    val observedStages: Set<MinecraftExecutionVerificationStage>,
    /** True only when actual world state was read back and matched the plan (real runtimes only). */
    val worldStateVerified: Boolean,
    val observedOperationCount: Int,
    val expectedOperationCount: Int,
    val cancellationVerified: Boolean,
    val reconnectVerified: Boolean,
    val notes: List<String> = emptyList(),
) {
    val reachedCompletion: Boolean get() = MinecraftExecutionVerificationStage.EXECUTION_COMPLETED in observedStages
    val wroteBlocks: Boolean get() = MinecraftExecutionVerificationStage.BLOCKS_WRITTEN in observedStages
    val reportedProgress: Boolean get() = MinecraftExecutionVerificationStage.PROGRESS_REPORTED in observedStages
    val failed: Boolean get() = MinecraftExecutionVerificationStage.EXECUTION_FAILED in observedStages

    /** The highest progress stage reached, in [MinecraftExecutionVerificationStage.progressOrder]. */
    val highestProgressStage: MinecraftExecutionVerificationStage?
        get() = MinecraftExecutionVerificationStage.progressOrder.lastOrNull { it in observedStages }

    init {
        require(observedOperationCount >= 0) { "An observed operation count cannot be negative" }
        require(expectedOperationCount >= 0) { "An expected operation count cannot be negative" }
        require(!worldStateVerified || mode == MinecraftEvidenceMode.REAL_RUNTIME) {
            "World-state verification requires a real Minecraft runtime; simulated runs never verify world state"
        }
        require(notes.size <= MAXIMUM_NOTES) { "Execution evidence notes are bounded" }
    }

    companion object {
        const val MAXIMUM_NOTES = 8

        val NOT_PERFORMED = MinecraftExecutionVerificationEvidence(
            mode = MinecraftEvidenceMode.NONE,
            observedStages = emptySet(),
            worldStateVerified = false,
            observedOperationCount = 0,
            expectedOperationCount = 0,
            cancellationVerified = false,
            reconnectVerified = false,
            notes = listOf("No execution verification was performed."),
        )
    }
}

/**
 * Where a certification level came from. The project's existing shipped production record is kept strictly separate
 * from evidence newly established by a Phase 14 test run, so no report can blur the two.
 */
enum class MinecraftCertificationSource(val displayName: String) {
    /** The certification already recorded in the shipped registry before Phase 14. */
    SHIPPED_PRODUCTION_RECORD("Existing shipped certification record"),

    /** Evidence produced by a simulated end-to-end run in this repository's test harness. */
    SIMULATED_TEST_RUN("Phase 14 simulated test run"),

    /** Evidence produced by a real Minecraft runtime harness. None exists in this environment. */
    REAL_RUNTIME_TEST_RUN("Phase 14 real runtime test run"),

    /** No evidence at all. */
    NONE("No evidence recorded"),
}

/**
 * Certification artifacts are committed, so they must never carry secrets. This sanitizer is applied to every
 * free-text field of an evidence record and to every report field before it is written or serialized.
 */
object MinecraftCertificationSanitizer {
    private val FORBIDDEN_PATTERNS: List<Pair<String, Regex>> = listOf(
        "private key material" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY"),
        "credential assignment" to Regex("(?i)(api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|pwd|keystore[_-]?password|authorization)\\s*[:=]"),
        "bearer token" to Regex("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{8,}"),
        "google api key" to Regex("AIza[0-9A-Za-z_\\-]{10,}"),
        "vendor secret token" to Regex("(?i)\\b(sk|ghp|gho|ghu|ghs|github_pat|xox[baprs])-?[0-9A-Za-z_\\-]{10,}"),
        "ip address" to Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
        "host name" to Regex("(?i)\\b[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9\\-]*[a-z0-9])?)*\\.(?:com|net|org|io|dev|local|internal|lan|test|invalid|example)\\b"),
        "keystore or key file" to Regex("(?i)keystore|keychain|\\.jks\\b|\\.keystore\\b|\\.pem\\b|\\.p12\\b|\\.pfx\\b"),
        "tls fingerprint" to Regex("\\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){7,}\\b"),
    )

    /** Bound for identifiers and version tokens. */
    const val MAXIMUM_FIELD_LENGTH = 200

    /** Bound for prose (reasons, notes, policy statements): longer, but still strictly limited. */
    const val MAXIMUM_TEXT_LENGTH = 480

    fun violation(value: String?, maxLength: Int = MAXIMUM_FIELD_LENGTH): String? {
        if (value == null) return null
        if (value.length > maxLength) return "field exceeds $maxLength characters"
        if (value.any { it.code < 0x20 && it != '\n' }) return "field contains a control character"
        FORBIDDEN_PATTERNS.forEach { (name, pattern) -> if (pattern.containsMatchIn(value)) return name }
        return null
    }

    fun isSanitized(value: String?, maxLength: Int = MAXIMUM_FIELD_LENGTH): Boolean = violation(value, maxLength) == null

    /** Fails closed: an evidence record that could leak a secret is rejected, not redacted silently. */
    fun requireSanitized(field: String, value: String?, maxLength: Int = MAXIMUM_FIELD_LENGTH) {
        violation(value, maxLength)?.let { reason ->
            throw IllegalArgumentException("Certification evidence field '$field' was rejected: $reason")
        }
    }
}

/**
 * One structured certification evidence record for one runtime profile (§2).
 *
 * It records *evidence*, never assumptions: which categories actually passed, which failed, which were not
 * performed, what the execution run really observed, whether a real Minecraft runtime was involved, and which
 * limitations were declared. Constructing a record with contradictory claims is rejected outright.
 */
data class MinecraftCertificationEvidence(
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
    val testSuiteId: String,
    val testRunId: String,
    /** Execution environment label, e.g. `jvm-unit-harness`. Policy decides whether it may carry real evidence. */
    val executionEnvironment: String,
    /**
     * Timestamp of the run when the harness has a trustworthy clock. Null is honest: this repository never invents
     * a timestamp for a test that did not happen.
     */
    val recordedAtEpochMillis: Long?,
    val evidenceLevel: MinecraftRuntimeCertification,
    val evidenceMode: MinecraftEvidenceMode,
    val categoryOutcomes: Map<MinecraftVerificationCategory, MinecraftVerificationOutcome>,
    val executionEvidence: MinecraftExecutionVerificationEvidence?,
    val knownLimitations: Set<MinecraftRuntimeLimitation>,
    val realRuntimeTested: Boolean,
    val certificationSource: MinecraftCertificationSource,
    val notes: List<String> = emptyList(),
) {
    val passedCategories: Set<MinecraftVerificationCategory>
        get() = categoryOutcomes.filterValues { it == MinecraftVerificationOutcome.PASSED }.keys

    val failedCategories: Set<MinecraftVerificationCategory>
        get() = categoryOutcomes.filterValues { it == MinecraftVerificationOutcome.FAILED }.keys

    val notPerformedCategories: Set<MinecraftVerificationCategory>
        get() = categoryOutcomes.filterValues {
            it == MinecraftVerificationOutcome.NOT_PERFORMED || it == MinecraftVerificationOutcome.NOT_AVAILABLE ||
                it == MinecraftVerificationOutcome.NOT_RUN || it == MinecraftVerificationOutcome.BLOCKED
        }.keys

    /** A real Minecraft runtime test happened, as claimed by the mode and the explicit flag together. */
    init {
        require(profileId.isNotEmpty() && profileId.length <= MAXIMUM_TOKEN) { "A profile ID must be a bounded token" }
        require(testSuiteId.isNotEmpty() && testSuiteId.length <= MAXIMUM_TOKEN) { "A test suite ID must be bounded" }
        require(testRunId.isNotEmpty() && testRunId.length <= MAXIMUM_TOKEN) { "A test run ID must be bounded" }
        require(executionEnvironment.isNotEmpty()) { "An execution environment label is required" }
        require(minecraftVersion.length <= MAXIMUM_TOKEN) { "A Minecraft version token must be bounded" }
        require(bridgeVersion.isNotEmpty() && bridgeVersion.length <= MAXIMUM_TOKEN) { "A bridge version is required" }
        require(blockStateCatalogRevision.length <= MAXIMUM_TOKEN) { "A catalog revision token must be bounded" }
        require(notes.size <= MinecraftExecutionVerificationEvidence.MAXIMUM_NOTES) { "Evidence notes are bounded" }
        require(realRuntimeTested == (evidenceMode == MinecraftEvidenceMode.REAL_RUNTIME)) {
            "realRuntimeTested must agree with the evidence mode; a simulated run never tests a real runtime"
        }
        require(evidenceMode != MinecraftEvidenceMode.NONE || evidenceLevel == MinecraftRuntimeCertification.NOT_PERFORMED) {
            "Evidence without a verification run must stay NOT_PERFORMED"
        }
        require(
            evidenceMode == MinecraftEvidenceMode.REAL_RUNTIME ||
                evidenceLevel.atMost(MinecraftRuntimeCertification.MAXIMUM_SIMULATED_LEVEL),
        ) {
            "Simulated evidence can never record ${MinecraftRuntimeCertification.RUNTIME_TESTED} or " +
                "${MinecraftRuntimeCertification.CERTIFIED}"
        }
        require(
            evidenceLevel != MinecraftRuntimeCertification.CERTIFIED ||
                categoryOutcomes[MinecraftVerificationCategory.CERTIFICATION_DECISION] ==
                MinecraftVerificationOutcome.PASSED,
        ) {
            "A CERTIFIED evidence level requires a recorded certification decision"
        }
        listOf(
            "profileId" to profileId, "testSuiteId" to testSuiteId, "testRunId" to testRunId,
            "executionEnvironment" to executionEnvironment, "minecraftVersion" to minecraftVersion,
            "bridgeVersion" to bridgeVersion, "loaderVersion" to loaderVersion,
            "fabricApiVersion" to fabricApiVersion, "blockStateCatalogRevision" to blockStateCatalogRevision,
        ).forEach { (field, value) -> MinecraftCertificationSanitizer.requireSanitized(field, value) }
        notes.forEachIndexed { index, note ->
            MinecraftCertificationSanitizer.requireSanitized(
                "notes[$index]", note, MinecraftCertificationSanitizer.MAXIMUM_TEXT_LENGTH,
            )
        }
    }

    companion object {
        const val MAXIMUM_TOKEN = 96

        /** Honest empty record: nothing was performed, nothing is claimed. */
        fun notPerformed(
            profileId: String,
            edition: MinecraftEdition,
            minecraftVersion: String,
            releaseChannel: MinecraftVersionChannel,
            loader: MinecraftLoader,
            loaderVersion: String? = null,
            javaRuntimeMajor: Int? = null,
            fabricApiVersion: String? = null,
            bridgeVersion: String,
            bridgeProtocolVersion: Int,
            buildPlanSchemaVersion: Int,
            blockStateCatalogRevision: String,
            testSuiteId: String,
            testRunId: String,
            executionEnvironment: String,
            knownLimitations: Set<MinecraftRuntimeLimitation> = emptySet(),
            reason: String = "RUNTIME_TEST_NOT_PERFORMED",
        ): MinecraftCertificationEvidence = MinecraftCertificationEvidence(
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
            testSuiteId = testSuiteId,
            testRunId = testRunId,
            executionEnvironment = executionEnvironment,
            recordedAtEpochMillis = null,
            evidenceLevel = MinecraftRuntimeCertification.NOT_PERFORMED,
            evidenceMode = MinecraftEvidenceMode.NONE,
            categoryOutcomes = MinecraftVerificationCategory.entries.associateWith {
                MinecraftVerificationOutcome.NOT_PERFORMED
            },
            executionEvidence = MinecraftExecutionVerificationEvidence.NOT_PERFORMED,
            knownLimitations = knownLimitations,
            realRuntimeTested = false,
            certificationSource = MinecraftCertificationSource.NONE,
            notes = listOf(reason),
        )
    }
}

/** Bounded helper so the ladder can be compared without exposing `ordinal` arithmetic at call sites. */
internal fun MinecraftRuntimeCertification.atMost(other: MinecraftRuntimeCertification): Boolean =
    evidenceRank <= other.evidenceRank
