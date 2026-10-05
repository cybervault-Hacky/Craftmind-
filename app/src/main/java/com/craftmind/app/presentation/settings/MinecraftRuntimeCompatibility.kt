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

/**
 * Settings-facing compatibility block. It shows only bridge-reported capabilities, structured reasons, bounded
 * diagnostics, and declared limitations; missing facts are shown as missing rather than synthesized.
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
) {
    var advancedExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(runtime.runtimeIdentityLabel(), style = MaterialTheme.typography.bodyMedium)
        Text(runtime.runtimeFactsLabel(), style = MaterialTheme.typography.bodySmall)
        Text(
            runtime.statusLabel(compatibility),
            style = MaterialTheme.typography.titleSmall,
            color = if (compatibility?.status == MinecraftCompatibilityStatus.SUPPORTED) {
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
        }
        TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
            Text(
                if (advancedExpanded) "Hide advanced compatibility details" else "Show advanced compatibility details",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (advancedExpanded) {
            Text("Adapter: ${compatibility?.adapterId?.value ?: "none"}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Capabilities reported by the authenticated bridge: " +
                    runtime.capabilities.sortedBy { it.name }.joinToString { "✓ ${it.displayName}" }.ifEmpty { "none" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (runtime.isBedrock || runtime.limitations.isNotEmpty()) {
            Text(
                "Declared limitations: " +
                    runtime.limitations.sortedBy { it.name }.joinToString { it.displayName }.ifEmpty { "none reported" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        compatibility?.runtimeCertification?.let { certification ->
            Text(
                "Recorded runtime certification: ${certification.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        compatibility?.let { result ->
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
            if (!result.canExecute) {
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
    }
}

private const val MAXIMUM_SETTINGS_DIAGNOSTICS = 4
