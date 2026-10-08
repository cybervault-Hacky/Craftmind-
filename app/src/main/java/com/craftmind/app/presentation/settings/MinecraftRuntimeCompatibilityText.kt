package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationDecision
import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationEvaluation
import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationEvidence
import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationRuleEngine
import com.craftmind.app.domain.minecraft.certification.MinecraftCertificationSource
import com.craftmind.app.domain.minecraft.certification.MinecraftEvidenceMode
import com.craftmind.app.domain.minecraft.certification.MinecraftVerificationCategory
import com.craftmind.app.domain.minecraft.certification.MinecraftVerificationOutcome
import com.craftmind.app.domain.minecraft.compatibility.CompatibilityAdapterSelection
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeCertification
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDetectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeResolution
import com.craftmind.app.domain.minecraft.compatibility.MinecraftVersionChannel

/**
 * Edition-aware runtime identity shared by Settings and Build Review. Java and Bedrock runtime facts are never
 * mixed: a Bedrock runtime has no JVM, loader, or Fabric API, and a Java runtime has no Bedrock platform.
 */
internal fun MinecraftRuntimeDescriptor.runtimeIdentityLabel(): String =
    "CraftMind app ${appVersion ?: "not echoed"} · ${edition.displayName} · Minecraft ${version.displayIdentifier}"

/**
 * Edition-aware runtime/bridge facts line; only bridge-reported values are shown. The release channel is the
 * channel parsed from the reported Minecraft identifier itself, so a snapshot/beta/pre-release runtime is visible
 * as such even before a profile matches.
 */
internal fun MinecraftRuntimeDescriptor.runtimeFactsLabel(): String = if (isBedrock) {
    "Target: ${edition.displayName} · version ${version.displayIdentifier} · release channel ${version.channel.name} · " +
        "platform ${platform.displayName}" +
        (platformVersion?.let { " $it" } ?: " · platform version not reported") +
        " · bridge ${bridgeVersion ?: "unknown"} · protocol ${bridgeProtocolVersion ?: "unknown"}"
} else {
    "Java runtime reported by bridge: ${javaRuntimeMajor?.let { "Java $it" } ?: "not reported"} · " +
        "loader ${loader.displayName} ${loaderVersion ?: "unknown"} · Fabric API ${fabricApiVersion ?: "not reported"} · " +
        "release channel ${version.channel.name} · bridge ${bridgeVersion ?: "unknown"} · " +
        "protocol ${bridgeProtocolVersion ?: "unknown"}"
}

/** Human-readable status line, including the recorded runtime certification level when one applies. */
internal fun MinecraftRuntimeDescriptor.statusLabel(compatibility: MinecraftCompatibilityResult?): String {
    val status = compatibility?.status ?: MinecraftCompatibilityStatus.UNKNOWN
    val certification = compatibility?.runtimeCertification
    return if (isBedrock) {
        "Bedrock compatibility: ${status.name} · runtime certification: ${certification?.displayName ?: "not performed"}"
    } else {
        "Compatibility: ${status.name}" +
            (certification?.let { " · runtime certification: ${it.displayName}" } ?: "")
    }
}

/**
 * The honest reason a plan cannot run, without exposing runtime internals. Execution is authorized only when the
 * resolver returns SUPPORTED for this exact runtime and plan, so an incomplete or unmatched runtime is reported
 * as such before any plan-specific cause is named.
 */
internal fun MinecraftCompatibilityResult.unavailableReason(
    runtime: MinecraftRuntimeDescriptor,
): String = when {
    // A recognized legacy/experimental runtime is never described as a version or limit problem: it simply has no
    // recorded runtime verification, so no BuildPlan may be sent.
    MinecraftCompatibilityReasonCode.RUNTIME_NOT_CERTIFIED in reasonCodes &&
        status != MinecraftCompatibilityStatus.SUPPORTED ->
        "This legacy or experimental Minecraft runtime is recognized, but CraftMind has no recorded runtime " +
            "certification for it. No BuildPlan will be sent for execution."
    runtime.isBedrock && status != MinecraftCompatibilityStatus.SUPPORTED ->
        "${runtime.edition.displayName}: not currently supported. CraftMind cannot safely execute builds on this " +
            "Bedrock runtime yet. No BuildPlan will be sent for execution."
    status != MinecraftCompatibilityStatus.SUPPORTED ->
        "This runtime is not a SUPPORTED, exactly matched CraftMind target (status ${status.name}). " +
            "No nearest-version or cross-edition fallback is used. No BuildPlan will be sent for execution."
    !planWithinLimits -> "This plan exceeds the limits reported by the authenticated runtime."
    !planContentSupported -> "The requested blocks or block states cannot be represented on this runtime."
    missingCapabilities.isNotEmpty() ->
        "The authenticated runtime does not provide a required capability: " +
            missingCapabilities.sortedBy(MinecraftCapability::name).joinToString { it.displayName } + "."
    else -> "The runtime is not a supported, exactly matched CraftMind target. No nearest-version or cross-edition fallback is used. " +
        "No BuildPlan will be sent for execution."
}

