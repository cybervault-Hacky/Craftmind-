package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.compatibility.CompatibilityAdapterSelection
import com.craftmind.app.domain.minecraft.compatibility.MinecraftAdapterSelectionStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftLoader
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
