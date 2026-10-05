package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.minecraft.compatibility.MinecraftCapability
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityReasonCode
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor

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