/**
 * Phase 13 wording for automatically detected runtimes.
 *
 * Everything below is derived from the authenticated bridge report and the typed detection/selection/resolution
 * results. Nothing is inferred from a launcher, a filename, a package name, a port, a saved preference, or a
 * previous connection, and a missing fact is shown as missing rather than filled in.
 */

/** Build Review never asks the user to choose a runtime: it states that the target was detected. */
internal const val TARGET_RUNTIME_DETECTED_AUTOMATICALLY = "Target Runtime: Detected automatically"

/** Wording for a release channel; the channel itself always comes from the authoritative identifier. */
internal val MinecraftVersionChannel.displayLabel: String
    get() = when (this) {
        MinecraftVersionChannel.RELEASE -> "Release"
        MinecraftVersionChannel.PRE_RELEASE -> "Pre-release"
        MinecraftVersionChannel.SNAPSHOT -> "Snapshot"
        MinecraftVersionChannel.BETA -> "Beta"
        MinecraftVersionChannel.ALPHA -> "Alpha"
        MinecraftVersionChannel.LEGACY -> "Legacy"
        MinecraftVersionChannel.UNKNOWN -> "Unknown"
    }

/** Human-readable detection status; it never claims a runtime that was not authoritatively reported. */
internal fun MinecraftRuntimeDetectionResult.detectionStatusLabel(): String = when (status) {
    MinecraftRuntimeDetectionStatus.DETECTED -> "Detected automatically from the authenticated bridge"
    MinecraftRuntimeDetectionStatus.INCOMPLETE -> "Runtime report incomplete"
    MinecraftRuntimeDetectionStatus.UNKNOWN -> "Unable to verify runtime"
    MinecraftRuntimeDetectionStatus.INVALID -> "Runtime report rejected"
}

/** Deterministic adapter wording; an unselected adapter is reported as none, never as a nearest match. */
internal fun CompatibilityAdapterSelection.adapterLabel(): String = when {
    !isSelected -> "none"
    matchedProfile != null -> "${matchedProfile.edition.displayName} / ${matchedProfile.loader.displayName}"
    matchedBedrockProfile != null ->
        "${matchedBedrockProfile.edition.displayName} / ${MinecraftLoader.BEDROCK_NATIVE.displayName} contract"
    else -> adapterId?.value ?: "none"
}

internal fun MinecraftAdapterSelectionStatus.statusLabel(): String = when (this) {
    MinecraftAdapterSelectionStatus.SELECTED -> "Selected"
    MinecraftAdapterSelectionStatus.NO_MATCH -> "No matching adapter"
    MinecraftAdapterSelectionStatus.AMBIGUOUS -> "Ambiguous adapter match"
    MinecraftAdapterSelectionStatus.INVALID -> "Adapter selection blocked"
}

/**
 * The "Minecraft Runtime" block: edition, version, release channel, loader, and Java runtime (or the Bedrock
 * platform). An undetected runtime shows the honest unknown state instead of synthesized facts.
 */
internal fun MinecraftRuntimeResolution.runtimeSummaryLines(): List<String> {
    if (!detection.isDetected) {
        return listOf("Unable to verify runtime", "Execution unavailable") +
            detection.diagnostics.take(MAXIMUM_VISIBLE_DIAGNOSTICS).map { "${it.field.displayName}: ${it.detail}" }
    }
    val descriptor = detection.descriptor
    return buildList {
        add(descriptor.edition.displayName)
        add(descriptor.version.displayIdentifier)
        add(detection.detectedReleaseChannel.displayLabel)
        if (descriptor.isBedrock) {
            add("Loader: ${MinecraftLoader.BEDROCK_NATIVE.displayName}")
            add("Platform: ${descriptor.platform.displayName}" +
                (descriptor.platformVersion?.let { " $it" } ?: ""))
        } else {
            add("${descriptor.loader.displayName} ${descriptor.loaderVersion ?: "unknown"}" +
                (descriptor.fabricApiVersion?.let { " · Fabric API $it" } ?: ""))
            add(descriptor.javaRuntimeMajor?.let { "Java $it" } ?: "Java runtime not reported")
        }
    }
}

/** The "Bridge" block: bridge version and protocol, both verified rather than assumed. */
internal fun MinecraftRuntimeResolution.bridgeSummaryLines(): List<String> = listOf(
    detection.descriptor.bridgeVersion ?: "unknown",
    "Protocol ${detection.descriptor.bridgeProtocolVersion ?: "unknown"}",
)

