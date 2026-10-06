package com.craftmind.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.minecraft.compatibility.BedrockRuntimeProfileRegistry
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResult
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityStatus
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeDescriptor
import com.craftmind.app.domain.minecraft.compatibility.MinecraftRuntimeResolution

/**
 * Settings-facing runtime and compatibility block (Phase 13).
 *
 * It shows what the authenticated bridge reported and what CraftMind derived from it: the automatically detected
 * edition, version, release channel, loader, Java runtime, bridge identity, the deterministically selected adapter,
 * the compatibility status, certification, declared limitations, and the structured reasons a build cannot run.
 * Missing facts are shown as missing rather than synthesized, and there is no control that lets the user choose an
 * edition, version, loader, or adapter — the only interactive element is a display toggle for technical detail.
 *
 * The compatibility status, the release channel, the recorded certification, the declared limitations, and the
 * reason a build cannot run are always visible; the raw capability/adapter detail is collapsible so the honest
 * summary is never hidden behind an advanced panel.
 */
@Composable
internal fun MinecraftRuntimeCompatibilityBlock(
    runtime: MinecraftRuntimeDescriptor,
    compatibility: MinecraftCompatibilityResult?,
    modifier: Modifier = Modifier,
    /** Phase 13 pipeline result; when absent the block falls back to the descriptor-only display. */
    resolution: MinecraftRuntimeResolution? = null,
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (resolution == null) {
            Text(runtime.runtimeIdentityLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(runtime.runtimeFactsLabel(), style = MaterialTheme.typography.bodySmall)
        } else if (resolution.detection.isDetected) {
            Text(runtime.runtimeIdentityLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(
                resolution.detection.detectionStatusLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Edition and version are already part of the identity line above.
            resolution.runtimeSummaryLines().drop(RUNTIME_HEADER_LINES).forEach { fact ->
                Text(fact, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(runtime.runtimeIdentityLabel(), style = MaterialTheme.typography.bodyMedium)
            resolution.runtimeSummaryLines().forEach { fact ->
                Text(fact, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                resolution.availabilityLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            runtime.statusLabel(compatibility ?: resolution?.compatibility),
            style = MaterialTheme.typography.titleSmall,
            color = if ((compatibility ?: resolution?.compatibility)?.status == MinecraftCompatibilityStatus.SUPPORTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        if (runtime.isBedrock) {
            Text(
                "Bridge: ${BedrockRuntimeProfileRegistry.BEDROCK_BRIDGE_DISPLAY_NAME} ${runtime.bridgeVersion ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (resolution != null) {
            Text(
                "Bridge: ${resolution.bridgeSummaryLines().joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        resolution?.let { pipeline ->
            Text(
                "Adapter: ${pipeline.adapterSummaryLines().joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Compatibility: ${pipeline.compatibilitySummaryLines().joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (pipeline.detection.isDetected && !pipeline.canExecute) {
                Text(
                    pipeline.availabilityLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Text(
                if (advancedExpanded) "Hide advanced compatibility details" else "Show advanced compatibility details",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (advancedExpanded) {
            Text(
                "Adapter: ${(compatibility ?: resolution?.compatibility)?.adapterId?.value ?: "none"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Capabilities reported by the authenticated bridge: " +
                    runtime.capabilities.sortedBy { it.name }.joinToString { "✓ ${it.displayName}" }.ifEmpty { "none" },
                style = MaterialTheme.typography.bodySmall,
            )
            resolution?.let { pipeline ->
                Text(
                    "Detection phase: ${pipeline.phase.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                )
                pipeline.pipelineReasonLines().take(MAXIMUM_SETTINGS_DIAGNOSTICS * 2).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                pipeline.binding?.let { binding ->
                    Text(
                        "Bound to session ${binding.identity.sessionId} · runtime ${binding.identity.runtimeKey}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (runtime.isBedrock || runtime.limitations.isNotEmpty()) {
            Text(
                "Declared limitations: " +
                    runtime.limitations.sortedBy { it.name }.joinToString { it.displayName }.ifEmpty { "none reported" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        (compatibility ?: resolution?.compatibility)?.let { result ->
            result.runtimeCertification?.let { certification ->
                Text(
                    "Recorded runtime certification: ${certification.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (advancedExpanded) {
                Text(
                    "Missing capabilities: " +
                        result.missingCapabilities.sortedBy { it.name }.joinToString { it.displayName }.ifEmpty { "none" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result.diagnostics.take(MAXIMUM_SETTINGS_DIAGNOSTICS).forEach { diagnostic ->
                Text(
                    "Unsupported content: ${diagnostic.blockId ?: "unknown block"}" +
                        (diagnostic.componentId?.let { " (component $it)" } ?: "") +
                        (diagnostic.stateProperties.takeIf { it.isNotEmpty() }?.let { " · state ${it.joinToString()}" } ?: "") +
                        " · ${diagnostic.reasonCode.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            result.reasonCodes.sortedBy { it.name }.takeIf { it.isNotEmpty() }?.let { codes ->
                Text(
                    "Structured reasons: ${codes.joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!result.canExecute && resolution == null) {
                Text(
                    result.unavailableReason(runtime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            result.reasons.forEach { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            result.warnings.forEach { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        resolution?.capabilityWarnings?.forEach { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Edition and version are already shown by [MinecraftRuntimeDescriptor.runtimeIdentityLabel]. */
private const val RUNTIME_HEADER_LINES = 2

private const val MAXIMUM_SETTINGS_DIAGNOSTICS = 4