/** The "Compatibility" block, including certification and the execution line. */
internal fun MinecraftRuntimeResolution.compatibilitySummaryLines(): List<String> = buildList {
    add(compatibility.status.name.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercase() })
    compatibility.runtimeCertification?.let { add("Certification: ${it.displayName}") }
    add(
        if (canExecute) {
            "Execution: authorized for this authenticated runtime"
        } else {
            "Execution: not authorized"
        },
    )
}

/** The "Adapter" line plus the selection status. */
internal fun MinecraftRuntimeResolution.adapterSummaryLines(): List<String> = listOf(
    selection.adapterLabel(),
    selection.status.statusLabel(),
)

/** Build Review / Settings headline for an unavailable build; the confirmation action stays disabled. */
internal fun MinecraftRuntimeResolution.availabilityLabel(): String = when {
    canExecute -> "Build available for this authenticated runtime."
    !detection.isDetected -> "Build unavailable — ${detection.detectionStatusLabel().lowercase(java.util.Locale.ROOT)}. " +
        "No BuildPlan will be sent for execution."
    else -> "Build unavailable — ${compatibility.unavailableReason(detection.descriptor)}"
}

/** Structured reasons from every pipeline stage, in a stable order, for the collapsible technical detail. */
internal fun MinecraftRuntimeResolution.pipelineReasonLines(): List<String> = buildList {
    detection.diagnostics.take(MAXIMUM_VISIBLE_DIAGNOSTICS).forEach { add("${it.reasonCode.name}: ${it.detail}") }
    compatibility.reasonCodes.sortedBy { it.name }.forEach { add("${it.name}: ${it.displayName}") }
    selection.reasons.forEach { add(it) }
    compatibility.reasons.forEach { add(it) }
    capabilityWarnings.forEach { add(it) }
}

private const val MAXIMUM_VISIBLE_DIAGNOSTICS = 6

/**
 * Phase 14 certification wording.
 *
 * The UI must let a reader tell *supported* from *certified*, and *simulated* from *real runtime* evidence. These
 * labels are deliberately literal: no phrase claims more than the recorded evidence, and ambiguous wording such as
 * "fully compatible" is never produced.
 */
internal fun certificationStateLabel(
    status: MinecraftCompatibilityStatus?,
    certification: MinecraftRuntimeCertification?,
    decision: MinecraftCertificationDecision?,
): String = when {
    status == null || certification == null -> "Unavailable — no authenticated runtime report"
    status == MinecraftCompatibilityStatus.UNSUPPORTED -> "Unsupported"
    status == MinecraftCompatibilityStatus.UNKNOWN -> "Unavailable — runtime could not be verified"
    decision == MinecraftCertificationDecision.CERTIFIED && certification.authorizesSupport ->
        "Supported / Certified production target"
    status == MinecraftCompatibilityStatus.SUPPORTED && certification.authorizesSupport ->
        "Supported / Certified production target"
    status == MinecraftCompatibilityStatus.EXPERIMENTAL &&
        certification == MinecraftRuntimeCertification.NOT_PERFORMED ->
        "Experimental / Runtime certification not performed"
    status == MinecraftCompatibilityStatus.EXPERIMENTAL -> "Experimental / Runtime test required"
    certification == MinecraftRuntimeCertification.NOT_PERFORMED -> "Not tested / Runtime test required"
    decision == MinecraftCertificationDecision.BLOCKED_BY_LIMITATION ->
        "Not certified / Blocked by a declared limitation"
    decision == MinecraftCertificationDecision.INSUFFICIENT_EVIDENCE -> "Not certified / Insufficient evidence"
    decision == MinecraftCertificationDecision.FAILED -> "Not certified / Verification failed"
    else -> "Not certified"
}

/** One verification category rendered honestly, including every non-passing outcome. */
internal fun MinecraftVerificationOutcome.evidenceMarker(): String = when (this) {
    MinecraftVerificationOutcome.PASSED -> "verified"
    MinecraftVerificationOutcome.FAILED -> "FAILED"
    MinecraftVerificationOutcome.NOT_RUN -> "not run"
    MinecraftVerificationOutcome.NOT_AVAILABLE -> "not available here"
    MinecraftVerificationOutcome.NOT_PERFORMED -> "not performed"
    MinecraftVerificationOutcome.BLOCKED -> "blocked by policy"
}

/** Compact evidence line: `static verified · unit verified · … · real runtime not performed`. */
internal fun MinecraftCertificationEvidence.verificationSummaryLabel(): String =
    categoryOutcomes.entries.sortedBy { it.key.rank }.joinToString(" · ") { (category, outcome) ->
        "${category.shortLabel} ${outcome.evidenceMarker()}"
    }

internal val MinecraftVerificationCategory.shortLabel: String
    get() = when (this) {
        MinecraftVerificationCategory.STATIC -> "static"
        MinecraftVerificationCategory.UNIT -> "unit"
        MinecraftVerificationCategory.PROTOCOL -> "protocol"
        MinecraftVerificationCategory.SIMULATED_INTEGRATION -> "simulated e2e"
        MinecraftVerificationCategory.REAL_RUNTIME_INTEGRATION -> "real runtime"
        MinecraftVerificationCategory.END_TO_END_EXECUTION -> "execution"
        MinecraftVerificationCategory.CERTIFICATION_DECISION -> "decision"
    }

/** Evidence-state line, always naming whether a real Minecraft runtime was involved. */
internal fun MinecraftCertificationEvidence.evidenceStateLabel(): String = when (evidenceMode) {
    MinecraftEvidenceMode.SIMULATED ->
        "Evidence: simulated end-to-end (${evidenceLevel.displayName}) · no Minecraft runtime was started or modified"
    MinecraftEvidenceMode.REAL_RUNTIME ->
        "Evidence: real Minecraft runtime (${evidenceLevel.displayName})"
    MinecraftEvidenceMode.NONE -> "Evidence: none recorded (${evidenceLevel.displayName})"
}

/** Why execution is allowed or blocked, in one honest sentence. */
internal fun MinecraftCertificationEvaluation.executionPermissionLabel(canExecute: Boolean): String = when {
    canExecute && isCertified ->
        "Execution allowed: the runtime is detected, exactly one adapter matched, and certification is recorded."
    canExecute ->
        "Execution allowed by the resolved compatibility result; certification evidence is reported separately."
    decision == MinecraftCertificationDecision.NOT_CERTIFIED && !realRuntimeTested ->
        "Execution blocked: no real Minecraft runtime test was performed, so this runtime is not certified."
    decision == MinecraftCertificationDecision.BLOCKED_BY_LIMITATION ->
        "Execution blocked: a declared limitation states this runtime is not verified."
    decision == MinecraftCertificationDecision.FAILED ->
        "Execution blocked: verification failed and the runtime is not certified."
    else -> "Execution blocked: the recorded evidence does not certify this runtime."
}

/**
 * The certification evaluation Settings shows for a resolved runtime (Phase 14 §11/§16).
 *
 * Settings holds no verification run of its own — evidence is produced by a certification run — so this reports what
 * the project has *recorded* for the matched profile: the shipped production record for the certified Java target, and
 * an explicit "nothing recorded" for every other runtime. It never invents new evidence and never upgrades a simulated
 * or absent run into certification.
 */
internal fun MinecraftRuntimeResolution.certificationEvaluation(
    resolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
    engine: MinecraftCertificationRuleEngine = MinecraftCertificationRuleEngine(),
): MinecraftCertificationEvaluation? {
    val adapterId = selection.adapterId ?: return null
    resolver.registeredProfiles()
        .firstOrNull { it.adapterId == adapterId }
        ?.let { return engine.evaluateShippedRecord(it) }
    resolver.registeredBedrockProfiles()
        .firstOrNull { it.adapterId == adapterId }
        ?.let { return engine.evaluateShippedBedrockContract(it) }
    return null
}

/** The evidence-state line: always says whether a real Minecraft runtime was involved, or that none was recorded. */
internal fun evidenceStateLine(
    evaluation: MinecraftCertificationEvaluation,
    evidence: MinecraftCertificationEvidence?,
): String = when {
    evidence != null -> evidence.evidenceStateLabel()
    evaluation.source == MinecraftCertificationSource.SHIPPED_PRODUCTION_RECORD ->
        "Evidence: recorded production certification · this build performed no new Minecraft runtime test"

    else -> "Evidence: none recorded (${evaluation.evidenceLevel.displayName})"
}

/**
 * The certification lines for the existing compatibility block: certification state, evidence state, and why execution
 * is allowed or blocked. Empty when no adapter was selected, because there is then nothing recorded to report.
 */
internal fun MinecraftRuntimeResolution.certificationSummaryLines(
    compatibility: MinecraftCompatibilityResult?,
    evidence: MinecraftCertificationEvidence? = null,
    resolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver,
    engine: MinecraftCertificationRuleEngine = MinecraftCertificationRuleEngine(),
): List<String> {
    val evaluation = certificationEvaluation(resolver, engine) ?: return emptyList()
    return listOf(
        "Certification: " + certificationStateLabel(
            compatibility?.status,
            compatibility?.runtimeCertification ?: evaluation.evidenceLevel,
            evaluation.decision,
        ),
        evidenceStateLine(evaluation, evidence),
        evaluation.executionPermissionLabel(canExecute),
    )
}
